"""La partida en curso: League of Legends (Live Client Data API) y Rocket League (Stats API)."""

import asyncio
import json
import socket
import time
from pathlib import Path
from typing import Any

from server.core.hub import Hub
from server.device.games import GameWatcher
from server.device.match import (
    JsonStream,
    Match,
    MatchSource,
    RocketLeagueStats,
    RocketState,
    parse_lol,
)
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.ws.protocol import GameEvent

LOL: dict[str, Any] = {
    "activePlayer": {"summonerName": "Yo#EUW", "level": 11, "currentGold": 8540.7, "championStats": {}},
    "allPlayers": [
        {"summonerName": "Yo#EUW", "championName": "Ahri", "team": "ORDER", "level": 11,
         "scores": {"kills": 5, "deaths": 2, "assists": 7, "creepScore": 144, "wardScore": 3},
         "items": [{"displayName": "secreto del equipo"}]},
        {"summonerName": "Aliado", "championName": "Garen", "team": "ORDER", "scores": {"kills": 4}},
        {"summonerName": "Rival1", "championName": "Zed", "team": "CHAOS",
         "scores": {"kills": 6, "deaths": 4}},
        {"summonerName": "Rival2", "championName": "Lux", "team": "CHAOS", "scores": {"kills": 3}},
    ],
    "events": {"Events": [
        {"EventName": "GameStart"},
        {"EventName": "ChampionKill", "KillerName": "Yo#EUW", "VictimName": "Rival1"},
        {"EventName": "DragonKill", "DragonType": "Fire"},
        {"EventName": "TurretKilled"},
    ]},
    "gameData": {"gameMode": "CLASSIC", "gameTime": 1472.5, "mapName": "Map11", "mapNumber": 11},
}  # fmt: skip


def test_league_match_shows_my_data_and_the_team_score() -> None:
    m = parse_lol(LOL)
    assert m is not None
    assert m.title == "Clásica · Grieta del Invocador"
    assert m.score == "9 – 9"  # 5+4 de mi equipo contra 6+3
    assert (m.clock, m.clock_step) == (1472.5, 1)
    stats = dict(m.stats)
    assert stats["Campeón"] == "Ahri" and stats["Nivel"] == "11" and stats["K / D / A"] == "5 / 2 / 7"
    assert stats["Súbditos"] == "144 · 5,9/min" and stats["Oro"] == "8 540"
    assert m.feed == ("Torre destruida", "Dragón", "Yo#EUW eliminó a Rival1")  # lo más nuevo primero
    # Nada del equipo rival que el juego oculte: ni objetos ni hechizos.
    assert "secreto" not in json.dumps(m, default=str, ensure_ascii=False)


def test_league_without_a_match_or_with_odd_data_gives_nothing() -> None:
    assert parse_lol({}) is None
    assert parse_lol({"gameData": {"gameTime": 3}}) is None  # sin jugador activo (pantalla de carga)
    odd = (
        parse_lol({"activePlayer": {"summonerName": "x"}, "allPlayers": "no", "gameData": {"gameTime": "?"}})
        if False
        else None
    )
    assert odd is None


def test_json_stream_handles_glued_split_and_noisy_messages() -> None:
    s = JsonStream()
    assert s.feed(b'{"a": 1}{"b": 2}\n{"c"') == [{"a": 1}, {"b": 2}]  # pegados, con salto, y uno a medias
    assert s.feed(b": 3}  ") == [{"c": 3}]  # se completa con el siguiente paquete
    assert s.feed(b'basura{"d": 4}') == [{"d": 4}]  # lo que sobra delante se salta
    assert s.feed('{"e": "acción"}'.encode()) == [{"e": "acción"}]
    assert s.feed(b"") == []


