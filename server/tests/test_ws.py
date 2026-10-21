from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession
from starlette.websockets import WebSocketDisconnect

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat

TOKEN = "t" * 43
SETTINGS = Settings(
    assistant=AssistantSettings(name="Prueba"),
    llm=LlmSettings(model="fake", system_prompt="Eres {name}."),
)


async def _probe() -> LlmStatus:
    return LlmStatus(True, "ok")


def make_client(model: FakeChat, store: ConversationStore | None = None) -> TestClient:
    store = store or ConversationStore(":memory:")
    app = create_app(SETTINGS, token=TOKEN, llm_probe=_probe, chat_model=model, store=store)
    return TestClient(app, base_url="http://127.0.0.1:8000")


def connect(client: TestClient, **kwargs: Any) -> Any:
    # URL completa: el servidor solo acepta el host 127.0.0.1/localhost.
    return client.websocket_connect("ws://127.0.0.1:8000/ws", **kwargs)


def login(ws: WebSocketTestSession) -> list[dict[str, Any]]:
    """Envía el token y devuelve los mensajes de bienvenida (ready + history)."""
    ws.send_json({"type": "hello", "token": TOKEN})
    return [ws.receive_json(), ws.receive_json()]


def receive_until(ws: WebSocketTestSession, kind: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    while True:
        event = ws.receive_json()
        events.append(event)
        if event["type"] == kind:
            return events


def chat(ws: WebSocketTestSession, text: str) -> list[dict[str, Any]]:
    ws.send_json({"type": "chat", "text": text})
    events = receive_until(ws, "done")
    assert ws.receive_json() == {"type": "state", "state": "idle"}
    return events


def test_chat_streams_tokens_and_states() -> None:
    model = FakeChat()
    with connect(make_client(model)) as ws:
        assert login(ws) == [
            {"type": "ready", "assistant": "Prueba", "model": "fake"},
            {"type": "history", "messages": []},
        ]
        events = chat(ws, "hola")

    assert events == [
        {"type": "state", "state": "thinking"},
        {"type": "state", "state": "speaking"},
        {"type": "token", "text": "Hola"},
        {"type": "token", "text": ", "},
        {"type": "token", "text": "¿qué tal?"},
        {"type": "done", "text": "Hola, ¿qué tal?"},
    ]
    sent = model.calls[0]
    assert sent[0].role == "system" and sent[0].content == "Eres Prueba."
    assert sent[-1].content == "hola"


def test_history_is_sent_on_next_turn() -> None:
    model = FakeChat(pieces=["respuesta"])
    with connect(make_client(model)) as ws:
        login(ws)
        chat(ws, "primera")
        chat(ws, "segunda")

    contents = [(m.role, m.content) for m in model.calls[1]]
    assert contents[1:] == [("user", "primera"), ("assistant", "respuesta"), ("user", "segunda")]


def test_history_survives_restart(tmp_path: Path) -> None:
    db = tmp_path / "history.sqlite"
    with connect(make_client(FakeChat(pieces=["Encantada, Pablo."]), ConversationStore(db))) as ws:
        login(ws)
        chat(ws, "Me llamo Pablo")

    # «Reinicio»: otra app y otro almacén sobre el mismo archivo.
    model = FakeChat(pieces=["Te llamas Pablo."])
    with connect(make_client(model, ConversationStore(db))) as ws:
        _, history = login(ws)
        assert history["messages"] == [
            {"role": "user", "content": "Me llamo Pablo"},
            {"role": "assistant", "content": "Encantada, Pablo."},
        ]
        chat(ws, "¿Cómo me llamo?")
    assert [m.content for m in model.calls[0][1:]] == [
        "Me llamo Pablo",
        "Encantada, Pablo.",
        "¿Cómo me llamo?",
    ]


def test_reset_starts_empty_conversation() -> None:
    model = FakeChat(pieces=["ok"])
    with connect(make_client(model)) as ws:
        login(ws)
        chat(ws, "algo viejo")
        ws.send_json({"type": "reset"})
        assert ws.receive_json() == {"type": "history", "messages": []}
        chat(ws, "algo nuevo")
    assert [m.content for m in model.calls[-1][1:]] == ["algo nuevo"]


def test_llm_down_gives_clear_error_and_recovers() -> None:
    model = FakeChat(fail="No puedo conectar con Ollama")
    with connect(make_client(model)) as ws:
        login(ws)
        ws.send_json({"type": "chat", "text": "hola"})
        events = receive_until(ws, "error")
        assert events[-1] == {"type": "error", "message": "No puedo conectar con Ollama"}
        assert ws.receive_json() == {"type": "state", "state": "error"}

        # Cuando el modelo vuelve, la conversación sigue sin reconectar.
        model.fail = None
        assert chat(ws, "otra vez")[-1]["text"] == "Hola, ¿qué tal?"

    # La pregunta fallida no queda en el historial.
    assert [m.content for m in model.calls[-1] if m.role == "user"] == ["otra vez"]


@pytest.mark.parametrize("hello", [{"type": "hello", "token": "malo"}, {"type": "chat", "text": "hola"}])
def test_rejects_bad_or_missing_token(hello: dict[str, str]) -> None:
    model = FakeChat()
    with connect(make_client(model)) as ws:
        ws.send_json(hello)
        assert ws.receive_json()["type"] == "error"
        with pytest.raises(WebSocketDisconnect) as exc:
            ws.receive_json()
    assert exc.value.code == 4401
    assert model.calls == []


def test_rejects_foreign_origin() -> None:
    client = make_client(FakeChat())
    with (
        pytest.raises(WebSocketDisconnect) as exc,
        connect(client, headers={"origin": "https://sitio-malicioso.example"}),
    ):
        pass
    assert exc.value.code == 4403


def test_accepts_own_origin() -> None:
    with connect(make_client(FakeChat()), headers={"origin": "http://127.0.0.1:8000"}) as ws:
        assert login(ws)[0]["type"] == "ready"


def test_invalid_message_does_not_close_connection() -> None:
    with connect(make_client(FakeChat())) as ws:
        login(ws)
        ws.send_text("esto no es json")
        assert ws.receive_json() == {"type": "error", "message": "Mensaje no válido."}
        assert chat(ws, "hola")[-1]["type"] == "done"
