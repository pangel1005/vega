from datetime import UTC, datetime, timedelta

from server.device.media import PLAYING, NowPlaying, app_name, live_position, pick_session


def test_app_names() -> None:
    assert app_name("Spotify.exe") == "Spotify"
    assert app_name("OperaSoftware.OperaGXWebBrowser.1746490942") == "Opera"
    assert app_name("MSEdge") == "Edge"
    assert app_name("Algo.Raro.123") == "Algo"


def test_position_advances_only_while_playing() -> None:
    t0 = datetime(2026, 10, 23, 6, 0, 0, tzinfo=UTC)
    assert live_position(100.0, t0, True, t0 + timedelta(seconds=5)) == 105.0
    assert live_position(100.0, t0, False, t0 + timedelta(seconds=5)) == 100.0


class _Info:
    def __init__(self, status: int) -> None:
        self.playback_status = status


class _Session:
    def __init__(self, name: str, status: int) -> None:
        self.name, self._status = name, status

    def get_playback_info(self) -> _Info:
        return _Info(self._status)


def test_prefers_what_is_playing() -> None:
    paused, playing = _Session("opera", 5), _Session("spotify", PLAYING)
    assert pick_session([paused, playing], current=paused) is playing
    assert pick_session([paused], current=paused) is paused
    assert pick_session([], current=None) is None


def test_same_song_ignores_normal_progress() -> None:
    a = NowPlaying("Spotify", "T", "A", "", True, 10.0, 200.0, "x", True, True)
    assert a.same_as(NowPlaying("Spotify", "T", "A", "", True, 12.0, 200.0, "x", True, True))
    assert not a.same_as(NowPlaying("Spotify", "T", "A", "", False, 10.0, 200.0, "x", True, True))
    assert not a.same_as(None)
