"""El resumen del día: lo que Vega ya sabe, dicho de una vez."""

from datetime import datetime, timedelta
from typing import Any

from server.device.notifications import PcNotification
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.tests.test_weather import NOW, forecast

# Miércoles 28 de octubre de 2026, las 8:00.
MORNING = datetime(2026, 10, 28, 8, 0)


def note(i: int) -> PcNotification:
    return PcNotification(id=i, app="WhatsApp", title="Ana", body="hola", at=NOW, icon="")


def router(mem: PersonalMemory, **kw: Any) -> SkillRouter:
    return SkillRouter(mem, now=lambda: MORNING, **kw)


def fresh() -> PersonalMemory:
    return PersonalMemory(ConversationStore(":memory:"))


def test_dia_vacio() -> None:
    reply = router(fresh()).handle("Vega, dame el resumen del día")
    assert reply and reply.text == "Hoy es miércoles 28 de octubre. No tienes recordatorios para hoy."


def test_buenos_dias_saluda_y_resume() -> None:
    reply = router(fresh()).handle("buenos días Vega")
    assert reply and reply.text.startswith("Buenos días. Hoy es miércoles 28 de octubre.")


def test_recordatorios_de_hoy_en_orden_y_no_los_de_otro_dia() -> None:
    mem = fresh()
    mem.add_reminder("reminder", "llamar al médico", MORNING.replace(hour=18))
    mem.add_reminder("reminder", "comprar pan", MORNING.replace(hour=9, minute=30))
    mem.add_reminder("reminder", "dentista", MORNING + timedelta(days=1))  # mañana: no sale
    mem.add_reminder(
        "timer", "temporizador de 5 minutos", MORNING + timedelta(minutes=5)
    )  # un temporizador no es un recordatorio
    reply = router(mem).handle("¿qué tengo hoy?")
    assert reply
    assert reply.text.index("comprar pan") < reply.text.index("llamar al médico")
    assert "dentista" not in reply.text and "temporizador" not in reply.text


def test_incluye_el_tiempo_las_notas_y_los_avisos() -> None:
    mem = fresh()
    mem.add_note("cambiar el aceite")
    mem.add_note("comprar leche")
    r = router(
        mem, weather=lambda: forecast(), weather_ready=True, notifications=lambda: [note(1), note(2), note(3)]
    )
    reply = r.handle("resumen de hoy")
    assert reply
    assert "El tiempo de hoy: casi despejado, máxima de 24 y mínima de 12." in reply.text
    assert "Tienes 2 notas apuntadas." in reply.text
    assert reply.text.endswith("Y 3 avisos sin leer en el PC.")


def test_sin_tiempo_activado_no_lo_menciona_ni_pide_activarlo() -> None:
    reply = router(fresh()).handle("¿cómo va el día?")
    assert reply and "tiempo" not in reply.text.lower()


def test_no_se_dispara_con_otras_frases() -> None:
    r = router(fresh())
    for frase in ("buenas noches", "hola", "¿qué hora es?", "resumen de la película"):
        reply = r.handle(frase)
        assert reply is None or "Hoy es miércoles" not in reply.text


def test_una_alarma_a_una_hora_es_un_aviso_y_no_una_cuenta_atras() -> None:
    mem = fresh()
    reply = router(mem).handle("Vega, pon una alarma a las 7 de la tarde")
    assert reply and "suena la alarma" in reply.text and "temporizador" not in reply.text.lower()
    [r] = mem.pending()
    assert r.kind == "reminder" and r.due_at == MORNING.replace(hour=19)
    # Con duración sigue siendo un temporizador, y sin hora pregunta.
    assert router(fresh()).handle("alarma de 10 minutos").text.startswith("Temporizador")  # type: ignore[union-attr]
    ask = router(fresh()).handle("pon una alarma")
    assert ask and "¿A qué hora?" in ask.text
