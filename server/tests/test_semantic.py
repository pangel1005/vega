"""Entender por significado: decidir (con un «modelo» falso) y el flujo completo por WebSocket."""

from collections.abc import Sequence
from typing import Any

import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, IntentSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.skills.semantic import (
    SemanticIntents,
    blocks_actions,
    fix_volume_direction,
    volume_vote,
)
from server.tests.fakes import FakeChat
from server.tests.test_actions import FakeExecutor
from server.tools.actions import ActionDef, ActionRunner

TOKEN = "t" * 43
ACTIONS = [
    ActionDef(
        id="apagar", label="Apagar PC", kind="power", arg="shutdown", confirm=True, say=["apaga el pc"]
    ),
    ActionDef(id="volumen-mas", label="Volumen +", kind="keys", arg="volume_up", say=["sube el volumen"]),
    ActionDef(id="volumen-menos", label="Volumen -", kind="keys", arg="volume_down", say=["baja el volumen"]),
    ActionDef(id="silenciar", label="Silenciar", kind="keys", arg="volume_mute", say=["silencia"]),
]


class BagEmbedder:
    """Un «modelo» de mentira: una palabra = una dimensión (así lo parecido es lo que comparte palabras)."""

    def __init__(self) -> None:
        self.vocab: dict[str, int] = {}
        self.calls = 0

    def embed(self, texts: Sequence[str]) -> np.ndarray:
        self.calls += 1
        words = [t.lower().replace("¿", "").replace("?", "").split() for t in texts]
        for ws in words:
            for w in ws:
                self.vocab.setdefault(w, len(self.vocab))
        out = np.zeros((len(texts), 512), dtype=np.float32)
        for i, ws in enumerate(words):
            for w in ws:
                out[i, self.vocab[w]] += 1.0
        return out


def intents(**kw: Any) -> SemanticIntents:
    return SemanticIntents(lambda: ACTIONS, BagEmbedder, **kw)


def test_a_phrase_that_shares_the_meaning_activates_the_button() -> None:
    m = intents(act=0.5, ask=0.3).match("apaga el pc ahora")
    assert m is not None and (m.kind, m.id, m.mode) == ("action", "apagar", "act")


def test_in_between_it_asks_instead_of_acting() -> None:
    m = intents(act=0.99, ask=0.3).match("apaga eso")
    assert m is not None and (m.id, m.mode) == ("apagar", "ask")  # parecido, pero no tanto como para actuar


def test_too_different_goes_to_chat() -> None:
    assert intents(act=0.5, ask=0.4).match("cuéntame un cuento muy largo") is None
    assert intents().match("   ") is None


def test_chat_phrases_do_not_activate_buttons() -> None:
    assert intents(act=0.3, ask=0.2).match("gracias") is None  # «gracias» es del grupo de charla


def test_negations_and_info_questions_never_activate_a_button() -> None:
    assert blocks_actions("no quiero apagar nada todavia")
    assert blocks_actions("que es spotify")
    assert blocks_actions("por que se apaga el pc")
    assert blocks_actions("explicame como apagar")
    assert not blocks_actions("apagame la compu")
    assert not blocks_actions("reinicia el pc")  # «reinicia» lleva «ni»: no es una negación
    assert not blocks_actions("inicia steam")
    m = intents(act=0.1, ask=0.1).match("no quiero apagar el pc")
    assert m is None or m.kind != "action"


def test_volume_direction_is_decided_by_the_words() -> None:
    known = {"volumen-mas", "volumen-menos", "silenciar"}
    assert (
        fix_volume_direction("pon el sonido mas alto", "silenciar", known) == "silenciar"
    )  # no es de volumen
    assert fix_volume_direction("pon el sonido mas alto", "volumen-menos", known) == "volumen-mas"
    assert fix_volume_direction("pon la musica mas bajita", "volumen-mas", known) == "volumen-menos"
    assert fix_volume_direction("subele", "volumen-menos", known) == "volumen-mas"
    assert fix_volume_direction("bajale", "volumen-mas", known) == "volumen-menos"
    assert (
        fix_volume_direction("cambia el volumen", "volumen-mas", known) == "volumen-mas"
    )  # sin pista: se deja
    assert (
        fix_volume_direction("sube y baja", "volumen-mas", known) == "volumen-mas"
    )  # contradictorio: se deja
    assert (
        fix_volume_direction("sube", "volumen-menos", {"volumen-menos"}) == "volumen-menos"
    )  # no hay botón de subir


def test_words_that_ask_for_volume_win_even_if_the_model_saw_another_audio_button() -> None:
    known = {"volumen-mas", "volumen-menos", "silenciar"}
    assert volume_vote("subele a la musica", known) == "volumen-mas"
    assert volume_vote("pon la musica mas bajita", known) == "volumen-menos"
    assert volume_vote("pausa la musica", known) is None
    s = intents(act=0.3, ask=0.2)
    m = s.match("sube el sonido de la musica")  # «música» sola atraería a otros botones; «sube» manda
    assert m is not None and m.id == "volumen-mas"
    m = s.match("baja la musica")
    assert m is not None and m.id == "volumen-menos"
    assert s.match("no subas el volumen") is None  # una negación no activa nada


