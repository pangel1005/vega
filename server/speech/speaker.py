"""Locutor: convierte frases en voz en orden y las envía, sin frenar la respuesta escrita.

Cada respuesta crea un `Speaker`. Las frases se encolan según llegan del modelo; un
único trabajador las sintetiza (en otro hilo) y las envía al teléfono en el mismo
orden: un evento `audio` seguido del audio en binario. Al terminar, `audio_end`.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Awaitable, Callable

from server.speech.tts import TextToSpeech, TtsUnavailable
from server.ws.protocol import AudioEnd, AudioEvent, ServerEvent

log = logging.getLogger(__name__)

Emit = Callable[[ServerEvent], Awaitable[None]]
# Envía la cabecera de una frase (evento `audio`) y su voz en binario, juntas.
EmitAudio = Callable[[ServerEvent, bytes], Awaitable[None]]


class Speaker:
    def __init__(self, tts: TextToSpeech, emit: Emit, emit_audio: EmitAudio) -> None:
        self._tts = tts
        self._emit = emit
        self._emit_audio = emit_audio
        self._queue: asyncio.Queue[str | None] = asyncio.Queue()
        self._worker: asyncio.Task[None] | None = None
        self._seq = 0
        self._started = asyncio.get_running_loop().time()
        self.first_audio_after: float | None = None  # segundos hasta la primera frase con voz

    def say(self, text: str) -> None:
        if not text.strip():
            return
        if self._worker is None:
            self._worker = asyncio.create_task(self._run())
        self._queue.put_nowait(text)

    async def finish(self) -> None:
        """Espera a que salga toda la voz encolada y avisa del final."""
        if self._worker is None:
            return
        self._queue.put_nowait(None)
        await self._worker
        await self._emit(AudioEnd())

    def cancel(self) -> None:
        if self._worker is not None:
            self._worker.cancel()

    async def _run(self) -> None:
        loop = asyncio.get_running_loop()
        while (text := await self._queue.get()) is not None:
            try:
                pcm = await asyncio.to_thread(self._tts.synthesize, text)
                rate = self._tts.sample_rate
            except TtsUnavailable as exc:
                log.warning("Sin voz: %s", exc)
                continue
            self._seq += 1
            if self.first_audio_after is None:
                self.first_audio_after = loop.time() - self._started
            await self._emit_audio(AudioEvent(seq=self._seq, rate=rate, text=text), pcm)
