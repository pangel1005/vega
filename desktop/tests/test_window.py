import time
from pathlib import Path
from typing import Any

import pytest

pytest.importorskip("PySide6")

from PySide6.QtCore import QObject, Signal  # noqa: E402
from PySide6.QtWidgets import QApplication  # noqa: E402

from desktop import theme  # noqa: E402
from desktop.context import Context  # noqa: E402
from desktop.widgets import Images  # noqa: E402
from desktop.window import MainWindow  # noqa: E402

SHOTS = Path(__file__).resolve().parents[2] / "data" / "desktop-capturas"


class FakeClient(QObject):
    """Un servidor de mentira: guarda lo que la app le manda."""

    event = Signal(dict)
    connection = Signal(bool)

    def __init__(self) -> None:
        super().__init__()
        self.sent: list[dict[str, Any]] = []
        self.open = True

    def send(self, message: dict[str, Any]) -> bool:
        self.sent.append(message)
        return self.open


@pytest.fixture(scope="module")
def app() -> QApplication:
    qapp = QApplication.instance() or QApplication([])
    theme.load_fonts()
    theme.use(theme.DAY)  # con la app ya creada: la hoja de estilo lleva la marca de las casillas
    qapp.setStyleSheet(theme.STYLE)
    return qapp


@pytest.fixture
def window(app: QApplication) -> tuple[MainWindow, FakeClient]:
    client = FakeClient()
    ctx = Context(client, Images("http://127.0.0.1:1"))  # type: ignore[arg-type]
    win = MainWindow(ctx)
    win.resize(1240, 760)
    return win, client


def feed(client: FakeClient, *events: dict[str, Any]) -> None:
    for e in events:
        client.event.emit(e)


def shot(app: QApplication, win: MainWindow, name: str) -> None:
    for _ in range(12):  # deja que la cara anime unos cuadros
        app.processEvents()
        time.sleep(0.03)
    SHOTS.mkdir(parents=True, exist_ok=True)
    win.grab().save(str(SHOTS / f"{name}.png"))


LIVE = (
    {"type": "ready", "assistant": "Vega", "model": "qwen3.5:4b"},
    {
        "type": "actions",
        "items": [
            {
                "id": "apagar",
                "label": "Apagar PC",
                "icon": "power",
                "color": "red",
                "confirm": True,
                "quick": True,
            },
            {
                "id": "play",
                "label": "Play / pausa",
                "icon": "play",
                "color": "orange",
                "confirm": False,
                "quick": True,
            },
            {
                "id": "mute",
                "label": "Silenciar",
                "icon": "mute",
                "color": "orange",
                "confirm": False,
                "quick": True,
            },
            {
                "id": "reiniciar",
                "label": "Reiniciar",
                "icon": "restart",
                "color": "amber",
                "confirm": True,
                "quick": False,
            },
        ],
    },
    {
        "type": "phone_state",
        "online": True,
        "brain": "phone",
        "sleeping": False,
        "mic": True,
        "battery": 91,
        "charging": True,
        "glass": False,
        "touch": "soft",
        "face": "WARM",
        "version": 428887,
    },
    {
        "type": "telemetry",
        "cpu": 21,
        "ram_used": 14.2,
        "ram_total": 32.0,
        "gpu_temp": 62,
        "gpu_util": 8,
        "vram_used": 3.1,
        "vram_total": 8.0,
        "upcoming": [{"kind": "timer", "text": "temporizador de 5 minutos", "due_in": 299}],
    },
    {
        "type": "media",
        "active": True,
        "app": "Spotify",
        "title": "Ricky Bobby",
        "artist": "Eladio Carrión",
        "playing": True,
        "position": 10,
        "duration": 200,
        "art": "",
        "can_previous": True,
        "can_next": True,
    },
    {
        "type": "notifications",
        "items": [
            {
                "id": 3,
                "app": "WhatsApp",
                "title": "Ana",
                "body": "¿Vienes a cenar esta noche? Hay pizza",
                "at": time.time() - 90,
                "icon": "",
            },
            {
                "id": 2,
                "app": "Discord",
                "title": "#general",
                "body": "Luis: ¿alguien para una partida?",
                "at": time.time() - 900,
                "icon": "",
            },
        ],
    },
    {
        "type": "history",
        "messages": [
            {"role": "user", "content": "¿Cómo está el PC?"},
            {"role": "assistant", "content": "El PC va bien: procesador al 21 %."},
        ],
    },
)


