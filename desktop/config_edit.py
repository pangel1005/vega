"""Leer y escribir config/actions.toml y config/assistant.toml sin perder los comentarios.

Usa `tomlkit`, que conserva comentarios, orden y formato de lo que no se toca. Antes de escribir
guarda una copia (`.bak`) y comprueba que lo nuevo es válido (los botones, con el mismo modelo
que usa el servidor); si no lo es, no escribe nada.
"""

from __future__ import annotations

import shutil
from pathlib import Path
from typing import Any

import tomlkit

ROOT = Path(__file__).resolve().parents[1]
ACTIONS_FILE = ROOT / "config" / "actions.toml"
SETTINGS_FILE = ROOT / "config" / "assistant.toml"
CAR_FILE = ROOT / "config" / "car.toml"

ACTION_FIELDS = (
    "id", "label", "jp", "icon", "color", "kind", "arg", "args", "steps", "confirm", "quick", "say",
)  # fmt: skip


class ConfigError(Exception):
    """Lo que se quiere guardar no es válido (el mensaje se muestra al usuario)."""


def _backup(path: Path) -> None:
    if path.exists():
        shutil.copy2(path, path.with_suffix(path.suffix + ".bak"))


def _write(path: Path, doc: tomlkit.TOMLDocument) -> None:
    _backup(path)
    path.write_text(tomlkit.dumps(doc), encoding="utf-8", newline="\n")


# --- Botones ---


def read_actions(path: Path = ACTIONS_FILE) -> list[dict[str, Any]]:
    doc = tomlkit.parse(path.read_text(encoding="utf-8"))
    return [dict(item.unwrap()) for item in doc.get("action", [])]


def validate_actions(actions: list[dict[str, Any]]) -> None:
    """Las mismas reglas que el servidor (modelo `ActionDef`), con un mensaje legible."""
    from pydantic import ValidationError

    from server.tools.actions import ActionDef, check_routines

    seen: set[str] = set()
    defs: list[ActionDef] = []
    for a in actions:
        try:
            defs.append(ActionDef(**a))
        except ValidationError as exc:
            first = exc.errors()[0]
            where = ".".join(str(p) for p in first["loc"])
            raise ConfigError(f"Botón «{a.get('label') or a.get('id')}»: {where}: {first['msg']}") from exc
        if a["id"] in seen:
            raise ConfigError(f"Hay dos botones con el id «{a['id']}».")
        seen.add(a["id"])
    try:
        check_routines(defs)
    except ValueError as exc:
        raise ConfigError(str(exc)) from exc
    if sum(1 for a in actions if a.get("quick")) > 6:
        raise ConfigError("Solo caben 6 botones en el panel principal (quick); el resto va en «Más».")


def write_actions(actions: list[dict[str, Any]], path: Path = ACTIONS_FILE) -> None:
    """Guarda los botones. Conserva el encabezado con las instrucciones y las secciones
    intermedias del archivo; cada botón se reescribe con los campos de siempre."""
    validate_actions(actions)
    doc = tomlkit.parse(path.read_text(encoding="utf-8"))
    old = {str(t["id"]): t for t in doc.get("action", [])}
    table = tomlkit.aot()
    for a in actions:
        item = old.get(a["id"])
        if item is None:
            item = tomlkit.table()
        # Una rutina lleva «steps» en vez de «arg».
        always = ("id", "label", "kind") if a.get("kind") == "routine" else ("id", "label", "kind", "arg")
        for key in ACTION_FIELDS:
            value = a.get(key)
            if value in (None, "", [], False) and key not in always:
                if key in item:
                    del item[key]
                continue
            item[key] = value
        table.append(item)
    doc["action"] = table
    _write(path, doc)


# --- Ajustes ---


def read_setting(section: str | None, key: str, path: Path = SETTINGS_FILE) -> Any:
    doc = tomlkit.parse(path.read_text(encoding="utf-8"))
    node: Any = doc if section is None else doc.get(section, {})
    return node.get(key)


def write_settings(values: dict[tuple[str | None, str], Any], path: Path = SETTINGS_FILE) -> None:
    """Cambia ajustes sueltos: `{("llm", "model"): "qwen3.5:4b", ("assistant", "name"): "Vega"}`."""
    doc = tomlkit.parse(path.read_text(encoding="utf-8"))
    for (section, key), value in values.items():
        node: Any = doc
        if section is not None:
            if section not in doc:
                doc[section] = tomlkit.table()
            node = doc[section]
        node[key] = value
    # Se comprueba con el cargador del servidor antes de tocar el archivo de verdad.
    probe = path.with_suffix(".probe.toml")
    probe.write_text(tomlkit.dumps(doc), encoding="utf-8")
    try:
        from server.core.config import load_settings

        load_settings(probe)
    except Exception as exc:
        raise ConfigError(f"Ajuste no válido: {exc}") from exc
    finally:
        probe.unlink(missing_ok=True)
    _write(path, doc)


# --- Pantalla del coche (Android Auto) ---


def read_car(path: Path = CAR_FILE) -> tuple[str, list[dict[str, Any]]]:
    doc = tomlkit.parse(path.read_text(encoding="utf-8"))
    return str(doc.get("title", "Vega")), [dict(i.unwrap()) for i in doc.get("item", [])]


def write_car(title: str, items: list[dict[str, Any]], path: Path = CAR_FILE) -> None:
    """Guarda la pantalla del coche conservando el encabezado con las instrucciones. Se comprueba con
    las mismas reglas que el servidor (incluido que los botones del PC existan) antes de escribir."""
    from server.core.car import CarError, load_car

    doc = tomlkit.parse(path.read_text(encoding="utf-8"))
    doc["title"] = title.strip() or "Vega"
    table = tomlkit.aot()
    for it in items:
        row = tomlkit.table()
        for key in ("id", "label", "kind", "arg"):
            row[key] = it[key]
        table.append(row)
    doc["item"] = table
    probe = path.with_suffix(".probe.toml")
    probe.write_text(tomlkit.dumps(doc), encoding="utf-8")
    try:
        known = {a["id"] for a in read_actions()}
        load_car(probe, known)
    except CarError as exc:
        raise ConfigError(str(exc)) from exc
    finally:
        probe.unlink(missing_ok=True)
    _write(path, doc)
