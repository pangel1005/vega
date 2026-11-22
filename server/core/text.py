"""Utilidades de texto en español."""

from __future__ import annotations

import re
import unicodedata

_OFFER = re.compile(
    r"^¿?\s*(quieres|necesitas|te gustar[ií]a|te interesa|hay algo|algo m[aá]s|puedo ayudarte|"
    r"en qu[eé] m[aá]s|deseas|prefieres que)",
    re.IGNORECASE,
)


def trim_offers(text: str, name: str = "") -> str:
    """Quita del final de una respuesta la pregunta de cortesía («¿quieres algo más?») y la presentación
    («Soy Vega, tu asistente»): en voz alta sobran. Nunca deja la respuesta vacía."""
    sentences = re.split(r"(?<=[.!?…])\s+", text.strip())
    intro = re.compile(rf"^soy\s+{re.escape(name)}(\W|$)", re.IGNORECASE) if name else None
    kept = list(sentences)
    while len(kept) > 1 and (_OFFER.match(kept[-1]) or (intro is not None and intro.match(kept[-1]))):
        kept.pop()
    return " ".join(kept)


def normalize(text: str) -> str:
    """Minúsculas, sin tildes ni signos: «¡Vega, Apágalo!» → «vega apagalo»."""
    plain = unicodedata.normalize("NFKD", text.lower())
    plain = "".join(c for c in plain if not unicodedata.combining(c))
    return " ".join(re.sub(r"[^a-z0-9 ]+", " ", plain).split())
