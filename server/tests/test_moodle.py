"""Entregas de Moodle: leer el calendario, programar los avisos y contestar por voz."""

import asyncio
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

import httpx
import pytest

from server.memory.personal import PersonalMemory, naive_local
from server.memory.store import ConversationStore
from server.skills.moodle import (
    MoodleError,
    MoodleSync,
    Task,
    TaskBook,
    alert_text,
    clean_title,
    clock,
    describe,
    fetch_calendar,
    ical_source,
    in_words,
    is_deadline,
    parse_ics,
)
from server.skills.router import SkillRouter
from server.tools import moodle_setup

NOW = datetime(2026, 11, 11, 20, 0)


def event(uid: str, summary: str, start: str, extra: str = "") -> str:
    return f"BEGIN:VEVENT\r\nUID:{uid}\r\nSUMMARY:{summary}\r\nDTSTART{start}\r\n{extra}END:VEVENT\r\n"


def calendar(*events: str) -> str:
    return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + "".join(events) + "END:VCALENDAR\r\n"


ICS = calendar(
    event(
        "1@m",
        "Ensayo 2 vence",
        ":20261111T230000",
        "CATEGORIES:Cálculo I\r\nURL:https://m.example/mod/assign/1\r\n",
    ),
    event("2@m", "Examen parcial se abre", ":20261112T080000"),
    event("3@m", "Cuestionario 4 cierra", ":20261113T100000", "CATEGORIES:Física\r\n"),
    event("4@m", "Reunión de grupo", ":20261114T100000"),
)


def fresh() -> tuple[ConversationStore, PersonalMemory]:
    store = ConversationStore(":memory:")
    return store, PersonalMemory(store)


def book(
    now: datetime = NOW, alerts: tuple[int, ...] = (60,)
) -> tuple[TaskBook, PersonalMemory, list[datetime]]:
    store, memory = fresh()
    clock_ = [now]
    return TaskBook(store, memory, now=lambda: clock_[0], alerts=alerts), memory, clock_


def task(
    uid: str = "1@m",
    title: str = "Ensayo 2",
    due: datetime = NOW + timedelta(hours=3),
    course: str = "Cálculo I",
) -> Task:
    return Task(uid, title, course, due)


# --- Leer el calendario ---


def test_solo_entran_las_entregas_con_su_curso_y_sin_los_eventos_que_abren() -> None:
    tasks = parse_ics(ICS)
    assert [(t.title, t.course) for t in tasks] == [("Ensayo 2", "Cálculo I"), ("Cuestionario 4", "Física")]
    assert tasks[0].due == datetime(2026, 11, 11, 23, 0) and tasks[0].url == "https://m.example/mod/assign/1"


def test_el_curso_pierde_el_sufijo_del_periodo() -> None:
    extra = "CATEGORIES:MAT-101-01_2026-4\r\n"
    text = calendar(event("u", "Vencimiento de Práctica 1", ":20261111T230000", extra))
    assert parse_ics(text)[0].course == "MAT-101-01"


def test_con_only_deadlines_false_entran_todos() -> None:
    assert len(parse_ics(ICS, only_deadlines=False)) == 4


def test_las_horas_en_utc_pasan_a_hora_local() -> None:
    tasks = parse_ics(calendar(event("u", "Tarea vence", ":20261111T230000Z")))
    assert tasks[0].due == naive_local(datetime(2026, 11, 11, 23, 0, tzinfo=UTC))


def test_un_dia_sin_hora_vence_a_las_23_59() -> None:
    tasks = parse_ics(calendar(event("u", "Tarea vence", ";VALUE=DATE:20261112")))
    assert tasks[0].due == datetime(2026, 11, 12, 23, 59)


def test_hora_con_una_zona_que_este_pc_no_conoce_se_toma_como_local() -> None:
    tasks = parse_ics(calendar(event("u", "Tarea vence", ";TZID=Zona/Inexistente:20261111T230000")))
    assert tasks[0].due == datetime(2026, 11, 11, 23, 0)


def test_lineas_plegadas_y_escapes() -> None:
    text = (
        "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nUID:a\r\nSUMMARY:Ensayo\\, parte 1\r\n  vence\r\n"
        "DTSTART:20261111T230000\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
    )
    assert parse_ics(text)[0].title == "Ensayo, parte 1"


def test_eventos_incompletos_o_con_fecha_rota_se_ignoran() -> None:
    text = calendar(
        "BEGIN:VEVENT\r\nSUMMARY:Sin uid vence\r\nDTSTART:20261111T230000\r\nEND:VEVENT\r\n",
        event("x", "Rota vence", ":nunca"),
    )
    assert parse_ics(text) == []


