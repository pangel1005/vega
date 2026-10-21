from server.speech.sentences import SentenceSplitter, clean_for_speech


def stream(text: str, step: int = 3) -> list[str]:
    """Simula los trozos que llegan del modelo."""
    s = SentenceSplitter()
    out: list[str] = []
    for i in range(0, len(text), step):
        out += s.feed(text[i : i + step])
    return out + s.flush()


def test_splits_into_sentences_as_they_arrive() -> None:
    s = SentenceSplitter()
    assert s.feed("La capital de Francia es París. Es una ciu") == ["La capital de Francia es París."]
    assert s.feed("dad muy famosa.") == []  # aún podría seguir la frase
    assert s.flush() == ["Es una ciudad muy famosa."]


def test_short_sentences_are_joined() -> None:
    assert stream("Sí. Claro que puedo ayudarte con eso ahora mismo. ¡Vamos!") == [
        "Sí. Claro que puedo ayudarte con eso ahora mismo.",
        "¡Vamos!",
    ]


def test_questions_and_exclamations() -> None:
    assert stream("¿Necesitas algo más de mi parte hoy? ¡Aquí estaré para ayudarte siempre!") == [
        "¿Necesitas algo más de mi parte hoy?",
        "¡Aquí estaré para ayudarte siempre!",
    ]


def test_long_text_without_period_is_cut_at_a_comma() -> None:
    text = "Primero abre el programa, " * 10 + "y listo"
    parts = stream(text)
    assert len(parts) > 1
    assert all(len(p) <= 200 for p in parts)
    assert " ".join(parts).replace("  ", " ") == " ".join(text.split())


def test_numbers_with_decimals_are_not_split() -> None:
    assert stream("La temperatura es de 21.5 grados en la sala ahora.") == [
        "La temperatura es de 21.5 grados en la sala ahora."
    ]


def test_clean_for_speech() -> None:
    assert clean_for_speech("**Hola** 😀 mira [esto](http://x.y) `ya`") == "Hola mira esto ya"
