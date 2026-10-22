import pytest

from server.speech.spoken import say_time, speakable
from server.speech.tts import CLAUSE_PAUSE, SENTENCE_PAUSE, pause_after


@pytest.mark.parametrize(
    ("h", "m", "said"),
    [
        (18, 0, "seis de la tarde"),
        (17, 30, "cinco y media de la tarde"),
        (9, 15, "nueve y cuarto de la mañana"),
        (9, 45, "diez menos cuarto de la mañana"),
        (12, 5, "doce y 5 del mediodía"),
        (0, 20, "doce y 20 de la madrugada"),
        (21, 0, "nueve de la noche"),
    ],
)
def test_say_time(h: int, m: int, said: str) -> None:
    assert say_time(h, m) == said


@pytest.mark.parametrize(
    ("text", "said"),
    [
        (
            "Vale, a las 18:00 te recuerdo: sacar la basura.",
            "Vale, a las seis de la tarde te recuerdo: sacar la basura.",
        ),
        ("mañana a las 13:00", "mañana a la una de la tarde"),
        ("Son las 7:30 h.", "Son las siete y media de la mañana."),
        ("La GPU está a 45 °C y usa 3,5 GB.", "La gráfica está a 45 grados y usa 3,5 gigas."),
        ("Hoy es 22/10/2026.", "Hoy es 22 de octubre de 2026."),
        ("procesador al 12 %", "procesador al 12 %"),  # el lector ya dice «por ciento»
        ("marcador 25:99", "marcador 25:99"),  # no es una hora
    ],
)
def test_speakable(text: str, said: str) -> None:
    assert speakable(text) == said


def test_pauses_follow_punctuation() -> None:
    assert pause_after("Todo va bien.") == SENTENCE_PAUSE
    assert pause_after("¿Quieres algo? ") == SENTENCE_PAUSE
    assert pause_after("Son las cinco,") == CLAUSE_PAUSE
    assert pause_after("y entonces") == 0.0