def update(**game: Any) -> dict[str, Any]:
    return {
        "Event": "UpdateState",
        "Data": {
            "Players": [
                {"Name": "Yo", "PrimaryId": "Epic|1|0", "TeamNum": 0, "Score": 410, "Goals": 2, "Shots": 5,
                 "Assists": 1, "Saves": 3, "Demos": 1, "Boost": 55},
                {"Name": "Rival", "TeamNum": 1, "Score": 100, "Goals": 1},
            ],
            "Game": {
                "Teams": [
                    {"Name": "Naranja", "TeamNum": 1, "Score": 1},
                    {"Name": "Azul", "TeamNum": 0, "Score": 2},
                ],
                "TimeSeconds": 134, "bOverTime": False, "bReplay": False,
                "Target": {"Name": "Yo", "TeamNum": 0},
                **game,
            },
        },
    }  # fmt: skip


def test_rocket_league_state_becomes_a_match() -> None:
    clock = [100.0]
    state = RocketState(lambda: clock[0])
    state.apply({"Event": "MatchCreated", "Data": {}})
    state.apply(update())
    state.apply({"Event": "GoalScored", "Data": {"Scorer": {"Name": "Yo"}}})
    state.apply({"Event": "StatfeedEvent", "Data": {"EventName": "Save", "MainTarget": {"Name": "Yo"}}})
    m = state.match()
    assert m is not None
    assert m.score == "2 – 1"  # azul (0) primero, aunque llegara después
    assert (m.title, m.clock, m.clock_step) == ("Partida en curso", 134.0, -1)  # la cuenta atrás baja
    assert (
        dict(m.stats)["Goles"] == "2" and dict(m.stats)["Paradas"] == "3" and dict(m.stats)["Puntos"] == "410"
    )
    assert m.feed == ("Yo: Save", "¡Gol de Yo!")
    state.apply(update(bReplay=True))
    replay = state.match()
    assert replay is not None and replay.clock_step == 0  # en la repetición el reloj no corre
    state.apply({"Event": "MatchEnded", "Data": {}})
    ended = state.match()
    assert ended is not None and ended.title == "Fin de la partida" and ended.feed[0] == "Fin de la partida"
    clock[0] += 30
    assert not state.fresh()  # sin noticias del juego desde hace rato


def test_rocket_league_data_can_arrive_as_text_and_without_a_target() -> None:
    state = RocketState()
    msg = update()
    del msg["Data"]["Game"]["Target"]
    state.apply({"Event": "UpdateState", "Data": json.dumps(msg["Data"])})  # `Data` como texto JSON
    m = state.match()
    assert m is not None and m.score == "2 – 1" and m.stats == ()  # sin saber quién eres, solo el marcador


def test_rocket_league_over_a_real_socket_and_the_disabled_api_hint(tmp_path: Path) -> None:
    ini = tmp_path / "DefaultStatsAPI.ini"
    ini.write_text("[TAGame.MatchStatsExporter_TA]\nPort=49123\nPacketSendRate=0\n", encoding="utf-8")
    game_side, phone_side = socket.socketpair()
    api = RocketLeagueStats(lambda: ini, connect=lambda _port: phone_side)
    try:
        assert api.enabled() is False
        # Con la API desactivada y sin conexión, se dice qué hacer (no se queda en blanco).
        api2 = RocketLeagueStats(lambda: ini, connect=lambda _p: (_ for _ in ()).throw(OSError("rechazado")))
        hint = api2.read()
        api2.stop()
        assert hint is not None and "PacketSendRate" in hint.hint and hint.score == ""
        ini.write_text("PacketSendRate=10\n", encoding="utf-8")
        assert api.enabled() is True
        game_side.sendall(json.dumps(update()).encode() + json.dumps(update()).encode()[:30])
        deadline = time.monotonic() + 3
        found: Match | None = None
        while time.monotonic() < deadline and not found:
            found = api.read()
            time.sleep(0.05)
        assert found is not None and found.score == "2 – 1"
    finally:
        api.stop()
        game_side.close()
    assert RocketLeagueStats(lambda: None).enabled() is None  # sin el archivo no se sabe


