from datetime import datetime

import pytest

from server.skills.when import parse_duration, parse_when, spoken_time

NOW = datetime(2026, 10, 22, 15, 0, 0)  # miércoles, 15:00


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("en 20 minutos", datetime(2026, 10, 22, 15, 20)),
        ("en veinte minutos", datetime(2026, 10, 22, 15, 20)),
        ("dentro de media hora", datetime(2026, 10, 22, 15, 30)),
        ("en un cuarto de hora", datetime(2026, 10, 22, 15, 15)),
        ("en 2 horas", datetime(2026, 10, 22, 17, 0)),
        ("en 1,5 horas", datetime(2026, 10, 22, 16, 30)),
        ("en 30 segundos", datetime(2026, 10, 22, 15, 0, 30)),
        ("a las 18:30", datetime(2026, 10, 22, 18, 30)),
        ("a las 6", datetime(2026, 10, 22, 18, 0)),  # son las 15: «a las 6» = esta tarde
        ("a las 6 de la mañana", datetime(2026, 10, 23, 6, 0)),  # ya pasó hoy: mañana
        ("a las 8 y media de la noche", datetime(2026, 10, 22, 20, 30)),
        ("a las cinco y cuarto", datetime(2026, 10, 22, 17, 15)),
        ("a la una", datetime(2026, 10, 23, 1, 0)),
        ("mañana a las 8", datetime(2026, 10, 23, 8, 0)),
        ("mañana", datetime(2026, 10, 23, 9, 0)),
    ],
)
def test_parse_when(text: str, expected: datetime) -> None:
    found = parse_when(f"llamar a mamá {text}", NOW)
    assert found is not None
    assert found.at == expected


def test_span_lets_us_remove_the_time_from_the_text() -> None:
    text = "llamar a mamá en 20 minutos"
    found = parse_when(text, NOW)
    assert found is not None
    assert (text[: found.span[0]] + text[found.span[1] :]).strip() == "llamar a mamá"


def test_no_time() -> None:
    assert parse_when("comprar leche", NOW) is None


def test_duration() -> None:
    text = "pon un temporizador de 5 minutos"
    found5 = parse_duration(text)
    assert found5 is not None and found5[0] == 300
    assert text[found5[1][0] : found5[1][1]] == "de 5 minutos"
    found = parse_duration("temporizador de media hora")
    assert found is not None and found[0] == 1800
    assert parse_duration("pon un temporizador") is None


def test_spoken_time() -> None:
    assert spoken_time(datetime(2026, 10, 22, 15, 20), NOW) == "en 20 minutos"
    assert spoken_time(datetime(2026, 10, 22, 18, 30), NOW) == "a las 18:30"
    assert spoken_time(datetime(2026, 10, 23, 9, 0), NOW) == "mañana a las 09:00"
    assert spoken_time(datetime(2026, 10, 22, 15, 0, 30), NOW) == "en 30 segundos"