def test_panel_shows_what_happens_on_the_phone(
    app: QApplication, window: tuple[MainWindow, FakeClient]
) -> None:
    win, client = window
    feed(client, *LIVE)
    panel = win.pages.widget(0)
    assert panel.phone_info.text().startswith("Despierta")  # type: ignore[attr-defined]
    assert "teléfono" in panel.phone_info.text()  # type: ignore[attr-defined]
    assert panel.brain_btn.text() == "IA: teléfono"  # type: ignore[attr-defined]
    assert panel.song.text() == "Ricky Bobby"  # type: ignore[attr-defined]
    assert "El PC va bien" in panel.answer.text()  # type: ignore[attr-defined]
    assert panel.rows["cpu"][0].text() == "21 %"  # type: ignore[attr-defined]
    assert "5 minutos" in panel.upcoming.text()  # type: ignore[attr-defined]
    shot(app, win, "1_panel")


def test_buttons_control_the_phone(window: tuple[MainWindow, FakeClient]) -> None:
    win, client = window
    feed(client, *LIVE)
    panel = win.pages.widget(0)
    panel.sleep_btn.click()  # type: ignore[attr-defined]
    panel.brain_btn.click()  # type: ignore[attr-defined]
    panel.glass_btn.click()  # type: ignore[attr-defined]
    assert [m["command"] for m in client.sent if m["type"] == "phone_control"] == [
        "sleep",
        "brain_pc",
        "glass_on",
    ]
    feed(client, {"type": "phone_state", "online": True, "sleeping": True})
    assert panel.sleep_btn.text() == "Despertar"  # type: ignore[attr-defined]
    panel.sleep_btn.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "phone_control", "command": "wake"}
    feed(client, {"type": "phone_state", "online": False})
    assert not panel.sleep_btn.isEnabled()  # type: ignore[attr-defined]


def test_pressing_keys_notifications_and_confirmation(window: tuple[MainWindow, FakeClient]) -> None:
    win, client = window
    feed(client, *LIVE)
    panel = win.pages.widget(0)
    keys = [panel.keys_row.itemAt(i).widget() for i in range(panel.keys_row.count())]  # type: ignore[attr-defined]
    assert [k.text() for k in keys] == ["Apagar PC", "Play / pausa", "Silenciar"]
    keys[0].click()
    assert client.sent[-1] == {"type": "action", "id": "apagar"}
    feed(
        client,
        {
            "type": "confirm",
            "nonce": "n1",
            "id": "apagar",
            "message": "¿Seguro? Pulsa de nuevo.",
            "expires_in": 10,
        },
    )
    assert panel.confirm_bar.isVisibleTo(panel) and win.ctx.state.mood == "alert"  # type: ignore[attr-defined]
    panel.confirm_yes.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "action_confirm", "nonce": "n1"}
    panel.clear_notes.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "notification_dismiss"}
    panel.play_btn.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "media_control", "command": "play_pause"}


def test_chat_and_losing_the_server(app: QApplication, window: tuple[MainWindow, FakeClient]) -> None:
    win, client = window
    feed(client, *LIVE)
    chat = win.pages.widget(1)
    chat.input.setText("hola Vega")  # type: ignore[attr-defined]
    chat.send.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "chat", "text": "hola Vega"}
    assert "hola Vega" in chat.view.toPlainText()  # type: ignore[attr-defined]
    win.show_page(1)
    shot(app, win, "2_conversacion")
    client.connection.emit(False)
    assert win.ctx.state.mood == "offline" and "parado" in win.link_text.text()
    win.show_page(0)
    shot(app, win, "3_sin_servidor")


