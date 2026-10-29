"""El teléfono manda una frase ya cortada y el PC la transcribe (más rápido que el teléfono)."""

import base64
from typing import Any

from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat, FakeSTT

TOKEN = "t" * 43
PCM = b"\x01\x00" * 16_000  # 1 s


def session(stt: FakeSTT | None) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        stt=stt,
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def hear(ws: WebSocketTestSession, id: int, audio: str) -> dict[str, Any]:
    ws.send_json({"type": "hear", "id": id, "audio": audio})
    event: dict[str, Any] = ws.receive_json()
    while event["type"] != "heard":
        event = ws.receive_json()
    return event


def start(ws: WebSocketTestSession) -> None:
    ws.send_json({"type": "hello", "token": TOKEN})
    while ws.receive_json()["type"] != "history":  # ready, (actions, voices…), history
        pass


def test_the_pc_transcribes_a_phrase_from_the_phone() -> None:
    with session(FakeSTT(texts=["vega abre discord"])) as ws:
        start(ws)
        assert hear(ws, 7, base64.b64encode(PCM).decode()) == {
            "type": "heard",
            "id": 7,
            "ok": True,
            "text": "vega abre discord",
        }


def test_bad_audio_is_refused_so_the_phone_uses_its_own_ears() -> None:
    with session(FakeSTT(texts=["no debe oírse"])) as ws:
        start(ws)
        assert hear(ws, 1, "esto no es base64!!")["ok"] is False
        assert (
            hear(ws, 2, base64.b64encode(b"\x01\x00" * 16_000 * 31).decode())["ok"] is False
        )  # pasa de max_seconds
        assert hear(ws, 3, base64.b64encode(PCM).decode())["text"] == "no debe oírse"  # y la conexión sigue


def test_without_speech_to_text_the_answer_is_not_ok() -> None:
    with session(None) as ws:
        start(ws)
        assert hear(ws, 5, base64.b64encode(PCM).decode()) == {
            "type": "heard",
            "id": 5,
            "ok": False,
            "text": "",
        }
