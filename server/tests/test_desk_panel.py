"""La «Mesa» del teléfono: volumen por programa, fondos con miniatura y los mensajes que la mueven."""

from __future__ import annotations

from io import BytesIO
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from PIL import Image
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.skills.desk import PANEL_MAX_APPS, Desk
from server.tests.fakes import FakeChat
from server.tests.test_desk import FakeAudio, make_desk
from server.tests.test_wake import until

TOKEN = "t" * 43


def app_of(desk: Desk | None) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    return create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(pieces=["ok"]),
        store=ConversationStore(":memory:"),
        desk=desk,
    )


def connect(app: Any) -> Any:
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def start(ws: WebSocketTestSession, role: str = "phone") -> None:
    ws.send_json({"type": "hello", "token": TOKEN, "role": role})
    for _ in range(2):  # ready, history
        ws.receive_json()


def ask(ws: WebSocketTestSession, **msg: Any) -> dict[str, Any]:
    ws.send_json({"type": "desk", **msg})
    return until(ws, "desk_state")[-1]


def by_key(state: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {a["key"]: a for a in state["apps"]}


def test_the_panel_lists_what_has_sound_in_order(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path, playing="Fortnite")
    apps = desk.panel()["apps"]
    assert [a["key"] for a in apps] == ["discord", "spotify", "navegador", "fortniteclient-win64-shipping"]
    assert [a["label"] for a in apps] == ["Discord", "Spotify", "Navegador", "Fortnite"]
    assert apps[0]["volume"] == 0.8 and apps[0]["muted"] is False


def test_an_unknown_program_gets_a_readable_name(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    labels = [a["label"] for a in desk.panel()["apps"]]
    assert labels[-1] == "Fortniteclient"


def test_two_browsers_make_one_row_and_the_noise_is_left_out(tmp_path: Path) -> None:
    sessions = [
        FakeAudio("chrome.exe", 0.4),
        FakeAudio("opera.exe", 0.9),
        FakeAudio("wallpaper64.exe", 1.0),
        FakeAudio("steamwebhelper.exe", 1.0),
    ]
    from server.device.mixer import Mixer

    desk = Desk(
        wallpaper=None,
        mixer=Mixer(lambda: list(sessions)),
        games=None,
        launch=lambda _u: None,
        running_game=lambda: None,
    )
    apps = desk.panel()["apps"]
    assert [a["key"] for a in apps] == ["navegador"]
    assert apps[0]["volume"] == 0.9  # el más alto, como al leerlo por voz


def test_the_panel_never_lists_more_than_the_limit(tmp_path: Path) -> None:
    from server.device.mixer import Mixer

    sessions = [FakeAudio(f"programa{i}.exe", 0.5) for i in range(12)]
    desk = Desk(
        wallpaper=None,
        mixer=Mixer(lambda: list(sessions)),
        games=None,
        launch=lambda _u: None,
        running_game=lambda: None,
    )
    assert len(desk.panel()["apps"]) == PANEL_MAX_APPS


def test_volume_and_mute_move_every_session_of_the_row(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    assert desk.panel_volume("discord", 0.25)
    assert fake["sessions"]["discord.exe"].volume == 0.25
    assert desk.panel_mute("spotify", True)
    assert fake["sessions"]["spotify.exe"].muted is True
    assert desk.panel_volume("navegador", 2.0)  # se acota a 1
    assert fake["sessions"]["opera.exe"].volume == 1.0


def test_unknown_or_dangerous_keys_do_nothing(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    assert not desk.panel_volume("zoom", 0.5)  # no está sonando
    assert not desk.panel_volume("../../x", 0.5)
    assert not desk.panel_mute("DISCORD EXE", True)
    assert fake["sessions"]["discord.exe"].volume == 0.8


def test_without_a_mixer_the_panel_has_no_apps(tmp_path: Path) -> None:
    desk = Desk(wallpaper=None, mixer=None, games=None, launch=lambda _u: None, running_game=lambda: None)
    assert desk.panel() == {
        "apps": [],
        "wallpaper": {"available": False, "current": "", "paused": False, "suggestions": []},
    }


def test_the_panel_offers_three_other_wallpapers(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    wallpaper = desk.panel()["wallpaper"]
    assert wallpaper["available"] is True
    ids = [s["id"] for s in wallpaper["suggestions"]]
    assert len(ids) == 3 and len(set(ids)) == 3
    assert fake["engine"].current is None


def test_picking_a_suggestion_applies_it_and_the_next_ones_exclude_it(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    first = desk.panel()["wallpaper"]["suggestions"][0]
    assert desk.panel_wallpaper(first["id"]) == first["title"]
    assert fake["wp_calls"][-1][1:3] == ["-control", "openWallpaper"]
    desk.panel_wallpaper_more()
    after = desk.panel()["wallpaper"]
    assert after["current"] == first["title"]
    assert first["id"] not in [s["id"] for s in after["suggestions"]]


def test_unknown_wallpaper_id_is_refused(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    assert desk.panel_wallpaper("999999") is None
    assert fake["wp_calls"] == []


def test_random_pause_and_play(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    assert desk.panel_wallpaper_random() is not None
    assert desk.panel_wallpaper_pause(True)
    assert desk.panel()["wallpaper"]["paused"] is True
    assert fake["wp_calls"][-1][-1] == "pause"
    assert desk.panel_wallpaper_pause(False)
    assert desk.panel()["wallpaper"]["paused"] is False


def test_thumbnail_is_a_small_jpeg_made_from_the_preview(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    folder: Path = fake["engine"].items()[0].project.parent
    Image.new("RGB", (1920, 1080), (200, 30, 30)).save(folder / "preview.jpg")
    data = desk.wallpaper_thumbnail(folder.name)
    assert data is not None
    image = Image.open(BytesIO(data))
    assert image.format == "JPEG" and image.width <= 256 and image.height <= 144


def test_thumbnail_takes_the_first_frame_of_a_gif(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    folder: Path = fake["engine"].items()[1].project.parent
    frames = [Image.new("RGB", (400, 300), c) for c in ((255, 0, 0), (0, 255, 0))]
    frames[0].save(folder / "preview.gif", save_all=True, append_images=frames[1:])
    data = desk.wallpaper_thumbnail(folder.name)
    assert data is not None and Image.open(BytesIO(data)).format == "JPEG"


def test_thumbnail_without_preview_or_with_a_broken_one_is_none(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    folder: Path = fake["engine"].items()[0].project.parent
    assert desk.wallpaper_thumbnail(folder.name) is None
    (folder / "preview.jpg").write_bytes(b"not an image")
    assert desk.wallpaper_thumbnail(folder.name) is None
    assert desk.wallpaper_thumbnail("nope") is None


def test_a_preview_outside_the_wallpaper_folder_is_ignored(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    folder: Path = fake["engine"].items()[0].project.parent
    Image.new("RGB", (10, 10)).save(tmp_path / "secret.png")
    (folder / "project.json").write_text('{"title": "Akane in the rain", "preview": "../../secret.png"}')
    assert desk.wallpaper_thumbnail(folder.name) is None


# --- por el WebSocket ---


def test_get_returns_the_state_to_the_phone(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    with connect(app_of(desk)) as ws:
        start(ws)
        state = ask(ws, action="get")
    assert "discord" in by_key(state) and state["wallpaper"]["available"] is True
    assert len(state["wallpaper"]["suggestions"]) == 3


def test_volume_and_mute_over_the_socket_answer_with_the_new_state(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    with connect(app_of(desk)) as ws:
        start(ws)
        state = ask(ws, action="volume", app="discord", level=0.3)
        assert by_key(state)["discord"]["volume"] == 0.3
        state = ask(ws, action="mute", app="spotify", muted=True)
        assert by_key(state)["spotify"]["muted"] is True
    assert fake["sessions"]["discord.exe"].volume == 0.3


def test_wallpaper_over_the_socket(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    with connect(app_of(desk)) as ws:
        start(ws)
        pick = ask(ws, action="get")["wallpaper"]["suggestions"][0]
        state = ask(ws, action="wallpaper", id=pick["id"])
        assert state["wallpaper"]["current"] == pick["title"]
        assert ask(ws, action="pause")["wallpaper"]["paused"] is True
        assert ask(ws, action="play")["wallpaper"]["paused"] is False
        assert ask(ws, action="random")["wallpaper"]["current"] != ""
        assert ask(ws, action="more")["wallpaper"]["available"] is True


@pytest.mark.parametrize(
    "msg",
    [
        {"action": "volume", "app": "discord"},  # sin nivel
        {"action": "volume", "level": 0.5},  # sin programa
        {"action": "volume", "app": "discord", "level": 1.5},
        {"action": "volume", "app": "Disc ord; rm", "level": 0.5},
        {"action": "mute", "app": "discord"},  # sin «muted»
        {"action": "wallpaper"},  # sin id
        {"action": "wallpaper", "id": "../x"},
        {"action": "explode"},
    ],
)
def test_bad_messages_are_refused_without_touching_anything(tmp_path: Path, msg: dict[str, Any]) -> None:
    desk, fake = make_desk(tmp_path)
    with connect(app_of(desk)) as ws:
        start(ws)
        ws.send_json({"type": "desk", **msg})
        assert until(ws, "error")[-1]["type"] == "error"
    assert fake["sessions"]["discord.exe"].volume == 0.8
    assert fake["wp_calls"] == []


def test_a_wallpaper_engine_that_does_not_answer_gives_an_error_and_the_state(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    desk._wallpaper._exe = None  # type: ignore[union-attr]
    with connect(app_of(desk)) as ws:
        start(ws)
        ws.send_json({"type": "desk", "action": "random"})
        events = until(ws, "desk_state")
    assert any(e["type"] == "error" for e in events)


def test_only_the_phone_can_move_the_desk(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    with connect(app_of(desk)) as ws:
        start(ws, role="desktop")
        ws.send_json({"type": "desk", "action": "volume", "app": "discord", "level": 0.1})
        ws.send_json({"type": "reset"})
        until(ws, "history")
    assert fake["sessions"]["discord.exe"].volume == 0.8


def test_without_a_desk_the_message_is_ignored(tmp_path: Path) -> None:
    with connect(app_of(None)) as ws:
        start(ws)
        ws.send_json({"type": "desk", "action": "get"})
        ws.send_json({"type": "reset"})
        events = until(ws, "history")
    assert all(e["type"] != "desk_state" for e in events)


# --- la miniatura por HTTP ---


def test_thumbnail_route(tmp_path: Path) -> None:
    desk, fake = make_desk(tmp_path)
    folder: Path = fake["engine"].items()[0].project.parent
    Image.new("RGB", (640, 360), (10, 90, 200)).save(folder / "preview.png")
    client = TestClient(app_of(desk), base_url="http://127.0.0.1:8000")
    ok = client.get(f"/app/wallpaper/{folder.name}.jpg")
    assert ok.status_code == 200 and ok.headers["content-type"] == "image/jpeg"
    assert Image.open(BytesIO(ok.content)).format == "JPEG"
    assert client.get("/app/wallpaper/1002.jpg").status_code == 404  # sin vista previa
    assert client.get("/app/wallpaper/abc.jpg").status_code == 404
    assert client.get("/app/wallpaper/99999999999999999999999.jpg").status_code == 404
