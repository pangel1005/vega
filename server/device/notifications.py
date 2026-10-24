"""Las notificaciones del PC (Discord, WhatsApp, el correo…), para verlas en el teléfono.

Windows guarda las que aparecen en el centro de notificaciones y deja leerlas con WinRT
(`UserNotificationListener`), si en Configuración → Privacidad → Notificaciones está
permitido el acceso. Se leen cada pocos segundos: app, título, texto, hora e icono.
Desde el teléfono se pueden descartar (desaparecen también del PC).

Privacidad: su contenido solo viaja al teléfono por el cable. No se guarda ni se escribe
en los registros.
"""

from __future__ import annotations

import hashlib
import io
import logging
from dataclasses import dataclass
from typing import Any

log = logging.getLogger(__name__)

MAX_ITEMS = 15
ICON_SIZE = 96
TITLE_MAX = 120
BODY_MAX = 400


@dataclass(frozen=True)
class PcNotification:
    id: int
    app: str
    title: str
    body: str
    at: float  # segundos desde 1970 (UTC)
    icon: str  # id del icono de la app en /app/notifications/icon/<id>.png ("" si no hay)


def _cut(text: str, limit: int) -> str:
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def title_and_body(texts: list[str], app: str) -> tuple[str, str]:
    """Las notificaciones traen varias líneas: la primera es el título y el resto el texto.
    Con una sola línea, el título es la app («Discord: Nuevo mensaje» no se repite)."""
    lines = [t.strip() for t in texts if t and t.strip()]
    if not lines:
        return app, ""
    if len(lines) == 1:
        return app, _cut(lines[0], BODY_MAX)
    return _cut(lines[0], TITLE_MAX), _cut(" · ".join(lines[1:]), BODY_MAX)


def newest(items: list[PcNotification], limit: int = MAX_ITEMS) -> list[PcNotification]:
    return sorted(items, key=lambda n: (n.at, n.id), reverse=True)[:limit]


class NotificationReader:
    """Lee el centro de notificaciones de Windows. Nunca lanza: si no se puede, devuelve None."""

    def __init__(self) -> None:
        self._listener: Any = None
        self._denied = False
        self._icons: dict[str, bytes] = {}  # id → PNG
        self._icon_of_app: dict[str, str] = {}  # app de Windows → id del icono

    async def _get(self) -> Any:
        if self._listener is None:
            from winrt.windows.ui.notifications.management import (
                UserNotificationListener,
                UserNotificationListenerAccessStatus,
            )

            listener = UserNotificationListener.current
            status = await listener.request_access_async()
            if status != UserNotificationListenerAccessStatus.ALLOWED:
                if not self._denied:
                    log.warning(
                        "Windows no deja leer las notificaciones: actívalo en Configuración → "
                        "Privacidad y seguridad → Notificaciones"
                    )
                self._denied = True
                return None
            self._listener = listener
        return self._listener

    async def read(self) -> list[PcNotification] | None:
        try:
            listener = await self._get()
            if listener is None:
                return None
            from winrt.windows.ui.notifications import KnownNotificationBindings, NotificationKinds

            found: list[PcNotification] = []
            for n in await listener.get_notifications_async(NotificationKinds.TOAST):
                try:
                    info = n.app_info
                    app = info.display_info.display_name or "PC"
                    binding = n.notification.visual.get_binding(KnownNotificationBindings.toast_generic)
                    texts = [t.text for t in binding.get_text_elements()] if binding else []
                    title, body = title_and_body(texts, app)
                    found.append(
                        PcNotification(
                            id=int(n.id),
                            app=app,
                            title=title,
                            body=body,
                            at=n.creation_time.timestamp(),
                            icon=await self._icon_for(info),
                        )
                    )
                except Exception as exc:  # una notificación rara no debe tapar las demás
                    log.debug("Notificación ilegible: %s", exc)
            return newest(found)
        except Exception as exc:
            log.debug("No pude leer las notificaciones: %s", exc)
            return None

    async def _icon_for(self, info: Any) -> str:
        key = str(info.app_user_model_id or info.id or "")
        if key in self._icon_of_app:
            return self._icon_of_app[key]
        icon_id = ""
        try:
            from PIL import Image
            from winrt.windows.foundation import Size
            from winrt.windows.storage.streams import Buffer, InputStreamOptions

            stream = await info.display_info.get_logo(Size(ICON_SIZE, ICON_SIZE)).open_read_async()
            size = int(stream.size)
            if 0 < size < 5_000_000:
                buf = Buffer(size)
                await stream.read_async(buf, size, InputStreamOptions.READ_AHEAD)
                with Image.open(io.BytesIO(bytes(memoryview(buf))[: buf.length])) as original:
                    img = original.convert("RGBA")
                img.thumbnail((ICON_SIZE, ICON_SIZE))
                out = io.BytesIO()
                img.save(out, "PNG")
                icon_id = hashlib.sha1(key.encode()).hexdigest()[:12]
                self._icons[icon_id] = out.getvalue()
        except Exception as exc:
            log.debug("Sin icono para una app de notificaciones: %s", exc)
        self._icon_of_app[key] = icon_id
        return icon_id

    def icon(self, icon_id: str) -> bytes | None:
        return self._icons.get(icon_id)

    async def dismiss(self, notification_id: int | None) -> bool:
        """Quita una notificación (o todas con None) del centro de notificaciones del PC."""
        try:
            listener = await self._get()
            if listener is None:
                return False
            if notification_id is not None:
                listener.remove_notification(notification_id)
                return True
            # «Borrar todo»: una a una, y todas las de Windows (no solo las 15 que ve el
            # teléfono). `clear_notifications` no hacía nada desde una app de escritorio.
            from winrt.windows.ui.notifications import NotificationKinds

            for n in await listener.get_notifications_async(NotificationKinds.TOAST):
                try:
                    listener.remove_notification(n.id)
                except Exception as exc:
                    log.warning("No pude quitar una notificación del PC: %s", exc)
            return True
        except Exception as exc:
            log.warning("No pude quitar notificaciones del PC: %s", exc)
            return False
