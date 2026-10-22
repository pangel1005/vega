"""Dobles de prueba. Solo se usan en los tests, nunca en el servidor real."""

from __future__ import annotations

from collections.abc import AsyncIterator, Sequence

from server.llm.base import ChatMessage, LlmUnavailable
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SttUnavailable, Transcript
from server.speech.tts import TtsUnavailable, VoiceInfo


class FakeTTS:
    """Sintetizador de mentira: 2 bytes de «audio» por carácter, y recuerda lo que dijo."""

    def __init__(self) -> None:
        self.said: list[str] = []
        self._voice = "es_MX-claude-high"

    @property
    def sample_rate(self) -> int:
        return 22050

    @property
    def voice(self) -> str:
        return self._voice

    def voices(self) -> list[VoiceInfo]:
        return [
            VoiceInfo("es_MX-claude-high", "Claude · México"),
            VoiceInfo("es_AR-daniela-high", "Daniela · Argentina"),
        ]

    def set_voice(self, voice_id: str) -> None:
        if voice_id not in {v.id for v in self.voices()}:
            raise TtsUnavailable(f"No tengo la voz «{voice_id}».")
        self._voice = voice_id

    def warmup(self) -> None:
        pass

    def synthesize(self, text: str) -> bytes:
        self.said.append(text)
        return b"\x01\x00" * len(text)


class FakeSTT:
    """Reconocedor que devuelve un texto fijo y guarda el audio que recibió."""

    def __init__(self, text: str = "hola Vega", fail: str | None = None, texts: Sequence[str] = ()) -> None:
        self.text = text
        self.fail = fail
        self.texts = list(texts)  # si se da, cada llamada «oye» la siguiente frase
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
        text = self.texts.pop(0) if self.texts else self.text
        return Transcript(text, len(pcm16) / (SAMPLE_RATE * BYTES_PER_SAMPLE), 0.05)


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
