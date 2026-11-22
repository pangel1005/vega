"""Opus para los altavoces ESP32 («xiaozhi»): paquetes de 60 ms a 16 kHz mono ⇄ PCM16.

Usa PyAV (que ya viene con faster-whisper y trae libopus): no hace falta instalar nada más.
El decodificador de FFmpeg entrega siempre 48 kHz; aquí se baja a 16 kHz, que es lo que espera Whisper.
"""

from __future__ import annotations

import logging

import av
import numpy as np

log = logging.getLogger(__name__)

RATE = 16_000
FRAME_MS = 60
FRAME_SAMPLES = RATE * FRAME_MS // 1000  # 960 muestras por paquete
BIT_RATE = 24_000


def _resampler(rate: int) -> av.AudioResampler:
    return av.AudioResampler(format="s16", layout="mono", rate=rate)


class OpusDecoder:
    """Paquetes Opus (uno por llamada) → PCM16 mono a 16 kHz."""

    def __init__(self) -> None:
        self._ctx = av.CodecContext.create("libopus", "r")
        self._ctx.open()
        self._resampler = _resampler(RATE)

    def decode(self, packet: bytes) -> bytes:
        """El audio de un paquete, o b"" si está corrupto (un paquete perdido no debe cortar la frase)."""
        if not packet:
            return b""
        try:
            out = bytearray()
            for frame in self._ctx.decode(av.Packet(packet)):
                for resampled in self._resampler.resample(frame):
                    out += resampled.to_ndarray().tobytes()
            return bytes(out)
        except (av.error.FFmpegError, ValueError):
            log.debug("Paquete Opus ilegible (%d bytes)", len(packet))
            self._resampler = _resampler(RATE)  # tras un paquete raro, el formato puede haber cambiado
            return b""


class OpusEncoder:
    """PCM16 mono (a cualquier frecuencia) → paquetes Opus de 60 ms a 16 kHz."""

    def __init__(self) -> None:
        self._ctx = av.CodecContext.create("libopus", "w")
        self._ctx.sample_rate = RATE
        self._ctx.layout = "mono"
        self._ctx.format = "s16"
        self._ctx.bit_rate = BIT_RATE
        self._ctx.options = {"frame_duration": str(FRAME_MS), "application": "voip"}
        self._ctx.open()
        self._rate: int | None = None
        self._resampler: av.AudioResampler | None = None
        self._pending = np.zeros(0, dtype=np.int16)
        self._pts = 0

    def _to_16k(self, pcm: bytes, rate: int) -> np.ndarray:
        samples = np.frombuffer(pcm[: len(pcm) - len(pcm) % 2], dtype=np.int16)
        if rate == RATE or samples.size == 0:
            return samples
        if self._resampler is None or self._rate != rate:
            self._rate = rate
            self._resampler = _resampler(RATE)
        frame = av.AudioFrame.from_ndarray(samples.reshape(1, -1), format="s16", layout="mono")
        frame.sample_rate = rate
        parts = [r.to_ndarray().reshape(-1) for r in self._resampler.resample(frame)]
        return np.concatenate(parts) if parts else np.zeros(0, dtype=np.int16)

    def _encode(self, samples: np.ndarray) -> list[bytes]:
        frame = av.AudioFrame.from_ndarray(samples.reshape(1, -1), format="s16", layout="mono")
        frame.sample_rate = RATE
        frame.pts = self._pts
        self._pts += samples.size
        return [bytes(p) for p in self._ctx.encode(frame)]

    def encode(self, pcm: bytes, rate: int = RATE) -> list[bytes]:
        """Los paquetes que ya se pueden mandar; lo que no llega a 60 ms espera a la siguiente llamada."""
        data = np.concatenate([self._pending, self._to_16k(pcm, rate)])
        packets: list[bytes] = []
        usable = data.size - data.size % FRAME_SAMPLES
        for i in range(0, usable, FRAME_SAMPLES):
            packets += self._encode(data[i : i + FRAME_SAMPLES])
        self._pending = data[usable:]
        return packets

    def flush(self) -> list[bytes]:
        """Cierra la frase: rellena con silencio el último trozo y vacía el codificador."""
        packets: list[bytes] = []
        if self._resampler is not None:  # el remuestreador guarda un resto: se vacía antes de cerrar
            tail = [r.to_ndarray().reshape(-1) for r in self._resampler.resample(None)]
            if tail:
                self._pending = np.concatenate([self._pending, *tail])
                while self._pending.size >= FRAME_SAMPLES:
                    packets += self._encode(self._pending[:FRAME_SAMPLES])
                    self._pending = self._pending[FRAME_SAMPLES:]
        if self._pending.size:
            padded = np.zeros(FRAME_SAMPLES, dtype=np.int16)
            padded[: self._pending.size] = self._pending
            packets += self._encode(padded)
        self._pending = np.zeros(0, dtype=np.int16)
        packets += [bytes(p) for p in self._ctx.encode(None)]
        return packets
