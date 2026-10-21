"""Utilidades de texto en español."""

from __future__ import annotations

import re
import unicodedata


def normalize(text: str) -> str:
    """Minúsculas, sin tildes ni signos: «¡Vega, Apágalo!» → «vega apagalo»."""
    plain = unicodedata.normalize("NFKD", text.lower())
    plain = "".join(c for c in plain if not unicodedata.combining(c))
    return " ".join(re.sub(r"[^a-z0-9 ]+", " ", plain).split())
