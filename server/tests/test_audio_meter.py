import time

import pytest

from server.device.audio_meter import SESSIONS_REFRESH_S, AudioMeter, ProcessMeter, _Meter


class FakeMeter:
    def __init__(self, peaks: list[float]) -> None:
        self.peaks = peaks
        self.reads = 0

    def GetPeakValue(self) -> float:  # noqa: N802
        self.reads += 1
        return self.peaks[min(self.reads - 1, len(self.peaks) - 1)]


def _wait(cond, seconds: float = 3.0) -> bool:  # type: ignore[no-untyped-def]
    end = time.time() + seconds
    while time.time() < end:
        if cond():
            return True
        time.sleep(0.01)
    return False


def test_poll_returns_the_latest_peak_and_clamps_it() -> None:
    meter = FakeMeter([0.4, 1.7])  # un valor fuera de rango se recorta
    m = AudioMeter(open_meter=lambda: meter)
    m.poll()  # arranca el hilo
    assert _wait(lambda: m.poll() == 1.0)
    m.stop()


def test_without_polling_the_meter_goes_to_sleep() -> None:
    now = [100.0]
    meter = FakeMeter([0.3])
    m = AudioMeter(open_meter=lambda: meter, clock=lambda: now[0])
    m.poll()
    assert _wait(lambda: meter.reads > 0)
    assert m.poll() in (None, 0.3)
    now[0] += 60  # nadie lo pide desde hace un minuto
    reads = meter.reads
    now[0] += 60
    time.sleep(0.3)
    assert meter.reads - reads < 3  # dormido: no sigue leyendo
    m.stop()


def test_if_the_meter_cannot_open_the_level_is_none_not_invented() -> None:
    def broken():  # type: ignore[no-untyped-def]
        raise OSError("sin dispositivo")

    m = AudioMeter(open_meter=broken)
    assert m.poll() is None
    time.sleep(0.2)
    assert m.poll() is None
    m.stop()


def test_process_meter_reads_only_the_sessions_of_the_asked_program() -> None:
    sessions: list[tuple[str, _Meter]] = [
        ("opera.exe", FakeMeter([0.9])),  # el navegador suena muy fuerte...
        ("discord.exe", FakeMeter([0.8])),
        ("spotify.exe", FakeMeter([0.2])),  # ...pero la onda es solo de Spotify
        ("spotify.exe", FakeMeter([0.35])),  # Spotify puede tener varias sesiones: vale la más fuerte
    ]
    m = ProcessMeter(("Spotify.exe",), sessions=lambda: sessions)
    assert m.GetPeakValue() == 0.35


def test_process_meter_says_nothing_to_measure_without_a_session_and_finds_it_when_it_appears() -> None:
    now = [0.0]
    sessions: list[tuple[str, _Meter]] = []
    m = ProcessMeter(("spotify.exe",), sessions=lambda: list(sessions), clock=lambda: now[0])
    with pytest.raises(LookupError):  # no suena en este PC: «sin audio», no «silencio»
        m.GetPeakValue()
    sessions.append(("spotify.exe", FakeMeter([0.6])))
    with pytest.raises(LookupError):  # aún no ha vuelto a buscar
        m.GetPeakValue()
    now[0] += SESSIONS_REFRESH_S
    assert m.GetPeakValue() == 0.6


def test_process_meter_recovers_when_a_session_dies() -> None:
    class Dying:
        def GetPeakValue(self) -> float:  # noqa: N802
            raise OSError("sesión cerrada")

    now = [0.0]
    lists: list[list[tuple[str, _Meter]]] = [[("spotify.exe", Dying())], [("spotify.exe", FakeMeter([0.4]))]]
    m = ProcessMeter(("spotify.exe",), sessions=lambda: lists.pop(0), clock=lambda: now[0])
    with pytest.raises(LookupError):  # la sesión murió: no se cae
        m.GetPeakValue()
    assert m.GetPeakValue() == 0.4  # y la siguiente lectura busca de nuevo


def test_audio_meter_reports_none_when_the_app_has_no_audio_and_recovers() -> None:
    sessions: list[tuple[str, _Meter]] = []
    m = AudioMeter(open_meter=lambda: ProcessMeter(("spotify.exe",), sessions=lambda: list(sessions)))
    m.poll()
    time.sleep(0.4)
    assert m.poll() is None  # Spotify no suena aquí: sin número, no un cero engañoso
    sessions.append(("spotify.exe", FakeMeter([0.7])))
    assert _wait(lambda: m.poll() == 0.7, 6.0)  # aparece la sesión (hasta 2 s para buscarla) y ya hay lectura
    m.stop()
