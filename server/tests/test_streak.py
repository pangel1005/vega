"""La racha del gimnasio: reglas de la llama, voz, protocolo y envío al teléfono."""

import asyncio
from datetime import date, datetime, timedelta

import pytest

from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.skills.streak import GymLog, chains, compute, describe

TODAY = date(2026, 11, 12)  # jueves
NOW = datetime(2026, 11, 12, 19, 30)


def d(offset: int) -> date:
    return TODAY + timedelta(days=offset)


# --- Reglas ---


def test_sin_dias_no_hay_llama() -> None:
    s = compute([], TODAY)
    assert (s.count, s.best, s.total, s.today, s.alive, s.heat, s.week) == (0, 0, 0, False, False, 0.0, 0)


def test_dias_seguidos_hacen_racha_y_hoy_arde_al_maximo() -> None:
    s = compute([d(-2), d(-1), d(0)], TODAY)
    assert (s.count, s.best, s.today, s.rest, s.alive, s.heat, s.rest_left) == (3, 3, True, 0, True, 1.0, 2)


def test_se_puede_descansar_hasta_dos_dias_sin_perderla() -> None:
    days = [d(-10), d(-7), d(-4), d(-1)]  # cada 3 días: dos de descanso entre medias
    assert compute(days, TODAY).count == 4
    assert chains([d(-10), d(-6)]) == [[d(-10)], [d(-6)]]  # tres días de descanso: se corta


def test_la_llama_se_va_apagando_mientras_descansas() -> None:
    days = [d(-5), d(-4)]
    yesterday = compute([d(-1)], TODAY)
    assert (yesterday.rest, yesterday.rest_left, yesterday.heat) == (0, 2, 0.85)
    one = compute([d(-6), d(-2)], TODAY)  # el último entreno fue anteayer: hoy es un día de descanso
    assert (one.rest, one.rest_left, round(one.heat, 2)) == (1, 1, 0.6)
    embers = compute([d(-3)], TODAY)  # dos de descanso: hoy o se apaga
    assert (embers.alive, embers.rest_left, round(embers.heat, 2)) == (True, 0, 0.35)
    out = compute(days, TODAY)  # cuatro días sin ir: apagada
    assert (out.alive, out.count, out.best, out.heat) == (False, 0, 2, 0.0)


def test_mejor_racha_total_y_semana() -> None:
    days = [d(-30), d(-29), d(-28), d(-27), d(-20), d(-3), d(-2), d(0)]
    s = compute(days, TODAY)
    assert (s.count, s.best, s.total) == (3, 4, 8)
    assert s.week == 3  # lunes 9, martes 10 y hoy jueves 12
    assert s.days == [x.isoformat() for x in days]  # todo cabe en las 6 semanas del calendario
    assert compute([d(1)], TODAY).total == 0  # lo futuro no cuenta


# --- Guardar ---


def log() -> GymLog:
    return GymLog(ConversationStore(":memory:"), now=lambda: NOW)


def test_encender_una_vez_por_dia_y_poder_corregir() -> None:
    g = log()
    assert g.light() and not g.light()
    assert g.state().today
    assert g.light(d(-1)) and g.state().count == 2
    assert not g.light(d(1))  # no se adelanta el futuro
    assert not g.light(d(-40))  # tan atrás ya no se corrige
    assert g.put_out() and not g.put_out()
    assert not g.state().today and g.state().count == 1


# --- Voz ---


def router() -> tuple[SkillRouter, GymLog]:
    store = ConversationStore(":memory:")
    g = GymLog(store, now=lambda: NOW)
    r = SkillRouter(PersonalMemory(store), now=lambda: NOW)
    r.attach_gym(g)
    return r, g


@pytest.mark.parametrize(
    "phrase",
    [
        "Vega, fui al gimnasio",
        "vengo del gym",
        "ya entrené",
        "acabo de volver del gimnasio",
        "enciende la llama",
    ],
)
def test_frases_que_encienden(phrase: str) -> None:
    r, g = router()
    reply = r.handle(phrase)
    assert reply and "Encendiste la llama" in reply.text and g.state().today


def test_ayer_y_preguntar_y_deshacer() -> None:
    r, g = router()
    reply = r.handle("ayer fui al gimnasio")
    assert reply and g.state().days == [d(-1).isoformat()]
    reply = r.handle("fui al gimnasio")
    assert reply and reply.text.startswith("¡Llama encendida! Llevas una racha de 2 días")
    reply = r.handle("fui al gimnasio")
    assert reply and reply.text.startswith("Hoy ya estaba encendida.")
    reply = r.handle("¿cómo va mi racha?")
    assert reply and "hoy ya está encendida" in reply.text
    reply = r.handle("no fui al gimnasio")
    assert reply and reply.text.startswith("Hecho, apagué ese día.") and not g.state().today


def test_describe_avisa_si_hoy_se_apaga_o_ya_se_apago() -> None:
    assert "hoy tienes que ir o se apaga" in describe(compute([d(-3)], TODAY))
    assert "puedes descansar 2 días más" in describe(compute([d(-1)], TODAY))
    assert describe(compute([d(-9)], TODAY)).startswith("La llama está apagada. Tu mejor racha fue de 1 día.")
    assert describe(compute([], TODAY)).startswith("Aún no has encendido la llama")


def test_frases_que_no_son_del_gimnasio() -> None:
    r, _ = router()
    for phrase in ("pon música", "qué hora es", "recuérdame ir al gimnasio a las 6"):
        reply = r.handle(phrase)
        assert reply is None or "llama" not in reply.text


# --- Protocolo y teléfono ---


def test_mensajes_de_la_racha() -> None:
    from server.ws.protocol import StreakCommand

    assert StreakCommand(action="light").day is None
    assert StreakCommand(action="out", day="2026-11-11").day == "2026-11-11"
    with pytest.raises(ValueError):
        StreakCommand(action="light", day="ayer")


class Phone:
    def __init__(self) -> None:
        self.events: list[object] = []

    async def announce(self, reminder: object, late: bool) -> None: ...
    async def send_telemetry(self, event: object) -> None: ...
    async def send_media(self, event: object) -> None: ...
    async def send_notifications(self, event: object) -> None: ...
    async def send_event(self, event: object) -> None:
        self.events.append(event)

    async def send_actions(self) -> None: ...


def test_el_centro_de_avisos_manda_la_racha_y_atiende_la_llama() -> None:
    from server.core.hub import Hub

    store = ConversationStore(":memory:")
    g = GymLog(store, now=lambda: NOW)
    hub = Hub(PersonalMemory(store), now=lambda: NOW, gym=g)
    phone = Phone()
    hub.add(phone)

    async def go() -> None:
        await hub.send_streak()
        await hub.send_streak()  # sin cambios: no se repite
        await hub.streak("light")  # la llama del teléfono
        await hub.streak("light", d(-1).isoformat())
        await hub.streak("out", d(-1).isoformat())

    asyncio.run(go())
    sent = [e for e in phone.events if getattr(e, "type", "") == "streak"]
    assert [(e.count, e.today) for e in sent] == [(0, False), (1, True), (2, True), (1, True)]  # type: ignore[attr-defined]
    assert sent[1].heat == 1.0 and sent[1].days == [TODAY.isoformat()]  # type: ignore[attr-defined]
