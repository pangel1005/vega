"""Counter-Strike 2 (Game State Integration) y Forza Horizon (Data Out por UDP)."""

import socket
import struct
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from fastapi.testclient import TestClient

from server.device.cs2 import CFG_NAME, Cs2State, cfg_dir, cfg_installed, cfg_text, load_token
from server.device.forza import DASH_SIZE, ForzaTelemetry, match_from, parse_dash
from server.device.match import Match, MatchSource
from server.web import register_gsi

TOKEN = "a" * 32


def payload(token: str = TOKEN, mine: bool = True, **round_: Any) -> dict[str, Any]:
    return {
        "auth": {"token": token},
        "provider": {"name": "Counter-Strike 2", "steamid": "7656"},
        "map": {"name": "de_mirage", "mode": "competitive", "phase": "live", "round": 11,
                "team_ct": {"score": 7}, "team_t": {"score": 4}},
        "round": {"phase": "live", **round_},
        "player": {
            "steamid": "7656" if mine else "9999", "name": "Yo", "team": "T",
            "state": {"health": 62, "armor": 100, "money": 3400},
            "match_stats": {"kills": 12, "deaths": 7, "assists": 3, "mvps": 2},
            "weapons": {"weapon_0": {"name": "weapon_knife", "state": "holstered"},
                        "weapon_1": {"name": "weapon_ak47", "state": "active"}},
        },
    }  # fmt: skip


def test_cs2_shows_my_data_with_my_team_first() -> None:
    state = Cs2State(TOKEN)
    assert state.match() is None  # sin avisos: el juego está en el menú
    assert state.feed(payload(bomb="planted"))
    m = state.match()
    assert m is not None
    assert m.title == "Competitivo · Mirage"
    assert m.score == "4 – 7" and m.colors == ("#F0B34B", "#5C9DFF")  # soy T: mi equipo va primero
    stats = dict(m.stats)
    assert stats["Vida"] == "62" and stats["Dinero"] == "$3400" and stats["K / D / A"] == "12 / 7 / 3"
    assert stats["Arma"] == "AK-47" and stats["MVP"] == "2"
    assert m.feed == ("¡Bomba plantada!", "Ronda 12 · en juego")


def test_cs2_hides_other_players_data_when_spectating() -> None:
    state = Cs2State(TOKEN)
    state.feed(payload(mine=False))
    m = state.match()
    assert m is not None and m.stats == () and m.score == "7 – 4"  # solo mapa y marcador


def test_cs2_ignores_notices_without_the_right_key_and_goes_stale() -> None:
    clock = [0.0]
    state = Cs2State(TOKEN, now=lambda: clock[0])
    assert not state.feed(payload(token="b" * 32))
    assert not state.feed({"map": {"name": "de_dust2"}})  # sin clave
    assert not state.feed({"auth": "no es un objeto"})
    assert state.match() is None
    assert state.feed(payload())
    clock[0] += 60
    assert state.match() is None  # hace rato que no dice nada: en el menú


def test_gsi_route_accepts_only_the_right_key_and_small_bodies() -> None:
    state = Cs2State(TOKEN)
    app = FastAPI()
    register_gsi(app, state.feed)
    client = TestClient(app)
    assert client.post("/app/gsi/cs2", json=payload()).status_code == 200
    assert client.post("/app/gsi/cs2", json=payload(token="x" * 32)).status_code == 403
    assert client.post("/app/gsi/cs2", content=b"no es json").status_code == 400
    assert client.post("/app/gsi/cs2", json=["una", "lista"]).status_code == 403
    big = {"auth": {"token": TOKEN}, "relleno": "x" * 250_000}
    assert client.post("/app/gsi/cs2", json=big).status_code == 413


