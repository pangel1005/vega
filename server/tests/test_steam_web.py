"""Lo que Steam dice por internet del juego: jugadores, noticias y, con cuenta, logros y amigos."""

import logging
from pathlib import Path

import pytest

from server.device.games import GameWatcher
from server.device.steam_web import (
    Achievements,
    News,
    SteamWeb,
    http_get,
    parse_achievements,
    parse_news,
    read_key,
)
from server.tests.test_games import library

KEY = "0123456789ABCDEF0123456789abcdef"
ME = 76561198093287018


class Steam:
    """Un Steam de mentira: responde según la dirección y apunta cada petición."""

    def __init__(self, fail: bool = False) -> None:
        self.calls: list[tuple[str, dict[str, str | int]]] = []
        self.fail = fail

    def __call__(self, url: str, params: dict[str, str | int]) -> dict[str, object] | None:
        self.calls.append((url, params))
        if self.fail:
            return None
        if "GetNumberOfCurrentPlayers" in url:
            return {"response": {"player_count": 54309, "result": 1}}
        if "GetNewsForApp" in url:
            return {
                "appnews": {
                    "newsitems": [{"title": "  Parche  20261022 ", "date": 1792654321}, {"title": ""}]
                }
            }
        if "GetPlayerAchievements" in url:
            return {
                "playerstats": {
                    "success": True,
                    "achievements": [
                        {"apiname": "A", "achieved": 1, "unlocktime": 100, "name": "Primera sangre"},
                        {"apiname": "B", "achieved": 1, "unlocktime": 300, "name": "Doble baja"},
                        {"apiname": "C", "achieved": 0, "unlocktime": 0, "name": "Leyenda"},
                    ],
                }
            }
        if "GetFriendList" in url:
            return {"friendslist": {"friends": [{"steamid": "1"}, {"steamid": "2"}, {"steamid": "3"}]}}
        if "GetPlayerSummaries" in url:
            return {
                "response": {
                    "players": [
                        {"personaname": "Ana", "gameid": "2767030"},
                        {"personaname": "Beto", "gameid": "730"},
                        {"personaname": "Cris"},
                    ]
                }
            }
        return None


def web(steam: Steam, key: str | None = KEY, friends: bool = True) -> SteamWeb:
    return SteamWeb(key, ME, get=steam, background=False, friends=friends)


def test_without_an_account_only_public_data_is_asked() -> None:
    steam = Steam()
    live = web(steam, key=None).live(2767030)
    assert live.players == 54309
    assert live.news == (News("Parche 20261022", 1792654321.0),)  # limpio y sin los títulos vacíos
    assert live.achievements is None and live.friends == ()
    assert all("key" not in params for _url, params in steam.calls)  # sin cuenta no viaja ninguna clave


def test_with_an_account_it_reads_achievements_and_friends_playing() -> None:
    live = web(Steam()).live(2767030)
    assert live.achievements == Achievements(unlocked=2, total=3, recent=("Doble baja", "Primera sangre"))
    assert live.friends == ("Ana",)  # solo el amigo que juega a ESTE juego


def test_friends_can_be_switched_off() -> None:
    steam = Steam()
    live = web(steam, friends=False).live(2767030)
    assert live.friends == () and live.achievements is not None
    assert not any("GetFriendList" in url for url, _ in steam.calls)


def test_answers_are_reused_and_failures_are_not_repeated_every_second() -> None:
    steam, clock = Steam(), [1000.0]
    w = SteamWeb(KEY, ME, get=steam, now=lambda: clock[0], background=False)
    w.live(7)
    n = len(steam.calls)
    w.live(7)
    assert len(steam.calls) == n  # lo reciente se reutiliza
    clock[0] += 61
    w.live(7)
    assert any(
        "GetNumberOfCurrentPlayers" in url for url, _ in steam.calls[n:]
    )  # los jugadores caducan al minuto
    assert not any("GetNewsForApp" in url for url, _ in steam.calls[n:])  # las noticias no
    down = Steam(fail=True)
    w2 = SteamWeb(KEY, ME, get=down, now=lambda: clock[0], background=False)
    for _ in range(5):
        assert w2.live(7).players is None
    first = len(down.calls)
    assert first > 0
    w2.live(7)
    assert len(down.calls) == first  # tras fallar espera antes de insistir


def test_the_last_good_answer_is_kept_when_steam_stops_answering() -> None:
    steam, clock = Steam(), [1000.0]
    w = SteamWeb(KEY, ME, get=steam, now=lambda: clock[0], background=False)
    assert w.live(7).players == 54309
    steam.fail = True
    clock[0] += 120
    assert w.live(7).players == 54309  # no se borra por un fallo pasajero


def test_the_key_is_checked_and_never_reaches_the_logs(caplog: pytest.LogCaptureFixture) -> None:
    assert read_key(f"  {KEY}\n") == KEY
    assert read_key("esto no es una clave") is None and read_key("") is None
    assert not web(Steam(), key=None).connected and web(Steam()).connected
    import httpx

    def boom(*_a: object, **_k: object) -> None:
        raise httpx.ConnectError(f"https://api.steampowered.com/x?key={KEY}")

    caplog.set_level(logging.DEBUG)
    original = httpx.get
    httpx.get = boom  # type: ignore[assignment]
    try:
        assert http_get("https://api.steampowered.com/x", {"key": KEY}) is None
    finally:
        httpx.get = original
    assert KEY not in caplog.text


def test_parsers_survive_odd_answers() -> None:
    assert parse_news({}) == () and parse_news({"appnews": {"newsitems": "x"}}) == ()
    assert (
        parse_achievements({"playerstats": {"success": False}}) is None
    )  # perfil privado o juego sin logros
    assert parse_achievements({"playerstats": {"success": True, "achievements": []}}) is None


def test_recent_games_and_fortnight_hours_come_from_steam_files(tmp_path: Path) -> None:
    steam = library(tmp_path)
    (steam / "steamapps" / "appmanifest_2767030.acf").write_text(
        '"AppState" { "appid" "2767030" "name" "Marvel Rivals" "installdir" "MarvelRivals"'
        ' "LastPlayed" "1793165618" }',
        encoding="utf-8",
    )
    (steam / "steamapps" / "appmanifest_1.acf").write_text(
        '"AppState" { "appid" "1" "name" "Viejo" "installdir" "v" "LastPlayed" "1000" }', encoding="utf-8"
    )
    (steam / "steamapps" / "appmanifest_431960.acf").write_text(
        '"AppState" { "appid" "431960" "name" "Wallpaper Engine" "installdir" "we"'
        ' "LastPlayed" "1793999999" }',
        encoding="utf-8",
    )
    mine = steam / "userdata" / str(ME - 76561197960265728) / "config" / "localconfig.vdf"
    mine.write_text('"apps" { "2767030" { "Playtime" "16090" "Playtime2wks" "257" } }', encoding="utf-8")
    w = GameWatcher(steam, None, tmp_path / "games", running_appid=lambda: 2767030, background=False)
    recent = w.recent()
    assert [r.name for r in recent] == [
        "Marvel Rivals",
        "Viejo",
    ]  # la herramienta no cuenta; el más nuevo primero
    assert recent[0].art_id == "steam-2767030" and round(recent[0].hours or 0, 1) == 268.2
    game = w.current()
    assert game is not None and game.played_2w is not None and round(game.played_2w, 1) == 4.3
    assert not w.account