def test_titulos_en_ingles_y_espanol() -> None:
    assert clean_title("Essay 2 is due") == "Essay 2"
    assert clean_title("Tarea 1 vence") == "Tarea 1"
    assert clean_title("vence") == "vence"
    assert clean_title("Vencimiento de Ensayo Unidad 1 (2 puntos)") == "Ensayo Unidad 1 (2 puntos)"
    assert clean_title("Se cierra Evaluación A Unidad") == "Evaluación A Unidad"
    assert clean_title("Vencimiento de IA &amp; Prompting") == "IA & Prompting"
    assert is_deadline("Quiz closes") and is_deadline("Cuestionario cierra")
    assert not is_deadline("Quiz opens") and not is_deadline("Charla de bienvenida")


# --- Cómo se dice ---


def test_frases() -> None:
    assert in_words(3600) == "en 1 hora"
    assert in_words(45 * 60) == "en 45 minutos"
    assert in_words(135 * 60) == "en 2 horas y 15 minutos"
    assert in_words(10) == "ahora mismo"
    assert clock(NOW.replace(hour=23, minute=59), NOW) == "hoy a las 23:59"
    assert clock(NOW + timedelta(days=1), NOW) == "mañana a las 20:00"
    assert clock(NOW + timedelta(days=3), NOW) == "el 14/11 a las 20:00"
    due = NOW + timedelta(hours=1)
    assert (
        alert_text(task(due=due), NOW)
        == "tu entrega «Ensayo 2» de Cálculo I vence en 1 hora, hoy a las 21:00"
    )


def test_describe() -> None:
    assert describe([], NOW) == "No tienes entregas pendientes en Moodle."
    text = describe([task(), task("2", "Informe", NOW + timedelta(days=1), "")], NOW)
    assert text.startswith(
        "Tienes 2 entregas pendientes: «Ensayo 2» de Cálculo I, hoy a las 23:00; «Informe», mañana"
    )
    assert "y 2 más" in describe([task(str(i)) for i in range(7)], NOW)


# --- Programar los avisos ---


def test_se_programa_un_aviso_una_hora_antes() -> None:
    b, memory, _ = book()
    assert b.sync([task()]) == 1
    (reminder,) = memory.pending()
    assert reminder.due_at == NOW + timedelta(hours=2)
    assert reminder.text == "tu entrega «Ensayo 2» de Cálculo I vence en 1 hora, hoy a las 23:00"


def test_sincronizar_otra_vez_no_repite_avisos() -> None:
    b, memory, _ = book()
    b.sync([task()])
    assert b.sync([task()]) == 0
    assert len(memory.pending()) == 1


def test_varios_avisos_por_tarea() -> None:
    b, memory, _ = book(alerts=(1440, 60))
    due = NOW + timedelta(days=2)
    assert b.sync([task(due=due)]) == 2
    assert [r.due_at for r in memory.pending()] == [due - timedelta(days=1), due - timedelta(hours=1)]


def test_si_ya_queda_menos_de_una_hora_avisa_ahora_una_sola_vez() -> None:
    b, memory, _ = book(alerts=(1440, 60))
    assert b.sync([task(due=NOW + timedelta(minutes=30))]) == 1
    (reminder,) = memory.pending()
    assert reminder.due_at == NOW and "vence en 30 minutos" in reminder.text
    assert b.sync([task(due=NOW + timedelta(minutes=30))]) == 0


def test_lo_ya_vencido_no_avisa() -> None:
    b, memory, _ = book()
    assert b.sync([task(due=NOW - timedelta(hours=1))]) == 0
    assert memory.pending() == []


def test_si_el_profesor_mueve_la_fecha_los_avisos_se_rehacen() -> None:
    b, memory, _ = book()
    b.sync([task()])
    new_due = NOW + timedelta(days=1)
    assert b.sync([task(due=new_due)]) == 1
    (reminder,) = memory.pending()
    assert reminder.due_at == new_due - timedelta(hours=1)


def test_entregada_anula_sus_avisos_y_no_vuelve() -> None:
    b, memory, _ = book()
    t = task()
    b.sync([t])
    b.mark_done(t)
    assert memory.pending() == []
    assert b.sync([t]) == 0 and b.upcoming() == []


def test_upcoming_solo_lo_pendiente_de_la_ultima_lectura_y_en_orden() -> None:
    b, _, clk = book()
    b.sync([task("1", "A", NOW + timedelta(days=2)), task("2", "B", NOW + timedelta(days=1))])
    assert [t.title for t in b.upcoming()] == ["B", "A"]
    clk[0] = NOW + timedelta(minutes=20)
    b.sync([task("2", "B", NOW + timedelta(days=1))])  # «A» ya no sale en Moodle
    assert [t.title for t in b.upcoming()] == ["B"]
    assert [t.title for t in b.upcoming(within=timedelta(hours=2))] == []


