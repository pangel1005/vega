from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from server.app import create_app
from server.core.config import DEFAULT_CONFIG, load_settings
from server.memory.store import ConversationStore
from server.tools.actions import ActionDef
from server.tools.app_icons import AppIcons, icon_source


def _launch(id_: str, arg: str, args: list[str] | None = None) -> ActionDef:
    return ActionDef(id=id_, label=id_, kind="launch", arg=arg, args=args or [])


def test_squirrel_apps_use_the_real_exe(tmp_path: Path) -> None:
    (tmp_path / "app-1.0.1").mkdir()
    (tmp_path / "app-1.0.1" / "Discord.exe").write_bytes(b"x")
    update = tmp_path / "Update.exe"
    update.write_bytes(b"x")
    action = _launch("discord", str(update), ["--processStart", "Discord.exe"])
    assert icon_source(action) == tmp_path / "app-1.0.1" / "Discord.exe"
    assert icon_source(_launch("otra", str(update))) == update


def test_missing_or_iconless_exe_gives_no_icon(tmp_path: Path) -> None:
    fake = tmp_path / "fake.exe"
    fake.write_bytes(b"no es un ejecutable")
    icons = AppIcons(
        [_launch("fake", str(fake)), _launch("nada", str(tmp_path / "no.exe"))], tmp_path / "cache"
    )
    assert icons.png("fake") is None
    assert icons.png("nada") is None
    assert icons.png("no-existe") is None
    assert icons.ids() == set()


@pytest.mark.skipif(not Path(r"C:\Windows\explorer.exe").exists(), reason="solo en Windows")
def test_real_windows_exe_gives_png_and_is_served(tmp_path: Path) -> None:
    icons = AppIcons([_launch("explorer", r"C:\Windows\explorer.exe")], tmp_path / "cache")
    png = icons.png("explorer")
    assert png is not None and png.startswith(b"\x89PNG")
    assert (tmp_path / "cache" / "explorer.png").exists()  # queda guardado
    app = create_app(
        load_settings(DEFAULT_CONFIG), token="t", store=ConversationStore(":memory:"), app_icons=icons
    )
    client = TestClient(app, base_url="http://127.0.0.1:8000")
    r = client.get("/app/icons/explorer.png")
    assert r.status_code == 200 and r.headers["content-type"] == "image/png"
    assert client.get("/app/icons/otra.png").status_code == 404
