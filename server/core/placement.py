"""Dónde corre la IA: en la gráfica cuando está libre, en el procesador cuando juegas.

Medido en la RTX 4060 Ti (8 GB) con el escritorio normal abierto (~3,5 GB ya ocupados):
  · modelo con 1 capa en la gráfica: ~0,6 GB de vídeo, respuesta completa en 3–4 s (~10 palabras/s)
  · modelo entero en la gráfica: +3 GB de vídeo (quedan ~1,4 GB libres), respuesta en ~0,7 s (~65 palabras/s)
Jugando, esos 3 GB son los que le faltan al juego (y la gráfica trabajando a tope durante cada respuesta
mete un tirón en los fotogramas). Por eso Vega solo ocupa la gráfica mientras no hay un juego abierto ni
otro programa que se haya quedado con el vídeo, y la deja en cuanto lo hay.
"""

from __future__ import annotations

import asyncio
import logging
import subprocess
import time
from collections.abc import Callable
from typing import Literal, Protocol

log = logging.getLogger(__name__)

Mode = Literal["free", "game"]


class PlacementState:
    """Lo que comparten el vigilante de la gráfica y las conexiones: dónde está la IA ahora y para qué se usa.

    `hybrid`: con la IA en el teléfono, el modelo grande del PC también contesta la charla libre mientras la
    gráfica esté libre (sin juego). `ready`: el modelo está cargado en la gráfica y puede contestar ya."""

    def __init__(self, hybrid: bool = False) -> None:
        self.hybrid = hybrid
        self.phone_brain = False  # la última conexión que pidió «IA en el teléfono»
        self.ready = False
        self.mode: Mode | None = None  # dónde la tiene el vigilante: «free» (gráfica) o «game» (procesador)

    @property
    def can_chat(self) -> bool:
        return self.hybrid and self.ready


class Placeable(Protocol):
    @property
    def busy(self) -> bool: ...

    async def vram_mib(self) -> int | None: ...

    async def place(self, gpu_layers: int, *, reload: bool = True) -> None: ...

    async def preload(self) -> None: ...


class SttPlaceable(Protocol):
    def place(self, model: str, device: str, compute_type: str) -> None: ...

    def warmup(self) -> None: ...


def nvidia_free_mib() -> int | None:
    """Vídeo libre de la gráfica NVIDIA (MiB), o None si no hay o no se puede leer."""
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        ).stdout
        return int(out.strip().splitlines()[0])
    except (OSError, ValueError, IndexError, subprocess.SubprocessError):
        return None


