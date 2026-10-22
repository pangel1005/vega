"""Escucha continua: corta el audio del micrófono en frases y reconoce la palabra de activación.

Funciona así:
1. `SpeechSegmenter` mira el volumen de cada trozo de 20 ms comparándolo con el ruido
   de fondo (que va aprendiendo). Cuando hay voz, empieza a guardar (con 300 ms de
   margen previo); tras 0,5 s de silencio, entrega la frase completa.
   Así Whisper solo trabaja cuando alguien habla, no con el silencio.
2. Whisper transcribe la frase y `parse_wake` comprueba si empieza por el nombre del
   asistente («Vega, …»). Lo que no empieza por el nombre se descarta y no se guarda.
"""

from __future__ import annotations

from collections import deque
from dataclasses import dataclass

import numpy as np

from server.core.text import normalize
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE

FRAME_SAMPLES = SAMPLE_RATE // 50  # 20 ms
FRAME_BYTES = FRAME_SAMPLES * BYTES_PER_SAMPLE


@dataclass
class SegmenterConfig:
    start_frames: int = 3  # 60 ms seguidos de voz para empezar
    end_silence_frames: int = 25  # 0,5 s de silencio para dar la frase por terminada
    preroll_frames: int = 15  # 300 ms de audio previo (para no cortar la primera sílaba)
    min_speech_frames: int = 15  # frases de menos de 0,3 s se descartan (golpes, clics)
    max_frames: int = 600  # 12 s como máximo por frase
    warmup_frames: int = 25  # 0,5 s al empezar solo para aprender el ruido de fondo
    min_threshold: float = 0.012  # volumen mínimo (RMS 0–1) para considerar voz
    noise_ratio: float = 3.0  # la voz debe superar el ruido de fondo en este factor


class SpeechSegmenter:
    def __init__(self, config: SegmenterConfig | None = None) -> None:
        self.cfg = config or SegmenterConfig()
        self._pending = b""
        self._preroll: deque[bytes] = deque(maxlen=self.cfg.preroll_frames)
        self._frames: list[bytes] = []
        self._voiced = 0
        self._loud_run = 0
        self._silence_run = 0
        self._noise = 0.004
        self._warmup = self.cfg.warmup_frames
        self.in_speech = False

    def reset(self) -> None:
        self._pending = b""
        self._preroll.clear()
        self._frames = []
        self._voiced = self._loud_run = self._silence_run = 0
        self.in_speech = False

    @property
    def threshold(self) -> float:
        return max(self.cfg.min_threshold, self._noise * self.cfg.noise_ratio)

    def feed(self, chunk: bytes) -> list[bytes]:
        """Añade audio PCM16. Devuelve las frases completas que se hayan cerrado (casi siempre ninguna)."""
        data = self._pending + chunk
        usable = len(data) - len(data) % FRAME_BYTES
        self._pending = data[usable:]
        done: list[bytes] = []
        for i in range(0, usable, FRAME_BYTES):
            segment = self._frame(data[i : i + FRAME_BYTES])
            if segment is not None:
                done.append(segment)
        return done

    def _frame(self, frame: bytes) -> bytes | None:
        samples = np.frombuffer(frame, dtype=np.int16).astype(np.float32) / 32768.0
        rms = float(np.sqrt(np.mean(samples * samples)))
        loud = rms > self.threshold

        if self._warmup > 0:
            # Calibración inicial: media del ruido de fondo, sin buscar voz todavía.
            self._warmup -= 1
            n = self.cfg.warmup_frames - self._warmup
            self._noise += (rms - self._noise) / n
            self._preroll.append(frame)
            return None

        if not self.in_speech:
            # El ruido de fondo se aprende solo fuera de las frases (sube despacio, baja rápido).
            rate = 0.05 if rms > self._noise else 0.2
            self._noise += (rms - self._noise) * rate
            self._preroll.append(frame)
            self._loud_run = self._loud_run + 1 if loud else 0
            if self._loud_run >= self.cfg.start_frames:
                self.in_speech = True
                self._frames = list(self._preroll)
                self._voiced = self._loud_run
                self._silence_run = 0
                self._preroll.clear()
            return None

        self._frames.append(frame)
        if loud:
            self._voiced += 1
            self._silence_run = 0
        else:
            self._silence_run += 1
        if self._silence_run >= self.cfg.end_silence_frames or len(self._frames) >= self.cfg.max_frames:
            audio, voiced = b"".join(self._frames), self._voiced
            self.reset()
            if voiced >= self.cfg.min_speech_frames:
                return audio
        return None


# --- Palabra de activación ---


def _edit_distance(a: str, b: str) -> int:
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def sounds_like_name(word: str, name: str) -> bool:
    """Whisper a veces oye «Mega», «Bega» o «Veca» en vez de «Vega»: se acepta una letra
    de diferencia (solo con nombres de 4 letras o más, para no confundir palabras cortas)."""
    base = normalize(name)
    if word in name_variants(name):
        return True
    return len(base) >= 4 and abs(len(word) - len(base)) <= 1 and _edit_distance(word, base) <= 1


def name_variants(name: str) -> set[str]:
    """Cómo puede escribir Whisper el nombre: «vega», «bega», «vegas»…"""
    base = normalize(name)
    swapped = base.replace("v", "#").replace("b", "v").replace("#", "b")
    return {base, swapped, base + "s", swapped + "s"}


@dataclass(frozen=True)
class Wake:
    woke: bool
    command: str


_FILLERS = {"oye", "hey", "ey", "eh", "hola", "ok", "okay", "a", "e"}


def parse_wake(text: str, name: str) -> Wake:
    """¿La frase empieza llamando al asistente? Devuelve también lo que viene después,
    con sus tildes y signos originales («Oye, Vega: ¿qué hora es?» → «¿qué hora es?»)."""
    tokens = text.split()
    seen = 0  # palabras reales vistas antes del nombre
    for i, token in enumerate(tokens):
        word = normalize(token)
        if not word:
            continue  # signos sueltos
        if sounds_like_name(word, name):
            tail = " ".join(tokens[i + 1 :]).lstrip(" ,.:;-—").strip()  # conserva «¿» y «¡»
            return Wake(True, tail)
        # Se permiten hasta dos muletillas antes del nombre («oye, Vega…», «hola Vega…»).
        seen += 1
        if seen > 2 or word not in _FILLERS:
            break
    return Wake(False, "")


_YES = {"si", "confirmo", "confirmado", "hazlo", "adelante", "dale", "claro", "vale", "ok", "okay"}
_NO = {"no", "cancela", "cancelar", "olvidalo", "dejalo", "para"}


def yes_or_no(text: str) -> bool | None:
    words = normalize(text).split()
    if not words or len(words) > 4:
        return None
    if words[0] in _NO:
        return False
    if words[0] in _YES:
        return True
    return None
