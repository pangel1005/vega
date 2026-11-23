"""Conexión real con Moodle: su servicio web (el de la app oficial «Moodle»), con un token tuyo.

A diferencia del calendario exportado, aquí Moodle dice qué entregas siguen **por hacer** (la «Cronología»
de la página de inicio): lo que ya entregaste desaparece solo y Vega deja de avisar de ello. Solo se leen
datos tuyos (nombre, cronología); nunca se entrega ni se cambia nada. El token se guarda en
`config/moodle.json` (fuera de git) y nunca se escribe en los registros; tu contraseña no se guarda: solo
sirve, una vez, para pedir el token.
"""

from __future__ import annotations

import html
import logging
from datetime import datetime, timedelta
from typing import Any

import httpx

from server.core.http import async_client
from server.skills.moodle import MoodleError, Task, clean_title

log = logging.getLogger(__name__)

PAGE = 50  # eventos por petición (el máximo que Moodle da)
MAX_PAGES = 10
BACK_DAYS = 7  # también lo vencido hace poco que sigue sin entregarse
AHEAD_DAYS = 240
UID_PREFIX = "ws:"  # distingue estas entregas de las leídas del calendario exportado

# Códigos de Moodle que significan «este acceso ya no vale» (avisar de una vez, no esperar horas).
_DEAD = {"invalidtoken", "invalidlogin", "accessexception", "webservicesnotenabled", "servicenotavailable"}


class AccessError(MoodleError):
    """El token no vale o el servicio web está apagado: hay que repetir la configuración."""

    fatal = True


def _raise_for(data: Any) -> None:
    """Moodle contesta 200 con `{"exception": ..., "errorcode": ...}` cuando algo falla."""
    if isinstance(data, dict) and ("exception" in data or ("error" in data and "errorcode" in data)):
        code = str(data.get("errorcode") or "desconocido")
        if code in _DEAD:
            raise AccessError(f"Moodle no acepta el acceso ({code}).")
        raise MoodleError(f"Moodle devolvió un error ({code}).")


async def _post(url: str, data: dict[str, Any], transport: httpx.AsyncBaseTransport | None = None) -> Any:
    try:
        async with async_client(timeout=30, follow_redirects=True, transport=transport) as client:
            response = await client.post(url, data=data)
    except httpx.HTTPError as exc:  # el mensaje de httpx puede llevar la dirección: solo el tipo
        raise MoodleError(f"No pude conectar con Moodle ({type(exc).__name__}).") from None
    if response.status_code != 200:
        raise MoodleError(f"Moodle contestó con el error {response.status_code}.")
    try:
        data_out = response.json()
    except ValueError:
        raise MoodleError("Moodle no devolvió datos legibles.") from None
    _raise_for(data_out)
    return data_out


async def request_token(
    site: str, username: str, password: str, transport: httpx.AsyncBaseTransport | None = None
) -> str:
    """Cambia usuario y contraseña por un token del servicio de la app móvil (la contraseña no se guarda)."""
    result = await _post(
        f"{site.rstrip('/')}/login/token.php",
        {"username": username, "password": password, "service": "moodle_mobile_app"},
        transport,
    )
    token = str(result.get("token") or "") if isinstance(result, dict) else ""
    if not token:
        raise MoodleError("Moodle no dio ningún token.")
    return token


def course_name(raw: str) -> str:
    """«ADM-01-201 ADMINISTRACIÓN Y GESTIÓN EMPRESARIAL» → «Administración y Gestión Empresarial»:
    el nombre sin el código y sin gritar (en voz alta suena mejor)."""
    name = html.unescape(raw).strip()
    words = name.split()
    if len(words) > 1 and any(c.isdigit() for c in words[0]):
        words = words[1:]  # lo primero es el código de la materia
    name = " ".join(words)
    if name.isupper() and len(words) > 1:
        small = {"y", "de", "del", "la", "el", "en", "a", "e", "o", "para", "por", "con"}
        name = " ".join(
            w.lower() if w.lower() in small and i else w.capitalize() for i, w in enumerate(words)
        )
    return name


def _local(seconds: float) -> datetime:
    return datetime.fromtimestamp(seconds)  # hora local del PC, sin zona (como todo lo demás)


def parse_action_events(data: Any) -> list[Task]:
    """Los eventos de la cronología que siguen por hacer, como entregas."""
    events = data.get("events") if isinstance(data, dict) else None
    tasks: dict[str, Task] = {}
    for event in events or []:
        try:
            action = event.get("action") or {}
            if action.get("actionable") is False or event.get("eventtype") == "open":
                continue
            course = event.get("course") or {}
            name = str(
                course.get("fullnamedisplay") or course.get("fullname") or course.get("shortname") or ""
            )
            uid = f"{UID_PREFIX}{int(event['id'])}"
            tasks[uid] = Task(
                uid=uid,
                title=clean_title(html.unescape(str(event.get("name") or ""))) or "Entrega",
                course=course_name(name),
                due=_local(float(event["timesort"])),
                url=str(event.get("url") or action.get("url") or ""),
            )
        except (KeyError, TypeError, ValueError):
            continue  # un evento raro no debe tirar la lectura de los demás
    return sorted(tasks.values(), key=lambda t: t.due)


class MoodleApi:
    def __init__(self, site: str, token: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._url = f"{site.rstrip('/')}/webservice/rest/server.php"
        self._token = token
        self._transport = transport

    async def call(self, function: str, **params: Any) -> Any:
        body = {"wstoken": self._token, "wsfunction": function, "moodlewsrestformat": "json", **params}
        return await _post(self._url, body, self._transport)

    async def site_info(self) -> dict[str, Any]:
        info = await self.call("core_webservice_get_site_info")
        return info if isinstance(info, dict) else {}

    async def pending(self, now: datetime) -> list[Task]:
        """Lo pendiente de la cronología: lo vencido hace poco y lo que viene (todas las páginas)."""
        since = int((now - timedelta(days=BACK_DAYS)).timestamp())
        until = int((now + timedelta(days=AHEAD_DAYS)).timestamp())
        tasks: dict[str, Task] = {}
        after = 0
        for _ in range(MAX_PAGES):
            page = await self.call(
                "core_calendar_get_action_events_by_timesort",
                timesortfrom=since,
                timesortto=until,
                aftereventid=after,
                limitnum=PAGE,
                limittononsuspendedevents=1,
            )
            for task in parse_action_events(page):
                tasks[task.uid] = task
            events = page.get("events") if isinstance(page, dict) else None
            last = int(page.get("lastid") or 0) if isinstance(page, dict) else 0
            if not events or len(events) < PAGE or not last:
                break
            after = last
        return sorted(tasks.values(), key=lambda t: t.due)
