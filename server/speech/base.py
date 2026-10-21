"""Interfaz común para el reconocimiento de voz (voz → texto)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

SAMPLE_RATE = 16_000
BYTES_PER_SAMPLE = 2  # PCM de 16 bits, mono


@dataclass(frozen=True)
class Transcript:
    text: str
    audio_seconds: float
    elapsed_seconds: float


class SttUnavailable(Exception):
    """El reconocimiento de voz no puede funcionar. El mensaje está pensado para el usuario."""


class SpeechToText(Protocol):
    @property
    def name(self) -> str: ...

    def warmup(self) -> None:
        """Carga el modelo por adelantado para que la primera frase no tarde más."""
        ...

    def transcribe(self, pcm16: bytes) -> Transcript:
        """Transcribe audio PCM16 mono a 16 kHz. Bloquea: llamar desde un hilo aparte."""
        ...
