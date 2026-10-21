"""Dobles de prueba. Solo se usan en los tests, nunca en el servidor real."""

from __future__ import annotations

from collections.abc import AsyncIterator, Sequence

from server.llm.base import ChatMessage, LlmUnavailable
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SttUnavailable, Transcript


class FakeSTT:
    """Reconocedor que devuelve un texto fijo y guarda el audio que recibió."""

    def __init__(self, text: str = "hola Vega", fail: str | None = None) -> None:
        self.text = text
        self.fail = fail
        self.received: list[bytes] = []

    @property
    def name(self) -> str:
        return "fake-stt"

    def warmup(self) -> None:
        pass

    def transcribe(self, pcm16: bytes) -> Transcript:
        self.received.append(pcm16)
        if self.fail:
            raise SttUnavailable(self.fail)
        return Transcript(self.text, len(pcm16) / (SAMPLE_RATE * BYTES_PER_SAMPLE), 0.05)


class FakeChat:
    """Modelo que responde con trozos fijos y recuerda lo que recibió."""

    def __init__(self, pieces: Sequence[str] = ("Hola", ", ", "¿qué tal?"), fail: str | None = None) -> None:
        self.pieces = list(pieces)
        self.fail = fail
        self.calls: list[list[ChatMessage]] = []
        self.preloads = 0

    @property
    def name(self) -> str:
        return "fake"

    async def preload(self) -> None:
        self.preloads += 1

    async def stream(self, messages: Sequence[ChatMessage]) -> AsyncIterator[str]:
        self.calls.append(list(messages))
        if self.fail:
            raise LlmUnavailable(self.fail)
        for piece in self.pieces:
            yield piece
