"""Texto a voz con Piper (local, en la CPU: no usa la GPU).

Las voces son archivos `.onnx` (+ `.onnx.json`) en `models/piper`. La voz elegida se
guarda en `data/prefs.json` para recordarla entre reinicios.
"""

from __future__ import annotations

import json
import logging
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Protocol

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
            self._load()

    def synthesize(self, text: str) -> bytes:
        with self._lock:
            voice = self._load()
            return b"".join(chunk.audio_int16_bytes for chunk in voice.synthesize(text))
