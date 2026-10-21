from pathlib import Path

import pytest

from server.core.config import DEFAULT_CONFIG, ConfigError, load_settings

VALID = """
[assistant]
name = "Orión"

[llm]
model = "qwen3:8b"
"""


def write(tmp_path: Path, text: str) -> Path:
    path = tmp_path / "assistant.toml"
    path.write_text(text, encoding="utf-8")
    return path


def test_project_config_loads() -> None:
    settings = load_settings(DEFAULT_CONFIG)
    assert settings.assistant.name
    assert settings.server.host == "127.0.0.1"


def test_name_comes_from_file(tmp_path: Path) -> None:
    settings = load_settings(write(tmp_path, VALID))
    assert settings.assistant.name == "Orión"
    assert settings.server.port == 8000


@pytest.mark.parametrize("host", ["0.0.0.0", "192.168.1.10", "localhost", "::"])
def test_rejects_non_loopback_host(tmp_path: Path, host: str) -> None:
    path = write(tmp_path, VALID + f'\n[server]\nhost = "{host}"\n')
    with pytest.raises(ConfigError, match="127.0.0.1"):
        load_settings(path)


def test_missing_file_is_clear_error(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="No encuentro"):
        load_settings(tmp_path / "no-existe.toml")


def test_unknown_keys_are_rejected(tmp_path: Path) -> None:
    with pytest.raises(ConfigError, match="nmae"):
        load_settings(write(tmp_path, VALID.replace("name", "nmae")))


def test_env_var_selects_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ASISTENTE_CONFIG", str(write(tmp_path, VALID)))
    assert load_settings().assistant.name == "Orión"
