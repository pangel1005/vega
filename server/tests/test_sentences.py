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


def test_first_clause_is_cut_early_to_start_speaking_sooner() -> None:
    s = SentenceSplitter()
    assert s.feed("Claro que sí, ahora mismo te lo explico") == ["Claro que sí,"]
    # Solo la primera: después vuelve la regla normal (frases completas).
    assert s.feed(", es muy sencillo. Fin del todo.") == ["ahora mismo te lo explico, es muy sencillo."]


def test_long_first_sentence_without_comma_is_cut_at_a_conjunction() -> None:
    s = SentenceSplitter()
    first = s.feed("Los pulpos tienen tres corazones y pueden cambiar el color de su piel")
    assert first == ["Los pulpos tienen tres corazones"]
    assert s.feed(" para camuflarse. ") == ["y pueden cambiar el color de su piel para camuflarse."]


def test_no_early_cut_when_disabled_or_short() -> None:
    assert SentenceSplitter(eager_first=False).feed("Claro que sí, ahora mismo te lo explico") == []
    assert SentenceSplitter().feed("Sí, claro") == []  # demasiado corto para cortar


def test_clean_for_speech() -> None:
    assert clean_for_speech("**Hola** 😀 mira [esto](http://x.y) `ya`") == "Hola mira esto ya"
