import numpy as np
import pytest

from server.core.text import normalize
from server.speech.listener import (
    FRAME_SAMPLES,
    SpeechSegmenter,
    name_variants,
    parse_wake,
    yes_or_no,
)

RATE = 16_000


def tone(seconds: float, amplitude: float) -> bytes:
    t = np.arange(int(RATE * seconds)) / RATE
    return (np.sin(2 * np.pi * 220 * t) * amplitude * 32767).astype(np.int16).tobytes()


def silence(seconds: float, amplitude: float = 0.001) -> bytes:
    rng = np.random.default_rng(0)
    return (rng.normal(0, amplitude, int(RATE * seconds)) * 32767).astype(np.int16).tobytes()


def feed_in_chunks(seg: SpeechSegmenter, audio: bytes, chunk: int = 3200) -> list[bytes]:
    out: list[bytes] = []
    for i in range(0, len(audio), chunk):
        out += seg.feed(audio[i : i + chunk])
    return out


def test_detects_one_phrase_with_preroll() -> None:
    seg = SpeechSegmenter()
    audio = silence(1.0) + tone(1.0, 0.2) + silence(1.0)
    phrases = feed_in_chunks(seg, audio)
    assert len(phrases) == 1
    seconds = len(phrases[0]) / 2 / RATE
    # 1 s de voz + 0,3 s previos + 0,5 s de silencio final (± un poco por los 60 ms de arranque)
    assert 1.6 <= seconds <= 1.9


def test_silence_and_short_clicks_are_ignored() -> None:
    seg = SpeechSegmenter()
    assert feed_in_chunks(seg, silence(3.0)) == []
    assert feed_in_chunks(seg, tone(0.1, 0.3) + silence(1.5)) == []  # golpe de 100 ms


def test_two_phrases_are_separated() -> None:
    seg = SpeechSegmenter()
    audio = silence(0.5) + tone(0.8, 0.2) + silence(1.0) + tone(0.8, 0.2) + silence(1.0)
    assert len(feed_in_chunks(seg, audio)) == 2


def test_long_phrase_is_cut_at_max() -> None:
    seg = SpeechSegmenter()
    phrases = feed_in_chunks(seg, silence(0.5) + tone(15.0, 0.2))
    assert len(phrases) == 1
    assert len(phrases[0]) / 2 / RATE == pytest.approx(600 * FRAME_SAMPLES / RATE)


def test_adapts_to_background_noise() -> None:
    seg = SpeechSegmenter()
    # Un ventilador constante, más fuerte que el umbral mínimo, no debe contar como voz.
    feed_in_chunks(seg, silence(4.0, amplitude=0.02))
    assert not seg.in_speech
    assert feed_in_chunks(seg, tone(1.0, 0.25) + silence(1.0, 0.02))


def test_partial_frames_are_kept_between_chunks() -> None:
    seg = SpeechSegmenter()
    audio = silence(0.5) + tone(1.0, 0.2) + silence(1.0)
    assert len(feed_in_chunks(seg, audio, chunk=777)) == 1  # trozos que no son múltiplo de 20 ms


# --- Palabra de activación ---


def test_normalize() -> None:
    assert normalize("¡Vega, Apágalo YA!") == "vega apagalo ya"


def test_name_variants() -> None:
    assert {"vega", "bega", "vegas"} <= name_variants("Vega")


@pytest.mark.parametrize(
    ("text", "command"),
    [
        ("Vega, ¿qué hora es?", "¿qué hora es?"),
        ("vega apaga el PC", "apaga el PC"),
        ("Oye Vega: sube el volumen.", "sube el volumen."),
        ("Bega, abre Spotify", "abre Spotify"),
        ("Vegas, pon música", "pon música"),
        ("Vega.", ""),
        ("Hola, Vega", ""),
        ("¿Mega? ¿Cuál es la capital de Francia?", "¿Cuál es la capital de Francia?"),  # Whisper real
        ("Veca, pon música", "pon música"),
    ],
)
def test_wake_word(text: str, command: str) -> None:
    wake = parse_wake(text, "Vega")
    assert wake.woke
    assert wake.command == command


@pytest.mark.parametrize(
    "text",
    [
        "Mañana voy a Vega Baja",  # el nombre no está al principio
        "Le dije a mi hermano que viniera",
        "Las Vegas es una ciudad",  # «las» no es una muletilla
        "Ve a la tienda",  # palabras cortas no cuentan como el nombre
        "Vegetal es mi sopa favorita",
        "",
    ],
)
def test_no_wake_word(text: str) -> None:
    assert not parse_wake(text, "Vega").woke


def test_wake_uses_configured_name() -> None:
    assert parse_wake("Orión, apaga el PC", "Orión").command == "apaga el PC"
    assert not parse_wake("Vega, apaga el PC", "Orión").woke


@pytest.mark.parametrize(
    ("text", "answer"),
    [
        ("Sí.", True),
        ("sí, confirmo", True),
        ("Hazlo", True),
        ("No", False),
        ("cancela", False),
        ("¿Qué tiempo hace hoy en Madrid?", None),
        ("", None),
    ],
)
def test_yes_or_no(text: str, answer: bool | None) -> None:
    assert yes_or_no(text) is answer