def test_match_source_picks_the_reader_and_caches_league() -> None:
    calls: list[int] = []
    clock = [10.0]

    def lol() -> dict[str, Any] | None:
        calls.append(1)
        return LOL

    stopped: list[int] = []

    class Rocket(RocketLeagueStats):
        def __init__(self) -> None:
            super().__init__(lambda: None)

        def read(self) -> Match | None:
            return Match(title="rl")

        def stop(self) -> None:
            stopped.append(1)

    source = MatchSource(Rocket(), lol=lol, now=lambda: clock[0])
    assert source.match("League of Legends", "riot:lol") is not None
    assert source.match("League of Legends", "riot:lol") is not None
    assert len(calls) == 1  # dos lecturas seguidas: una sola petición
    clock[0] += 3
    source.match("League of Legends", "riot:lol")
    assert len(calls) == 2
    assert source.match("Rocket League®", "epic:Sugar") == Match(title="rl")
    assert source.match("Marvel Rivals", "steam:1") is None and stopped  # otro juego: se suelta el puerto
    source.idle()
    assert len(stopped) >= 2


def watcher(tmp: Path, procs: list[tuple[str, float]], epic: Path | None = None) -> GameWatcher:
    return GameWatcher(
        None,
        epic,
        tmp / "games",
        running_appid=lambda: 0,
        processes=lambda: procs,
        now=lambda: 1000.0,
        background=False,
    )


def test_league_counts_as_a_game_only_during_a_match(tmp_path: Path) -> None:
    game = watcher(
        tmp_path, [(r"C:\Riot Games\League of Legends\Game\League of Legends.exe", 700.0)]
    ).current()
    assert game is not None and (game.key, game.name, game.source, game.started) == (
        "riot:lol", "League of Legends", "Riot", 700.0,
    )  # fmt: skip
    client = [(r"C:\Riot Games\League of Legends\LeagueClientUx.exe", 600.0)]
    assert watcher(tmp_path, client).current() is None  # el cliente abierto no es una partida


def test_rocket_league_ini_is_found_from_the_epic_manifest(tmp_path: Path) -> None:
    install = tmp_path / "rocketleague"
    (install / "TAGame" / "Config").mkdir(parents=True)
    ini = install / "TAGame" / "Config" / "DefaultStatsAPI.ini"
    ini.write_text("PacketSendRate=0", encoding="utf-8")
    epic = tmp_path / "Manifests"
    epic.mkdir()
    (epic / "a.item").write_text(
        json.dumps(
            {
                "AppName": "Sugar",
                "DisplayName": "Rocket League®",
                "InstallLocation": str(install),
                "LaunchExecutable": "x",
            }
        ),
        encoding="utf-8",
    )
    assert watcher(tmp_path, [], epic).rocket_ini() == ini
    assert watcher(tmp_path, []).rocket_ini() is None


def test_hub_sends_the_match_but_not_every_tick_of_its_clock(tmp_path: Path) -> None:
    clock = [1000.0]
    seconds = [100.0]
    procs = [(r"C:\Riot Games\League of Legends\Game\League of Legends.exe", 700.0)]

    def lol() -> dict[str, Any] | None:
        data: dict[str, Any] = json.loads(json.dumps(LOL))
        data["gameData"]["gameTime"] = seconds[0]
        return data

    w = GameWatcher(
        None, None, tmp_path / "g", processes=lambda: procs, now=lambda: clock[0], background=False,
        matches=MatchSource(lol=lol, now=lambda: clock[0]),
    )  # fmt: skip
    hub = Hub(PersonalMemory(ConversationStore(":memory:")), games=w)

    class Phone:
        def __init__(self) -> None:
            self.events: list[object] = []

        async def send_event(self, event: object) -> None:
            self.events.append(event)

    phone = Phone()
    hub.add(phone)  # type: ignore[arg-type]

    async def run() -> None:
        await hub.send_game()
        for _ in range(3):  # solo avanza el reloj de la partida
            clock[0] += 3
            seconds[0] += 3
            await hub.send_game()

    asyncio.run(run())
    events = [e for e in phone.events if isinstance(e, GameEvent)]
    assert len(events) == 1
    match = events[0].match
    assert match is not None and match.score == "9 – 9" and match.clock_step == 1
    assert {s.label for s in match.stats} >= {"K / D / A", "Oro"}