def test_buttons_page_lists_the_real_actions(
    app: QApplication, window: tuple[MainWindow, FakeClient]
) -> None:
    win, _ = window
    page = win.pages.widget(2)
    assert page.table.rowCount() >= 10  # type: ignore[attr-defined]
    win.show_page(2)
    shot(app, win, "4_botones")
    win.show_page(3)
    shot(app, win, "7_coche")
    win.show_page(5)
    shot(app, win, "8_mantenimiento")
    win.show_page(6)
    shot(app, win, "5_ajustes")


def test_buttons_page_slots_choose_the_phone_row(
    app: QApplication, window: tuple[MainWindow, FakeClient]
) -> None:
    win, _ = window
    page = win.pages.widget(2)
    page.rows = [  # type: ignore[attr-defined]
        {"id": "a", "label": "A", "kind": "keys", "arg": "x", "quick": True},
        {"id": "b", "label": "B", "kind": "keys", "arg": "y", "quick": False},
        {"id": "c", "label": "C", "kind": "keys", "arg": "z", "quick": True},
    ]
    page._paint()  # type: ignore[attr-defined]
    texts = [b.text() for b in page.slot_main]  # type: ignore[attr-defined]
    assert texts == ["A", "C", "+ Vacío", "+ Vacío", "+ Vacío", "+ Vacío"]
    assert not page.save_btn.isEnabled()  # type: ignore[attr-defined]
    page.put(2, 1)  # type: ignore[attr-defined]  # «B» al tercer hueco
    assert [b.text() for b in page.slot_main][:3] == ["A", "C", "B"]  # type: ignore[attr-defined]
    page.move_slot(2, -1)  # type: ignore[attr-defined]
    assert [b.text() for b in page.slot_main][:3] == ["A", "B", "C"]  # type: ignore[attr-defined]
    page.clear_slot(0)  # type: ignore[attr-defined]
    assert [b.text() for b in page.slot_main][:3] == ["B", "C", "+ Vacío"]  # type: ignore[attr-defined]
    assert page.save_btn.isEnabled() and page.dirty  # type: ignore[attr-defined]
    win.show_page(2)
    shot(app, win, "4_botones_huecos")


