"""Escucha continua por WebSocket: «Vega, …» activa; lo demás se ignora; órdenes de botones por voz."""

from typing import Any

import numpy as np
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat, FakeSTT
from server.tests.test_actions import FakeExecutor
from server.tools.actions import ActionDef, ActionRunner

TOKEN = "t" * 43
RATE = 16_000
ACTIONS = [
    ActionDef(
        id="apagar", label="Apagar PC", kind="power", arg="shutdown", confirm=True, say=["apaga el pc"]
    ),
    ActionDef(id="volumen-mas", label="Volumen +", kind="keys", arg="volume_up", say=["sube el volumen"]),
]


def phrase() -> bytes:
    """0,6 s de calma + 1 s de «voz» (un tono) + 1 s de silencio: el detector corta una frase."""
    t = np.arange(RATE) / RATE
    voice = (np.sin(2 * np.pi * 220 * t) * 0.2 * 32767).astype(np.int16).tobytes()
    quiet = np.zeros(int(RATE * 0.6), dtype=np.int16).tobytes()
    return quiet + voice + quiet + quiet


def session(texts: list[str], model: FakeChat, ex: FakeExecutor) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=model,
        store=ConversationStore(":memory:"),
        stt=FakeSTT(texts=texts),
        actions=ActionRunner(ACTIONS, ex),
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def start(ws: WebSocketTestSession) -> None:
    ws.send_json({"type": "hello", "token": TOKEN})
    for _ in range(3):  # ready, actions, history
        ws.receive_json()
    ws.send_json({"type": "wake_mode", "on": True})


def say(ws: WebSocketTestSession) -> None:
    audio = phrase()
    for i in range(0, len(audio), 3200):
        ws.send_bytes(audio[i : i + 3200])


def until(ws: WebSocketTestSession, kind: str) -> list[dict[str, Any]]:
    events = []
    while True:
        events.append(ws.receive_json())
        if events[-1]["type"] == kind:
            return events


def test_question_with_wake_word_goes_to_model() -> None:
    model, ex = FakeChat(pieces=["Son las cinco."]), FakeExecutor()
    with session(["Vega, ¿qué hora es?"], model, ex) as ws:
        start(ws)
        say(ws)
        events = until(ws, "done")
    transcript = next(e for e in events if e["type"] == "transcript")
    assert transcript["text"] == "¿qué hora es?"
    assert model.calls[0][-1].content == "¿qué hora es?"


def test_phrases_without_wake_word_are_ignored() -> None:
    model, ex = FakeChat(pieces=["ok"]), FakeExecutor()
    with session(["Mañana llueve en Madrid"], model, ex) as ws:
        start(ws)
        say(ws)
        # Nada que contar sobre esa frase: lo siguiente que llega es la respuesta a este mensaje.
        ws.send_json({"type": "chat", "text": "hola"})
        events = until(ws, "done")
    assert all(e["type"] != "transcript" for e in events)
    assert [m.content for m in model.calls[0] if m.role == "user"] == ["hola"]


def test_voice_command_presses_button() -> None:
    model, ex = FakeChat(), FakeExecutor()
    with session(["Oye Vega, sube el volumen por favor"], model, ex) as ws:
        start(ws)
        say(ws)
        result = until(ws, "action_result")[-1]
    assert result == {
        "type": "action_result",
        "id": "volumen-mas",
        "ok": True,
        "message": "Volumen +: hecho.",
    }
    assert ex.ran == ["volumen-mas"] and model.calls == []


def test_sensitive_voice_command_needs_spoken_yes() -> None:
    ex = FakeExecutor()
    with session(["Vega, apaga el PC", "Sí, confirmo"], FakeChat(), ex) as ws:
        start(ws)
        say(ws)
        confirm = until(ws, "confirm")[-1]
        assert confirm["id"] == "apagar"
        assert ex.ran == []  # todavía no
        say(ws)
        assert until(ws, "action_result")[-1]["ok"] is True
    assert ex.ran == ["apagar"]


def test_spoken_no_cancels() -> None:
    ex = FakeExecutor()
    with session(["Vega, apaga el PC", "No"], FakeChat(), ex) as ws:
        start(ws)
        say(ws)
        until(ws, "confirm")
        say(ws)
        assert until(ws, "action_result")[-1]["message"] == "Cancelado."
    assert ex.ran == []


def test_name_alone_waits_for_the_command() -> None:
    model, ex = FakeChat(pieces=["Hecho."]), FakeExecutor()
    with session(["Vega.", "cuéntame un chiste"], model, ex) as ws:
        start(ws)
        say(ws)
        assert until(ws, "state")[-1] == {"type": "state", "state": "listening"}
        say(ws)  # sin repetir el nombre
        until(ws, "done")
    assert model.calls[0][-1].content == "cuéntame un chiste"


def test_touching_the_face_opens_attention() -> None:
    model = FakeChat(pieces=["Hecho."])
    with session(["dime algo bonito"], model, FakeExecutor()) as ws:
        start(ws)
        ws.send_json({"type": "attention"})
        assert ws.receive_json() == {"type": "state", "state": "listening"}
        say(ws)
        until(ws, "done")
    assert model.calls[0][-1].content == "dime algo bonito"
    assert model.preloads == 1


def test_wake_mode_off_ignores_audio() -> None:
    model = FakeChat(pieces=["ok"])
    with session(["Vega, ¿qué hora es?"], model, FakeExecutor()) as ws:
        start(ws)
        ws.send_json({"type": "wake_mode", "on": False})
        say(ws)
        ws.send_json({"type": "chat", "text": "hola"})
        events = until(ws, "done")
    assert all(e["type"] != "transcript" for e in events)