def test_las_muy_viejas_se_olvidan() -> None:
    b, _, clk = book()
    b.sync([task(due=NOW + timedelta(hours=1))])
    clk[0] = NOW + timedelta(days=40)
    b.sync([])
    assert b.find("ensayo", NOW) == [] and b._db.query("SELECT * FROM tasks") == []


def test_aviso_de_moodle_roto() -> None:
    b, memory, _ = book()
    b.warn_broken(7)
    (reminder,) = memory.pending()
    assert "desde hace 7 horas" in reminder.text


# --- Por voz ---


def voice(now: datetime = NOW) -> tuple[SkillRouter, TaskBook]:
    store, memory = fresh()
    b = TaskBook(store, memory, now=lambda: now)
    router = SkillRouter(memory, now=lambda: now)
    router.attach_moodle(b)
    return router, b


def test_que_tareas_tengo() -> None:
    router, b = voice()
    b.sync([task(), task("2", "Informe de laboratorio", NOW + timedelta(days=1), "Física")])
    for phrase in (
        "¿Qué tareas tengo?",
        "Vega, mis entregas pendientes",
        "qué tengo que entregar",
        "revisa Moodle",
    ):
        reply = router.handle(phrase)
        assert reply and reply.text.startswith("Tienes 2 entregas pendientes"), phrase


def test_sin_entregas() -> None:
    router, _ = voice()
    reply = router.handle("qué tareas tengo")
    assert reply and reply.text == "No tienes entregas pendientes en Moodle."


def test_sin_moodle_conectado_explica_como() -> None:
    store, memory = fresh()
    reply = SkillRouter(memory, now=lambda: NOW).handle("qué tareas tengo")
    assert reply and "moodle_setup" in reply.text


def test_ya_entregue() -> None:
    router, b = voice()
    b.sync([task(), task("2", "Informe de laboratorio", NOW + timedelta(days=1))])
    reply = router.handle("Vega, ya entregué el ensayo 2")
    assert reply and "«Ensayo 2» entregada" in reply.text
    assert [t.title for t in b.upcoming()] == ["Informe de laboratorio"]
    reply = router.handle("marca el informe de laboratorio como entregada")
    assert reply and "«Informe de laboratorio» entregada" in reply.text and b.upcoming() == []


def test_ya_entregue_con_dos_que_encajan_pregunta_cual() -> None:
    router, b = voice()
    b.sync([task("1", "Informe 1"), task("2", "Informe 2")])
    reply = router.handle("ya entregué el informe")
    assert reply and "¿Cuál" in reply.text and len(b.upcoming()) == 2


def test_ya_hice_otra_cosa_no_es_una_entrega() -> None:
    router, b = voice()
    b.sync([task()])
    assert router.handle("ya hice la cena") is None
    assert len(b.upcoming()) == 1


def test_el_resumen_del_dia_cuenta_las_entregas_de_los_proximos_dos_dias() -> None:
    router, b = voice()
    b.sync([task(), task("2", "Lejana", NOW + timedelta(days=9))])
    reply = router.handle("buenos días")
    assert reply and "Entregas próximas: Tienes" not in reply.text
    assert "Entregas próximas: " in reply.text and "«Ensayo 2»" in reply.text and "Lejana" not in reply.text


# --- Bajar el calendario ---


def transport(body: str = ICS, status: int = 200) -> httpx.MockTransport:
    return httpx.MockTransport(lambda request: httpx.Response(status, text=body))


def test_fetch_ok_y_errores_sin_la_clave_en_el_mensaje() -> None:
    assert "BEGIN:VCALENDAR" in asyncio.run(
        fetch_calendar("https://m.example/x?authtoken=SECRETO", transport())
    )
    for t, texto in (
        (transport(status=500), "500"),
        (transport("<html>entra</html>"), "no devolvió un calendario"),
    ):
        with pytest.raises(MoodleError, match=texto) as caught:
            asyncio.run(fetch_calendar("https://m.example/x?authtoken=SECRETO", t))
        assert "SECRETO" not in str(caught.value)

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no hay red https://m.example/x?authtoken=SECRETO")

    with pytest.raises(MoodleError) as caught:
        asyncio.run(fetch_calendar("https://m.example/x?authtoken=SECRETO", httpx.MockTransport(boom)))
    assert "SECRETO" not in str(caught.value) and "ConnectError" in str(caught.value)


