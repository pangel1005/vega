"""Moodle de verdad: el servicio web (cronología y token) y lo que cambia al usarlo en vez del calendario."""

import asyncio
import json
from collections.abc import Callable
from datetime import datetime, timedelta
from pathlib import Path

import httpx
import pytest

from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.moodle import (
    MoodleError,
    MoodleSync,
    Task,
    TaskBook,
    alert_text,
    load_config,
    write_config,
)
from server.skills.moodle_api import AccessError, MoodleApi, course_name, parse_action_events, request_token
from server.tools import moodle_setup

NOW = datetime(2026, 11, 11, 20, 0)


def stamp(day: int, hour: int, minute: int = 0) -> float:
    return datetime(2026, 11, day, hour, minute).timestamp()


def event(i: int, name: str, due: float, **extra: object) -> dict[str, object]:
    return {
        "id": i,
        "name": name,
        "timesort": due,
        "eventtype": "due",
        "course": {"fullnamedisplay": "Programaci&oacute;n II", "shortname": "MAT-101-01_2026-4"},
        "url": f"https://m.example/mod/assign/view.php?id={i}",
        "action": {"name": "Agregar entrega", "actionable": True},
        **extra,
    }


def timeline(*events: dict[str, object]) -> dict[str, object]:
    last = events[-1]["id"] if events else 0
    return {"events": list(events), "firstid": 1, "lastid": last}


def mock(handler: Callable[[httpx.Request], httpx.Response]) -> httpx.MockTransport:
    return httpx.MockTransport(handler)


def book() -> tuple[TaskBook, PersonalMemory, list[datetime]]:
    store = ConversationStore(":memory:")
    memory = PersonalMemory(store)
    clk = [NOW]
    return TaskBook(store, memory, now=lambda: clk[0]), memory, clk


def task(uid: str, title: str, due: datetime) -> Task:
    return Task(uid, title, "Cálculo I", due)


# --- Leer la cronología ---


def test_los_eventos_de_la_cronologia_se_leen_como_entregas() -> None:
    data = timeline(
        event(7, "Vencimiento de Ensayo &amp; Resumen", stamp(15, 23)),
        event(8, "Examen se abre", stamp(16, 8), eventtype="open"),  # no es una entrega
        event(9, "Hecha", stamp(17, 8), action={"name": "Ver", "actionable": False}),
        {"id": "roto"},  # un evento raro no tira a los demás
        event(10, "Cuestionario cierra", stamp(14, 9)),
    )
    tasks = parse_action_events(data)
    assert [(t.uid, t.title, t.course) for t in tasks] == [
        ("ws:10", "Cuestionario", "Programación II"),
        ("ws:7", "Ensayo & Resumen", "Programación II"),
    ]
    assert tasks[1].due == datetime(2026, 11, 15, 23, 0) and tasks[1].url.endswith("id=7")
    assert parse_action_events({}) == [] and parse_action_events(None) == []


def test_el_nombre_de_la_materia_pierde_el_codigo_y_los_gritos() -> None:
    assert course_name("ADM-01-201 ADMINISTRACI&Oacute;N Y GESTI&Oacute;N EMPRESARIAL") == (
        "Administración y Gestión Empresarial"
    )
    assert course_name("RED-301-01 CERTIFICACIÓN PROFESIONAL") == "Certificación Profesional"
    assert course_name("Programación II") == "Programación II"  # ya viene bien
    assert course_name("MAT-101-01") == "MAT-101-01"  # solo código: se deja
    assert course_name("") == ""


