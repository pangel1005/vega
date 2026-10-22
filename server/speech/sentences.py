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


FIRST_MIN_CHARS = 12  # la primera frase puede cortarse ya en una coma tras estos caracteres
FIRST_MAX_CHARS = 60  # sin coma, la primera frase se corta en una conjunción antes de esto
# Puntos naturales para cortar una frase larga sin coma («…tres corazones | y pueden…»).
_JOINS = (" y ", " o ", " pero ", " porque ", " que ", " para ", " cuando ", " aunque ", " como ", " con ")


class SentenceSplitter:
    """`eager_first`: la primera frase se corta en la primera coma, para que la voz
    empiece antes (con voces lentas de generar, como Kokoro, se nota mucho)."""

    def __init__(self, eager_first: bool = True) -> None:
        self._buffer = ""
        self._eager = eager_first

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
        if self._eager:
            sentence = self._take_first_clause()
            if sentence is not None:
                return sentence
        start = 0
        for match in _END.finditer(self._buffer):
            candidate = self._buffer[: match.end()]
            if len(clean_for_speech(candidate)) >= MIN_CHARS:
                self._buffer = self._buffer[match.end() :]
                self._eager = False
                return clean_for_speech(candidate)
            start = match.end()
        if len(self._buffer) > MAX_CHARS:
            cut = self._buffer.rfind(",", start, MAX_CHARS)
            if cut > MIN_CHARS:
                sentence, self._buffer = self._buffer[: cut + 1], self._buffer[cut + 1 :]
                self._eager = False
                return clean_for_speech(sentence)
        return None

    def _take_first_clause(self) -> str | None:
        """Primer trozo corto: hasta una coma («Claro que sí, …») o, si la frase se alarga
        sin pausas, hasta una conjunción («Los pulpos tienen tres corazones | y …»)."""
        end = _END.search(self._buffer)
        cut = self._buffer.find(", ", FIRST_MIN_CHARS - 1)
        if cut != -1 and (end is None or cut < end.start()):
            sentence, self._buffer = self._buffer[: cut + 1], self._buffer[cut + 2 :]
            self._eager = False
            return clean_for_speech(sentence)
        if end is not None or len(self._buffer) < FIRST_MAX_CHARS:
            return None  # la frase acaba pronto o aún es corta: regla normal
        window = self._buffer[:FIRST_MAX_CHARS]
        join = max(window.rfind(j) for j in _JOINS)
        if join < 20:
            return None
        sentence, self._buffer = self._buffer[:join], self._buffer[join + 1 :]
        self._eager = False
        return clean_for_speech(sentence)