def test_sincronizador_lee_y_programa() -> None:
    b, memory, _ = book()
    source = ical_source("https://m.example/x", transport=transport())
    sync = MoodleSync(b, source, now=lambda: NOW)
    assert asyncio.run(sync.refresh()) is True
    assert len(memory.pending()) == 2  # «vence» a las 23:00 y «cierra» dentro de dos días
    assert [t.title for t in b.upcoming()] == ["Ensayo 2", "Cuestionario 4"]


def test_si_moodle_falla_seis_horas_avisa_una_sola_vez(caplog: pytest.LogCaptureFixture) -> None:
    b, memory, _ = book()
    clk = [NOW]
    source = ical_source("https://m.example/x?authtoken=SECRETO", transport=transport(status=403))
    sync = MoodleSync(b, source, now=lambda: clk[0])
    assert asyncio.run(sync.refresh()) is False and memory.pending() == []
    clk[0] = NOW + timedelta(hours=6, minutes=1)
    asyncio.run(sync.refresh())
    asyncio.run(sync.refresh())
    assert len(memory.pending()) == 1 and "desde hace 6 horas" in memory.pending()[0].text
    assert "SECRETO" not in caplog.text


# --- Configuración ---


def test_la_configuracion_por_defecto_esta_apagada_con_una_hora_de_aviso() -> None:
    from server.core.config import MoodleSettings

    s = MoodleSettings()
    assert s.enabled is False and s.alerts == [60] and s.only_deadlines is True


def test_setup_guarda_solo_si_dices_que_si(tmp_path: Path) -> None:
    said: list[str] = []
    path = tmp_path / "moodle.json"
    url = "https://m.example/x?authtoken=abc"
    assert not moodle_setup.run(
        url, ask=lambda _: "n", say=said.append, path=path, fetch=lambda u: ICS, now=lambda: NOW
    )
    assert not path.exists() and "Entregas por hacer: 2" in said[1]
    assert moodle_setup.run(
        url, ask=lambda _: "s", say=said.append, path=path, fetch=lambda u: ICS, now=lambda: NOW
    )
    assert json.loads(path.read_text(encoding="utf-8")) == {"url": url}
    assert all("authtoken" not in line for line in said)


def test_setup_rechaza_lo_que_no_es_una_direccion(tmp_path: Path) -> None:
    said: list[str] = []
    assert not moodle_setup.run("hola", say=said.append, path=tmp_path / "m.json", fetch=lambda u: ICS)
    assert "https://" in said[0]


# --- El tablero del teléfono ---


def test_board_incluye_lo_vencido_hace_poco_y_no_lo_entregado() -> None:
    b, _, _ = book()
    b.sync(
        [
            task("1", "Reciente", NOW - timedelta(days=2)),
            task("2", "Antigua", NOW - timedelta(days=9)),
            task("3", "Futura", NOW + timedelta(days=1)),
            task("4", "Hecha", NOW + timedelta(days=2)),
        ]
    )
    assert [t.title for t in b.board()] == ["Reciente", "Futura", "Hecha"]
    assert b.mark_done_uid("4") and not b.mark_done_uid("no-existe")
    assert [t.title for t in b.board()] == ["Reciente", "Futura"]
    assert [t.title for t in b.upcoming()] == ["Futura"]  # las voces siguen sin contar lo vencido


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


def test_el_centro_de_avisos_manda_las_entregas_al_telefono_solo_si_cambian() -> None:
    from server.core.hub import Hub

    b, memory, _ = book()
    b.sync([task("1", "Ensayo"), task("2", "Informe", NOW + timedelta(days=1))])
    hub = Hub(memory, now=lambda: NOW, tasks=b.board, task_done=b.mark_done_uid)
    phone = Phone()
    hub.add(phone)

    async def go() -> None:
        await hub.send_tasks()
        await hub.send_tasks()  # sin cambios: no se repite
        await hub.task_done("1")  # desde el tablero: llega el nuevo reparto
        await hub.task_done("no-existe")

    asyncio.run(go())
    sent = [e for e in phone.events if getattr(e, "type", "") == "tasks"]
    assert [[i.title for i in e.items] for e in sent] == [["Ensayo", "Informe"], ["Informe"]]  # type: ignore[attr-defined]


def test_el_mensaje_del_tablero_se_valida() -> None:
    from server.ws.protocol import TasksCommand, TasksEvent

    assert TasksCommand(action="get").uid is None
    assert TasksCommand(action="done", uid="123@moodle").uid == "123@moodle"
    with pytest.raises(ValueError):
        TasksCommand(action="done")
    event = TasksEvent.model_validate({"items": [{"uid": "1", "title": "A", "due": 1.5}]})
    assert event.items[0].course == "" and '"type":"tasks"' in event.model_dump_json()