def test_la_api_pide_todas_las_paginas_y_manda_el_token_en_el_cuerpo() -> None:
    seen: list[dict[str, str]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        body = dict(x.split("=", 1) for x in request.content.decode().split("&"))
        seen.append(body)
        assert request.url.path.endswith("/webservice/rest/server.php") and "tok" not in str(request.url)
        ids = range(1, 51) if body["aftereventid"] == "0" else (51, 52)
        return httpx.Response(
            200, json=timeline(*[event(i, f"Tarea {i} vence", stamp(15, 9) + i) for i in ids])
        )

    api = MoodleApi("https://m.example/", "tok", mock(handler))
    assert len(asyncio.run(api.pending(NOW))) == 52
    assert [b["aftereventid"] for b in seen] == ["0", "50"]
    assert (
        seen[0]["wstoken"] == "tok" and seen[0]["wsfunction"] == "core_calendar_get_action_events_by_timesort"
    )


def test_los_errores_de_moodle_no_llevan_el_token() -> None:
    def invalid(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"exception": "moodle_exception", "errorcode": "invalidtoken"})

    def other(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"exception": "x", "errorcode": "nopermissions"})

    def broken(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin red https://m.example/?wstoken=SECRETO")

    for handler, kind in ((invalid, AccessError), (other, MoodleError), (broken, MoodleError)):
        api = MoodleApi("https://m.example", "SECRETO", mock(handler))
        with pytest.raises(kind) as caught:
            asyncio.run(api.site_info())
        assert "SECRETO" not in str(caught.value)
    assert AccessError.fatal and not MoodleError.fatal


def test_pedir_el_token_con_usuario_y_contrasena() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        body = request.content.decode()
        assert request.url.path.endswith("/login/token.php") and "service=moodle_mobile_app" in body
        ok = "username=20200001" in body and "password=buena" in body
        return httpx.Response(
            200, json={"token": "abc123"} if ok else {"error": "x", "errorcode": "invalidlogin"}
        )

    assert asyncio.run(request_token("https://m.example/", "20200001", "buena", mock(handler))) == "abc123"
    with pytest.raises(AccessError) as caught:
        asyncio.run(request_token("https://m.example", "20200001", "mala", mock(handler)))
    assert "mala" not in str(caught.value)


# --- Lo que cambia con la conexión real ---


def test_con_el_servicio_web_lo_entregado_deja_de_avisar_y_de_salir() -> None:
    b, memory, clk = book()
    a, c = task("ws:1", "A", NOW + timedelta(days=1)), task("ws:2", "B", NOW + timedelta(days=2))
    assert b.sync([a, c], authoritative=True) == 2 and len(memory.pending()) == 2
    clk[0] = NOW + timedelta(minutes=30)
    b.sync([c], authoritative=True)  # Moodle ya no lista «A»: la entregaste
    assert [r.text for r in memory.pending()] == [alert_text(c, c.due - timedelta(hours=1))]
    assert [t.title for t in b.board()] == ["B"]


def test_el_calendario_exportado_no_da_nada_por_entregado() -> None:
    b, memory, _ = book()
    b.sync([task("1", "A", NOW + timedelta(days=1)), task("2", "B", NOW + timedelta(days=2))])
    b.sync([task("2", "B", NOW + timedelta(days=2))])  # sin authoritative: «A» sigue avisando
    assert len(memory.pending()) == 2


def test_al_pasar_del_calendario_al_servicio_web_se_retiran_las_del_calendario() -> None:
    b, memory, _ = book()
    b.sync([task("ical-1", "Ensayo", NOW + timedelta(days=1))])
    b.sync([task("ws:5", "Ensayo", NOW + timedelta(days=1))], authoritative=True)
    assert len(memory.pending()) == 1 and [t.uid for t in b.board()] == ["ws:5"]


def test_acceso_roto_avisa_de_una_vez_y_solo_una() -> None:
    b, memory, _ = book()

    async def dead() -> list[Task]:
        raise AccessError("Moodle no acepta el acceso (invalidtoken).")

    sync = MoodleSync(b, dead, authoritative=True, now=lambda: NOW)
    assert asyncio.run(sync.refresh()) is False
    asyncio.run(sync.refresh())
    (reminder,) = memory.pending()
    assert "ya no acepta mi acceso" in reminder.text


# --- La configuración ---


def test_load_config(tmp_path: Path, caplog: pytest.LogCaptureFixture) -> None:
    path = tmp_path / "moodle.json"
    assert load_config(path) is None  # no existe
    path.write_text(json.dumps({"url": "https://m.example/calendar/x?authtoken=a"}), encoding="utf-8")
    config = load_config(path)
    assert config and config.url and config.site == "https://m.example" and config.token is None
    path.write_text(json.dumps({"site": "https://m.example", "token": " tok "}), encoding="utf-8")
    config = load_config(path)
    assert config and config.token == "tok" and config.url is None
    path.write_text(json.dumps({"token": "tok"}), encoding="utf-8")  # sin dirección: el token no sirve solo
    assert load_config(path) is None
    path.write_text(json.dumps({"url": "ftp://nada"}), encoding="utf-8")
    assert load_config(path) is None
    path.write_text("no es json", encoding="utf-8")
    assert load_config(path) is None
    assert "moodle_setup" in caplog.text


def test_write_config_suma_a_lo_guardado(tmp_path: Path) -> None:
    path = tmp_path / "moodle.json"
    write_config(path, url="https://m.example/x")
    write_config(path, site="https://m.example", token="t")
    assert json.loads(path.read_text(encoding="utf-8")) == {
        "url": "https://m.example/x",
        "site": "https://m.example",
        "token": "t",
    }


def test_setup_conectado_guarda_token_y_conserva_el_calendario(tmp_path: Path) -> None:
    path = tmp_path / "moodle.json"
    write_config(path, url="https://m.example/calendar/x?authtoken=a")

    def handler(request: httpx.Request) -> httpx.Response:
        if b"core_webservice_get_site_info" in request.content:
            return httpx.Response(200, json={"fullname": "Ana Pérez", "username": "2020"})
        due = (NOW + timedelta(days=1)).timestamp()
        return httpx.Response(200, json=timeline(event(1, "Ensayo vence", due)))

    said: list[str] = []
    api = MoodleApi("https://m.example", "tok", mock(handler))

    def connect(answer: str) -> bool:
        return moodle_setup.run_connected(
            "https://m.example/",
            "tok",
            ask=lambda _: answer,
            say=said.append,
            path=path,
            now=lambda: NOW,
            api=api,
        )

    assert not connect("n")
    assert "token" not in json.loads(path.read_text(encoding="utf-8"))
    assert connect("s")
    saved = json.loads(path.read_text(encoding="utf-8"))
    assert saved["token"] == "tok" and saved["site"] == "https://m.example" and "authtoken=a" in saved["url"]
    assert any("Conectado como Ana Pérez" in line for line in said)
    assert all("tok" not in line.replace("token", "") for line in said)
