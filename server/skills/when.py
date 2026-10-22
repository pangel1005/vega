"""Entender cuándo en español hablado: «en 20 minutos», «a las 6 y media», «mañana a las 8».

Whisper escribe a veces cifras («20», «18:30») y a veces palabras («veinte», «media
hora»): se aceptan las dos. Devuelve la hora y el trozo de texto que la describe,
para quitarlo del recordatorio («llamar a mamá en 20 minutos» → «llamar a mamá»).
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, timedelta

NUMBERS = {
    "un": 1, "una": 1, "uno": 1, "dos": 2, "tres": 3, "cuatro": 4, "cinco": 5, "seis": 6,
    "siete": 7, "ocho": 8, "nueve": 9, "diez": 10, "once": 11, "doce": 12, "trece": 13,
    "catorce": 14, "quince": 15, "dieciséis": 16, "dieciseis": 16, "veinte": 20,
    "veinticinco": 25, "treinta": 30, "cuarenta": 40, "cuarenta y cinco": 45, "cincuenta": 50,
    "sesenta": 60, "noventa": 90,
}  # fmt: skip
_NUM = r"(\d+(?:[.,]\d+)?|" + "|".join(sorted(map(re.escape, NUMBERS), key=len, reverse=True)) + ")"
_UNITS = {"segundo": 1, "minuto": 60, "hora": 3600, "dia": 86400, "día": 86400}

_IN = re.compile(
    rf"\b(?:en|dentro\s+de)\s+(?:(?P<n>{_NUM})\s+(?P<u>segundos?|minutos?|horas?|d[ií]as?)"
    r"|(?P<half>media\s+hora)|(?P<quarter>un\s+cuarto\s+de\s+hora))\b",
    re.IGNORECASE,
)
_DURATION = re.compile(  # para temporizadores: «de 5 minutos», «5 minutos»
    rf"\b(?:de\s+)?(?P<n>{_NUM})\s+(?P<u>segundos?|minutos?|horas?)\b|\b(?P<half>media\s+hora)\b",
    re.IGNORECASE,
)
_AT = re.compile(
    rf"\b(?P<tomorrow>mañana\s+)?a\s+las?\s+(?P<h>{_NUM})(?::(?P<m>\d{{2}}))?"
    r"(?:\s+y\s+(?P<extra>media|cuarto|\d{1,2}))?"
    r"(?:\s+(?:de\s+la\s+)?(?P<part>mañana|tarde|noche|madrugada))?\b",
    re.IGNORECASE,
)
_TOMORROW = re.compile(r"\bmañana\b", re.IGNORECASE)


@dataclass(frozen=True)
class When:
    at: datetime
    span: tuple[int, int]  # posición del texto que describe la hora


def _number(text: str) -> float:
    t = text.lower().replace(",", ".")
    return float(t) if t[0].isdigit() else float(NUMBERS[t])


def _seconds(n: str | None, unit: str | None, half: str | None, quarter: str | None = None) -> float:
    if half:
        return 1800
    if quarter:
        return 900
    assert n is not None and unit is not None
    key = unit.lower().rstrip("s")
    return _number(n) * _UNITS[key]


def parse_in(text: str, now: datetime) -> When | None:
    """«en 20 minutos», «dentro de media hora»…"""
    m = _IN.search(text)
    if not m:
        return None
    secs = _seconds(m["n"], m["u"], m["half"], m["quarter"])
    return When(now + timedelta(seconds=secs), m.span())


def parse_duration(text: str) -> tuple[float, tuple[int, int]] | None:
    """Duración de un temporizador: «de 5 minutos» → 300 s."""
    m = _DURATION.search(text)
    if not m:
        return None
    return _seconds(m["n"], m["u"], m["half"]), m.span()


def parse_at(text: str, now: datetime) -> When | None:
    """«a las 6», «a las 6 y media de la tarde», «mañana a las 8», «a las 18:30»."""
    m = _AT.search(text)
    if not m:
        return None
    hour = int(_number(m["h"]))
    minute = int(m["m"]) if m["m"] else 0
    extra = (m["extra"] or "").lower()
    minute += 30 if extra == "media" else 15 if extra == "cuarto" else int(extra) if extra.isdigit() else 0
    part = (m["part"] or "").lower()
    if part in ("tarde", "noche") and hour < 12:
        hour += 12
    if part == "noche" and hour == 24:
        hour = 0
    if hour > 23 or minute > 59:
        return None
    at = now.replace(hour=hour, minute=minute, second=0, microsecond=0)
    if m["tomorrow"]:
        at += timedelta(days=1)
    elif at <= now:
        # «a las 6» sin decir de la tarde: la próxima vez que sean las 6 (si son las 15, las 18).
        if not part and hour < 12 and at + timedelta(hours=12) > now:
            at += timedelta(hours=12)
        else:
            at += timedelta(days=1)
    return When(at, m.span())


def parse_when(text: str, now: datetime) -> When | None:
    """Primero «en …», luego «a las …»; «mañana» a secas significa mañana a las 9:00."""
    found = parse_in(text, now) or parse_at(text, now)
    if found:
        return found
    m = _TOMORROW.search(text)
    if m:
        at = (now + timedelta(days=1)).replace(hour=9, minute=0, second=0, microsecond=0)
        return When(at, m.span())
    return None


def spoken_time(at: datetime, now: datetime) -> str:
    """«a las 18:30», «mañana a las 9:00» o «en 5 minutos» (para confirmar en voz alta)."""
    delta = (at - now).total_seconds()
    if delta < 3600:
        mins = max(1, round(delta / 60))
        if delta < 60:
            return f"en {int(delta)} segundos"
        return f"en {mins} minuto{'s' if mins != 1 else ''}"
    day = "mañana " if at.date() > now.date() else ""
    return f"{day}a las {at:%H:%M}"
