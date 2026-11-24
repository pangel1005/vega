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


def test_phone_voice_starts_with_the_first_sentence() -> None:
    """La voz para el teléfono se genera frase a frase: la primera suena mientras se hace la siguiente."""
    import asyncio
    from typing import Any

    from server.core.orchestrator import Orchestrator
    from server.memory.store import ConversationStore
    from server.tests.fakes import FakeChat

    said: list[str] = []

    class Voice:
        sample_rate = 16000
        voice = "x"

        def synthesize(self, text: str) -> bytes:
            said.append(text)
            return b"\0\0" * 160

        def voices(self) -> list[Any]:
            return []

    async def emit(_: Any) -> None: ...

    async def emit_audio(*_: Any) -> None: ...

    orch = Orchestrator(
        FakeChat(pieces=["x"]),
        "Eres Vega.",
        ConversationStore(":memory:"),
        emit,
        tts=Voice(),  # type: ignore[arg-type]
        emit_audio=emit_audio,
    )
    text = "El PC va bien: procesador al 21 %. La gráfica está a 62 grados y con 7 de 8 GB ocupados."
    asyncio.run(orch.say_for_phone(text))
    assert len(said) >= 2 and " ".join(said).replace("  ", " ").startswith("El PC va bien")


def test_short_kokoro_phrases_are_made_once(tmp_path: Path) -> None:
    from server.speech import tts

    k = tts.KokoroTTS(tmp_path)
    made: list[str] = []

    def create(text: str, voice: str) -> tuple[bytes, int]:
        made.append(text)
        return b"\0\0", 24000

    k._create = create  # type: ignore[method-assign,assignment]
    for _ in range(3):
        k.synthesize("Hecho.", "kokoro:ef_dora")
    k.synthesize("Una frase bastante más larga que no se guarda en la caché.", "kokoro:ef_dora")
    k.synthesize("Una frase bastante más larga que no se guarda en la caché.", "kokoro:ef_dora")
    assert made.count("Hecho.") == 1 and len(made) == 3
