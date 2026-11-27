"""Vega por Telegram: a quién obedece, qué hace con lo que le escriben y cómo entrega los avisos (sin red)."""

from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core import telegram as tg
from server.core.config import AssistantSettings, LlmSettings, Settings, TelegramSettings
from server.core.hub import Hub
from server.core.telegram import (
    HELP,
    MAX_QUESTION,
    MAX_TEXT,
    TelegramApi,
    TelegramAuthError,
    TelegramBridge,
    TelegramError,
    TelegramListener,
    format_pending,
    load_config,
)
from server.memory.personal import PersonalMemory, Reminder
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat
from server.tests.test_hub import Client
from server.tools import telegram_setup

OWNER = 4242
NOW = 1_800_000_000.0


class FakeApi:
    """La API de Telegram de mentira: apunta lo que se envía y reparte las actualizaciones que se le den."""

    def __init__(self, batches: list[Any] | None = None, fail_sends: int = 0) -> None:
        self.sent: list[tuple[int, str]] = []
        self.buttons: list[Any] = []  # los botones de cada mensaje enviado (None si no tenía)
        self.edits: list[dict[str, Any]] = []
        self.answered: list[Any] = []
        self.actions: list[dict[str, Any]] = []
        self.polls: list[dict[str, Any]] = []
        self.batches = list(batches or [])
        self.fail_sends = fail_sends
        self.closed = False

    async def call(self, method: str, payload: dict[str, Any], timeout: float = 15.0) -> Any:
        if method == "sendMessage":
            if self.fail_sends > 0:
                self.fail_sends -= 1
                raise TelegramError(None, "ConnectError")
            self.sent.append((payload["chat_id"], payload["text"]))
            self.buttons.append(payload.get("reply_markup"))
            return {}
        if method == "editMessageText":
            self.edits.append(payload)
            return {}
        if method == "answerCallbackQuery":
            self.answered.append(payload["callback_query_id"])
            return True
        if method == "sendChatAction":
            self.actions.append(payload)
            return True
        if method == "getUpdates":
            self.polls.append(payload)
            if not self.batches:
                await asyncio.sleep(0.05)  # da tiempo a que se atiendan los mensajes ya recibidos
                raise asyncio.CancelledError
            batch = self.batches.pop(0)
            if isinstance(batch, Exception):
                raise batch
            return batch
        raise AssertionError(method)

    async def close(self) -> None:
        self.closed = True


def message(
    text: str,
    *,
    user: int = OWNER,
    chat: int | None = None,
    kind: str = "private",
    date: float = NOW,
    is_bot: bool = False,
    update_id: int = 1,
) -> dict[str, Any]:
    return {
        "update_id": update_id,
        "message": {
            "text": text,
            "date": date,
            "from": {"id": user, "is_bot": is_bot},
            "chat": {"id": user if chat is None else chat, "type": kind},
        },
    }


class Box:
    def __init__(self, reply: str = "Hecho.") -> None:
        self.reply = reply
        self.asked: list[str] = []
        self.error: Exception | None = None
        self.delay = 0.0

    async def answer(self, text: str) -> str:
        self.asked.append(text)
        if self.delay:
            await asyncio.sleep(self.delay)
        if self.error:
            raise self.error
        return self.reply


async def _text(value: str) -> str:
    return value


def make(
    api: FakeApi, box: Box | None = None, allowed: frozenset[int] = frozenset({OWNER})
) -> tuple[TelegramBridge, Box]:
    box = box or Box()
    sleeps: list[float] = []

    async def sleep(seconds: float) -> None:
        sleeps.append(seconds)

    bridge = TelegramBridge(
        api,  # type: ignore[arg-type]
        allowed,
        box.answer,
        lambda: _text("ESTADO"),
        lambda: _text("PENDIENTE"),
        clock=lambda: NOW + 5,
        sleep=sleep,
    )
    bridge.sleeps = sleeps  # type: ignore[attr-defined]
    return bridge, box


def run(coro: Any) -> Any:
    return asyncio.run(coro)


# --- A quién obedece ---


