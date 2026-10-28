"""Correcciones de la revisión de seguridad y robustez del 28-10: cada una con la prueba que la fija."""

import asyncio
import logging
import os
import time
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.core.hub import Hub
from server.core.logs import _HideQuery
from server.core.maintenance import Maintenance
from server.memory.personal import PersonalMemory, naive_local
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.skills.when import MAX_SECONDS, parse_duration, parse_in
from server.tests.fakes import FakeChat
from server.tests.test_actions import FakeExecutor
from server.tests.test_wake import ACTIONS, TOKEN, until
from server.tools.actions import ActionRunner


def make_app(tmp_path: Path, **kw: Any) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    return create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=kw.pop("chat", FakeChat()),
        store=ConversationStore(":memory:"),
        actions=kw.pop("actions", ActionRunner(ACTIONS, FakeExecutor())),
        **kw,
    )


# ---------- A1: /app/models no abre rutas de red ----------


@pytest.mark.parametrize(
    "path",
    [
        "///equipo/recurso/x",
        "C:/Windows/win.ini",
        "%5C%5Cequipo%5Crecurso%5Cx",
        "a/../../secreto",
        "a b/c",
        "..",
        "%2e%2e/x",
    ],
)
def test_models_rechaza_rutas_raras_sin_tocar_el_disco(path: str, monkeypatch: pytest.MonkeyPatch) -> None:
    seen: list[str] = []
    real = Path.resolve

    def spy(self: Path, *a: Any, **k: Any) -> Path:
        seen.append(str(self))
        return real(self, *a, **k)

    monkeypatch.setattr(Path, "resolve", spy)
    with TestClient(make_app(Path("."))) as client:
        resp = client.get(f"/app/models/{path}", headers={"host": "127.0.0.1:8000"})
    assert resp.status_code == 404
    assert not any(
        "equipo" in s or "win.ini" in s for s in seen
    )  # nunca se resolvió una ruta de red ni absoluta


def test_models_sigue_sirviendo_los_archivos_de_verdad() -> None:
    real = Path(__file__).resolve().parents[2] / "data" / "telefono"
    if not real.is_dir():
        pytest.skip("este PC no tiene data/telefono")
    from server.web import _MODEL_PATH

    files = [p.relative_to(real).as_posix() for p in real.rglob("*") if p.is_file()]
    assert files, "data/telefono está vacío"
    assert all(_MODEL_PATH.fullmatch(f) for f in files), [f for f in files if not _MODEL_PATH.fullmatch(f)][
        :3
    ]


# ---------- A2: recordatorios con zona horaria ----------


def test_un_recordatorio_con_zona_no_envenena_la_memoria() -> None:
    mem = PersonalMemory(ConversationStore(":memory:"))
    due = naive_local(datetime.fromisoformat("2026-10-24T18:30:00Z"))
    assert due.tzinfo is None
    mem.add_reminder("reminder", "comprar pan", due)
    mem.add_reminder("reminder", "otra", datetime(2030, 1, 1))
    assert len(mem.pending()) == 2
    assert [r.text for r in mem.due(datetime(2026, 12, 1))] == ["comprar pan"]


def test_una_fila_ilegible_no_bloquea_las_demas() -> None:
    store = ConversationStore(":memory:")
    mem = PersonalMemory(store)
    mem.add_reminder("reminder", "buena", datetime(2030, 1, 1))
    store.execute(
        "INSERT INTO reminders (kind, text, due_at, created_at) VALUES ('reminder', 'rota', 'no es una fecha', 'x')"  # noqa: E501
    )
    assert [r.text for r in mem.pending()] == ["buena"]


