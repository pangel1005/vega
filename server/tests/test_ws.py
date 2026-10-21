from typing import Any

import pytest
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession
from starlette.websockets import WebSocketDisconnect

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.tests.fakes import FakeChat

TOKEN = "t" * 43
SETTINGS = Settings(
    assistant=AssistantSettings(name="Prueba"),
    llm=LlmSettings(model="fake", system_prompt="Eres {name}."),
)


async def _probe() -> LlmStatus:
    return LlmStatus(True, "ok")


def make_client(model: FakeChat) -> TestClient:
    return TestClient(create_app(SETTINGS, token=TOKEN, llm_probe=_probe, chat_model=model))


def connect(client: TestClient, **kwargs: Any) -> Any:
    return client.websocket_connect("/ws", **kwargs)


def receive_until(ws: WebSocketTestSession, kind: str) -> list[dict[str, Any]]:
    events: list[dict[str, Any]] = []
    while True:
        event = ws.receive_json()
        events.append(event)
        if event["type"] == kind:
            return events


def test_chat_streams_tokens_and_states() -> None:
    model = FakeChat()
    with connect(make_client(model)) as ws:
        ws.send_json({"type": "hello", "token": TOKEN})
        assert ws.receive_json() == {"type": "ready", "assistant": "Prueba", "model": "fake"}

        ws.send_json({"type": "chat", "text": "hola"})
        events = receive_until(ws, "done")
        assert ws.receive_json() == {"type": "state", "state": "idle"}

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
        ws.send_json({"type": "hello", "token": TOKEN})
        ws.receive_json()
        for text in ("primera", "segunda"):
            ws.send_json({"type": "chat", "text": text})
            receive_until(ws, "done")
            ws.receive_json()  # state idle

    contents = [(m.role, m.content) for m in model.calls[1]]
    assert contents[1:] == [("user", "primera"), ("assistant", "respuesta"), ("user", "segunda")]


def test_llm_down_gives_clear_error_and_recovers() -> None:
    model = FakeChat(fail="No puedo conectar con Ollama")
    with connect(make_client(model)) as ws:
        ws.send_json({"type": "hello", "token": TOKEN})
        ws.receive_json()
        ws.send_json({"type": "chat", "text": "hola"})
        events = receive_until(ws, "error")
        assert events[-1] == {"type": "error", "message": "No puedo conectar con Ollama"}
        assert ws.receive_json() == {"type": "state", "state": "error"}

        # Cuando el modelo vuelve, la conversación sigue sin reconectar.
        model.fail = None
        ws.send_json({"type": "chat", "text": "otra vez"})
        assert receive_until(ws, "done")[-1]["text"] == "Hola, ¿qué tal?"

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
        ws.send_json({"type": "hello", "token": TOKEN})
        assert ws.receive_json()["type"] == "ready"


def test_invalid_message_does_not_close_connection() -> None:
    with connect(make_client(FakeChat())) as ws:
        ws.send_json({"type": "hello", "token": TOKEN})
        ws.receive_json()
        ws.send_text("esto no es json")
        assert ws.receive_json() == {"type": "error", "message": "Mensaje no válido."}
        ws.send_json({"type": "chat", "text": "hola"})
        assert receive_until(ws, "done")[-1]["type"] == "done"
