from pathlib import Path
from typing import Any

from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.car import CarConfig
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat
from server.tests.test_actions import FakeExecutor
from server.tests.test_wake import ACTIONS, TOKEN, until
from server.tools.actions import ActionRunner

ITEM = '[[item]]\nid = "{id}"\nlabel = "{label}"\nkind = "builtin"\narg = "{arg}"\n'


def test_car_screen_is_sent_on_connect_and_reloaded_for_everyone(tmp_path: Path) -> None:
    p = tmp_path / "car.toml"
    p.write_text('title = "Mi coche"\n' + ITEM.format(id="z", label="Dormir", arg="sleep"), encoding="utf-8")

    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        actions=ActionRunner(ACTIONS, FakeExecutor()),
        car=CarConfig(p),
    )
    phone: Any
    desk: Any
    # `with TestClient`: las dos conexiones comparten un solo bucle de eventos, como en el servidor
    # real (si cada una tuviera el suyo, enviar de una a otra no despertaría a la que espera).
    with (
        TestClient(app, base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as phone,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as desk,
    ):
        phone.send_json({"type": "hello", "token": TOKEN})
        layout = until(phone, "car_layout")[-1]
        assert layout["title"] == "Mi coche" and [i["arg"] for i in layout["items"]] == ["sleep"]
        desk.send_json({"type": "hello", "token": TOKEN, "role": "desktop"})
        until(desk, "car_layout")
        # El escritorio cambia la pantalla y todos reciben la nueva.
        p.write_text(ITEM.format(id="n", label="Siguiente", arg="next"), encoding="utf-8")
        desk.send_json({"type": "reload_car"})
        assert until(phone, "car_layout")[-1]["items"][0]["arg"] == "next"
        assert until(desk, "car_layout")[-1]["items"][0]["arg"] == "next"
        # Un archivo roto no cambia nada y avisa solo a quien lo pidió.
        p.write_text("[[item]\n", encoding="utf-8")
        desk.send_json({"type": "reload_car"})
        assert "TOML" in until(desk, "error")[-1]["message"]
