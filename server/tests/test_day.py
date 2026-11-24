"""El día con Vega: «buenos días» con la llama y «buenas noches» (lo de mañana, la música y apagar la PC)."""

from __future__ import annotations

import asyncio
from datetime import datetime, timedelta
from typing import Any

from server.core.orchestrator import Orchestrator
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.moodle import Task, TaskBook
from server.skills.router import SkillRouter
from server.skills.streak import GymLog, compute, morning, night
from server.tests.fakes import FakeChat
from server.tests.test_actions import FakeExecutor
from server.tools.actions import ActionDef, ActionRunner

NIGHT = datetime(2026, 11, 12, 23, 10)  # jueves por la noche
MORNING = datetime(2026, 11, 13, 7, 45)


def world(now: datetime) -> tuple[SkillRouter, PersonalMemory, TaskBook, GymLog]:
    store = ConversationStore(":memory:")
    memory = PersonalMemory(store)
    book = TaskBook(store, memory, now=lambda: now)
    gym = GymLog(store, now=lambda: now)
    router = SkillRouter(memory, now=lambda: now)
    router.attach_moodle(book)
    router.attach_gym(gym)
    return router, memory, book, gym


# --- La llama, en una frase ---


def test_the_flame_in_the_morning() -> None:
    today = MORNING.date()
    assert morning(compute([], today)) is None  # nunca encendida: no se dice nada
    assert morning(compute([today], today)) == "La llama ya está encendida hoy: 1 día de racha."
    yesterday = [today - timedelta(days=2), today - timedelta(days=1)]
    assert morning(compute(yesterday, today)) == "Tu llama del gimnasio lleva 2 días."
    last_chance = [today - timedelta(days=4), today - timedelta(days=3)]
    expected = "Hoy toca gimnasio: si no vas, se apaga tu llama de 2 días."
    assert morning(compute(last_chance, today)) == expected
    assert "apagada" in (morning(compute([today - timedelta(days=9)], today)) or "")


def test_the_flame_at_night() -> None:
    today = NIGHT.date()
    assert night(compute([today], today)) == "Hoy encendiste la llama: 1 día de racha."
    assert night(compute([today - timedelta(days=1)], today)) == (
        "Hoy descansaste del gimnasio; tu llama de 1 día sigue viva."
    )
    expected = "Mañana toca gimnasio o se apaga tu llama de 1 día."
    assert night(compute([today - timedelta(days=2)], today)) == expected
    assert "se apaga" in (night(compute([today - timedelta(days=3)], today)) or "")
    assert "apagada" in (night(compute([today - timedelta(days=8)], today)) or "")


# --- Buenos días ---


def test_good_morning_includes_the_flame_and_the_deliveries() -> None:
    router, _, book, gym = world(MORNING)
    gym.light(MORNING.date() - timedelta(days=1))
    book.sync([Task("1", "Ensayo final", "Cálculo", MORNING + timedelta(hours=14))])
    reply = router.handle("buenos días Vega")
    assert reply is not None
    assert reply.text.startswith("Buenos días. Hoy es viernes 13 de noviembre.")
    assert "Entregas próximas: «Ensayo final» de Cálculo, hoy a las 21:45." in reply.text
    assert "Tu llama del gimnasio lleva 1 día." in reply.text


# --- Buenas noches ---


def test_good_night_tells_what_comes_tomorrow_and_asks_to_turn_off_the_pc() -> None:
    router, memory, book, gym = world(NIGHT)
    gym.light(NIGHT.date())
    memory.add_reminder("reminder", "llevar el carnet", NIGHT.replace(hour=8, minute=0) + timedelta(days=1))
    memory.add_reminder("reminder", "dentista", NIGHT + timedelta(days=3))  # no es de mañana
    book.sync(
        [
            Task("1", "Informe", "Física", NIGHT.replace(hour=23, minute=59) + timedelta(days=1)),
            Task("2", "Proyecto", "Redes", NIGHT + timedelta(days=5)),  # no es de mañana
        ]
    )
    paused: list[bool] = []

    def pause() -> bool:
        paused.append(True)
        return True

    router.attach_music(pause)
    reply = router.handle("Buenas noches, Vega")
    assert reply is not None and reply.sleep and reply.ask == ("apagar", "¿Apago la PC?")
    assert reply.text == (
        "Buenas noches. He pausado la música. Te queda: llevar el carnet mañana a las 08:00. "
        "Entrega para mañana: «Informe», mañana a las 23:59. Hoy encendiste la llama: 1 día de racha."
    )
    assert paused == [True]


