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


def test_conversation_continues_without_the_name_after_the_reply() -> None:
    """Tras responder (cuando el teléfono avisa de que terminó de sonar), sigue escuchando
    unos segundos sin «Vega»: se puede seguir la conversación con naturalidad."""
    model = FakeChat(pieces=["Hola."])
    with session(["Vega, hola", "¿y qué tal estás?"], model, FakeExecutor()) as ws:
        start(ws)
        say(ws)
        until(ws, "done")
        until(ws, "state")  # vuelve a reposo
        ws.send_json({"type": "playback_done"})
        assert until(ws, "state")[-1] == {"type": "state", "state": "listening"}
        say(ws)  # sin repetir el nombre
        until(ws, "done")
    assert [m.content for m in model.calls[1] if m.role == "user"][-1] == "¿y qué tal estás?"


def test_playback_done_without_spoken_question_does_nothing() -> None:
    model = FakeChat(pieces=["ok"])
    with session(["algo sin nombre"], model, FakeExecutor()) as ws:
        start(ws)
        ws.send_json({"type": "chat", "text": "hola"})  # escrito, no por voz
        until(ws, "done")
        ws.send_json({"type": "playback_done"})
        say(ws)  # sin «Vega»: se ignora
        ws.send_json({"type": "chat", "text": "otra"})
        events = until(ws, "done")
    assert all(e.get("state") != "listening" for e in events)
    assert all(e["type"] != "transcript" for e in events)


def test_wake_mode_off_ignores_audio() -> None:
    model = FakeChat(pieces=["ok"])
    with session(["Vega, ¿qué hora es?"], model, FakeExecutor()) as ws:
        start(ws)
        ws.send_json({"type": "wake_mode", "on": False})
        say(ws)
        ws.send_json({"type": "chat", "text": "hola"})
        events = until(ws, "done")
    assert all(e["type"] != "transcript" for e in events)


def test_brain_on_phone_frees_the_gpu_and_stops_listening() -> None:
    """Con el cerebro en el teléfono, el PC saca el modelo de la gráfica y deja de atender
    la voz; al volver al PC, lo carga otra vez."""
    model = FakeChat(pieces=["ok"])
    with session(["Vega, ¿qué hora es?"], model, FakeExecutor()) as ws:
        start(ws)
        ws.send_json({"type": "brain", "where": "phone"})
        say(ws)
        ws.send_json({"type": "chat", "text": "hola"})
        events = until(ws, "done")
        assert all(e["type"] != "transcript" for e in events)
        assert model.unloads == 1
        preloads = model.preloads
        ws.send_json({"type": "brain", "where": "pc"})
        ws.send_json({"type": "chat", "text": "otra"})
        until(ws, "done")
    assert model.preloads == preloads + 1


def phone(ws: WebSocketTestSession, text: str) -> dict[str, Any]:
    ws.send_json({"type": "phone_command", "text": text})
    return until(ws, "phone_reply")[-1]


def test_phone_brain_uses_pc_buttons_and_skills_but_never_the_model() -> None:
    """Con la IA en el teléfono, el PC resuelve sus botones y habilidades; lo demás no."""
    model, ex = FakeChat(), FakeExecutor()
    with session([], model, ex) as ws:
        start(ws)
        ws.send_json({"type": "brain", "where": "phone"})
        assert phone(ws, "sube el volumen") == {
            "type": "phone_reply",
            "handled": True,
            "text": "Volumen +: hecho.",
        }
        assert phone(ws, "apunta comprar pan")["text"] == "Apuntado: comprar pan."
        assert phone(ws, "¿cuál es la capital de Francia?") == {
            "type": "phone_reply",
            "handled": False,
            "text": "",
        }
    assert ex.ran == ["volumen-mas"] and model.calls == []


def test_phone_brain_confirms_sensitive_buttons_by_voice() -> None:
    ex = FakeExecutor()
    with session([], FakeChat(), ex) as ws:
        start(ws)
        ws.send_json({"type": "brain", "where": "phone"})
        assert "Di sí" in phone(ws, "apaga el PC")["text"]
        assert ex.ran == []
        assert phone(ws, "sí")["handled"] is True
    assert ex.ran == ["apagar"]


def test_memory_made_on_the_phone_joins_the_pc() -> None:
    with session([], FakeChat(), FakeExecutor()) as ws:
        start(ws)
        ws.send_json(
            {
                "type": "memory_import",
                "facts": ["mi perro se llama Toby"],
                "notes": ["comprar leche"],
                "reminders": [{"kind": "reminder", "text": "llamar a mamá", "due_at": "2099-01-01T10:00:00"}],
            }
        )
        assert phone(ws, "lee mis notas")["text"] == "Tienes 1 nota: comprar leche."
        assert "Toby" in phone(ws, "qué sabes de mí")["text"]
        assert "llamar a mamá" in phone(ws, "qué recordatorios tengo")["text"]


def test_without_pc_voice_the_phone_speaks_itself() -> None:
    with session([], FakeChat(), FakeExecutor()) as ws:  # sin voz en el PC
        start(ws)
        ws.send_json({"type": "say", "text": "Hola."})
        assert until(ws, "audio_end")[-1] == {"type": "audio_end"}


def test_sleep_button_stops_listening_and_frees_the_gpu() -> None:
    model = FakeChat(pieces=["ok"])
    with session(["Vega, ¿qué hora es?"], model, FakeExecutor()) as ws:
        start(ws)
        ws.send_json({"type": "sleep", "on": True})
        say(ws)  # dormida: no atiende
        ws.send_json({"type": "chat", "text": "hola"})
        events = until(ws, "done")
        assert all(e["type"] != "transcript" for e in events)
        assert model.unloads == 1
        preloads = model.preloads
        ws.send_json({"type": "sleep", "on": False})
        ws.send_json({"type": "chat", "text": "otra"})
        until(ws, "done")
    assert model.preloads == preloads + 1  # al despertar, el modelo vuelve a la memoria


def test_saying_goodnight_tells_the_phone_to_sleep() -> None:
    with session(["Vega, duérmete"], FakeChat(), FakeExecutor()) as ws:
        start(ws)
        say(ws)
        events = until(ws, "sleep")
    assert events[-1] == {"type": "sleep"}