def test_skills_without_data_are_resolved_and_the_ones_with_data_are_left_to_their_rules() -> None:
    m = intents(act=0.3, ask=0.2).match("que tiempo hace hoy")
    assert m is not None and (m.kind, m.id, m.canonical) == ("skill", "weather", "qué tiempo hace")
    assert (
        intents(act=0.3, ask=0.2).match("pon un temporizador de cinco minutos") is None
    )  # necesita el tiempo


def test_skills_never_run_on_a_negation_or_when_unsure() -> None:
    assert intents(act=0.3, ask=0.2).match("duérmete") is not None  # claro: se entiende
    assert intents(act=0.3, ask=0.2).match("no te duermas todavía duérmete") is None  # niega: no se duerme
    assert intents(act=0.99, ask=0.2).match("duérmete ya") is None  # dudosa: una habilidad no pregunta


def test_if_the_model_cannot_load_everything_keeps_working_without_it() -> None:
    def broken() -> BagEmbedder:
        raise OSError("sin modelo y sin red")

    s = SemanticIntents(lambda: ACTIONS, broken)
    assert s.match("apaga el pc") is None  # no se cae
    assert s.match("apaga el pc") is None  # y no lo reintenta a cada frase


def test_the_index_follows_the_buttons_when_they_change() -> None:
    actions = list(ACTIONS)
    s = SemanticIntents(lambda: actions, BagEmbedder, act=0.5, ask=0.3)
    assert s.match("abre discord") is None
    actions.append(ActionDef(id="discord", label="Discord", kind="launch", arg="x", say=["abre discord"]))
    m = s.match("abre discord")
    assert m is not None and m.id == "discord"


# --- El flujo completo, por WebSocket ---


def session(texts: list[str], ex: FakeExecutor, **kw: Any) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    semantic = SemanticIntents(lambda: ACTIONS, BagEmbedder, **kw)
    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(pieces=["charla"]),
        store=ConversationStore(":memory:"),
        actions=ActionRunner(ACTIONS, ex),
        semantic=semantic,
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def until(ws: WebSocketTestSession, kind: str) -> list[dict[str, Any]]:
    events = []
    while True:
        events.append(ws.receive_json())
        if events[-1]["type"] == kind:
            return events


def phone(ws: WebSocketTestSession, text: str) -> dict[str, Any]:
    ws.send_json({"type": "phone_command", "text": text})
    return until(ws, "phone_reply")[-1]


def login(ws: WebSocketTestSession) -> None:
    ws.send_json({"type": "hello", "token": TOKEN})
    for _ in range(3):
        ws.receive_json()
    ws.send_json({"type": "brain", "where": "phone"})


def test_phone_command_by_meaning_runs_the_button() -> None:
    ex = FakeExecutor()
    with session([], ex, act=0.5, ask=0.3) as ws:
        login(ws)
        reply = phone(ws, "sube el sonido un poco")  # no es la frase exacta
    assert reply["handled"] is True
    assert ex.ran == ["volumen-mas"]


def test_phone_command_not_sure_asks_and_yes_runs_it() -> None:
    ex = FakeExecutor()
    with session([], ex, act=0.99, ask=0.3) as ws:
        login(ws)
        asked = phone(ws, "sube volumen")
        assert asked["handled"] is True and "Volumen +" in asked["text"] and "sí o no" in asked["text"]
        assert ex.ran == []  # preguntó, no hizo nada todavía
        done = phone(ws, "sí")
    assert done["handled"] is True
    assert ex.ran == ["volumen-mas"]


def test_phone_command_not_sure_asks_and_no_cancels() -> None:
    ex = FakeExecutor()
    with session([], ex, act=0.99, ask=0.3) as ws:
        login(ws)
        phone(ws, "sube volumen")
        reply = phone(ws, "no")
    assert reply == {"type": "phone_reply", "handled": True, "text": "Vale, no hago nada."}
    assert ex.ran == []


def test_a_dangerous_button_found_by_meaning_still_asks_for_confirmation() -> None:
    ex = FakeExecutor()
    with session([], ex, act=0.5, ask=0.3) as ws:
        login(ws)
        reply = phone(ws, "apaga ya el pc")
    assert reply["handled"] is True and "Di sí" in reply["text"]  # apagar pide confirmar siempre
    assert ex.ran == []


def test_an_unrelated_phrase_is_not_the_pcs_business() -> None:
    ex = FakeExecutor()
    with session([], ex, act=0.5, ask=0.4) as ws:
        login(ws)
        reply = phone(ws, "cuál es la capital de francia")
    assert reply["handled"] is False


def test_intent_settings_defaults_are_off_in_code_and_validated() -> None:
    assert IntentSettings().semantic is False
    with pytest.raises(ValueError):
        IntentSettings(act=1.5)
