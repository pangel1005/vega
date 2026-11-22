"""Recorte del relleno al final de una respuesta hablada."""

import pytest

from server.core.text import trim_offers


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Es 391. ¿Quieres que te lea algo más?", "Es 391."),
        ("Grazie. ¿Necesitas ayuda con algo más?", "Grazie."),
        (
            "En inglés es good morning. Soy Vega, tu asistente de voz. ¿Quieres hacer algo más hoy?",
            "En inglés es good morning.",
        ),
        (
            "Un firewall es una puerta. ¿Quieres apagar la luz de la cocina o subir el volumen?",
            "Un firewall es una puerta.",
        ),
        # Una pregunta que forma parte de la respuesta se queda.
        ("¿Qué tal estás?", "¿Qué tal estás?"),
        (
            "Depende. ¿Prefieres algo ligero o algo caliente?",
            "Depende. ¿Prefieres algo ligero o algo caliente?",
        ),
        # Nunca deja la respuesta vacía.
        ("¿Quieres que te ayude?", "¿Quieres que te ayude?"),
        ("Soy Vega, tu asistente.", "Soy Vega, tu asistente."),
        ("Hoy es martes a las 14:15.", "Hoy es martes a las 14:15."),
    ],
)
def test_trim_offers(text: str, expected: str) -> None:
    assert trim_offers(text, "Vega") == expected
