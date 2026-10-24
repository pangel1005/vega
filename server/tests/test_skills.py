from datetime import datetime, timedelta

import pytest

from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter

NOW = datetime(2026, 10, 22, 15, 0, 0)


@pytest.fixture
def router() -> SkillRouter:
    return SkillRouter(
        PersonalMemory(ConversationStore(":memory:")), now=lambda: NOW, phone_battery=lambda: 91
    )


def say(r: SkillRouter, text: str) -> str:
    reply = r.handle(text)
    assert reply is not None, f"ninguna habilidad reconoció: {text!r}"
    return reply.text


def test_memory_cycle(router: SkillRouter) -> None:
    assert "Todavía no sé nada" in say(router, "¿Qué sabes de mí?")
    assert (
        say(router, "Recuerda que me gusta el café sin azúcar.")
        == "Entendido, lo recordaré: te gusta el café sin azúcar."
    )
    say(router, "acuérdate de que mi hermana se llama Lucía")
    known = say(router, "Qué recuerdas sobre mí")
    assert "café sin azúcar" in known and "Lucía" in known
    assert "café" in say(router, "Olvida que me gusta el café")
    assert "café" not in say(router, "¿qué sabes de mí?")
    assert say(router, "olvida lo de mi coche") == "No tenía guardado nada sobre eso."


def test_facts_are_split_and_answered_directly(router: SkillRouter) -> None:
    reply = say(router, "Recuerda que me gusta el café sin azúcar y que mi perro se llama Toby")
    assert reply == "Entendido, lo recordaré: te gusta el café sin azúcar y tu perro se llama Toby."
    assert say(router, "¿Cómo se llama mi perro?") == "Tu perro se llama Toby."
    assert say(router, "¿Cómo me gusta el café?") == "Te gusta el café sin azúcar."
    say(router, "Olvida que me gusta el café")
    assert say(router, "¿Cómo se llama mi perro?") == "Tu perro se llama Toby."  # el perro sigue
    # Preguntas sobre el usuario sin dato guardado: al modelo.
    assert router.handle("¿Cuál es mi color favorito?") is None
    # Frases que no son preguntas no se responden desde la memoria.
    assert router.handle("Mi perro está enfermo") is None


def test_reminders(router: SkillRouter) -> None:
    assert (
        say(router, "Recuérdame llamar a mamá en 20 minutos.")
        == "Vale, en 20 minutos te recuerdo: llamar a mamá."
    )
    assert (
        say(router, "Recuérdame en 10 minutos que saque la ropa")
        == "Vale, en 10 minutos te recuerdo: saque la ropa."
    )
    assert (
        say(router, "Avísame a las 6 de sacar la basura") == "Vale, a las 18:00 te recuerdo: sacar la basura."
    )
    assert "¿Cuándo" in say(router, "recuérdame comprar pan")
    pending = say(router, "¿Qué recordatorios tengo?")
    assert "llamar a mamá en 20 minutos" in pending and "sacar la basura a las 18:00" in pending
    assert say(router, "Cancela los recordatorios") == "Cancelados 3 recordatorios."
    assert say(router, "qué recordatorios tengo") == "No tienes recordatorios pendientes."


def test_reminders_become_due() -> None:
    mem = PersonalMemory(ConversationStore(":memory:"))
    r = SkillRouter(mem, now=lambda: NOW)
    r.handle("recuérdame estirar en 5 minutos")
    assert mem.due(NOW) == []
    due = mem.due(NOW + timedelta(minutes=5))
    assert [x.text for x in due] == ["estirar"]
    mem.mark_delivered(due[0].id)
    assert mem.due(NOW + timedelta(hours=1)) == []


def test_timer(router: SkillRouter) -> None:
    assert say(router, "Pon un temporizador de 5 minutos.") == "Temporizador de 5 minutos en marcha."
    assert say(router, "temporizador de media hora") == "Temporizador de media hora en marcha."
    assert "¿De cuánto" in say(router, "pon un temporizador")
    assert say(router, "Cancela el temporizador") == "Temporizador cancelado."
    assert say(router, "cancela el temporizador") == "No había ningún temporizador en marcha."


def test_notes(router: SkillRouter) -> None:
    assert say(router, "Apunta comprar leche.") == "Apuntado: comprar leche."
    say(router, "anota: llamar al dentista")
    assert say(router, "Lee mis notas") == "Tienes 2 notas: comprar leche; llamar al dentista."
    assert say(router, "Borra mis notas") == "Borradas 2 notas."
    say(router, "apunta algo")
    assert say(router, "borra mis notas") == "Borrada 1 nota."
    assert say(router, "qué notas tengo") == "No tienes notas."


def test_status(router: SkillRouter) -> None:
    text = say(router, "¿Cómo está el PC?")
    assert text.startswith(("El PC va bien", "Ojo, el PC va caliente"))
    assert "procesador al" in text and "91 % de batería" in text


@pytest.mark.parametrize(
    "text",
    ["¿Cuál es la capital de Francia?", "Cuéntame un chiste", "¿Qué hora es?", "Recuerdo cuando era niño"],
)
def test_other_phrases_go_to_the_model(router: SkillRouter, text: str) -> None:
    assert router.handle(text) is None


def test_facts_reach_the_model_prompt() -> None:
    mem = PersonalMemory(ConversationStore(":memory:"))
    assert mem.prompt_context() == ""
    mem.remember("se llama Pablo")
    assert mem.prompt_context() == "Lo que sabes del usuario (háblale de tú): se llama Pablo."
    mem.remember("me gusta el café y mi perro se llama Toby")
    assert "te gusta el café y tu perro se llama Toby" in mem.prompt_context()


def test_timer_time_left(router: SkillRouter) -> None:
    assert say(router, "¿Cuánto le queda al temporizador?") == "No hay ningún temporizador en marcha."
    say(router, "pon un temporizador de 2 minutos")
    assert say(router, "¿Cuánto falta del temporizador?") == "Le queda 2 minutos."


def test_pc_notifications_by_voice() -> None:
    from server.device.notifications import PcNotification

    at = NOW.timestamp()
    notes = [
        PcNotification(3, "WhatsApp", "Ana", "¿Vienes a cenar?", at - 120, ""),
        PcNotification(2, "Discord", "#general", "Luis: ¿partida?", at - 600, ""),
        PcNotification(1, "Outlook", "Outlook", "Factura de octubre", at - 7200, ""),
    ]
    router = SkillRouter(
        PersonalMemory(ConversationStore(":memory:")), now=lambda: NOW, notifications=lambda: notes
    )
    assert say(router, "¿Qué notificaciones tengo?") == (
        "Tienes 3 notificaciones: WhatsApp de Ana, Discord de #general y Outlook."
    )
    assert say(router, "Léeme el último mensaje") == "Ana, por WhatsApp, hace 2 minutos: ¿Vienes a cenar?"
    assert (
        say(router, "léeme el último mensaje de Discord")
        == "#general, por Discord, hace 10 minutos: Luis: ¿partida?"
    )
    assert say(router, "lee la última notificación de Outlook") == "Outlook, hace 2 horas: Factura de octubre"
    assert say(router, "Lee mis notas") == "No tienes notas."  # las notas siguen siendo las notas
    empty = SkillRouter(PersonalMemory(ConversationStore(":memory:")), now=lambda: NOW)
    assert say(empty, "¿Tengo notificaciones?") == "No tienes notificaciones en el PC."
