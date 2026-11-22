"""Modo mixto: con la IA en el teléfono, la charla libre sube al modelo grande del PC mientras no se juega."""

from typing import Any

from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.core.placement import PlacementState
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat
from server.tests.test_wake import until

TOKEN = "t" * 43


def session(model: FakeChat, state: PlacementState | None, store: ConversationStore | None = None) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=model,
        store=store or ConversationStore(":memory:"),
        placement=state,
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def start(ws: WebSocketTestSession, role: str = "phone") -> None:
    ws.send_json({"type": "hello", "token": TOKEN, "role": role})
    for _ in range(2):  # ready, history (esta sesión no tiene botones)
        ws.receive_json()


def chat(ws: WebSocketTestSession, text: str, history: list[dict[str, str]] | None = None) -> dict[str, Any]:
    ws.send_json({"type": "phone_chat", "text": text, "history": history or []})
    return until(ws, "phone_chat_reply")[-1]


def ready_state() -> PlacementState:
    state = PlacementState(hybrid=True)
    state.mode = "free"
    state.phone_brain = True
    state.ready = True
    return state


def test_pc_answers_the_free_chat_when_it_is_free() -> None:
    model = FakeChat(pieces=["Paris ", "es la capital."])
    with session(model, ready_state()) as ws:
        start(ws)
        assert chat(ws, "¿capital de Francia?") == {
            "type": "phone_chat_reply",
            "handled": True,
            "text": "Paris es la capital.",
        }
    sent = model.calls[0]
    assert sent[-1].content == "¿capital de Francia?"


def test_the_previous_exchanges_reach_the_model() -> None:
    model = FakeChat(pieces=["Roma."])
    history = [
        {"role": "user", "content": "¿capital de Francia?"},
        {"role": "assistant", "content": "París."},
    ]
    with session(model, ready_state()) as ws:
        start(ws)
        chat(ws, "¿y la de Italia?", history)
    texts = [m.content for m in model.calls[0]]
    assert "¿capital de Francia?" in texts and "París." in texts and texts[-1] == "¿y la de Italia?"


def test_it_does_not_save_anything() -> None:
    store = ConversationStore(":memory:")
    with session(FakeChat(pieces=["Vale."]), ready_state(), store) as ws:
        start(ws)
        chat(ws, "dime un secreto")
    assert store.recent(store.latest_conversation(), 10) == []


def test_with_a_game_open_the_phone_answers_by_itself() -> None:
    state = ready_state()
    state.ready = False  # el vigilante la sacó de la gráfica
    model = FakeChat()
    with session(model, state) as ws:
        start(ws)
        assert chat(ws, "hola") == {"type": "phone_chat_reply", "handled": False, "text": ""}
    assert model.calls == []


def test_without_hybrid_the_pc_never_answers() -> None:
    with session(FakeChat(), PlacementState(hybrid=False)) as ws:
        start(ws)
        assert chat(ws, "hola")["handled"] is False


def test_a_model_error_falls_back_to_the_phone() -> None:
    with session(FakeChat(fail="Ollama no responde"), ready_state()) as ws:
        start(ws)
        assert chat(ws, "hola")["handled"] is False


def test_with_the_phone_brain_and_a_free_gpu_the_model_stays_loaded() -> None:
    state = PlacementState(hybrid=True)
    state.mode = "free"
    model = FakeChat(pieces=["Hola."])
    with session(model, state) as ws:
        start(ws)
        ws.send_json({"type": "brain", "where": "phone"})
        reply = {"handled": False}
        for _ in range(50):  # el modelo se va cargando de fondo; en cuanto está listo contesta
            reply = chat(ws, "hola")
            if reply["handled"]:
                break
    assert reply["handled"] is True and model.unloads == 0 and model.preloads >= 1


def test_with_the_phone_brain_and_a_game_the_model_is_unloaded() -> None:
    state = PlacementState(hybrid=True)
    state.mode = "game"
    model = FakeChat()
    with session(model, state) as ws:
        start(ws)
        ws.send_json({"type": "brain", "where": "phone"})
        assert chat(ws, "hola")["handled"] is False
    assert model.unloads == 1
