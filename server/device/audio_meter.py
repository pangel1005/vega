"""Volumen real de lo que suena por los altavoces del PC, para que la cara de Vega se mueva con la música.

Usa el medidor de pico de Windows (IAudioMeterInformation, con pycaw): lo mismo que dibuja el mezclador de
volumen. No oye ni guarda el audio: solo un número de 0 a 1 que dice cómo de fuerte suena ahora.

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
RETRY_S = 3.0  # si falla (cambió el dispositivo de salida), espera antes de volver a abrir


class _Meter(Protocol):
    def GetPeakValue(self) -> float: ...  # noqa: N802 (el nombre es el de la API de Windows)


def open_default_meter() -> _Meter:
    """Abre el medidor de la salida de audio predeterminada (Windows)."""
    from ctypes import POINTER, cast

    from comtypes import CLSCTX_ALL  # type: ignore[import-untyped]
    from pycaw.pycaw import AudioUtilities, IAudioMeterInformation  # type: ignore[import-untyped]

    device = AudioUtilities.GetSpeakers()
    endpoint = getattr(device, "_dev", device)  # según la versión de pycaw
    interface = endpoint.Activate(IAudioMeterInformation._iid_, CLSCTX_ALL, None)
    return cast(interface, POINTER(IAudioMeterInformation))  # type: ignore[no-any-return]


class AudioMeter:
    def __init__(
        self,
        open_meter: Callable[[], _Meter] = open_default_meter,
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
                except Exception as exc:
                    log.debug("No pude leer el volumen de la salida: %s", type(exc).__name__)
                    meter, self._level = None, None
                    self._stop.wait(RETRY_S)
                    continue
                self._stop.wait(SAMPLE_S)
        finally:
            if com is not None:
                com.CoUninitialize()
