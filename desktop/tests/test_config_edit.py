import shutil
from pathlib import Path

import pytest

from desktop import config_edit
from desktop.config_edit import ConfigError

REAL_ACTIONS = Path(__file__).resolve().parents[2] / "config" / "actions.toml"
REAL_SETTINGS = Path(__file__).resolve().parents[2] / "config" / "assistant.toml"


@pytest.fixture
def actions_file(tmp_path: Path) -> Path:
    target = tmp_path / "actions.toml"
    shutil.copy(REAL_ACTIONS, target)
    return target


def test_reads_the_real_actions_and_they_are_valid(actions_file: Path) -> None:
    actions = config_edit.read_actions(actions_file)
    assert len(actions) >= 10 and any(a["id"] == "apagar" for a in actions)
    config_edit.validate_actions(actions)


def test_editing_keeps_comments_and_makes_a_backup(actions_file: Path) -> None:
    actions = config_edit.read_actions(actions_file)
    original = actions_file.read_text(encoding="utf-8")
    actions[0]["label"] = "Apagar ya"
    config_edit.write_actions(actions, actions_file)
    text = actions_file.read_text(encoding="utf-8")
    assert "SEGURIDAD: solo se puede ejecutar lo que está en este archivo" in text  # el encabezado sigue
    assert 'label = "Apagar ya"' in text
    assert actions_file.with_suffix(".toml.bak").read_text(encoding="utf-8") == original
    assert [a["id"] for a in config_edit.read_actions(actions_file)] == [a["id"] for a in actions]


def test_add_remove_and_reorder(actions_file: Path) -> None:
    actions = config_edit.read_actions(actions_file)
    new = {
        "id": "calculadora",
        "label": "Calculadora",
        "kind": "launch",
        "arg": "calc.exe",
        "say": ["abre la calculadora"],
    }
    config_edit.write_actions([new, *actions[2:]], actions_file)
    after = config_edit.read_actions(actions_file)
    assert after[0]["id"] == "calculadora" and after[0]["say"] == ["abre la calculadora"]
    assert actions[0]["id"] not in [a["id"] for a in after]


def test_invalid_actions_are_refused_without_touching_the_file(actions_file: Path) -> None:
    before = actions_file.read_text(encoding="utf-8")
    actions = config_edit.read_actions(actions_file)
    with pytest.raises(ConfigError, match="Mayúsculas|id"):
        config_edit.write_actions([{**actions[0], "id": "Con Espacios"}], actions_file)
    with pytest.raises(ConfigError, match="dos botones"):
        config_edit.write_actions([actions[0], actions[0]], actions_file)
    with pytest.raises(ConfigError, match="kind"):
        config_edit.write_actions([{**actions[0], "kind": "hackear"}], actions_file)
    with pytest.raises(ConfigError, match="6 botones"):
        config_edit.write_actions([{**a, "quick": True} for a in actions[:8]], actions_file)
    assert actions_file.read_text(encoding="utf-8") == before


def test_settings_are_edited_in_place(tmp_path: Path) -> None:
    target = tmp_path / "assistant.toml"
    shutil.copy(REAL_SETTINGS, target)
    old = config_edit.read_setting("llm", "gpu_layers", target)
    config_edit.write_settings({("llm", "gpu_layers"): 0, ("notifications", "enabled"): False}, target)
    assert config_edit.read_setting("llm", "gpu_layers", target) == 0 != old
    assert config_edit.read_setting("notifications", "enabled", target) is False
    assert "Voz a texto (faster-whisper)" in target.read_text(encoding="utf-8")  # los comentarios siguen
    with pytest.raises(ConfigError):
        config_edit.write_settings({("llm", "gpu_layers"): "mucha"}, target)
    assert config_edit.read_setting("llm", "gpu_layers", target) == 0  # lo inválido no se escribió


REAL_CAR = Path(__file__).resolve().parents[2] / "config" / "car.toml"


def test_car_screen_is_edited_in_place_and_validated(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    target = tmp_path / "car.toml"
    shutil.copy(REAL_CAR, target)
    title, items = config_edit.read_car(target)
    assert title == "Vega" and len(items) == 6
    new = {"id": "vol", "label": "Volumen +", "kind": "action", "arg": "volumen-mas"}
    config_edit.write_car("Mi coche", [new, *items[:5]], target)
    text = target.read_text(encoding="utf-8")
    assert "SEGURIDAD: solo existe lo que está en esta lista" in text and 'title = "Mi coche"' in text
    assert config_edit.read_car(target)[1][0] == new
    assert target.with_suffix(".toml.bak").exists()
    before = target.read_text(encoding="utf-8")
    with pytest.raises(ConfigError, match="no está en actions.toml"):
        config_edit.write_car("Vega", [{**new, "arg": "no-existe"}], target)
    with pytest.raises(ConfigError, match="no existe"):
        config_edit.write_car("Vega", [{**new, "kind": "builtin", "arg": "hackear"}], target)
    with pytest.raises(ConfigError, match="Solo caben 6"):
        config_edit.write_car("Vega", [{**new, "id": f"i{n}"} for n in range(7)], target)
    assert target.read_text(encoding="utf-8") == before


def test_routines_round_trip_and_are_validated_with_the_rest(actions_file: Path) -> None:
    actions = config_edit.read_actions(actions_file)
    mine = {
        "id": "mi-rutina",
        "label": "Mi rutina",
        "kind": "routine",
        "steps": ["silenciar", "wait:2", "bloquear"],
    }
    config_edit.write_actions([*actions, mine], actions_file)
    back = {a["id"]: a for a in config_edit.read_actions(actions_file)}
    assert back["mi-rutina"]["steps"] == ["silenciar", "wait:2", "bloquear"]
    assert "arg" not in back["mi-rutina"]
    # Un paso inexistente o una rutina dentro de otra no se guardan.
    with pytest.raises(ConfigError, match="no existe"):
        config_edit.write_actions([*actions, {**mine, "steps": ["fantasma"]}], actions_file)
    with pytest.raises(ConfigError, match="no puede incluir"):
        config_edit.write_actions(
            [*actions, mine, {**mine, "id": "otra", "steps": ["mi-rutina"]}], actions_file
        )