def test_cs2_cfg_and_token(tmp_path: Path) -> None:
    token = load_token(tmp_path / "data" / "gsi_token.txt")
    assert len(token) == 32 and load_token(tmp_path / "data" / "gsi_token.txt") == token  # se crea una vez
    text = cfg_text(token, 8000)
    assert "http://127.0.0.1:8000/app/gsi/cs2" in text and token in text and '"player_weapons" "1"' in text
    install = tmp_path / "cs2"
    assert cfg_dir(install) is None and not cfg_installed(install) and not cfg_installed(None)
    (install / "game" / "csgo" / "cfg").mkdir(parents=True)
    assert cfg_dir(install) is not None and not cfg_installed(install)
    (install / "game" / "csgo" / "cfg" / CFG_NAME).write_text(text, encoding="utf-8")
    assert cfg_installed(install)


def dash(**fields: Any) -> bytes:
    """Un paquete de Forza Horizon 4/5 (324 bytes) con los campos que importan."""
    data = bytearray(DASH_SIZE)
    struct.pack_into("<i", data, 0, fields.get("race_on", 1))
    struct.pack_into("<3f", data, 8, 8000.0, 900.0, fields.get("rpm", 6200.0))
    struct.pack_into("<2i", data, 216, fields.get("car_class", 4), fields.get("index", 812))
    struct.pack_into("<2f", data, 256, fields.get("speed", 50.0), fields.get("power", 368_000.0))
    struct.pack_into("<H", data, 312, fields.get("lap", 0))
    struct.pack_into("<6B", data, 314, fields.get("position", 0), 0, 0, 0, 0, fields.get("gear", 4))
    return bytes(data)


def test_forza_packet_becomes_driving_stats() -> None:
    parsed = parse_dash(dash())
    assert parsed is not None and parsed["gear"] == 4 and parsed["class"] == 4
    m = match_from(parsed)
    stats = dict(m.stats)
    assert m.title == "Conduciendo"
    assert stats["Velocidad"] == "180 km/h" and stats["Marcha"] == "4" and stats["RPM"] == "6 200"
    assert stats["Potencia"] == "500 CV" and stats["Clase"] == "S1 812" and "Posición" not in stats
    racing = dict(match_from(parse_dash(dash(position=3, lap=1)) or {}).stats)
    assert racing["Posición"] == "3" and racing["Vuelta"] == "2"
    assert match_from(parse_dash(dash(race_on=0)) or {}).title == "En pausa"
    assert dict(match_from(parse_dash(dash(gear=0)) or {}).stats)["Marcha"] == "R"


def test_forza_with_unknown_sizes_or_without_packets_says_what_to_do() -> None:
    assert parse_dash(b"\x00" * 311) is None
    clock = [0.0]
    f = ForzaTelemetry(5999, now=lambda: clock[0])
    f.feed(b"\x00" * 311)  # un Forza con otro formato
    hint = f._hint()
    assert "311" in hint.hint and "324" in hint.hint
    f.stop()
    f.feed(dash())
    live = f._live()
    assert live is not None and live.stats
    clock[0] += 10
    assert f._live() is None  # sin paquetes: pausa o menú
    f.stop()


def test_forza_receives_real_udp_packets() -> None:
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    probe.bind(("127.0.0.1", 0))
    port = probe.getsockname()[1]
    probe.close()
    forza = ForzaTelemetry(port)
    try:
        forza.read()  # arranca la escucha
        sender = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        found: Match | None = None
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and not (found and found.stats):
            sender.sendto(dash(speed=30.0), ("127.0.0.1", port))
            time.sleep(0.1)
            found = forza.read()
        sender.close()
        assert found is not None and dict(found.stats)["Velocidad"] == "108 km/h"
    finally:
        forza.stop()


def test_match_source_picks_cs2_and_forza() -> None:
    state = Cs2State(TOKEN)
    forza = ForzaTelemetry(5998)
    source = MatchSource(cs2=state, cs2_installed=lambda: False, forza=forza)
    hint = source.match("Counter-Strike 2", "steam:730")
    assert hint is not None and "cs2_gsi.py" in hint.hint  # sin el archivo de integración: se dice qué hacer
    state.feed(payload())
    shown = source.match("Counter-Strike 2", "steam:730")
    assert shown is not None and shown.score == "4 – 7"
    source2 = MatchSource(cs2=state, cs2_installed=lambda: True, forza=None)
    assert source2.match("Marvel Rivals", "steam:1") is None
    forza.stop()
