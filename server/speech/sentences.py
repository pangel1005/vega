"""Corta en frases la respuesta que va llegando del modelo, para empezar a hablar enseguida.

En vez de esperar a la respuesta completa, cada frase se convierte en voz en cuanto
termina (punto, exclamación, interrogación…). Las frases muy cortas se juntan con la
siguiente para que la voz no suene entrecortada, y las muy largas se cortan en una coma.
"""

from __future__ import annotations

import re

MIN_CHARS = 24  # frases más cortas se unen a la siguiente («Sí.» + «Claro que puedo…»)
MAX_CHARS = 180  # sin punto en tanto texto, se corta en la última coma

_END = re.compile(r"[.!?…:;]+[\"»”')\]]*\s+|\n+")
_EMOJI = re.compile("[\U0001f000-\U0001faff\U00002600-\U000027bf\U0001f900-\U0001f9ff]+")
_MARKDOWN = re.compile(r"[*_#`>|~]+")


def clean_for_speech(text: str) -> str:
    """Quita lo que no se debe leer en voz alta: emojis, símbolos de formato y espacios de más."""
    text = _EMOJI.sub("", text)
    text = _MARKDOWN.sub("", text)
    text = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", text)  # [enlace](url) → enlace
    return " ".join(text.split())


class SentenceSplitter:
    def __init__(self) -> None:
        self._buffer = ""

    def feed(self, piece: str) -> list[str]:
        """Añade un trozo de texto y devuelve las frases ya completas (puede ser ninguna)."""
        self._buffer += piece
        out: list[str] = []
        while True:
            sentence = self._take()
            if sentence is None:
                return out
            out.append(sentence)

    def flush(self) -> list[str]:
        """Lo que quede al final de la respuesta."""
        rest, self._buffer = clean_for_speech(self._buffer), ""
        return [rest] if rest else []

    def _take(self) -> str | None:
        start = 0
        for match in _END.finditer(self._buffer):
            candidate = self._buffer[: match.end()]
            if len(clean_for_speech(candidate)) >= MIN_CHARS:
                self._buffer = self._buffer[match.end() :]
                return clean_for_speech(candidate)
            start = match.end()
        if len(self._buffer) > MAX_CHARS:
            cut = self._buffer.rfind(",", start, MAX_CHARS)
            if cut > MIN_CHARS:
                sentence, self._buffer = self._buffer[: cut + 1], self._buffer[cut + 1 :]
                return clean_for_speech(sentence)
        return None