def test_the_owner_gets_an_answer() -> None:
    api = FakeApi()
    bridge, box = make(api)
    run(bridge.handle(message("recuérdame beber agua")))
    assert box.asked == ["recuérdame beber agua"]
    assert api.sent == [(OWNER, "Hecho.")]
    assert api.actions == [{"chat_id": OWNER, "action": "typing"}]  # «escribiendo…» mientras piensa


@pytest.mark.parametrize(
    "update",
    [
        message("hola", user=999),  # otra persona
        message("hola", user=OWNER, kind="group", chat=-100),  # el dueño, pero en un grupo
        message("hola", user=OWNER, chat=999),  # el chat no es el suyo
        message("hola", is_bot=True),  # un bot
        message("hola", date=NOW - 3600),  # mensaje viejo (el PC estaba apagado)
        {"update_id": 1, "edited_message": {"text": "x"}},
        {
            "update_id": 1,
            "message": {"photo": [], "chat": {"id": OWNER, "type": "private"}, "from": {"id": OWNER}},
        },
        message("   "),
    ],
)
def test_everything_else_is_ignored_without_a_reply(update: dict[str, Any]) -> None:
    api = FakeApi()
    bridge, box = make(api)
    run(bridge.handle(update))
    assert api.sent == [] and box.asked == []


def test_a_stranger_gets_no_reply_not_even_a_refusal() -> None:
    api = FakeApi()
    bridge, _ = make(api)
    run(bridge.handle(message("/start", user=777)))
    assert api.sent == [] and api.actions == []


# --- Órdenes ---


def test_commands_answer_without_asking_the_model() -> None:
    api = FakeApi()
    bridge, box = make(api)
    texts = ("/ayuda", "/start", "/help", "/estado", "/ESTADO@mi_vega_bot", "/recordatorios", "/reiniciar")
    for text in texts:
        run(bridge.handle(message(text)))
    assert [t for _, t in api.sent] == [
        HELP,
        HELP,
        HELP,
        "ESTADO",
        "ESTADO",
        "PENDIENTE",
        "No conozco ese comando. /ayuda te dice qué puedo hacer.",
    ]
    assert box.asked == []


def test_the_help_lists_the_day_and_power_commands() -> None:
    for command in ("/hoy", "/noche", "/apagar", "/cancelar"):
        assert command in HELP


def test_long_questions_and_long_answers_are_cut() -> None:
    api = FakeApi()
    bridge, box = make(api, Box("x" * 9000))
    run(bridge.handle(message("a" * 5000)))
    assert len(box.asked[0]) == MAX_QUESTION
    assert len(api.sent[0][1]) == MAX_TEXT


def test_an_answer_that_fails_gets_a_friendly_message() -> None:
    api = FakeApi()
    box = Box()
    box.error = RuntimeError("secreto interno")
    bridge, _ = make(api, box)
    run(bridge.handle(message("hola")))
    assert api.sent == [(OWNER, "No he podido contestar; mira los registros del PC.")]
    assert "secreto" not in api.sent[0][1]


