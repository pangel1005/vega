"""Vega en otro PC: ajustes de serie, hilos automáticos y botones de programas que no están."""

from __future__ import annotations

from pathlib import Path

from server.core.config import EXAMPLE_CONFIG, ensure_user_config, load_settings
from server.tools.actions import ActionDef, installed, only_installed


def test_the_example_config_is_valid_and_has_the_personal_things_off() -> None:
    s = load_settings(EXAMPLE_CONFIG)
    assert not s.telegram.enabled and not s.moodle.enabled and not s.camera.enabled
    assert s.llm.threads == 0 and 2 <= s.llm.cpu_threads <= 8


def test_the_user_config_is_born_from_the_example_once(tmp_path: Path) -> None:
    example = tmp_path / "assistant.example.toml"
    example.write_text('[assistant]\nname = "Vega"\n', encoding="utf-8")
    mine = tmp_path / "assistant.toml"
    assert ensure_user_config(mine, example) is True
    assert mine.read_text(encoding="utf-8") == example.read_text(encoding="utf-8")
    mine.write_text('[assistant]\nname = "Nova"\n', encoding="utf-8")
    assert ensure_user_config(mine, example) is False  # nunca pisa tus ajustes
    assert "Nova" in mine.read_text(encoding="utf-8")


def launch(id_: str, arg: str) -> ActionDef:
    return ActionDef(id=id_, label=id_, kind="launch", arg=arg)


def test_buttons_for_programs_that_are_not_installed_are_hidden() -> None:
    here = {Path(r"C:\Apps\discord.exe")}

    def exists(p: Path) -> bool:
        return p in here

    discord = launch("discord", r"C:\Apps\discord.exe")
    opera = launch("opera", r"C:\Apps\opera.exe")
    notepad = launch("notas", "notepad.exe")  # se busca en el PATH: no se puede saber, se deja
    lock = ActionDef(id="bloquear", label="Bloquear", kind="power", arg="lock")
    routine = ActionDef(id="noche", label="Noche", kind="routine", steps=["opera", "bloquear"])
    kept = only_installed([discord, opera, notepad, lock, routine], exists)
    assert [a.id for a in kept] == ["discord", "notas", "bloquear"]  # la rutina usaba Opera: fuera también
    assert installed(lock, exists) and not installed(opera, exists)


def test_the_real_buttons_file_loads() -> None:
    from server.tools.actions import load_actions

    assert any(a.id == "apagar" for a in load_actions())


def test_http_clients_share_one_ssl_context() -> None:
    import asyncio

    from server.core import http

    asyncio.run(http.warm())
    first = http.ssl_context()
    assert http.ssl_context() is first  # se crea una vez, no en cada cliente

    async def make() -> None:
        async with http.async_client(timeout=1) as client:
            assert client is not None

    asyncio.run(make())
    assert http.ssl_context() is first
