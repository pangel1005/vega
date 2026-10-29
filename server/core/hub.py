"""Centro de avisos: lleva la cuenta de los clientes conectados, les entrega los
recordatorios cuando vencen y les manda la telemetría (estado del PC y próximos avisos)
para el panel del teléfono.

Un recordatorio solo se marca como entregado si había alguien conectado: si el
teléfono estaba desenchufado, se avisa en cuanto se vuelve a conectar.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from datetime import datetime
from typing import Protocol

from server.device.games import Game, GameWatcher
from server.device.media import Command, MediaWatcher, NowPlaying
from server.device.notifications import NotificationReader, PcNotification
from server.memory.personal import PersonalMemory, Reminder
from server.skills.status import PcSample, sample_pc
from server.ws.protocol import (
    GameEvent,
    GameNews,
    MediaEvent,
    MusicLevelEvent,
    MusicSpectrumEvent,
    NotificationItem,
    NotificationsEvent,
    PhoneControlEvent,
    PhoneState,
    PhoneStateEvent,
    RecentGameItem,
    ServerEvent,
    TelemetryEvent,
    Upcoming,
)

log = logging.getLogger(__name__)

CHECK_SECONDS = 1.0
TELEMETRY_EVERY = 2  # vueltas de CHECK_SECONDS entre lecturas del PC
UPCOMING_MAX = 3
LEVEL_SECONDS = 0.05  # el volumen de la música llega ~20 veces por segundo


class Listener(Protocol):
    async def announce(self, reminder: Reminder, late: bool) -> None: ...

    async def send_telemetry(self, event: TelemetryEvent) -> None: ...

    async def send_media(self, event: MediaEvent) -> None: ...

    async def send_notifications(self, event: NotificationsEvent) -> None: ...

    async def send_event(self, event: ServerEvent) -> None: ...

    async def send_actions(self) -> None: ...


class Hub:
    def __init__(
        self,
        memory: PersonalMemory,
        now: Callable[[], datetime] = datetime.now,
        sample: Callable[[], PcSample] = sample_pc,
        media: MediaWatcher | None = None,
        notifications: NotificationReader | None = None,
        level: Callable[[], float | None] | None = None,
        level_apps: tuple[str, ...] = ("spotify",),
        spectrum: Callable[[], list[float] | None] | None = None,
        games: GameWatcher | None = None,
    ) -> None:
        self._games = games
        self._game_sent: GameEvent | None = None
        self._game_known = False
        self._memory = memory
        self._level = level  # volumen real de la app que suena (0 a 1), o None si no se puede medir
        self._spectrum = spectrum  # bandas de frecuencia del audio de Spotify (o None si no se analiza)
        self._level_apps = (
            level_apps  # solo se mide si suena una de estas apps (Spotify, no el audio general)
        )
        self._now = now
        self._sample = sample
        self._media = media
        self._media_sent: NowPlaying | None = None
        self._media_known = False  # ¿ya se mandó algo (aunque sea «nada suena»)?
        self._notifications = notifications
        self._notes_sent: list[PcNotification] | None = None  # None = aún no se mandó nada
        self._clients: set[Listener] = set()
        self._roles: dict[Listener, str] = {}
        self._phone_state: PhoneState | None = None
        self._task: asyncio.Task[None] | None = None
        self._level_task: asyncio.Task[None] | None = None

    def add(self, client: Listener, role: str = "phone") -> None:
        self._clients.add(client)
        self._roles[client] = role
        self._media_known = False  # el que llega recibe lo que suena en la próxima vuelta
        self._game_known = False  # y el juego
        self._notes_sent = None  # y las notificaciones

    def remove(self, client: Listener) -> asyncio.Task[None] | None:
        """Quita un cliente. Si era el último teléfono, avisa a la app del PC de que se fue."""
        self._clients.discard(client)
        role = self._roles.pop(client, "phone")
        if role == "phone" and not self._with_role("phone"):
            self._phone_state = None
            return asyncio.ensure_future(self.broadcast("desktop", PhoneStateEvent(online=False)))
        return None

    def _with_role(self, role: str) -> list[Listener]:
        return [c for c in self._clients if self._roles.get(c, "phone") == role]

    async def broadcast(self, role: str, event: ServerEvent) -> None:
        """Manda un evento a todos los clientes de un tipo («phone» o «desktop»)."""
        for client in self._with_role(role):
            try:
                await client.send_event(event)
            except Exception:
                log.debug("No pude mandar un evento a un cliente", exc_info=True)

    async def broadcast_all(self, event: ServerEvent) -> None:
        for role in ("phone", "desktop"):
            await self.broadcast(role, event)

    async def mirror(self, event: ServerEvent) -> None:
        """Lo que pasa en el teléfono (oye, piensa, responde) se ve también en la app del PC."""
        await self.broadcast("desktop", event)

    async def set_phone_state(self, state: PhoneState) -> None:
        self._phone_state = state
        await self.broadcast("desktop", PhoneStateEvent(**state.model_dump(), online=True))

    async def send_phone_state(self, client: Listener) -> None:
        """A la app del PC que acaba de conectar: cómo está el teléfono ahora."""
        event = (
            PhoneStateEvent(**self._phone_state.model_dump(), online=True)
            if self._phone_state is not None
            else PhoneStateEvent(online=bool(self._with_role("phone")))
        )
        await client.send_event(event)

    async def control_phone(self, command: str) -> None:
        await self.broadcast("phone", PhoneControlEvent(command=command))  # type: ignore[arg-type]

    async def refresh_actions(self) -> None:
        for client in list(self._clients):
            try:
                await client.send_actions()
            except Exception:
                log.debug("No pude mandar los botones a un cliente", exc_info=True)

    async def deliver_due(self) -> int:
        """Entrega los recordatorios vencidos. Devuelve cuántos se entregaron."""
        if not self._clients:
            return 0
        now = self._now()
        due = await asyncio.to_thread(self._memory.due, now)
        for reminder in due:
            late = (now - reminder.due_at).total_seconds() > 60  # p. ej. el teléfono estaba desenchufado
            for client in list(self._clients):
                try:
                    await client.announce(reminder, late)
                except Exception:  # un cliente que se cae no debe impedir avisar a los demás
                    log.exception("No pude avisar a un cliente")
            await asyncio.to_thread(self._memory.mark_delivered, reminder.id)
            log.info(
                "Recordatorio %d entregado (%s)", reminder.id, reminder.kind
            )  # sin el texto: lo dijiste en voz alta
        return len(due)

    def telemetry(self) -> TelemetryEvent:
        """Lee el PC y los próximos avisos (bloquea un poco: llamar en un hilo)."""
        pc = self._sample()
        now = self._now()
        upcoming = [
            Upcoming(kind=r.kind, text=r.text, due_in=max(0, round((r.due_at - now).total_seconds())))
            for r in self._memory.pending()[:UPCOMING_MAX]
        ]
        return TelemetryEvent(
            cpu=pc.cpu,
            ram_used=round(pc.ram_used, 1),
            ram_total=round(pc.ram_total, 1),
            gpu_temp=pc.gpu_temp,
            gpu_util=pc.gpu_util,
            vram_used=None if pc.vram_used is None else round(pc.vram_used, 1),
            vram_total=None if pc.vram_total is None else round(pc.vram_total, 1),
            upcoming=upcoming,
        )

    async def send_telemetry(self) -> None:
        if not self._clients:
            return
        event = await asyncio.to_thread(self.telemetry)
        for client in list(self._clients):
            try:
                await client.send_telemetry(event)
            except Exception:
                log.debug("No pude mandar la telemetría a un cliente", exc_info=True)

    async def send_media(self) -> None:
        """Manda lo que suena si cambió (canción, pausa, carátula o un salto de posición)."""
        if self._media is None or not self._clients:
            return
        now = await self._media.snapshot()
        if self._media_known and (now.same_as(self._media_sent) if now else self._media_sent is None):
            return
        self._media_sent, self._media_known = now, True
        event = (
            MediaEvent(active=False)
            if now is None
            else MediaEvent(
                active=True,
                app=now.app,
                title=now.title,
                artist=now.artist,
                album=now.album,
                playing=now.playing,
                position=now.position,
                duration=now.duration,
                art=now.art_id,
                can_previous=now.can_previous,
                can_next=now.can_next,
            )
        )
        for client in list(self._clients):
            try:
                await client.send_media(event)
            except Exception:
                log.debug("No pude mandar lo que suena a un cliente", exc_info=True)

    def _game_event(self, game: Game | None) -> GameEvent:
        if self._games is None:
            return GameEvent(active=False)
        if game is None:
            recent = [
                RecentGameItem(
                    name=r.name,
                    art=r.art_id,
                    hours=None if r.hours is None else round(r.hours, 1),
                    last_played=r.last_played,
                )
                for r in self._games.recent()
            ]
            return GameEvent(active=False, recent=recent, account=self._games.account)
        info = self._games.info(game)
        live = self._games.live(game)
        ach = live.achievements
        return GameEvent(
            active=True,
            key=game.key,
            name=game.name,
            source=game.source,
            played=max(0.0, round(time.time() - game.started)),
            hours=None if game.hours is None else round(game.hours, 1),
            size_gb=game.size_gb,
            art=game.art_id,
            summary=info.summary if info else "",
            genres=list(info.genres) if info else [],
            developer=info.developer if info else "",
            released=info.released if info else "",
            rating=info.rating if info else None,
            tags=list(info.tags) if info else [],
            achievements=(ach.total if ach else info.achievements if info else None),
            played_2w=None if game.played_2w is None else round(game.played_2w, 1),
            players=live.players,
            news=[GameNews(title=n.title, at=n.at) for n in live.news],
            ach_unlocked=ach.unlocked if ach else None,
            ach_recent=list(ach.recent) if ach else [],
            friends=list(live.friends),
            account=self._games.account,
        )

    async def send_game(self) -> None:
        """Manda al teléfono el juego que se está jugando, cuando empieza, termina o llega su información."""
        if self._games is None or not self._with_role("phone"):
            return
        event = self._game_event(await asyncio.to_thread(self._games.current))
        previous = self._game_sent
        # `played` crece solo: el teléfono lo sigue contando, así que no cuenta como cambio.
        if (
            self._game_known
            and previous is not None
            and event.model_copy(update={"played": 0}) == previous.model_copy(update={"played": 0})
        ):
            return
        self._game_sent, self._game_known = event, True
        await self.broadcast("phone", event)

    def game_art(self, art_id: str) -> bytes | None:
        return self._games.art(art_id) if self._games else None

    async def send_notifications(self) -> None:
        """Manda las notificaciones del PC si cambiaron (llega una, se lee o se descarta)."""
        if self._notifications is None or not self._clients:
            return
        items = await self._notifications.read()
        if items is None or items == self._notes_sent:
            return
        self._notes_sent = items
        event = NotificationsEvent(
            items=[
                NotificationItem(id=n.id, app=n.app, title=n.title, body=n.body, at=n.at, icon=n.icon)
                for n in items
            ]
        )
        for client in list(self._clients):
            try:
                await client.send_notifications(event)
            except Exception:
                log.debug("No pude mandar las notificaciones a un cliente", exc_info=True)

    async def dismiss_notification(self, notification_id: int | None) -> None:
        if self._notifications is not None and await self._notifications.dismiss(notification_id):
            await self.send_notifications()

    def latest_notifications(self) -> list[PcNotification]:
        """Las últimas notificaciones leídas del PC (para «¿qué notificaciones tengo?»)."""
        return list(self._notes_sent or [])

    def notification_icon(self, icon_id: str) -> bytes | None:
        return self._notifications.icon(icon_id) if self._notifications else None

    async def media_control(self, command: Command, position: float | None = None) -> None:
        if self._media is not None and await self._media.control(command, position):
            self._media_known = False  # que el teléfono vea el cambio enseguida
            await asyncio.sleep(0.3)  # la app tarda un instante en reflejarlo
            await self.send_media()

    def media_art(self, art_id: str) -> bytes | None:
        return self._media.art(art_id) if self._media else None

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._loop())
        if self._level_task is None and (self._level is not None or self._spectrum is not None):
            self._level_task = asyncio.create_task(self._level_loop())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()
        if self._level_task is not None:
            self._level_task.cancel()

    async def send_level(self) -> bool:
        """Manda el volumen de la música al teléfono, si suena algo; devuelve si mandó algo."""
        sent = self._media_sent
        playing = sent is not None and sent.playing and any(a in sent.app.lower() for a in self._level_apps)
        if self._level is None or not playing or not self._with_role("phone"):
            return False
        level = self._level()
        if level is None:
            return False
        await self.broadcast("phone", MusicLevelEvent(level=round(level, 3)))
        return True

    async def send_spectrum(self) -> bool:
        """Manda al teléfono las bandas de frecuencia de la música de Spotify; devuelve si mandó algo."""
        sent = self._media_sent
        playing = sent is not None and sent.playing and any(a in sent.app.lower() for a in self._level_apps)
        if self._spectrum is None or not playing or not self._with_role("phone"):
            return False
        bands = self._spectrum()
        if bands is None:
            return False
        await self.broadcast("phone", MusicSpectrumEvent(bands=bands))
        return True

    async def send_music(self) -> bool:
        """Manda al teléfono el volumen y el espectro de Spotify.

        El espectro solo si Spotify tiene audio real en este PC: si suena por otro dispositivo, la captura
        daría silencio y las barras se quedarían planas (engañosas).
        """
        level = await self.send_level()
        if self._level is not None and not level:
            return False
        return await self.send_spectrum() or level

    async def _level_loop(self) -> None:
        while True:
            try:
                sent = await self.send_music()
            except Exception:
                log.exception("Fallo al medir la música")
                sent = False
            await asyncio.sleep(LEVEL_SECONDS if sent else 0.5)

    async def _loop(self) -> None:
        tick = 0
        while True:
            try:
                await self.deliver_due()
            except Exception:
                log.exception("Fallo al revisar recordatorios")
            try:
                await self.send_media()
            except Exception:
                log.exception("Fallo al leer lo que suena")
            try:
                await self.send_game()
            except Exception:
                log.exception("Fallo al leer el juego")
            if tick % TELEMETRY_EVERY == 0:
                try:
                    await self.send_telemetry()
                except Exception:
                    log.exception("Fallo al leer la telemetría")
            else:  # en las vueltas sin telemetría, para repartir el trabajo
                try:
                    await self.send_notifications()
                except Exception:
                    log.exception("Fallo al leer las notificaciones")
            tick += 1
            await asyncio.sleep(CHECK_SECONDS)
