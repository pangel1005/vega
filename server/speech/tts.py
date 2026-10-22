"""Texto a voz local, en la CPU (no usa la GPU). Dos motores:

- Kokoro (models/kokoro): voces naturales (Dora, Alex, Santa). ~3,4 veces más rápido
  que el tiempo real en este PC: suena humano, tarda algo más.
- Piper (models/piper): más robótico, pero ~28 veces más rápido que el tiempo real.

`VoiceRouter` junta las voces de los dos y usa el motor de la voz elegida, que se
guarda en `data/prefs.json` para recordarla entre reinicios.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Any, Protocol

import numpy as np

if TYPE_CHECKING:
    from piper import PiperVoice

log = logging.getLogger(__name__)

COUNTRIES = {"MX": "México", "AR": "Argentina", "ES": "España", "CO": "Colombia", "US": "EE. UU."}


@dataclass(frozen=True)
class VoiceInfo:
    id: str
    label: str


def voice_label(voice_id: str) -> str:
    """«es_MX-claude-high» → «Claude · México»."""
    try:
        locale, name, _quality = voice_id.split("-", 2)
        country = COUNTRIES.get(locale.split("_")[1], locale)
    except (ValueError, IndexError):
        return voice_id
    return f"{name.capitalize()} · {country}"


class TextToSpeech(Protocol):
    @property
    def sample_rate(self) -> int: ...

    @property
    def voice(self) -> str: ...

    def voices(self) -> list[VoiceInfo]: ...

    def set_voice(self, voice_id: str) -> None: ...

    def warmup(self) -> None: ...

    def synthesize(self, text: str) -> bytes:
        """PCM16 mono a `sample_rate`. Bloquea: llamar desde un hilo aparte."""
        ...


class TtsUnavailable(Exception):
    """No hay voz disponible. El mensaje está pensado para el usuario."""


KOKORO_VOICES = {
    "ef_dora": "Dora · natural",
    "em_alex": "Alex · natural",
    "em_santa": "Santa · natural",
}
KOKORO_PREFIX = "kokoro:"


class KokoroTTS:
    """Voces naturales de Kokoro (82 M parámetros, ONNX en la CPU)."""

    def __init__(self, model_dir: Path) -> None:
        self._model = model_dir / "kokoro-v1.0.onnx"
        self._voices_file = model_dir / "voices-v1.0.bin"
        self._engine: Any = None
        self._lock = threading.Lock()

    @property
    def available(self) -> bool:
        return self._model.exists() and self._voices_file.exists()

    def voices(self) -> list[VoiceInfo]:
        if not self.available:
            return []
        return [VoiceInfo(KOKORO_PREFIX + v, label) for v, label in KOKORO_VOICES.items()]

    def _load(self) -> Any:
        if self._engine is None:
            from kokoro_onnx import Kokoro

            start = time.perf_counter()
            self._engine = Kokoro(str(self._model), str(self._voices_file))
            log.info("Kokoro listo en %.1f s", time.perf_counter() - start)
        return self._engine

    def warmup(self, voice_id: str) -> None:
        self.synthesize("Hola.", voice_id)

    def synthesize(self, text: str, voice_id: str) -> tuple[bytes, int]:
        with self._lock:
            samples, rate = self._load().create(text, voice=voice_id.removeprefix(KOKORO_PREFIX), lang="es")
        pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
        return pcm.tobytes(), int(rate)


class PiperTTS:
    def __init__(self, voices_dir: Path, default_voice: str, prefs_file: Path) -> None:
        self._dir = voices_dir
        self._prefs = prefs_file
        self._lock = threading.Lock()
        self._loaded: dict[str, PiperVoice] = {}
        saved = self._read_prefs().get("voice")
        available = {v.id for v in self.voices()}
        self._voice = saved if saved in available else default_voice

    def _read_prefs(self) -> dict[str, str]:
        try:
            data = json.loads(self._prefs.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    @property
    def voice(self) -> str:
        return self._voice

    @property
    def sample_rate(self) -> int:
        return int(self._load().config.sample_rate)

    def voices(self) -> list[VoiceInfo]:
        return [
            VoiceInfo(p.stem, voice_label(p.stem))
            for p in sorted(self._dir.glob("*.onnx"))
            if p.with_suffix(".onnx.json").exists()
        ]

    def set_voice(self, voice_id: str) -> None:
        if voice_id not in {v.id for v in self.voices()}:
            raise TtsUnavailable(f"No tengo la voz «{voice_id}».")
        self._voice = voice_id
        prefs = self._read_prefs() | {"voice": voice_id}
        self._prefs.parent.mkdir(parents=True, exist_ok=True)
        self._prefs.write_text(json.dumps(prefs, ensure_ascii=False, indent=2), encoding="utf-8")

    def _load(self) -> PiperVoice:
        voice = self._voice
        if voice not in self._loaded:
            path = self._dir / f"{voice}.onnx"
            if not path.exists():
                raise TtsUnavailable(f"No encuentro la voz {path}. Descárgala en models/piper.")
            from piper import PiperVoice

            start = time.perf_counter()
            self._loaded[voice] = PiperVoice.load(str(path))
            log.info("Voz lista: %s en %.1f s", voice, time.perf_counter() - start)
        return self._loaded[voice]

    def warmup(self) -> None:
        with self._lock:
            voice = self._load()
            # La primera síntesis prepara el motor (~1-2 s): mejor ahora que en la primera respuesta.
            for _ in voice.synthesize("Hola."):
                pass

    def synthesize(self, text: str) -> bytes:
        with self._lock:
            voice = self._load()
            return b"".join(chunk.audio_int16_bytes for chunk in voice.synthesize(text))


class VoiceRouter:
    """Une Kokoro y Piper en una sola lista de voces; habla con el motor de la voz elegida."""

    def __init__(self, kokoro: KokoroTTS, piper: PiperTTS, default_voice: str, prefs_file: Path) -> None:
        self._kokoro = kokoro
        self._piper = piper
        self._prefs = prefs_file
        self._last_rate = 24_000
        saved = _read_json(prefs_file).get("voice")
        ids = {v.id for v in self.voices()}
        self._voice = next((v for v in (saved, default_voice) if v in ids), next(iter(sorted(ids)), ""))
        if not self._voice.startswith(KOKORO_PREFIX) and self._voice:
            self._piper.set_voice(self._voice)

    @property
    def voice(self) -> str:
        return self._voice

    @property
    def sample_rate(self) -> int:
        return self._last_rate

    def voices(self) -> list[VoiceInfo]:
        return self._kokoro.voices() + self._piper.voices()

    def set_voice(self, voice_id: str) -> None:
        if voice_id not in {v.id for v in self.voices()}:
            raise TtsUnavailable(f"No tengo la voz «{voice_id}».")
        if not voice_id.startswith(KOKORO_PREFIX):
            self._piper.set_voice(voice_id)
        self._voice = voice_id
        prefs = _read_json(self._prefs) | {"voice": voice_id}
        self._prefs.parent.mkdir(parents=True, exist_ok=True)
        self._prefs.write_text(json.dumps(prefs, ensure_ascii=False, indent=2), encoding="utf-8")

    def warmup(self) -> None:
        if self._voice.startswith(KOKORO_PREFIX):
            self._kokoro.warmup(self._voice)
        else:
            self._piper.warmup()

    def synthesize(self, text: str) -> bytes:
        if self._voice.startswith(KOKORO_PREFIX):
            pcm, self._last_rate = self._kokoro.synthesize(text, self._voice)
            return pcm
        pcm = self._piper.synthesize(text)
        self._last_rate = self._piper.sample_rate
        return pcm


def _read_json(path: Path) -> dict[str, str]:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}
