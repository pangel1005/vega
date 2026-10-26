"""La app del PC pide mantenimiento por el WebSocket; el teléfono no puede."""

from typing import Any

import pytest
from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.core.maintenance import CleanResult, DiskCategory, FreeResult, RamProc, RamStatus
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat
from server.tests.test_actions import FakeExecutor
from server.tests.test_wake import ACTIONS, TOKEN, until
from server.tools.actions import ActionDef, ActionRunner
from server.tools.windows import WindowsExecutor


class FakeMaintenance:
    """Un mantenimiento de mentira: apunta lo que se le pide y no toca nada del PC."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, object]] = []

    def ram_status(self) -> RamStatus:
        self.calls.append(("status", None))
        return RamStatus(32_000, 11_500, 520, 74, ["qwen3.5:4b"], [RamProc("opera.exe", 13_000)])

    def free_ram(self, deep: bool = False) -> FreeResult:
        self.calls.append(("free_ram", deep))
        return FreeResult(11_000, 14_000, ["Ollama soltó qwen3.5:4b (3,0 GB)."])

    def disk_scan(self) -> list[DiskCategory]:
        self.calls.append(("scan", None))
        return [
            DiskCategory("logs", "Registros viejos", 2_000, 3, "Se vuelven a crear.", True, ["a.log.1"]),
            DiskCategory(
                "cache-gradle", "Caché de Gradle", 9_000_000_000, 90, "Se vuelve a bajar.", False, ["caches"]
            ),
        ]

    def disk_clean(self, ids: list[str]) -> CleanResult:
        self.calls.append(("clean", list(ids)))
        if "nope" in ids:
            raise ValueError("Categoría desconocida: nope")
        return CleanResult(freed=2_000, removed=3, categories=list(ids))


def make_app(maint: FakeMaintenance | None) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    return create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        actions=ActionRunner(ACTIONS, FakeExecutor()),
        maintenance=maint,  # type: ignore[arg-type]
    )


def test_el_escritorio_pide_y_recibe_solo_el(tmp_path: Any) -> None:
    maint = FakeMaintenance()
    phone: Any
    desk: Any
    with (
        TestClient(make_app(maint), base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as phone,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as desk,
    ):
        phone.send_json({"type": "hello", "token": TOKEN})
        until(phone, "ready")
        desk.send_json({"type": "hello", "token": TOKEN, "role": "desktop"})
        until(desk, "ready")

        desk.send_json({"type": "maintenance", "op": "status"})
        status = until(desk, "maintenance_result")[-1]
        assert (
            status["ok"]
            and status["data"]["ollama_models"] == ["qwen3.5:4b"]
            and status["data"]["free_mb"] == 11_500
        )

        desk.send_json({"type": "maintenance", "op": "free_ram", "deep": True})
        freed = until(desk, "maintenance_result")[-1]
        assert freed["data"]["freed_mb"] == 3_000 and "Libres ahora" in freed["message"]

        desk.send_json({"type": "maintenance", "op": "disk_scan"})
        cats = until(desk, "maintenance_result")[-1]["data"]["categories"]
        assert [(c["id"], c["default"]) for c in cats] == [("logs", True), ("cache-gradle", False)]

        desk.send_json({"type": "maintenance", "op": "disk_clean", "ids": ["logs"]})
        assert until(desk, "maintenance_result")[-1]["data"]["categories"] == ["logs"]

        desk.send_json({"type": "maintenance", "op": "disk_clean", "ids": ["nope"]})
        bad = until(desk, "maintenance_result")[-1]
        assert not bad["ok"] and "desconocida" in bad["message"]

        # El teléfono no puede pedir mantenimiento: se ignora, sin respuesta ni efecto.
        before = len(maint.calls)
        phone.send_json({"type": "maintenance", "op": "free_ram"})
        phone.send_json({"type": "chat", "text": "hola"})  # algo que sí contesta, para saber que siguió vivo
        until(phone, "done")
        assert len(maint.calls) == before


def test_sin_mantenimiento_se_dice_claro() -> None:
    desk: Any
    with (
        TestClient(make_app(None), base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as desk,
    ):
        desk.send_json({"type": "hello", "token": TOKEN, "role": "desktop"})
        until(desk, "ready")
        desk.send_json({"type": "maintenance", "op": "free_ram"})
        result = until(desk, "maintenance_result")[-1]
        assert not result["ok"] and "no está disponible" in result["message"]


def test_peticiones_malformadas_no_tiran_la_conexion() -> None:
    desk: Any
    with (
        TestClient(make_app(FakeMaintenance()), base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as desk,
    ):
        desk.send_json({"type": "hello", "token": TOKEN, "role": "desktop"})
        until(desk, "ready")
        desk.send_json({"type": "maintenance", "op": "formatear"})  # operación inventada
        desk.send_json({"type": "maintenance", "op": "disk_clean", "ids": ["x"] * 11})  # demasiadas
        desk.send_json({"type": "maintenance", "op": "status"})
        assert until(desk, "maintenance_result")[-1]["ok"]


# ---------- Botones del teléfono y de voz ----------


def test_el_boton_de_liberar_ram_y_el_de_limpiar_disco_solo_hacen_lo_seguro() -> None:
    maint = FakeMaintenance()
    executor = WindowsExecutor(maint)  # type: ignore[arg-type]
    ram = ActionDef(id="liberar-ram", label="Liberar RAM", kind="maintenance", arg="free-ram")
    disk = ActionDef(
        id="limpiar-disco", label="Limpiar disco", kind="maintenance", arg="clean-disk", confirm=True
    )
    assert "Libres ahora" in executor.run(ram)
    assert ("free_ram", False) in maint.calls  # sin «a fondo»
    assert "Liberé" in executor.run(disk)
    # Solo la categoría marcada de serie (los logs); la caché de Gradle, que se vuelve a bajar, no.
    assert ("clean", ["logs"]) in maint.calls


def test_mantenimiento_desconocido_se_rechaza() -> None:
    with pytest.raises(ValueError, match="desconocido"):
        ActionDef(id="x", label="X", kind="maintenance", arg="formatear")
