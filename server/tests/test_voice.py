"""Voz por WebSocket: listen_start → audio binario → listen_stop → transcript → respuesta."""

from typing import Any

from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings, SpeechSettings
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat, FakeSTT

TOKEN = "t" * 43
SETTINGS = Settings(
    assistant=AssistantSettings(name="Prueba"),
    llm=LlmSettings(model="fake"),
    speech=SpeechSettings(max_seconds=1),
)
ONE_SECOND = b"\x01\x00" * 16_000


async def _probe() -> LlmStatus:
    return LlmStatus(True, "ok")


def session(stt: FakeSTT | None, model: FakeChat | None = None) -> Any:
    app = create_app(
        SETTINGS,
        token=TOKEN,
        llm_probe=_probe,
        chat_model=model or FakeChat(pieces=["Respuesta."]),
        store=ConversationStore(":memory:"),
        stt=stt,
    )
    client = TestClient(app, base_url="http://127.0.0.1:8000")
    return client.websocket_connect("ws://127.0.0.1:8000/ws")


def login(ws: WebSocketTestSession) -> None:
    ws.send_json({"type": "hello", "token": TOKEN})
    ws.receive_json()  # ready
    ws.receive_json()  # history


def until(ws: WebSocketTestSession, kind: str) -> list[dict[str, Any]]:
    events = []
    while True:
        events.append(ws.receive_json())
        if events[-1]["type"] == kind:
            return events


def test_spoken_question_is_transcribed_and_answered() -> None:
    stt = FakeSTT(text="¿Qué hora es?")
    model = FakeChat(pieces=["Son las cinco."])
    with session(stt, model) as ws:
        login(ws)
        ws.send_json({"type": "listen_start"})
        assert ws.receive_json() == {"type": "state", "state": "listening"}
        ws.send_bytes(b"\x01\x00" * 800)
        ws.send_bytes(b"\x02\x00" * 800)
        ws.send_json({"type": "listen_stop"})
        events = until(ws, "done")

    assert events[0] == {"type": "state", "state": "thinking"}
    assert events[1] == {
        "type": "transcript",
        "text": "¿Qué hora es?",
        "audio_seconds": 0.1,
        "elapsed_ms": 50,
    }
    assert events[-1] == {"type": "done", "text": "Son las cinco."}
    assert stt.received == [b"\x01\x00" * 800 + b"\x02\x00" * 800]
    assert model.calls[0][-1].content == "¿Qué hora es?"
    assert model.preloads == 1  # el modelo se empezó a cargar al pulsar «hablar»


def test_audio_is_capped_at_max_seconds() -> None:
    stt = FakeSTT()
    with session(stt) as ws:
        login(ws)
        ws.send_json({"type": "listen_start"})
        ws.receive_json()
        for _ in range(3):
            ws.send_bytes(ONE_SECOND)
        ws.send_json({"type": "listen_stop"})
        until(ws, "done")
    assert len(stt.received[0]) == len(ONE_SECOND)


def test_audio_outside_recording_is_ignored() -> None:
    stt = FakeSTT()
    with session(stt) as ws:
        login(ws)
        ws.send_bytes(ONE_SECOND)  # sin listen_start: se descarta
        ws.send_json({"type": "listen_start"})
        ws.receive_json()
        ws.send_bytes(b"\x05")  # tamaño impar: no es PCM16 válido
        ws.send_bytes(b"\x03\x00" * 10)
        ws.send_json({"type": "listen_stop"})
        until(ws, "done")
    assert stt.received == [b"\x03\x00" * 10]


def test_silence_returns_empty_transcript_without_calling_model() -> None:
    model = FakeChat()
    with session(FakeSTT(text=""), model) as ws:
        login(ws)
        ws.send_json({"type": "listen_start"})
        ws.receive_json()
        ws.send_bytes(b"\x00\x00" * 1600)
        ws.send_json({"type": "listen_stop"})
        events = until(ws, "transcript")
        assert events[-1]["text"] == ""
        assert ws.receive_json() == {"type": "state", "state": "idle"}
    assert model.calls == []


def test_cancel_discards_audio() -> None:
    stt = FakeSTT()
    with session(stt) as ws:
        login(ws)
        ws.send_json({"type": "listen_start"})
        ws.receive_json()
        ws.send_bytes(ONE_SECOND)
        ws.send_json({"type": "listen_cancel"})
        assert ws.receive_json() == {"type": "state", "state": "idle"}
        ws.send_json({"type": "listen_stop"})  # ya no hay grabación: no hace nada
        ws.send_json({"type": "chat", "text": "sigo aquí"})
        until(ws, "done")
    assert stt.received == []


def test_stt_failure_is_clear_error() -> None:
    with session(FakeSTT(fail="No pude cargar el reconocimiento de voz")) as ws:
        login(ws)
        ws.send_json({"type": "listen_start"})
        ws.receive_json()
        ws.send_bytes(ONE_SECOND)
        ws.send_json({"type": "listen_stop"})
        events = until(ws, "error")
        assert events[-1]["message"] == "No pude cargar el reconocimiento de voz"
        assert ws.receive_json() == {"type": "state", "state": "error"}


def test_without_stt_voice_is_reported_unavailable() -> None:
    with session(None) as ws:
        login(ws)
        ws.send_json({"type": "listen_start"})
        assert ws.receive_json() == {
            "type": "error",
            "message": "El reconocimiento de voz no está disponible.",
        }