def test_maintenance_page_frees_ram_and_cleans_disk_after_confirming(
    app: QApplication, window: tuple[MainWindow, FakeClient], monkeypatch: pytest.MonkeyPatch
) -> None:
    from PySide6.QtWidgets import QMessageBox

    win, client = window
    feed(client, *LIVE)
    client.connection.emit(True)
    page = win.pages.widget(5)
    assert client.sent[-1] == {"type": "maintenance", "op": "status"}  # al conectar pide el estado
    feed(
        client,
        {
            "type": "maintenance_result", "op": "status", "ok": True, "message": "",
            "data": {
                "total_mb": 32_000, "free_mb": 11_500, "vega_mb": 520, "ollama_mb": 74,
                "ollama_models": ["qwen3.5:4b"], "top": [{"name": "opera.exe", "mb": 13_000}],
            },
        },
    )  # fmt: skip
    assert "11,2 GB" in page.ram_line.text() and "qwen3.5:4b" in page.ram_line.text()  # type: ignore[attr-defined]
    assert "opera.exe" in page.ram_top.text()  # type: ignore[attr-defined]

    page.deep.setChecked(True)  # type: ignore[attr-defined]
    page.free_btn.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "maintenance", "op": "free_ram", "deep": True}
    feed(client, {"type": "maintenance_result", "op": "free_ram", "ok": True,
                  "message": "Libres ahora: 14,6 GB.", "data": {}})  # fmt: skip
    assert page.ram_result.text() == "Libres ahora: 14,6 GB."  # type: ignore[attr-defined]

    page.scan_btn.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "maintenance", "op": "disk_scan"}
    cats = [
        {"id": "logs", "label": "Registros viejos", "size": 5_000_000, "files": 4, "note": "Se vuelven a crear.",  # noqa: E501
         "default": True, "examples": ["server.log.1"]},
        {"id": "cache-gradle", "label": "Caché de Gradle", "size": 3 * 2**30, "files": 900,
         "note": "Se vuelve a bajar.", "default": False, "examples": ["caches"]},
    ]  # fmt: skip
    feed(client, {"type": "maintenance_result", "op": "disk_scan", "ok": True, "message": "",
                  "data": {"categories": cats}})  # fmt: skip
    assert page.selected() == ["logs"]  # type: ignore[attr-defined]  # solo lo que se regenera al momento va marcado
    assert page.clean_btn.isEnabled()  # type: ignore[attr-defined]
    win.show_page(5)
    shot(app, win, "8_mantenimiento_disco")

    sent = len(client.sent)
    monkeypatch.setattr(QMessageBox, "question", staticmethod(lambda *a, **k: QMessageBox.StandardButton.No))
    page.clean_btn.click()  # type: ignore[attr-defined]
    assert len(client.sent) == sent  # sin «sí» no se borra nada
    monkeypatch.setattr(QMessageBox, "question", staticmethod(lambda *a, **k: QMessageBox.StandardButton.Yes))
    page.clean_btn.click()  # type: ignore[attr-defined]
    assert client.sent[-1] == {"type": "maintenance", "op": "disk_clean", "ids": ["logs"]}


def test_maintenance_page_without_server_is_disabled(
    window: tuple[MainWindow, FakeClient],
) -> None:
    win, client = window
    client.connection.emit(False)
    page = win.pages.widget(5)
    assert not page.free_btn.isEnabled() and not page.scan_btn.isEnabled()  # type: ignore[attr-defined]


def test_routine_dialog_builds_steps_and_validates_them(app: QApplication) -> None:
    from desktop.pages.buttons import ActionDialog

    others = [
        {"id": "silenciar", "label": "Silenciar", "kind": "keys", "arg": "volume_mute"},
        {"id": "bloquear", "label": "Bloquear", "kind": "power", "arg": "lock"},
        {"id": "apagar", "label": "Apagar PC", "kind": "power", "arg": "shutdown", "confirm": True},
    ]
    d = ActionDialog(None, None, others)
    d.id.setText("modo-noche")
    d.label.setText("Modo noche")
    d.kind.setCurrentText("routine")
    d._add_step("silenciar")
    d._add_step("espera 2")
    d._add_step("bloquear")
    d._accept()
    assert d.error.text() == ""
    assert d.result_action["steps"] == ["silenciar", "wait:2", "bloquear"]
    assert d.result_action["arg"] == "" and d.result_action["args"] == []

    # Un paso que no existe se rechaza al momento, con un mensaje claro.
    bad = ActionDialog(None, None, others)
    bad.id.setText("x")
    bad.label.setText("X")
    bad.kind.setCurrentText("routine")
    bad._add_step("fantasma")
    bad._accept()
    assert "no existe" in bad.error.text() and not bad.result_action

    # Si incluye un botón que pide confirmación, la rutina también debe pedirla.
    risky = ActionDialog(None, None, others)
    risky.id.setText("y")
    risky.label.setText("Y")
    risky.kind.setCurrentText("routine")
    risky._add_step("apagar")
    risky._accept()
    assert "confirmación" in risky.error.text()
    risky.confirm.setChecked(True)
    risky._accept()
    assert risky.result_action["confirm"] is True

    # Al editarla de nuevo, las esperas se leen como «espera N».
    again = ActionDialog(d.result_action, None, others)
    assert again.steps.toPlainText().splitlines() == ["silenciar", "espera 2", "bloquear"]


