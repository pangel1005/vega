"""Fecha y hora en español, para dárselas al modelo (que no tiene reloj propio)."""

from __future__ import annotations

from datetime import datetime

DIAS = ("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
MESES = (
    "enero", "febrero", "marzo", "abril", "mayo", "junio",
    "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
)  # fmt: skip


def spanish_datetime(now: datetime) -> str:
    """«martes 21 de octubre de 2026, 17:43»."""
    return f"{DIAS[now.weekday()]} {now.day} de {MESES[now.month - 1]} de {now.year}, {now:%H:%M}"


def time_context(now: datetime | None = None) -> str:
    """Línea que se añade a las instrucciones del modelo en cada pregunta."""
    return f"Fecha y hora actuales (hora local del usuario): {spanish_datetime(now or datetime.now())}."