def test_good_night_with_nothing_to_do() -> None:
    router, *_ = world(NIGHT)
    router.attach_music(lambda: False)  # no sonaba nada
    reply = router.handle("me voy a dormir")
    assert reply is not None
    assert reply.text == "Buenas noches. Mañana no tienes entregas ni recordatorios."


def test_putting_vega_to_sleep_is_still_just_sleep() -> None:
    router, *_ = world(NIGHT)
    reply = router.handle("vete a dormir")
    assert reply is not None and reply.sleep and reply.ask is None


# --- La pregunta «¿Apago la PC?» en el PC ---


def power_button() -> ActionDef:
    return ActionDef(id="apagar", label="Apagar PC", kind="power", arg="shutdown", confirm=True)


def orchestrator(router: SkillRouter, ex: FakeExecutor) -> tuple[Orchestrator, list[Any]]:
    events: list[Any] = []

    async def emit(event: Any) -> None:
        events.append(event)

    runner = ActionRunner([power_button()], ex)
    orch = Orchestrator(
        FakeChat(pieces=["x"]),
        "Eres Vega.",
        ConversationStore(":memory:"),
        emit,
        actions=runner,
        skills=router,
    )
    return orch, events


def said(events: list[Any]) -> list[str]:
    return [e.text for e in events if type(e).__name__ == "DoneEvent"]


def slept(events: list[Any]) -> bool:
    return any(type(e).__name__ == "SleepEvent" for e in events)


def test_good_night_asks_and_no_puts_the_phone_to_sleep() -> None:
    router, *_ = world(NIGHT)
    ex = FakeExecutor()
    orch, events = orchestrator(router, ex)

    async def go() -> None:
        await orch.handle_command("buenas noches", spoken=True)
        assert said(events)[-1].endswith("¿Apago la PC? Di sí o no.")
        assert not slept(events)  # espera la respuesta antes de dormirse
        await orch.handle_command("no", spoken=True)

    asyncio.run(go())
    assert said(events)[-1] == "Vale. Que descanses."
    assert slept(events) and ex.ran == []


def test_good_night_yes_still_asks_to_confirm_the_shutdown() -> None:
    router, *_ = world(NIGHT)
    ex = FakeExecutor()
    orch, events = orchestrator(router, ex)

    async def go() -> None:
        await orch.handle_command("buenas noches", spoken=True)
        await orch.handle_command("sí", spoken=True)
        assert ex.ran == []  # apagar pide siempre confirmación
        assert any(type(e).__name__ == "ConfirmEvent" for e in events)

    asyncio.run(go())


def test_without_the_power_button_good_night_just_sleeps() -> None:
    router, *_ = world(NIGHT)
    events: list[Any] = []

    async def emit(event: Any) -> None:
        events.append(event)

    store = ConversationStore(":memory:")
    orch = Orchestrator(FakeChat(pieces=["x"]), "Eres Vega.", store, emit, skills=router)
    asyncio.run(orch.handle_command("buenas noches", spoken=True))
    assert "Apago" not in said(events)[-1] and slept(events)
    assert orch.last_ask == ("apagar", "¿Apago la PC?")  # Telegram pone sus botones con esto


def test_good_night_on_the_phone_brain_sleeps_after_no() -> None:
    """IA en el teléfono: el «no» a «¿Apago la PC?» contesta «Que descanses» y pide dormir al teléfono."""
    router, *_ = world(NIGHT)
    ex = FakeExecutor()
    orch, events = orchestrator(router, ex)

    async def go() -> None:
        await orch.phone_command("buenas noches")
        await orch.phone_command("no")

    asyncio.run(go())
    replies = [e for e in events if type(e).__name__ == "PhoneReplyEvent"]
    assert replies[0].text.endswith("¿Apago la PC? Di sí o no.") and not replies[0].sleep
    assert replies[1].text == "Vale. Que descanses." and replies[1].sleep
    assert ex.ran == []
