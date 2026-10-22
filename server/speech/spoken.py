"""Texto escrito → texto para leer en voz alta.

El lector fonético (espeak) lee los símbolos al pie de la letra: «18:00» sonaba
«dieciocho, cero cero» y «45 °C», «grados ce». Aquí se reescriben como se dicen.
"""

from __future__ import annotations

import re

_UNITS = ("una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce")
_MONTHS = ("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
           "octubre", "noviembre", "diciembre")  # fmt: skip


def _hour(h: int) -> str:
    return _UNITS[(h % 12 or 12) - 1]


def _part_of_day(h: int) -> str:
    if h < 6:
        return "de la madrugada"
    if h < 12:
        return "de la mañana"
    if h < 13:
        return "del mediodía"
    if h < 20:
        return "de la tarde"
    return "de la noche"


def say_time(h: int, m: int) -> str:
    """18:30 → «seis y media de la tarde»; 9:45 → «diez menos cuarto de la mañana»."""
    if m == 0:
        words = _hour(h)
    elif m == 15:
        words = f"{_hour(h)} y cuarto"
    elif m == 30:
        words = f"{_hour(h)} y media"
    elif m == 45:
        h += 1
        words = f"{_hour(h)} menos cuarto"
    else:
        words = f"{_hour(h)} y {m}"
    return f"{words} {_part_of_day(h % 24)}"


def _time(match: re.Match[str]) -> str:
    article, h, m = match["art"], int(match["h"]), int(match["m"])
    if h > 23 or m > 59:
        return match.group(0)
    if article:
        # «a las 1:00» → «a la una»
        article = ("la" if h % 12 == 1 else "las") + " "
    return article + say_time(h, m)


_TIME = re.compile(r"(?:\b(?P<art>las|la)\s+)?\b(?P<h>\d{1,2}):(?P<m>\d{2})\b(?:\s*h\b)?", re.I)
_DATE = re.compile(r"\b(\d{1,2})/(\d{1,2})/(\d{4})\b")
_REPLACE = [
    (re.compile(r"\s*°\s*C\b"), " grados"),
    (re.compile(r"\s*°"), " grados"),
    (re.compile(r"(?<=\d)\s*GB\b"), " gigas"),
    (re.compile(r"(?<=\d)\s*MB\b"), " megas"),
    (re.compile(r"(?<=\d)\s*km/h\b"), " kilómetros por hora"),
    (re.compile(r"(?<=\d)\s*km\b"), " kilómetros"),
    (re.compile(r"\bGPU\b"), "gráfica"),
    (re.compile(r"\bCPU\b"), "procesador"),
    (re.compile(r"\bRAM\b"), "memoria"),
]


def _date(match: re.Match[str]) -> str:
    d, mo, y = int(match[1]), int(match[2]), match[3]
    if not (1 <= d <= 31 and 1 <= mo <= 12):
        return match.group(0)
    return f"{d} de {_MONTHS[mo - 1]} de {y}"


def speakable(text: str) -> str:
    text = _TIME.sub(_time, text)
    text = _DATE.sub(_date, text)
    for pattern, words in _REPLACE:
        text = pattern.sub(words, text)
    return text