def test_settings_weather_needs_a_chosen_city_and_saves_coordinates(
    app: QApplication, window: tuple[MainWindow, FakeClient], tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import shutil

    from desktop import config_edit
    from server.skills.weather import Place

    cfg = tmp_path / "assistant.toml"
    shutil.copy(config_edit.SETTINGS_FILE, cfg)
    monkeypatch.setattr(config_edit, "SETTINGS_FILE", cfg)
    monkeypatch.setattr(
        config_edit, "write_settings", lambda v, path=cfg, _real=config_edit.write_settings: _real(v, path)
    )
    monkeypatch.setattr(
        config_edit, "read_setting", lambda s, k, path=cfg, _real=config_edit.read_setting: _real(s, k, path)
    )
    win, _ = window
    page = win.pages.widget(6)  # Ajustes
    page.load()  # type: ignore[attr-defined]
    assert not page.weather_on.isChecked() and "Aún no hay ciudad" in page.weather_state.text()  # type: ignore[attr-defined]

    # Activarlo sin elegir ciudad no activa nada (no habría coordenadas que enviar).
    page.weather_on.setChecked(True)  # type: ignore[attr-defined]
    page.save()  # type: ignore[attr-defined]
    assert config_edit.read_setting("weather", "enabled", cfg) is False

    # Se elige una de las ciudades que devuelve la búsqueda.
    page._cities_found([Place("Sevilla", "Andalucía", "España", 37.3891, -5.9845)])  # type: ignore[attr-defined]
    assert page.city.text() == "Sevilla" and "37.39" in page.weather_state.text()  # type: ignore[attr-defined]
    page.save()  # type: ignore[attr-defined]
    assert config_edit.read_setting("weather", "enabled", cfg) is True
    assert config_edit.read_setting("weather", "latitude", cfg) == 37.3891
    # El servidor acepta lo que se guardó.
    from server.core.config import load_settings

    assert load_settings(cfg).weather.ready

    # Una búsqueda sin resultados lo dice.
    page._cities_found([])  # type: ignore[attr-defined]
    assert "No encontré" in page.weather_state.text()  # type: ignore[attr-defined]


def test_day_and_night_like_the_phone(app: QApplication, window: tuple[MainWindow, FakeClient]) -> None:
    """De día papel y tinta; de noche, tinta y luz (los mismos colores que el teléfono)."""
    win, client = window
    feed(client, *LIVE)
    win.show()
    try:
        for name, pal in (("9_dia", theme.DAY), ("9_noche", theme.NIGHT)):
            theme.use(pal)
            app.setStyleSheet(theme.STYLE)
            theme.signals.changed.emit()
            shot(app, win, name)
            panel = win.pages.widget(0)
            assert (
                panel.face_tile._goal.name()
                == (  # type: ignore[attr-defined]
                    theme.NIGHT.tile if pal.night else theme.face_accent("idle")
                ).lower()
            )
    finally:
        theme.use(theme.DAY)
        app.setStyleSheet(theme.STYLE)
        win.hide()


def test_theme_follows_the_hour_unless_chosen() -> None:
    assert theme.pal_for(8) is theme.DAY and theme.pal_for(19) is theme.DAY
    assert theme.pal_for(20) is theme.NIGHT and theme.pal_for(3) is theme.NIGHT
    assert theme.pal_for(3, chosen=1) is theme.DAY and theme.pal_for(12, chosen=2) is theme.NIGHT


def test_accent_text_is_readable_on_the_tiles() -> None:
    for pal in (theme.DAY, theme.NIGHT):
        theme.use(pal)
        for accent in (pal.sun, pal.coral, pal.mint, pal.red, pal.sky):
            assert theme.contrast(theme.readable(accent), pal.tile) >= 4.5
    theme.use(theme.DAY)
