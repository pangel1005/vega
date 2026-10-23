"""Lo que suena en el PC (Spotify, el navegador…), para la pantalla «Sonando» del teléfono.

Windows ya sabe qué se reproduce: es lo que sale en el panel de volumen (los «controles
multimedia del sistema»). Se lee con WinRT: canción, artista, álbum, carátula, posición,
y se puede pausar, pasar o volver atrás en esa misma app. En otro sistema no hay datos y
la pantalla simplemente no aparece.
"""

from __future__ import annotations

import hashlib
import io
import logging
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Any, Literal

log = logging.getLogger(__name__)

Command = Literal["play_pause", "next", "previous"]
PLAYING = 4  # GlobalSystemMediaTransportControlsSessionPlaybackStatus.PLAYING
PAUSED = 5
ART_SIZE = 320

# Nombre legible de la app a partir de su identificador de Windows.
_APPS = {
    "spotify": "Spotify",
    "opera": "Opera",
    "chrome": "Chrome",
    "msedge": "Edge",
    "firefox": "Firefox",
    "vlc": "VLC",
    "zunemusic": "Reproductor multimedia",
    "music.ui": "Reproductor multimedia",
}


def app_name(app_id: str) -> str:
    """«Spotify.exe» → «Spotify»; «OperaSoftware.OperaGXWebBrowser.17…» → «Opera»."""
    low = app_id.lower()
    for key, name in _APPS.items():
        if key in low:
            return name
    base = app_id.rsplit("\\", 1)[-1].removesuffix(".exe").removesuffix(".EXE")
    return base.split(".")[0] or "PC"


@dataclass(frozen=True)
class NowPlaying:
    app: str
    title: str
    artist: str
    album: str
    playing: bool
    position: float  # segundos, en el momento de la lectura
    duration: float  # 0 si la app no lo dice
    art_id: str  # vacío si no hay carátula
    can_previous: bool
    can_next: bool

    def same_as(self, other: NowPlaying | None) -> bool:
        """Igual a efectos de la pantalla: misma canción y estado, y la posición no saltó
        (el teléfono la avanza solo entre lecturas)."""
        if other is None:
            return False
        if (self.app, self.title, self.artist, self.playing, self.art_id) != (
            other.app,
            other.title,
            other.artist,
            other.playing,
            other.art_id,
        ):
            return False
        return abs(self.position - other.position) < 3.0


def pick_session(sessions: list[Any], current: Any) -> Any | None:
    """La que está sonando; si ninguna suena, la que Windows considera actual."""
    for s in sessions:
        try:
            if s.get_playback_info().playback_status == PLAYING:
                return s
        except Exception:
            continue
    return current or (sessions[0] if sessions else None)


def live_position(position: float, updated: datetime, playing: bool, now: datetime) -> float:
    """Las apps no actualizan la posición cada segundo: si suena, se suma lo transcurrido."""
    if not playing:
        return position
    return position + max(0.0, (now - updated).total_seconds())


class MediaWatcher:
    """Lee lo que suena en Windows. Todo es asíncrono (WinRT) y nunca lanza: sin datos → None."""

    def __init__(self) -> None:
        self._manager: Any = None
        self._art: dict[str, bytes] = {}  # id → JPEG (solo las últimas carátulas)
        self._art_for: tuple[str, str, str] | None = None
        self._art_id = ""

    async def _sessions(self) -> tuple[list[Any], Any]:
        if self._manager is None:
            from winrt.windows.media.control import (
                GlobalSystemMediaTransportControlsSessionManager as Manager,
            )

            self._manager = await Manager.request_async()
        return list(self._manager.get_sessions()), self._manager.get_current_session()

    async def snapshot(self) -> NowPlaying | None:
        try:
            sessions, current = await self._sessions()
            session = pick_session(sessions, current)
            if session is None:
                return None
            props = await session.try_get_media_properties_async()
            info = session.get_playback_info()
            timeline = session.get_timeline_properties()
            playing = info.playback_status == PLAYING
            if not props.title and not props.artist:
                return None
            app = app_name(session.source_app_user_model_id)
            key = (app, props.title, props.artist)
            if key != self._art_for or not self._art_id:
                # Carátula nueva solo al cambiar de canción (o si aún no había llegado).
                self._art_id = await self._read_art(props.thumbnail)
                self._art_for = key
            position = live_position(
                timeline.position.total_seconds(), timeline.last_updated_time, playing, datetime.now(UTC)
            )
            duration = timeline.end_time.total_seconds()
            return NowPlaying(
                app=app,
                title=props.title or "",
                artist=props.artist or props.album_artist or "",
                album=props.album_title or "",
                playing=playing,
                position=round(min(position, duration) if duration > 0 else position, 1),
                duration=round(duration, 1),
                art_id=self._art_id,
                can_previous=bool(info.controls.is_previous_enabled),
                can_next=bool(info.controls.is_next_enabled),
            )
        except Exception as exc:  # sin WinRT, sin sesiones o una app que responde raro
            log.debug("No pude leer lo que suena: %s", exc)
            return None

    async def _read_art(self, thumbnail: Any) -> str:
        if thumbnail is None:
            return ""
        try:
            from PIL import Image
            from winrt.windows.storage.streams import Buffer, InputStreamOptions

            stream = await thumbnail.open_read_async()
            size = int(stream.size)
            if size <= 0 or size > 10_000_000:
                return ""
            buf = Buffer(size)
            await stream.read_async(buf, size, InputStreamOptions.READ_AHEAD)
            raw = bytes(memoryview(buf))[: buf.length]
            with Image.open(io.BytesIO(raw)) as original:
                img = original.convert("RGB")
            img.thumbnail((ART_SIZE, ART_SIZE))
            out = io.BytesIO()
            img.save(out, "JPEG", quality=88)
            data = out.getvalue()
            art_id = hashlib.sha1(data).hexdigest()[:12]
            self._art[art_id] = data
            while len(self._art) > 8:  # unas pocas: el teléfono pide la actual
                self._art.pop(next(iter(self._art)))
            return art_id
        except Exception as exc:
            log.debug("No pude leer la carátula: %s", exc)
            return ""

    def art(self, art_id: str) -> bytes | None:
        return self._art.get(art_id)

    async def control(self, command: Command) -> bool:
        """Pausa/continúa, siguiente o anterior en la app que está sonando."""
        try:
            sessions, current = await self._sessions()
            session = pick_session(sessions, current)
            if session is None:
                return False
            if command == "play_pause":
                return bool(await session.try_toggle_play_pause_async())
            if command == "next":
                return bool(await session.try_skip_next_async())
            return bool(await session.try_skip_previous_async())
        except Exception as exc:
            log.debug("No pude controlar la reproducción: %s", exc)
            return False
