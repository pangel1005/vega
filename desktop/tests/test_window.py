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
    win.show_page(4)
    shot(app, win, "5_ajustes")
