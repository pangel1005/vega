"""Mide faster-whisper con distintos modelos y dispositivos.

    uv run python tools/bench_stt.py audio1.wav [audio2.wav ...]

Para cada combinación: tiempo de carga, tiempo de transcripción (2.ª pasada,
ya «caliente»), texto obtenido y memoria de vídeo usada.
"""

from __future__ import annotations

import subprocess
import sys
import time
import wave
from pathlib import Path

import numpy as np

from server.core.config import ROOT

COMBOS = [
    ("small", "cpu", "int8"),
    ("small", "cuda", "int8_float16"),
    ("large-v3-turbo", "cpu", "int8"),
    ("large-v3-turbo", "cuda", "int8_float16"),
]


def read_wav(path: Path) -> np.ndarray:
    with wave.open(str(path)) as w:
        assert w.getframerate() == 16000 and w.getsampwidth() == 2 and w.getnchannels() == 1
        pcm = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return pcm.astype(np.float32) / 32768.0


def vram_mib() -> int:
    out = subprocess.run(
        ["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
        capture_output=True,
        text=True,
    ).stdout
    return int(out.strip().splitlines()[0])


def main() -> int:
    from server.speech.whisper import enable_cuda_dlls

    enable_cuda_dlls()
    from faster_whisper import WhisperModel

    audios = [read_wav(Path(p)) for p in sys.argv[1:]]
    for name, device, compute in COMBOS:
        before = vram_mib()
        start = time.perf_counter()
        try:
            model = WhisperModel(
                name,
                device=device,
                compute_type=compute,
                cpu_threads=8,
                download_root=str(ROOT / "models" / "whisper"),
            )
        except Exception as exc:  # es un banco de pruebas: se informa y se sigue
            print(f"{name:16} {device:4}: no se pudo cargar ({type(exc).__name__}: {exc})")
            continue
        load = time.perf_counter() - start
        for audio in audios:
            for _ in range(2):  # la 2.ª pasada mide el tiempo «en caliente»
                t = time.perf_counter()
                segments, _ = model.transcribe(
                    audio, language="es", beam_size=1, vad_filter=True, condition_on_previous_text=False
                )
                text = " ".join(s.text.strip() for s in segments)
                elapsed = time.perf_counter() - t
            print(
                f"{name:16} {device:4} carga {load:5.1f} s | audio {len(audio) / 16000:4.1f} s"
                f" → {elapsed:5.2f} s | VRAM +{vram_mib() - before} MiB | {text}"
            )
        del model
    return 0


if __name__ == "__main__":
    sys.exit(main())