class GpuFollower:
    """Mira cada pocos segundos si hay un juego (o poco vídeo libre) y mueve el modelo de lenguaje y el
    reconocimiento de voz entre la gráfica y el procesador."""

    def __init__(
        self,
        *,
        chat: Placeable,
        stt: SttPlaceable | None,
        gaming: Callable[[], bool],
        free_mib: Callable[[], int | None],
        light_layers: int,
        free_layers: int,
        stt_free: tuple[str, str, str],
        stt_game: tuple[str, str, str],
        need_mib: int = 4000,
        stt_mib: int = 1000,
        leave_mib: int = 300,
        leave_after: float = 10.0,
        back_after: float = 20.0,
        cooloff: float = 180.0,
        poll: float = 3.0,
        clock: Callable[[], float] = time.monotonic,
        state: PlacementState | None = None,
    ) -> None:
        self.state = state or PlacementState()
        self._chat = chat
        self._stt = stt
        self._gaming = gaming
        self._free_mib = free_mib
        self._light = light_layers
        self._free_layers = free_layers
        self._stt_free = stt_free
        self._stt_game = stt_game
        self._need = need_mib
        self._stt_mib = stt_mib
        self._leave = leave_mib  # con menos vídeo libre que esto, otro programa se lo está quedando
        self._leave_after = leave_after  # …y solo si dura: un vídeo o una pestaña dan picos de un momento
        self._cooloff = cooloff  # tras irse por falta de vídeo (no por un juego), no volver antes de esto
        self._low_since: float | None = None
        self._not_before = 0.0
        self._back_after = back_after  # segundos sin juego antes de volver (evita idas y venidas)
        self._poll = poll
        self._clock = clock
        self._mode: Mode | None = None
        self._clear_since: float | None = None

    @property
    def mode(self) -> Mode | None:
        return self._mode

    async def run(self) -> None:
        while True:
            try:
                await self.step()
            except asyncio.CancelledError:
                raise
            except Exception:  # vigilar la gráfica nunca debe tumbar el servidor
                log.exception("No pude reubicar la IA")
            await asyncio.sleep(self._poll)

    async def step(self) -> Mode | None:
        """Una vuelta: decide el sitio y, si cambia, lo aplica. Devuelve el sitio actual."""
        free = await asyncio.to_thread(self._free_mib)
        if free is None:  # sin gráfica NVIDIA: se queda como está configurado
            return self._mode
        gaming = await asyncio.to_thread(self._gaming)
        now = self._clock()
        if self._mode == "free":
            if free >= self._leave:
                self._low_since = None
            elif self._low_since is None:
                self._low_since = now
            starved = self._low_since is not None and now - self._low_since >= self._leave_after
            if (gaming or starved) and not self._chat.busy:  # nunca a mitad de una respuesta
                why = (
                    "juego abierto"
                    if gaming
                    else f"solo {free} MiB libres desde hace {self._leave_after:.0f} s"
                )
                if not gaming:
                    self._not_before = now + self._cooloff
                self._low_since = None
                await self._apply("game", why=why)
            return self._mode
        # En la modalidad ligera (o recién arrancado): hay que dejar pasar un rato sin juego y que quepa.
        if gaming:
            self._clear_since = None
            if self._mode is None:
                await self._apply("game", why="juego abierto")
            return self._mode
        if self._clear_since is None:
            self._clear_since = now
        waited = now - self._clear_since
        if self._mode is not None and (waited < self._back_after or now < self._not_before):
            return self._mode
        mine = await self._chat.vram_mib() or 0
        # La voz a texto solo cuenta si hay que volver a ponerla en la gráfica (viene del procesador): al
        # arrancar ya está cargada y su vídeo ya está ocupado.
        stt_back = self._stt is not None and self._stt_free[1] == "cuda" and self._mode == "game"
        need = self._need + (self._stt_mib if stt_back else 0)
        if free + mine >= need and not self._chat.busy:
            await self._apply("free", why=f"sin juego y con {free + mine} MiB libres")
        elif self._mode is None:
            await self._apply("game", why=f"no caben ({free + mine} MiB libres, hacen falta {need})")
        return self._mode

    async def _apply(self, mode: Mode, *, why: str) -> None:
        log.info("IA en %s: %s", "la gráfica" if mode == "free" else "el procesador", why)
        self._mode = mode
        self.state.mode = mode
        self._clear_since = None
        # Primero el modelo de lenguaje (al irse libera la gráfica ya), después la voz a texto.
        self.state.ready = False
        if mode == "free":
            await self._chat.place(self._free_layers)
            if self.state.hybrid and self.state.phone_brain:
                # Con la IA en el teléfono nadie lo cargaría: se deja listo para la charla libre.
                await self._chat.preload()
                self.state.ready = True
        else:
            # Con la IA en el teléfono, jugando no queda nada del modelo en la gráfica.
            await self._chat.place(self._light, reload=not self.state.phone_brain)
        if self._stt is not None:
            model, device, compute = self._stt_free if mode == "free" else self._stt_game
            # En otro hilo: `place` espera el candado de la voz a texto, y si ese hilo está cargando el modelo
            # (10–35 s) o transcribiendo, esperarlo aquí congelaría el servidor y el teléfono se caería.
            await asyncio.to_thread(self._stt.place, model, device, compute)
            await asyncio.to_thread(self._warm_quietly)

    def _warm_quietly(self) -> None:
        try:
            assert self._stt is not None
            self._stt.warmup()
        except Exception:  # la próxima transcripción lo vuelve a intentar y avisa del error
            log.debug("No pude precargar la voz a texto", exc_info=True)
