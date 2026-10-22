"""Voz de respuesta: frases en orden, audio binario tras cada cabecera, voz elegible y recordada."""

import json
from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.speech.tts import PiperTTS, voice_label
from server.tests.fakes import FakeChat, FakeTTS
from server.tests.test_actions import FakeExecutor
from server.tools.actions import ActionDef, ActionRunner

TOKEN = "t" * 43


def session(model: FakeChat, tts: FakeTTS) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=model,
        store=ConversationStore(":memory:"),
        tts=tts,
        actions=ActionRunner(
            [ActionDef(id="silenciar", label="Silenciar", kind="keys", arg="volume_mute")], FakeExecutor()
        ),
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def receive(ws: WebSocketTestSession) -> dict[str, Any] | bytes:
    message = ws.receive()
    if message.get("bytes") is not None:
        return bytes(message["bytes"])
    return dict(json.loads(message["text"]))


def start(ws: WebSocketTestSession) -> dict[str, Any]:
    ws.send_json({"type": "hello", "token": TOKEN})
    events = [receive(ws) for _ in range(4)]  # ready, actions, voices, history
    voices = next(e for e in events if isinstance(e, dict) and e["type"] == "voices")
    return voices


def until_idle(ws: WebSocketTestSession) -> list[dict[str, Any] | bytes]:
    out: list[dict[str, Any] | bytes] = []
    while True:
        item = receive(ws)
        out.append(item)
        if isinstance(item, dict) and item == {"type": "state", "state": "idle"}:
            return out


def test_reply_is_spoken_sentence_by_sentence() -> None:
    tts = FakeTTS()
    pieces = ["La capital de Francia ", "es París. ", "Es una ciudad ", "muy famosa en Europa."]
    with session(FakeChat(pieces=pieces), tts) as ws:
        start(ws)
        ws.send_json({"type": "chat", "text": "¿Capital de Francia?"})
        items = until_idle(ws)

    headers = [i for i in items if isinstance(i, dict) and i["type"] == "audio"]
    assert [h["text"] for h in headers] == [
        "La capital de Francia es París.",
        "Es una ciudad muy famosa en Europa.",
    ]
    assert [h["seq"] for h in headers] == [1, 2] and all(h["rate"] == 22050 for h in headers)
    # Cada cabecera va seguida de su audio binario.
    for header in headers:
        nxt = items[items.index(header) + 1]
        assert isinstance(nxt, bytes) and len(nxt) == 2 * len(header["text"])
    kinds = [i["type"] if isinstance(i, dict) else "binario" for i in items]
    assert kinds.index("audio_end") > kinds.index("done")
    assert tts.said == [h["text"] for h in headers]


def test_voices_are_listed_and_can_be_changed() -> None:
    tts = FakeTTS()
    with session(FakeChat(), tts) as ws:
        voices = start(ws)
        assert voices["current"] == "es_MX-claude-high"
        assert [v["id"] for v in voices["items"]] == ["es_MX-claude-high", "es_AR-daniela-high"]

        ws.send_json({"type": "set_voice", "voice": "es_AR-daniela-high"})
        changed = receive(ws)
        assert isinstance(changed, dict) and changed["current"] == "es_AR-daniela-high"
        intro = receive(ws)
        assert isinstance(intro, dict) and intro["type"] == "audio" and "nueva voz" in intro["text"]

        ws.send_json({"type": "set_voice", "voice": "no-existe"})
        while True:
            item = receive(ws)
            if isinstance(item, dict) and item["type"] == "error":
                assert "no-existe" in item["message"]
                break


def test_button_presses_are_not_spoken() -> None:
    tts = FakeTTS()
    with session(FakeChat(), tts) as ws:
        start(ws)
        ws.send_json({"type": "action", "id": "silenciar"})
        until_idle(ws)
    assert tts.said == []  # con un toque basta el aviso en pantalla


def test_voice_label() -> None:
    assert voice_label("es_MX-claude-high") == "Claude · México"
    assert voice_label("es_AR-daniela-high") == "Daniela · Argentina"
    assert voice_label("raro") == "raro"


def test_chosen_voice_is_remembered(tmp_path: Path) -> None:
    voices = tmp_path / "voces"
    voices.mkdir()
    for name in ("es_MX-claude-high", "es_AR-daniela-high"):
        (voices / f"{name}.onnx").write_bytes(b"")
        (voices / f"{name}.onnx.json").write_text("{}", encoding="utf-8")
    prefs = tmp_path / "prefs.json"

    tts = PiperTTS(voices, "es_MX-claude-high", prefs)
    assert tts.voice == "es_MX-claude-high"
    tts.set_voice("es_AR-daniela-high")
    assert PiperTTS(voices, "es_MX-claude-high", prefs).voice == "es_AR-daniela-high"