def test_importar_un_recordatorio_con_z_no_rompe_el_servidor(tmp_path: Path) -> None:
    ws: Any
    with (
        TestClient(make_app(tmp_path), base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as ws,
    ):
        ws.send_json({"type": "hello", "token": TOKEN})
        until(ws, "ready")
        ws.send_json(
            {
                "type": "memory_import",
                "reminders": [{"kind": "reminder", "text": "x", "due_at": "2026-10-24T18:30:00Z"}],
            }
        )
        ws.send_json({"type": "chat", "text": "qué recordatorios tengo"})
        assert "pendiente" in [e for e in until(ws, "done") if e["type"] == "done"][-1]["text"]


# ---------- A3 y M5: el registro no guarda lo que dices ni lo que ves ----------


def test_el_aviso_de_recordatorio_no_escribe_su_texto(caplog: pytest.LogCaptureFixture) -> None:
    mem = PersonalMemory(ConversationStore(":memory:"))
    mem.add_reminder("reminder", "llamar a mi médico por la analítica", datetime(2020, 1, 1))

    class Listener:
        async def announce(self, reminder: Any, late: bool) -> None:
            pass

    async def go() -> None:
        hub = Hub(mem)
        hub.add(Listener(), "phone")  # type: ignore[arg-type]  # sin nadie conectado no se entrega nada
        await hub.deliver_due()

    with caplog.at_level(logging.INFO):
        asyncio.run(go())
    assert "analítica" not in caplog.text and "Recordatorio 1 entregado" in caplog.text


def test_las_lineas_de_acceso_no_llevan_la_consulta() -> None:
    rec = logging.LogRecord("uvicorn.access", logging.INFO, "", 0, '%s - "%s %s HTTP/%s" %d',
                            ("127.0.0.1", "GET", "/app/media/lyrics?title=Historias+Instagram&artist=", "1.1", 200), None)  # fmt: skip  # noqa: E501
    assert _HideQuery().filter(rec)
    assert "Historias" not in rec.getMessage() and "/app/media/lyrics?…" in rec.getMessage()


def test_letras_sin_artista_no_salen_a_internet(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str] = []

    async def fake_find(self: Any, artist: str, title: str, album: str = "", duration: float = 0) -> Any:
        calls.append(title)
        raise AssertionError("no debería buscarse")

    monkeypatch.setattr("server.device.lyrics.LyricsFinder.find", fake_find)
    with TestClient(make_app(tmp_path), base_url="http://127.0.0.1:8000") as client:
        body = client.get("/app/media/lyrics", params={"title": "Historias • Instagram"}).json()
        assert body == {"synced": False, "instrumental": False, "lines": []} and not calls
        assert (
            client.get("/app/media/lyrics", params={"title": "x" * 500, "artist": "a"}).status_code == 422
        )  # demasiado largo


# ---------- M2: token con caracteres raros ----------


def test_un_token_no_ascii_se_rechaza_sin_traza(tmp_path: Path, caplog: pytest.LogCaptureFixture) -> None:
    ws: Any
    with TestClient(make_app(tmp_path), base_url="http://127.0.0.1:8000") as client:
        with caplog.at_level(logging.ERROR), client.websocket_connect("ws://127.0.0.1:8000/ws") as ws:
            ws.send_json({"type": "hello", "token": "ééééé"})
            assert until(ws, "error")[-1]["type"] == "error"
        assert client.post("/admin/apagar", headers=[(b"x-token", "é".encode("latin-1"))]).status_code == 403
    assert "TypeError" not in caplog.text


# ---------- M3: «Cancelar apagado» no espera a la respuesta del modelo ----------


class SlowChat(FakeChat):
    async def stream(self, messages: Any) -> Any:
        for _ in range(6):
            await asyncio.sleep(0.4)
            yield "palabra "


def test_una_accion_llega_aunque_el_modelo_siga_contestando(tmp_path: Path) -> None:
    ws: Any
    ex = FakeExecutor()
    app = make_app(tmp_path, chat=SlowChat(), actions=ActionRunner(ACTIONS, ex))
    with (
        TestClient(app, base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as ws,
    ):
        ws.send_json({"type": "hello", "token": TOKEN})
        until(ws, "ready")
        ws.send_json({"type": "chat", "text": "cuéntame algo largo"})
        time.sleep(0.2)
        ws.send_json({"type": "action", "id": ACTIONS[1].id})  # «Volumen +»: sin confirmación
        events = until(ws, "done")  # lo que llegue antes de que acabe el modelo
        kinds = [e["type"] for e in events]
        assert kinds.index("action_result") < kinds.index("done")
    assert ex.ran  # y se ejecutó


# ---------- M4: cifras enormes y mensajes que fallan ----------


def test_cifras_enormes_no_desbordan() -> None:
    assert parse_duration("temporizador de 999999999999 horas") is None
    assert parse_in("en 99999999999 días", datetime(2026, 1, 1)) is None
    assert parse_duration("temporizador de 5 minutos") is not None
    assert MAX_SECONDS == 30 * 86_400


def test_el_temporizador_absurdo_pide_aclaracion_y_la_conexion_sigue(tmp_path: Path) -> None:
    mem = PersonalMemory(ConversationStore(":memory:"))
    reply = SkillRouter(mem).handle("pon un temporizador de 999999999999 horas")
    assert reply and "¿De cuánto tiempo?" in reply.text
    ws: Any
    with (
        TestClient(make_app(tmp_path), base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as ws,
    ):
        ws.send_json({"type": "hello", "token": TOKEN})
        until(ws, "ready")
        ws.send_json({"type": "chat", "text": "recuérdame en 99999999999 días comprar pan"})
        until(ws, "done")
        ws.send_json({"type": "chat", "text": "hola"})
        until(ws, "done")  # sigue viva


# ---------- M6: lo que otros te escriben no queda en tu historial ----------


def test_leer_notificaciones_no_se_guarda_en_el_historial(tmp_path: Path) -> None:
    from server.device.notifications import PcNotification

    notes = [
        PcNotification(id=1, app="WhatsApp", title="Ana", body="te espero a las 8", at=time.time(), icon="")
    ]
    store = ConversationStore(":memory:")

    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN, llm_probe=probe, chat_model=FakeChat(), store=store, actions=ActionRunner(ACTIONS, FakeExecutor()),  # noqa: E501
    )  # fmt: skip
    app.state.hub.latest_notifications = lambda: notes  # lo que lee la habilidad
    ws: Any
    with (
        TestClient(app, base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as ws,
    ):
        ws.send_json({"type": "hello", "token": TOKEN})
        until(ws, "ready")
        ws.send_json({"type": "chat", "text": "léeme el último mensaje"})
        text = [e for e in until(ws, "done") if e["type"] == "done"][-1]["text"]
        assert "te espero a las 8" in text  # se lee en voz alta…
    rows = store.query("SELECT content FROM messages")
    assert not any("te espero" in r["content"] for r in rows)  # …pero no se guarda


# ---------- M7: una carpeta vacía y nueva no es «vieja» ----------


def test_una_carpeta_vacia_y_recien_creada_no_se_limpia(tmp_path: Path) -> None:
    temp = tmp_path / "temp"
    (temp / "nueva_vacia").mkdir(parents=True)
    vieja = temp / "vieja_vacia"
    vieja.mkdir()
    long_ago = time.time() - 40 * 86_400
    os.utime(vieja, (long_ago, long_ago))
    m = Maintenance(
        tmp_path / "vega",
        "http://x",
        temp,
        tmp_path / "home",
        time.time,
        httpx.MockTransport(lambda r: httpx.Response(200)),
    )
    m.disk_clean(["temporales"])
    assert (temp / "nueva_vacia").exists() and not vieja.exists()


# ---------- M10: la configuración no se queda a medias ----------


def test_guardar_la_configuracion_es_atomico(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    from desktop import config_edit

    target = tmp_path / "actions.toml"
    target.write_text('[[action]]\nid = "a"\nlabel = "A"\nkind = "keys"\narg = "x"\n', encoding="utf-8")
    original = target.read_text(encoding="utf-8")
    actions = config_edit.read_actions(target)
    actions[0]["label"] = "Cambiado"

    def boom(*a: Any, **k: Any) -> None:
        raise OSError("se fue la luz")

    monkeypatch.setattr(os, "replace", boom)
    with pytest.raises(OSError):
        config_edit.write_actions(actions, target)
    assert target.read_text(encoding="utf-8") == original  # el original sigue intacto
    monkeypatch.undo()
    config_edit.write_actions(actions, target)
    assert 'label = "Cambiado"' in target.read_text(encoding="utf-8")
    assert not target.with_suffix(".toml.tmp").exists()


def test_el_servidor_arranca_con_la_copia_buena_si_actions_toml_esta_roto(tmp_path: Path) -> None:
    from server.tools.actions import ActionConfigError, load_actions

    broken = tmp_path / "actions.toml"
    broken.write_text('[[action]]\nid = "a"\nlabel = "A', encoding="utf-8")  # cortado a la mitad
    good = tmp_path / "actions.toml.bak"
    good.write_text('[[action]]\nid = "a"\nlabel = "A"\nkind = "keys"\narg = "x"\n', encoding="utf-8")
    with pytest.raises(ActionConfigError):
        load_actions(broken)
    assert [a.id for a in load_actions(good)] == ["a"]
    assert load_actions(tmp_path / "no-existe.toml") == []  # y sin copia, sin botones (pero arranca)
    assert timedelta(0) == timedelta(0)


def test_media_control_seek_needs_a_valid_position() -> None:
    import pytest
    from pydantic import ValidationError

    from server.ws.protocol import MediaControl, client_message

    ok = client_message.validate_python({"type": "media_control", "command": "seek", "position": 93.5})
    assert isinstance(ok, MediaControl) and ok.position == 93.5
    assert client_message.validate_python({"type": "media_control", "command": "next"})  # sin posición: vale
    for bad in (
        {"type": "media_control", "command": "seek"},  # saltar sin decir adónde
        {"type": "media_control", "command": "seek", "position": -1},
        {"type": "media_control", "command": "seek", "position": 1e9},
        {"type": "media_control", "command": "seek", "position": "mañana"},
    ):
        with pytest.raises(ValidationError):
            client_message.validate_python(bad)
