import time

from server.device.audio_meter import AudioMeter


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
