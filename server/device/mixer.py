"""El mezclador de volumen de Windows, por programa: «baja Discord», «silencia el navegador», «Spotify al 30».

Usa las sesiones de audio de Windows (`ISimpleAudioVolume`, con pycaw): lo mismo que mueve el mezclador
de volumen del sistema. Solo se puede cambiar lo que tiene una sesión de audio abierta, es decir, lo que
suena o ha sonado hace poco. No oye ni guarda audio: solo lee y cambia números (volumen y silencio).
"""

from __future__ import annotations

import logging
from collections.abc import Callable
from typing import Protocol

log = logging.getLogger(__name__)

# Lo que se dice → los programas de Windows a los que se refiere (nombre del .exe en minúsculas).
ALIASES: dict[str, tuple[str, ...]] = {
    "discord": ("discord.exe",),
    "spotify": ("spotify.exe",),
    "opera": ("opera.exe",),
    "navegador": ("opera.exe", "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe"),
    "chrome": ("chrome.exe",),
    "edge": ("msedge.exe",),
    "firefox": ("firefox.exe",),
    "youtube": ("opera.exe", "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe"),
    "whatsapp": ("whatsapp.root.exe", "whatsapp.exe"),
    "steam": ("steamwebhelper.exe", "steam.exe"),
    "obs": ("obs64.exe",),
    "vlc": ("vlc.exe",),
    "telegram": ("telegram.exe",),
    "teams": ("ms-teams.exe", "teams.exe"),
    "zoom": ("zoom.exe",),
}


class AudioControl(Protocol):
    name: str  # el .exe en minúsculas

    def get_volume(self) -> float: ...
    def set_volume(self, level: float) -> None: ...
    def get_mute(self) -> bool: ...
    def set_mute(self, muted: bool) -> None: ...


class _WindowsControl:
    def __init__(self, name: str, volume: object) -> None:
        self.name = name
        self._v = volume

    def get_volume(self) -> float:
        return float(self._v.GetMasterVolume())  # type: ignore[attr-defined]

    def set_volume(self, level: float) -> None:
        self._v.SetMasterVolume(max(0.0, min(1.0, level)), None)  # type: ignore[attr-defined]

    def get_mute(self) -> bool:
        return bool(self._v.GetMute())  # type: ignore[attr-defined]

    def set_mute(self, muted: bool) -> None:
        self._v.SetMute(1 if muted else 0, None)  # type: ignore[attr-defined]


def windows_controls() -> list[AudioControl]:
    """Las sesiones de audio abiertas ahora, en todas las salidas activas (altavoces, auriculares…)."""
    from comtypes import CLSCTX_ALL  # type: ignore[import-untyped]
    from pycaw.pycaw import (  # type: ignore[import-untyped]
        AudioUtilities,
        IAudioSessionControl2,
        IAudioSessionManager2,
    )
    from pycaw.utils import AudioSession  # type: ignore[import-untyped]

    found: list[AudioControl] = []
    devices = AudioUtilities.GetDeviceEnumerator().EnumAudioEndpoints(0, 1)
    for d in range(devices.GetCount()):
        try:
            manager = devices.Item(d).Activate(IAudioSessionManager2._iid_, CLSCTX_ALL, None)
            sessions = manager.QueryInterface(IAudioSessionManager2).GetSessionEnumerator()
        except Exception:  # una salida que se acaba de desconectar
            continue
        for i in range(sessions.GetCount()):
            try:
                control = sessions.GetSession(i)
                if control is None:
                    continue
                session = AudioSession(control.QueryInterface(IAudioSessionControl2))
                if session.Process is None:
                    continue
                found.append(_WindowsControl(session.Process.name().lower(), session.SimpleAudioVolume))
            except Exception:  # un proceso que acaba de cerrarse
                continue
    return found


def resolve(word: str, active: list[str]) -> tuple[str, ...]:
    """Qué programas son `word` («discord», «fortnite»…): los del diccionario o, si no, los que están
    sonando y empiezan por esa palabra (el .exe de un juego casi siempre empieza por su nombre)."""
    word = word.lower()
    if word in ALIASES:
        return ALIASES[word]
    return tuple(dict.fromkeys(n for n in active if len(word) >= 4 and n.startswith(word)))


class Mixer:
    def __init__(self, controls: Callable[[], list[AudioControl]] = windows_controls) -> None:
        self._controls = controls

    def active(self) -> list[str]:
        return sorted({c.name for c in self._safe()})

    def _safe(self) -> list[AudioControl]:
        try:
            return self._controls()
        except Exception:  # sin audio, o Windows sin esas interfaces
            log.debug("No pude leer el mezclador de Windows", exc_info=True)
            return []

    def _of(self, names: tuple[str, ...]) -> list[AudioControl]:
        return [c for c in self._safe() if c.name in names]

    def sessions(self) -> list[tuple[str, float, bool]]:
        """(programa, volumen 0–1, silenciado) de cada sesión de audio abierta, en una sola lectura."""
        out: list[tuple[str, float, bool]] = []
        for c in self._safe():
            try:
                out.append((c.name, c.get_volume(), c.get_mute()))
            except Exception:  # una sesión que se acaba de cerrar
                continue
        return out

    def volume(self, names: tuple[str, ...]) -> float | None:
        found = self._of(names)
        return max(c.get_volume() for c in found) if found else None

    def set_volume(self, names: tuple[str, ...], level: float) -> bool:
        found = self._of(names)
        for c in found:
            c.set_volume(level)
            if level > 0 and c.get_mute():
                c.set_mute(False)  # subirle el volumen a algo silenciado es querer oírlo
        return bool(found)

    def change(self, names: tuple[str, ...], delta: float) -> float | None:
        """Sube o baja (delta en 0–1) y devuelve el nivel nuevo, o None si no hay sesión de ese programa."""
        found = self._of(names)
        if not found:
            return None
        target = round(max(0.0, min(1.0, max(c.get_volume() for c in found) + delta)), 2)
        for c in found:
            c.set_volume(target)
            if target > 0 and c.get_mute():
                c.set_mute(False)
        return target

    def mute(self, names: tuple[str, ...], muted: bool) -> bool:
        found = self._of(names)
        for c in found:
            c.set_mute(muted)
        return bool(found)
