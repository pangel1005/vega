"""Panel de botones: registro cerrado, confirmación de un solo uso y auditoría.

Ninguna prueba ejecuta nada en el PC: se usa un ejecutor de mentira.
"""

from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat
from server.tools.actions import (
    ACTIONS_FILE,
    CONFIRM_SECONDS,
    ActionConfigError,
    ActionDef,
    ActionFailed,
    ActionRunner,
    load_actions,
)
from server.tools.keys import unknown_keys


class FakeExecutor:
    def __init__(self, fail: str | None = None) -> None:
        self.ran: list[str] = []
        self.fail = fail

    def run(self, action: ActionDef) -> str:
        if self.fail:
            raise ActionFailed(self.fail)
        self.ran.append(action.id)
        return f"{action.label}: hecho."


class Clock:
    def __init__(self) -> None:
        self.t = 100.0

    def __call__(self) -> float:
        return self.t


POWER = ActionDef(id="apagar", label="Apagar PC", kind="power", arg="shutdown", confirm=True)
MUTE = ActionDef(id="silenciar", label="Silenciar", kind="keys", arg="volume_mute")


def runner(executor: FakeExecutor, clock: Clock | None = None) -> ActionRunner:
    return ActionRunner([POWER, MUTE], executor, clock or Clock())


# --- Configuración ---


def test_project_actions_file_is_valid() -> None:
    actions = load_actions(ACTIONS_FILE)
    ids = {a.id for a in actions}
    assert {"apagar", "reiniciar", "cancelar-apagado", "grabar"} <= ids
    assert all(a.confirm for a in actions if a.kind == "power" and a.arg in ("shutdown", "restart", "sleep"))


def write(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "actions.toml"
    path.write_text(text, encoding="utf-8")
    return path


@pytest.mark.parametrize(
    ("block", "problem"),
    [
        ('id = "x"\nlabel = "X"\nkind = "keys"\narg = "win+tecla_rara"', "tecla_rara"),
        ('id = "x"\nlabel = "X"\nkind = "power"\narg = "formatear"', "formatear"),
        ('id = "x"\nlabel = "X"\nkind = "shell"\narg = "del /s"', "kind"),
        ('id = "X Y"\nlabel = "X"\nkind = "keys"\narg = "a"', "id"),
        ('id = "x"\nlabel = "X"\nkind = "keys"\narg = "a"\ncomando = "rm"', "comando"),
    ],
)
def test_invalid_actions_are_rejected(tmp_path: Path, block: str, problem: str) -> None:
    with pytest.raises(ActionConfigError, match=problem):
        load_actions(write(tmp_path, f"[[action]]\n{block}\n"))


def test_duplicate_ids_are_rejected(tmp_path: Path) -> None:
    block = '[[action]]\nid = "x"\nlabel = "X"\nkind = "keys"\narg = "a"\n'
    with pytest.raises(ActionConfigError, match="repetidas"):
        load_actions(write(tmp_path, block * 2))


def test_public_view_hides_paths() -> None:
    app = ActionDef(id="app", label="App", kind="launch", arg="C:\\secreto\\app.exe", args=["--x"])
    assert "arg" not in app.public() and "args" not in app.public()


def test_key_names() -> None:
    assert unknown_keys("win+alt+r") == []
    assert unknown_keys("ctrl+nada") == ["nada"]


# --- Ejecución ---


def test_simple_action_runs_once() -> None:
    ex = FakeExecutor()
    outcome = runner(ex).request("silenciar")
    assert outcome.kind == "done" and ex.ran == ["silenciar"]


def test_unknown_action_never_runs() -> None:
    ex = FakeExecutor()
    assert runner(ex).request("formatear-disco").kind == "error"
    assert ex.ran == []


def test_sensitive_action_needs_single_use_confirmation() -> None:
    ex = FakeExecutor()
    r = runner(ex)
    asked = r.request("apagar")
    assert asked.kind == "confirm" and asked.nonce and ex.ran == []
    assert r.confirm(asked.nonce).kind == "done"
    assert ex.ran == ["apagar"]
    assert r.confirm(asked.nonce).kind == "error"  # el código no sirve dos veces
    assert ex.ran == ["apagar"]


def test_confirmation_expires() -> None:
    ex, clock = FakeExecutor(), Clock()
    r = runner(ex, clock)
    asked = r.request("apagar")
    clock.t += CONFIRM_SECONDS + 0.1
    assert asked.nonce and r.confirm(asked.nonce).kind == "error"
    assert ex.ran == []


def test_repeated_presses_are_debounced() -> None:
    ex, clock = FakeExecutor(), Clock()
    r = runner(ex, clock)
    assert r.request("silenciar").kind == "done"
    assert r.request("silenciar").kind == "ignored"
    clock.t += 1.5
    assert r.request("silenciar").kind == "done"
    assert ex.ran == ["silenciar", "silenciar"]


def test_executor_failure_is_reported() -> None:
    assert runner(FakeExecutor(fail="No había apagado pendiente.")).request("silenciar").message == (
        "No había apagado pendiente."
    )


def test_attempts_are_audited(caplog: pytest.LogCaptureFixture) -> None:
    with caplog.at_level("INFO", logger="audit"):
        r = runner(FakeExecutor())
        r.request("formatear-disco")
        r.request("silenciar")
    lines = [rec.getMessage() for rec in caplog.records if rec.name == "audit"]
    assert any('"rechazada"' in line and "formatear-disco" in line for line in lines)
    assert any('"ejecutada"' in line and "silenciar" in line for line in lines)


# --- Por WebSocket ---

TOKEN = "t" * 43


def ws_session(ex: FakeExecutor) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Prueba"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        actions=runner(ex),
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def test_panel_over_websocket() -> None:
    ex = FakeExecutor()
    with ws_session(ex) as ws:
        ws.send_json({"type": "hello", "token": TOKEN})
        assert ws.receive_json()["type"] == "ready"
        actions = ws.receive_json()
        assert actions["type"] == "actions"
        assert [a["id"] for a in actions["items"]] == ["apagar", "silenciar"]
        assert ws.receive_json()["type"] == "history"

        ws.send_json({"type": "action", "id": "silenciar"})
        assert ws.receive_json() == {"type": "state", "state": "acting"}
        assert ws.receive_json() == {
            "type": "action_result",
            "id": "silenciar",
            "ok": True,
            "message": "Silenciar: hecho.",
        }
        assert ws.receive_json() == {"type": "state", "state": "idle"}

        ws.send_json({"type": "action", "id": "apagar"})
        confirm = ws.receive_json()
        assert confirm["type"] == "confirm" and confirm["id"] == "apagar" and confirm["expires_in"] == 10
        assert ex.ran == ["silenciar"]  # todavía no se apagó nada

        ws.send_json({"type": "action_confirm", "nonce": confirm["nonce"]})
        ws.receive_json()  # acting
        assert ws.receive_json()["ok"] is True
    assert ex.ran == ["silenciar", "apagar"]
