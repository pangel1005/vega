"""`SpeechToText` con faster-whisper (CTranslate2) y el filtro de voz Silero VAD que trae incluido."""

from __future__ import annotations

import logging
import os
import sys
import threading
import time
from pathlib import Path
from typing import TYPE_CHECKING

import numpy as np

from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SttUnavailable, Transcript

if TYPE_CHECKING:
    from faster_whisper import WhisperModel

log = logging.getLogger(__name__)


def enable_cuda_dlls() -> None:
    """En Windows, hace visibles las DLL de CUDA instaladas como paquetes de pip (nvidia-*)."""
    if sys.platform != "win32":
        return
    import site

    for base in site.getsitepackages():
        nvidia = Path(base) / "nvidia"
        if not nvidia.is_dir():
            continue
        for bin_dir in nvidia.glob("*/bin"):
            os.add_dll_directory(str(bin_dir))
            os.environ["PATH"] = f"{bin_dir}{os.pathsep}{os.environ['PATH']}"


class WhisperSTT:
    def __init__(
        self,
        model: str,
        device: str,
        compute_type: str,
        language: str,
        download_root: Path,
        cpu_threads: int = 8,
    ) -> None:
        self._model_name = model
        self._device = device
        self._compute_type = compute_type
        self._language = language
        self._download_root = download_root
        self._cpu_threads = cpu_threads
        self._model: WhisperModel | None = None
        self._lock = threading.Lock()

    @property
    def name(self) -> str:
        return f"whisper-{self._model_name} ({self._device})"

    def _load(self) -> WhisperModel:
        if self._model is not None:
            return self._model
        enable_cuda_dlls()
        from faster_whisper import WhisperModel

        start = time.perf_counter()
        try:
            self._model = self._create(WhisperModel, self._device, self._compute_type)
            self._model.transcribe(np.zeros(SAMPLE_RATE // 2, dtype=np.float32), language=self._language)
        except (RuntimeError, ValueError) as exc:
            if self._device == "cpu":
                raise SttUnavailable(f"No pude cargar el reconocimiento de voz: {exc}") from exc
            # Sin GPU (o sin librerías de CUDA): funciona igual en CPU, más despacio.
            log.warning("Whisper no arranca en %s (%s); uso la CPU", self._device, exc)
            self._device = "cpu"
            self._model = self._create(WhisperModel, "cpu", "int8")
        log.info("Voz a texto lista: %s en %.1f s", self.name, time.perf_counter() - start)
        return self._model

    def _create(self, cls: type[WhisperModel], device: str, compute_type: str) -> WhisperModel:
        def build(local_only: bool) -> WhisperModel:
            return cls(
                self._model_name,
                device=device,
                compute_type=compute_type,
                cpu_threads=self._cpu_threads,
                download_root=str(self._download_root),
                local_files_only=local_only,
            )

        # Primero el modelo ya descargado: así se arranca sin internet.
        try:
            return build(local_only=True)
        except (OSError, ValueError):
            log.info("Descargando el modelo de voz %s (solo la primera vez)…", self._model_name)
            return build(local_only=False)

    def warmup(self) -> None:
        with self._lock:
            self._load()

    def transcribe(self, pcm16: bytes) -> Transcript:
        audio = np.frombuffer(pcm16, dtype=np.int16).astype(np.float32) / 32768.0
        seconds = len(pcm16) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        with self._lock:
            model = self._load()
            start = time.perf_counter()
            segments, _ = model.transcribe(
                audio,
                language=self._language,
                beam_size=1,
                vad_filter=True,  # Silero VAD: quita los silencios y evita «inventar» texto en ellos
                condition_on_previous_text=False,
            )
            text = " ".join(s.text.strip() for s in segments).strip()
            elapsed = time.perf_counter() - start
        log.info("Transcripción: %.1f s de audio en %.2f s", seconds, elapsed)
        return Transcript(text=text, audio_seconds=seconds, elapsed_seconds=elapsed)
