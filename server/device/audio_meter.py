"""Volumen real de lo que suena por los altavoces del PC, para que la cara de Vega se mueva con la música.

Usa el medidor de pico de Windows (IAudioMeterInformation, con pycaw): lo mismo que dibuja el mezclador de
volumen, pero solo el de la sesión de audio de la app pedida (Spotify), no el de todo el PC: así el navegador,
Discord o los juegos no mueven la onda. No oye ni guarda el audio: solo un número de 0 a 1 (cuánto suena).

Un hilo propio lo lee unas 25 veces por segundo, pero solo mientras alguien lo pide (`poll`):
sin música, duerme.
"""

from __future__ import annotations

import logging
import threading
import time
from collections.abc import Callable
from typing import Any, Protocol

log = logging.getLogger(__name__)

SAMPLE_S = 0.04  # ~25 lecturas por segundo
IDLE_S = 0.5  # cuando nadie lo pide
ACTIVE_FOR_S = 1.5  # sigue leyendo este rato después de cada `poll`
LOOKUP_S = 0.25  # cada cuánto se mira si la app ya tiene audio
RETRY_S = 3.0  # si falla (cambió el dispositivo de salida), espera antes de volver a abrir


class _Meter(Protocol):
    def GetPeakValue(self) -> float: ...  # noqa: N802 (el nombre es el de la API de Windows)


SESSIONS_REFRESH_S = 2.0  # cada cuánto se vuelve a buscar la sesión de la app (nace al empezar a sonar)


def windows_sessions() -> list[tuple[str, _Meter]]:
    """Las sesiones de audio abiertas ahora en Windows, en TODAS las salidas activas (altavoces, auriculares,
    monitores…): una app puede sonar por una que no sea la predeterminada.
    Devuelve (nombre del programa en minúsculas, su medidor)."""
    from comtypes import CLSCTX_ALL  # type: ignore[import-untyped]
    from pycaw.pycaw import (  # type: ignore[import-untyped]
        AudioUtilities,
        IAudioMeterInformation,
        IAudioSessionControl2,
        IAudioSessionManager2,
    )
    from pycaw.utils import AudioSession  # type: ignore[import-untyped]

    found: list[tuple[str, _Meter]] = []
    devices = AudioUtilities.GetDeviceEnumerator().EnumAudioEndpoints(0, 1)  # salidas (0) activas (1)
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
                found.append(
                    (session.Process.name().lower(), session._ctl.QueryInterface(IAudioMeterInformation))
                )
            except Exception:  # un proceso que acaba de cerrarse
                continue
    return found


class ProcessMeter:
    """Pico de las sesiones de audio de unos programas (p. ej. `spotify.exe`).

    Si ahora no tienen ninguna sesión (no suenan en este PC: pausados, o por otro dispositivo como Spotify
    Connect) lanza `LookupError`: no hay nada que medir, y es distinto de «suena en silencio» (0).
    """

    def __init__(
        self,
        names: tuple[str, ...],
        sessions: Callable[[], list[tuple[str, _Meter]]] = windows_sessions,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._names = tuple(n.lower() for n in names)
        self._sessions = sessions
        self._clock = clock
        self._meters: list[_Meter] = []
        self._refreshed = float("-inf")

    def GetPeakValue(self) -> float:  # noqa: N802 (mismo nombre que el medidor de Windows)
        now = self._clock()
        if now - self._refreshed >= SESSIONS_REFRESH_S:
            self._meters = [m for name, m in self._sessions() if name in self._names]
            self._refreshed = now
        if not self._meters:
            raise LookupError("sin sesión de audio")
        try:
            return max(m.GetPeakValue() for m in self._meters)
        except Exception as exc:  # una sesión que ya no existe: se vuelve a buscar en la próxima lectura
            self._meters, self._refreshed = [], float("-inf")
            raise LookupError("la sesión de audio se cerró") from exc


def open_spotify_meter() -> _Meter:
    return ProcessMeter(("spotify.exe",))


class AudioMeter:
    def __init__(
        self,
        open_meter: Callable[[], _Meter] = open_spotify_meter,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._open = open_meter
        self._clock = clock
        self._level: float | None = None
        self._wanted_until = 0.0
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()

    def poll(self) -> float | None:
        """Pide la lectura y mantiene el hilo despierto: el último pico (0 a 1), o None si no se mide."""
        self._wanted_until = self._clock() + ACTIVE_FOR_S
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, name="vega-medidor", daemon=True)
            self._thread.start()
        return self._level

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        meter: _Meter | None = None
        com: Any = None
        try:
            import comtypes

            com = comtypes
            com.CoInitialize()  # cada hilo que usa COM lo inicia
        except Exception:
            com = None  # sin comtypes (o sin Windows) el medidor falla al abrir y se queda en None
        try:
            while not self._stop.is_set():
                if self._clock() >= self._wanted_until:
                    self._level = None  # nadie lo pide: sin lectura vieja que engañe
                    meter = None  # y se vuelve a abrir al empezar de nuevo (el dispositivo pudo cambiar)
                    self._stop.wait(IDLE_S)
                    continue
                try:
                    if meter is None:
                        meter = self._open()
                    self._level = max(0.0, min(1.0, float(meter.GetPeakValue())))
                except (
                    LookupError
                ):  # la app no tiene audio en este PC: sin lectura, y se vuelve a mirar enseguida
                    self._level = None
                    self._stop.wait(LOOKUP_S)
                    continue
                except Exception as exc:
                    log.debug("No pude leer el volumen de la salida: %s", type(exc).__name__)
                    meter, self._level = None, None
                    self._stop.wait(RETRY_S)
                    continue
                self._stop.wait(SAMPLE_S)
        finally:
            if com is not None:
                com.CoUninitialize()
