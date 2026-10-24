"""Chivato de cuelgues: si el servidor deja de atender, apunta dónde se quedó trabado.

Una noche el servidor se quedó colgado horas y no quedó rastro de por qué; el vigilante lo
reinicia, pero no explica la causa. Este hilo aparte comprueba cada pocos segundos que el
bucle principal (el que atiende al teléfono) sigue vivo. Si lleva más de `threshold` segundos
sin responder, escribe en `logs/cuelgues/` la pila de cada hilo: la línea exacta en la que
está cada uno. Una vez por cuelgue (si se recupera y vuelve a colgarse, otro archivo).
"""

from __future__ import annotations

import asyncio
import logging
import sys
import threading
import time
import traceback
from datetime import datetime
from pathlib import Path

log = logging.getLogger(__name__)


def dump_threads() -> str:
    """La pila de cada hilo del proceso, legible."""
    names = {t.ident: t.name for t in threading.enumerate()}
    parts = []
    for ident, frame in sys._current_frames().items():
        parts.append(
            f"--- Hilo {names.get(ident, '?')} ({ident}) ---\n" + "".join(traceback.format_stack(frame))
        )
    return "\n".join(parts)


class StallWatch:
    def __init__(self, folder: Path, threshold: float = 20.0, every: float = 5.0) -> None:
        self._folder = folder
        self._threshold = threshold
        self._every = every
        self._last_beat = time.monotonic()
        self._dumped = False
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.dumps: list[Path] = []

    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        self._last_beat = time.monotonic()
        self._thread = threading.Thread(target=self._run, args=(loop,), name="chivato-cuelgues", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def _beat(self) -> None:
        self._last_beat = time.monotonic()
        self._dumped = False  # se recuperó: el próximo cuelgue tendrá su propio archivo

    def _run(self, loop: asyncio.AbstractEventLoop) -> None:
        while not self._stop.wait(self._every):
            try:
                loop.call_soon_threadsafe(self._beat)
            except RuntimeError:
                return  # el bucle ya se cerró
            stalled = time.monotonic() - self._last_beat
            if stalled > self._threshold and not self._dumped:
                self._dumped = True
                self._write(stalled)

    def _write(self, stalled: float) -> None:
        self._folder.mkdir(parents=True, exist_ok=True)
        path = self._folder / f"cuelgue-{datetime.now():%Y%m%d-%H%M%S}.txt"
        path.write_text(
            f"El servidor lleva {stalled:.0f} s sin atender. Pila de cada hilo:\n\n{dump_threads()}",
            encoding="utf-8",
        )
        self.dumps.append(path)
        log.error("El servidor lleva %.0f s sin atender: pila de los hilos en %s", stalled, path)
