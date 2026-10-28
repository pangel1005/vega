import time

import numpy as np
import pytest

from server.device.audio_capture import (
    BANDS,
    FFT_SIZE,
    SAMPLE_RATE,
    SpectrumAnalyzer,
    SpotifyTap,
    band_edges,
)


def tone(*freqs: float, amp: float = 0.3, seconds: float = 0.2) -> bytes:
    """PCM int16 estéreo de una o varias senoidales (la misma en los dos canales)."""
    t = np.arange(int(SAMPLE_RATE * seconds)) / SAMPLE_RATE
    mono = np.sum([np.sin(2 * np.pi * f * t) for f in freqs], axis=0) * amp / max(len(freqs), 1)
    return np.repeat((mono * 32767).astype(np.int16), 2).tobytes()


def band_of(freq: float) -> int:
    edges = band_edges()
    return int(np.searchsorted(edges, freq) - 1)


def analyzed(pcm: bytes) -> list[float]:
    a = SpectrumAnalyzer()
    a.feed(pcm)
    return a.bands()


def test_bands_are_24_log_spaced_and_cover_the_audible_range() -> None:
    edges = band_edges()
    assert len(edges) == BANDS + 1 == 25
    assert edges[0] == pytest.approx(45) and edges[-1] == pytest.approx(16_000)
    ratios = edges[1:] / edges[:-1]
    assert np.allclose(ratios, ratios[0])  # cada banda es igual de ancha en octavas


def test_a_pure_tone_lights_its_own_band_and_almost_nothing_else() -> None:
    bands = analyzed(tone(1000))
    assert len(bands) == BANDS and all(0.0 <= v <= 1.0 for v in bands)
    top = int(np.argmax(bands))
    assert top == band_of(1000) and abs(center(top) - 1000) < 150
    assert bands[top] > 0.5
    others = [v for i, v in enumerate(bands) if abs(i - top) > 2]
    assert max(others) < 0.1  # lejos de la nota no suena nada (no se «mancha» todo el espectro)


def center(band: int) -> float:
    edges = band_edges()
    return float(np.sqrt(edges[band] * edges[band + 1]))


def test_each_tone_moves_to_its_band_from_bass_to_treble() -> None:
    for band in (
        8,
        12,
        16,
        20,
        23,
    ):  # el centro de cada banda (en el borde entre dos, la nota repartiría luz)
        assert int(np.argmax(analyzed(tone(center(band))))) == band, band
    # Los graves tienen muy pocas frecuencias por banda: ahí se acepta la banda de al lado.
    assert abs(int(np.argmax(analyzed(tone(center(3))))) - 3) <= 1


def test_two_tones_light_two_bands() -> None:
    bands = analyzed(tone(center(7), center(18), amp=0.6))
    lit = {i for i, v in enumerate(bands) if v > 0.4}
    assert 7 in lit and 18 in lit


def test_bass_and_treble_of_the_same_loudness_look_alike() -> None:
    bass = max(analyzed(tone(100, amp=0.3)))
    treble = max(analyzed(tone(8000, amp=0.3)))
    assert abs(bass - treble) < 0.25  # sin esto los graves se verían mucho más grandes


def test_louder_is_taller_and_silence_is_zero() -> None:
    quiet = max(analyzed(tone(1000, amp=0.02)))
    loud = max(analyzed(tone(1000, amp=0.5)))
    assert loud > quiet > 0.0
    assert analyzed(bytes(FFT_SIZE * 4)) == [0.0] * BANDS


def test_feeding_in_small_chunks_equals_feeding_it_all_and_odd_bytes_are_ignored() -> None:
    pcm = tone(1000, seconds=0.2)
    whole = SpectrumAnalyzer()
    whole.feed(pcm)
    chunked = SpectrumAnalyzer()
    for i in range(0, len(pcm), 3840):
        chunked.feed(pcm[i : i + 3840] + b"\x01")  # un byte suelto de más: media muestra, se descarta
    assert int(np.argmax(chunked.bands())) == int(np.argmax(whole.bands()))
    chunked.feed(b"")  # nada: no rompe


# --- Quién captura y cuándo ---


class FakeCapture:
    def __init__(self, pid: int, pcm: bytes, fail_on_start: bool = False) -> None:
        self.pid, self.pcm, self.fail_on_start = pid, pcm, fail_on_start
        self.started = self.stopped = False
        self.reads = 0

    def start(self) -> None:
        if self.fail_on_start:
            raise OSError("sin permiso")
        self.started = True

    def read(self, wait_ms: int = 40) -> bytes:
        self.reads += 1
        time.sleep(0.005)
        return self.pcm

    def stop(self) -> None:
        self.stopped = True


def wait(cond, seconds: float = 4.0) -> bool:  # type: ignore[no-untyped-def]
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.01)
    return False


def test_tap_returns_the_spectrum_of_the_music_while_polled() -> None:
    made: list[FakeCapture] = []

    def opener(pid: int) -> FakeCapture:
        made.append(FakeCapture(pid, tone(1000)))
        return made[-1]

    tap = SpotifyTap(open_capture=opener, find_pid=lambda names: 4321)
    tap.poll()
    assert wait(lambda: tap.poll() is not None)
    bands = tap.poll()
    assert bands is not None and int(np.argmax(bands)) == band_of(1000)
    assert made[0].pid == 4321 and made[0].started
    tap.stop()


def test_tap_says_none_when_spotify_is_not_running() -> None:
    tap = SpotifyTap(open_capture=lambda pid: FakeCapture(pid, tone(1000)), find_pid=lambda names: None)
    tap.poll()
    time.sleep(0.3)
    assert tap.poll() is None  # sin Spotify no hay espectro (y no se inventa)
    tap.stop()


def test_tap_none_when_capture_fails_and_it_keeps_running() -> None:
    tap = SpotifyTap(
        open_capture=lambda pid: FakeCapture(pid, tone(1000), fail_on_start=True), find_pid=lambda names: 7
    )
    tap.poll()
    time.sleep(0.3)
    assert tap.poll() is None
    tap.stop()


def test_tap_releases_the_audio_when_nobody_asks() -> None:
    now = [100.0]
    made: list[FakeCapture] = []

    def opener(pid: int) -> FakeCapture:
        made.append(FakeCapture(pid, tone(1000)))
        return made[-1]

    tap = SpotifyTap(open_capture=opener, find_pid=lambda names: 1, clock=lambda: now[0])
    tap.poll()
    assert wait(lambda: made and made[0].reads > 0)
    now[0] += 60  # nadie lo pide desde hace un minuto
    assert wait(lambda: made[0].stopped)  # se suelta la captura: no se escucha nada sin motivo
    assert wait(lambda: tap._bands is None)
    tap.stop()