def test_an_answer_that_takes_too_long_is_given_up(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(tg, "ANSWER_TIMEOUT", 0.05)
    api = FakeApi()
    box = Box()
    box.delay = 1.0
    bridge, _ = make(api, box)
    run(bridge.handle(message("hola")))
    assert "tardando demasiado" in api.sent[0][1]


def test_an_empty_answer_still_gets_a_reply() -> None:
    api = FakeApi()
    bridge, _ = make(api, Box(""))
    run(bridge.handle(message("hola")))
    assert api.sent[0][1]


def test_questions_are_answered_one_at_a_time() -> None:
    api = FakeApi()
    box = Box()
    box.delay = 0.05
    bridge, _ = make(api, box)
    order: list[str] = []
    original = box.answer

    async def tracked(text: str) -> str:
        order.append("empieza " + text)
        result = await original(text)
        order.append("acaba " + text)
        return result

    box.answer = tracked  # type: ignore[method-assign]
    bridge._answer = tracked  # noqa: SLF001

    async def both() -> None:
        await asyncio.gather(bridge.handle(message("uno")), bridge.handle(message("dos")))

    run(both())
    assert order == ["empieza uno", "acaba uno", "empieza dos", "acaba dos"]


# --- El bucle ---


def test_the_loop_handles_updates_and_remembers_the_offset() -> None:
    api = FakeApi([[message("hola", update_id=10)], [message("adiós", update_id=11)]])
    bridge, box = make(api)

    async def go() -> None:
        with pytest.raises(asyncio.CancelledError):
            await bridge.run()
        await asyncio.sleep(0)

    run(go())
    assert api.polls[0]["offset"] is None
    assert api.polls[1]["offset"] == 11 and api.polls[2]["offset"] == 12
    assert api.polls[0]["allowed_updates"] == ["message", "callback_query"]  # mensajes y botones
    assert api.closed


def test_a_rejected_token_switches_the_bridge_off_without_retrying() -> None:
    api = FakeApi([TelegramAuthError(401, "token rechazado")])
    bridge, _ = make(api)
    run(bridge.run())  # vuelve por sí solo, no se queda reintentando
    assert len(api.polls) == 1 and api.closed


def test_network_failures_back_off_and_recover() -> None:
    api = FakeApi([TelegramError(None, "ConnectError")] * 3 + [[message("hola")]])
    bridge, box = make(api)

    async def go() -> None:
        with pytest.raises(asyncio.CancelledError):
            await bridge.run()
        await asyncio.sleep(0)

    run(go())
    assert bridge.sleeps[:3] == [2.0, 5.0, 10.0]  # type: ignore[attr-defined]
    assert box.asked == ["hola"]


# --- Avisos ---


def test_notify_retries_and_reports_the_outcome() -> None:
    api = FakeApi(fail_sends=1)
    bridge, _ = make(api)
    assert run(bridge.notify("hola")) is True
    assert api.sent == [(OWNER, "hola")] and bridge.sleeps == [2.0]  # type: ignore[attr-defined]
    api2 = FakeApi(fail_sends=99)
    bridge2, _ = make(api2)
    assert run(bridge2.notify("hola")) is False


def reminder(kind: Any = "reminder", text: str = "sacar la ropa") -> Reminder:
    return Reminder(1, kind, text, datetime(2026, 11, 11, 10, 0))


@pytest.mark.parametrize(
    ("mode", "phone", "kind", "late", "expected"),
    [
        ("away", False, "reminder", False, "🔔 Te recuerdo: sacar la ropa."),
        ("away", False, "timer", False, "⏰ ¡Tiempo! Terminó el sacar la ropa."),
        (
            "away",
            False,
            "reminder",
            True,
            "🔔 Mientras no estabas conectado tenía que recordarte: sacar la ropa.",
        ),
        ("away", True, "reminder", False, None),  # el teléfono lo dice en voz alta
        ("always", True, "reminder", False, "🔔 Te recuerdo: sacar la ropa."),
    ],
)
def test_reminders_go_to_telegram_depending_on_the_phone(
    mode: str, phone: bool, kind: str, late: bool, expected: str | None
) -> None:
    api = FakeApi()
    bridge, _ = make(api)
    listener = TelegramListener(bridge, lambda: phone, mode)
    run(listener.announce(reminder(kind), late))
    assert api.sent == ([] if expected is None else [(OWNER, expected)])


def test_the_hub_delivers_a_reminder_to_telegram_when_no_phone_is_connected() -> None:
    now = datetime(2026, 11, 11, 10, 5)
    memory = PersonalMemory(ConversationStore(":memory:"))
    memory.add_reminder("reminder", "sacar la ropa", now - timedelta(minutes=2))
    hub = Hub(memory, now=lambda: now)
    api = FakeApi()
    bridge, _ = make(api)
    hub.add(TelegramListener(bridge, hub.has_phone, "away"), "telegram")
    assert run(hub.deliver_due()) == 1  # sin teléfono, antes no se entregaba nada
    assert api.sent == [(OWNER, "🔔 Mientras no estabas conectado tenía que recordarte: sacar la ropa.")]
    assert run(hub.deliver_due()) == 0  # y no se repite


def test_the_hub_leaves_it_to_the_phone_when_it_is_connected() -> None:
    now = datetime(2026, 11, 11, 10, 0, 30)
    memory = PersonalMemory(ConversationStore(":memory:"))
    memory.add_reminder("reminder", "sacar la ropa", now - timedelta(seconds=5))
    hub = Hub(memory, now=lambda: now)
    phone, api = Client(), FakeApi()
    bridge, _ = make(api)
    hub.add(phone, "phone")
    hub.add(TelegramListener(bridge, hub.has_phone, "away"), "telegram")
    assert hub.has_phone()
    assert run(hub.deliver_due()) == 1
    assert phone.heard == [("sacar la ropa", False)] and api.sent == []


# --- Textos ---


def test_pending_reminders_are_listed_by_time() -> None:
    now = datetime(2026, 11, 11, 9, 0)
    items = [
        Reminder(1, "reminder", "sacar la ropa", datetime(2026, 11, 11, 10, 0)),
        Reminder(2, "timer", "huevos", datetime(2026, 11, 11, 9, 10)),
        Reminder(3, "reminder", "llamar al banco", datetime(2026, 11, 13, 16, 30)),
    ]
    text = format_pending(items, now)
    assert (
        "🔔 10:00 — sacar la ropa" in text
        and "⏱️ 09:10 — huevos" in text
        and "13/11 16:30 — llamar al banco" in text
    )
    assert format_pending([], now) == "No tengo nada pendiente."


# --- Configuración y token ---


def test_config_needs_a_token_and_at_least_one_account(tmp_path: Path) -> None:
    path = tmp_path / "telegram.json"
    assert load_config(path) is None  # no existe
    path.write_text("no es json")
    assert load_config(path) is None
    path.write_text(json.dumps({"token": "T", "chat_ids": []}))
    assert load_config(path) is None  # sin cuentas autorizadas no arranca
    path.write_text(json.dumps({"token": "", "chat_ids": [1]}))
    assert load_config(path) is None
    path.write_text(json.dumps({"token": "123:ABC", "chat_ids": [1, "2"]}), encoding="utf-8")
    cfg = load_config(path)
    assert cfg is not None and cfg.token == "123:ABC" and cfg.chat_ids == frozenset({1, 2})


def api_with(handler: Any) -> TelegramApi:
    return TelegramApi("123:SECRETTOKEN", httpx.AsyncClient(transport=httpx.MockTransport(handler)))


def test_the_api_returns_the_result_and_posts_to_the_bot_address() -> None:
    seen: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(str(request.url))
        return httpx.Response(200, json={"ok": True, "result": [1, 2]})

    assert run(api_with(handler).call("getUpdates", {})) == [1, 2]
    assert seen == ["https://api.telegram.org/bot123:SECRETTOKEN/getUpdates"]


@pytest.mark.parametrize(
    ("response", "error"),
    [
        (httpx.Response(401, json={"ok": False}), TelegramAuthError),
        (httpx.Response(404, json={"ok": False}), TelegramAuthError),
        (httpx.Response(409, json={"ok": False}), TelegramError),
        (httpx.Response(200, json={"ok": False}), TelegramError),
        (httpx.Response(200, text="<html>"), TelegramError),
    ],
)
def test_api_errors_are_typed(response: httpx.Response, error: type[TelegramError]) -> None:
    with pytest.raises(error):
        run(api_with(lambda _r: response).call("getMe", {}))


def test_errors_never_carry_the_token(caplog: pytest.LogCaptureFixture) -> None:
    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError(
            f"no hay red para {request.url}"
        )  # httpx a veces pone la dirección en el mensaje

    with pytest.raises(TelegramError) as raised:
        run(api_with(boom).call("getMe", {}))
    assert "SECRETTOKEN" not in str(raised.value) and "SECRETTOKEN" not in repr(raised.value)
    assert raised.value.__cause__ is None  # tampoco por la cadena de excepciones
    bridge, _ = make(FakeApi(fail_sends=1))
    with caplog.at_level(logging.DEBUG):
        run(bridge.send(OWNER, "hola"))
    assert "SECRETTOKEN" not in caplog.text


# --- La preparación (telegram_setup) ---


def updates_for(*who: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {"update_id": 100 + i, "message": {"chat": {"id": w["id"], "type": "private"}, "from": w}}
        for i, w in enumerate(who)
    ]


def test_setup_picks_the_first_real_person() -> None:
    bot = {"id": 1, "is_bot": True}
    me = {"id": OWNER, "is_bot": False, "first_name": "Pedro"}
    found = telegram_setup.first_private_message(updates_for(bot, me))
    assert found is not None and found[0]["id"] == OWNER and found[1] == 101
    assert telegram_setup.first_private_message([]) is None
    group = [{"update_id": 1, "message": {"chat": {"id": -5, "type": "group"}, "from": me}}]
    assert telegram_setup.first_private_message(group) is None


def test_setup_writes_the_config_and_keeps_earlier_accounts(tmp_path: Path) -> None:
    path = tmp_path / "config" / "telegram.json"
    telegram_setup.write_config(path, "T1", 5)
    telegram_setup.write_config(path, "T2", 7)
    assert json.loads(path.read_text(encoding="utf-8")) == {"token": "T2", "chat_ids": [5, 7]}


def setup_client(who: dict[str, Any], calls: list[str]) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        method = request.url.path.rsplit("/", 1)[-1]
        calls.append(method)
        if method == "getMe":
            return httpx.Response(200, json={"ok": True, "result": {"username": "mi_vega_bot"}})
        return httpx.Response(200, json={"ok": True, "result": updates_for(who)})

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_setup_asks_before_authorising_anyone(tmp_path: Path) -> None:
    who = {"id": OWNER, "is_bot": False, "first_name": "Pedro", "username": "pedro"}
    said: list[str] = []
    calls: list[str] = []
    path = tmp_path / "telegram.json"
    assert not telegram_setup.run(setup_client(who, calls), "T", lambda _q: "n", said.append, path, wait=5)
    assert not path.exists() and any("Pedro" in line and str(OWNER) in line for line in said)
    assert telegram_setup.run(setup_client(who, calls), "T", lambda _q: "s", said.append, path, wait=5)
    assert json.loads(path.read_text(encoding="utf-8"))["chat_ids"] == [OWNER]
    assert calls[-1] == "getUpdates"  # marca el mensaje como leído
    assert not any(line == "T" for line in said)  # el token no se enseña


def test_setup_explains_a_bad_token() -> None:
    client = httpx.Client(transport=httpx.MockTransport(lambda _r: httpx.Response(401, json={"ok": False})))
    with pytest.raises(telegram_setup.SetupError, match="no vale"):
        telegram_setup.run(client, "malo", lambda _q: "s", lambda _l: None, Path("x"), wait=1)


# --- Dentro del servidor ---


def test_the_server_starts_the_bridge_and_registers_it_for_reminders() -> None:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    api = FakeApi()  # sin lotes: el primer sondeo se cancela
    bridge, _ = make(api)
    app = create_app(
        Settings(
            assistant=AssistantSettings(name="Vega"),
            llm=LlmSettings(model="fake"),
            telegram=TelegramSettings(enabled=True),
        ),
        token="t" * 43,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        telegram=bridge,
    )
    with TestClient(app, base_url="http://127.0.0.1:8000"):
        assert "telegram" in set(app.state.hub._roles.values())  # noqa: SLF001


def test_reminders_never_means_telegram_is_not_a_reminder_client() -> None:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    bridge, _ = make(FakeApi())
    app = create_app(
        Settings(
            assistant=AssistantSettings(name="Vega"),
            llm=LlmSettings(model="fake"),
            telegram=TelegramSettings(enabled=True, reminders="never"),
        ),
        token="t" * 43,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        telegram=bridge,
    )
    with TestClient(app, base_url="http://127.0.0.1:8000"):
        assert "telegram" not in set(app.state.hub._roles.values())  # noqa: SLF001


# --- Apagar la PC ---


class Power:
    def __init__(self, fail: str | None = None) -> None:
        self.calls: list[str] = []
        self.fail = fail

    def __call__(self, what: str) -> str:
        self.calls.append(what)
        if self.fail:
            raise tg.PowerError(self.fail)
        return "Apagando la PC en 60 s." if what == "shutdown" else "Apagado cancelado."


def with_power(
    api: FakeApi, power: Power, box: Box | None = None, game: str = "", clock: list[float] | None = None
) -> tuple[TelegramBridge, Box]:
    box = box or Box()
    now = clock if clock is not None else [NOW + 5]
    bridge = TelegramBridge(
        api,  # type: ignore[arg-type]
        frozenset({OWNER}),
        box.answer,
        lambda: _text("ESTADO"),
        lambda: _text("PENDIENTE"),
        clock=lambda: now[0],
        power=power,
        before_off=lambda: _text(game),
    )
    return bridge, box


def press(data: str, user: int = OWNER, chat: int = OWNER, update_id: int = 9) -> dict[str, Any]:
    return {
        "update_id": update_id,
        "callback_query": {
            "id": f"q{update_id}",
            "from": {"id": user, "is_bot": False},
            "message": {"message_id": 77, "chat": {"id": chat, "type": "private"}, "date": NOW},
            "data": data,
        },
    }


def offered_code(api: FakeApi) -> str:
    row = api.buttons[-1]["inline_keyboard"][0]
    assert [b["text"] for b in row] == ["🔌 Sí, apagar", "Cancelar"]
    return str(row[0]["callback_data"]).split(":", 1)[1]


def test_apagar_asks_with_buttons_and_warns_about_the_open_game() -> None:
    api, power = FakeApi(), Power()
    bridge, box = with_power(api, power, game="🎮 Tienes abierto Fortnite: se cerrará.")
    run(bridge.handle(message("/apagar")))
    text = api.sent[-1][1]
    assert text.startswith("⚠️ ¿Apago la PC?") and "Fortnite" in text and "60 s" in text
    offered_code(api)
    assert power.calls == [] and box.asked == []  # preguntar no apaga nada


def test_yes_shuts_down_and_leaves_a_cancel_button() -> None:
    api, power = FakeApi(), Power()
    bridge, _ = with_power(api, power)
    run(bridge.handle(message("apaga la pc")))
    code = offered_code(api)
    run(bridge.handle(press(f"off:{code}")))
    assert power.calls == ["shutdown"]
    edit = api.edits[-1]
    assert edit["message_id"] == 77 and edit["text"] == "🔌 Apagando la PC en 60 s."
    assert edit["reply_markup"]["inline_keyboard"][0][0]["callback_data"] == "abort"
    assert api.answered == ["q9"]  # el botón deja de girar en Telegram
    run(bridge.handle(press("abort", update_id=10)))
    assert power.calls == ["shutdown", "cancel"] and api.edits[-1]["text"] == "✋ Apagado cancelado."


def test_each_question_works_once_and_expires() -> None:
    api, power = FakeApi(), Power()
    clock = [NOW + 5]
    bridge, _ = with_power(api, power, clock=clock)
    run(bridge.handle(message("/apagar")))
    code = offered_code(api)
    run(bridge.handle(press(f"no:{code}")))
    assert api.edits[-1]["text"] == "Vale, no la apago."
    run(bridge.handle(press(f"off:{code}")))  # ya se contestó: no vale
    assert power.calls == [] and "ya no vale" in api.edits[-1]["text"]
    run(bridge.handle(message("/apagar", date=clock[0])))
    late = offered_code(api)
    clock[0] += tg.OFFER_SECONDS + 1
    run(bridge.handle(press(f"off:{late}")))
    assert power.calls == [] and "ya no vale" in api.edits[-1]["text"]
    run(bridge.handle(press("off:inventado")))
    assert power.calls == []


def test_a_stranger_cannot_press_the_buttons() -> None:
    api, power = FakeApi(), Power()
    bridge, _ = with_power(api, power)
    run(bridge.handle(message("/apagar")))
    code = offered_code(api)
    run(bridge.handle(press(f"off:{code}", user=999)))
    run(bridge.handle(press(f"off:{code}", chat=999)))
    run(bridge.handle(press("abort", user=999)))
    assert power.calls == [] and api.edits == [] and api.answered == []
    run(bridge.handle(press(f"off:{code}")))  # el código sigue valiendo para el dueño
    assert power.calls == ["shutdown"]


def test_cancel_by_text_or_command() -> None:
    api, power = FakeApi(), Power()
    bridge, box = with_power(api, power)
    run(bridge.handle(message("no apagues la pc")))
    run(bridge.handle(message("/cancelar")))
    assert power.calls == ["cancel", "cancel"] and box.asked == []
    assert api.sent[-1][1] == "✋ Apagado cancelado."


def test_windows_errors_are_told_plainly() -> None:
    api, power = FakeApi(), Power(fail="No había ningún apagado en marcha.")
    bridge, _ = with_power(api, power)
    run(bridge.handle(message("/cancelar")))
    assert api.sent[-1][1] == "No había ningún apagado en marcha."


def test_questions_about_shutting_down_still_go_to_vega() -> None:
    api, power = FakeApi(), Power()
    bridge, box = with_power(api, power)
    run(bridge.handle(message("¿cómo se apaga la pc en windows?")))
    assert box.asked == ["¿cómo se apaga la pc en windows?"] and power.calls == []


def test_without_power_it_says_it_is_not_enabled() -> None:
    api = FakeApi()
    bridge, box = make(api)
    run(bridge.handle(message("/apagar")))
    run(bridge.handle(message("apaga el ordenador")))
    assert [t for _, t in api.sent] == ["Apagar la PC desde Telegram no está activado."] * 2
    assert box.asked == []


def test_good_night_from_telegram_ends_with_the_power_buttons() -> None:
    api, power = FakeApi(), Power()
    asked: list[str] = []

    async def answer(text: str) -> tg.Reply:
        asked.append(text)
        return tg.Reply("Buenas noches. Mañana no tienes entregas ni recordatorios.", ask="apagar")

    bridge = TelegramBridge(
        api,  # type: ignore[arg-type]
        frozenset({OWNER}),
        answer,
        lambda: _text("ESTADO"),
        lambda: _text("PENDIENTE"),
        clock=lambda: NOW + 5,
        power=power,
    )
    run(bridge.handle(message("/noche")))
    assert asked == ["buenas noches"]
    text = api.sent[-1][1]
    assert text.startswith("Buenas noches. Mañana no tienes") and "⚠️ ¿Apago la PC?" in text
    offered_code(api)
    run(bridge.handle(message("/hoy")))
    assert asked[-1] == "resumen del dia"


class Done:
    def __init__(self, code: int) -> None:
        self.returncode = code


def test_windows_power_runs_shutdown_with_a_delay() -> None:
    calls: list[list[str]] = []

    def run_ok(cmd: list[str], **_: Any) -> Done:
        calls.append(cmd)
        return Done(0)

    assert tg.windows_power("shutdown", run=run_ok) == "Apagando la PC en 60 s."
    assert calls[-1][:4] == ["shutdown", "/s", "/t", "60"]
    assert tg.windows_power("cancel", run=run_ok) == "Apagado cancelado."
    assert calls[-1] == ["shutdown", "/a"]
    with pytest.raises(tg.PowerError):
        tg.windows_power("cancel", run=lambda cmd, **_: Done(1116))
    with pytest.raises(tg.PowerError):
        tg.windows_power("restart", run=run_ok)


def test_shutting_down_puts_the_phone_to_sleep_and_cancel_wakes_it() -> None:
    api, power = FakeApi(), Power()
    calls: list[bool] = []

    async def on_power(off: bool) -> None:
        calls.append(off)

    bridge = TelegramBridge(
        api,  # type: ignore[arg-type]
        frozenset({OWNER}),
        Box().answer,
        lambda: _text("ESTADO"),
        lambda: _text("PENDIENTE"),
        clock=lambda: NOW + 5,
        power=power,
        on_power=on_power,
    )
    run(bridge.handle(message("/apagar")))
    assert calls == []  # preguntar no duerme a nadie
    run(bridge.handle(press(f"off:{offered_code(api)}")))
    assert calls == [True]
    run(bridge.handle(message("/cancelar")))
    assert calls == [True, False]
