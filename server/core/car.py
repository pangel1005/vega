"""La pantalla de Vega en Android Auto: qué casillas tiene y qué hace cada una.

Se escribe en `config/car.toml` (la app del PC lo edita). Igual que con los botones del panel, solo
existe lo que está en la lista de abajo: ni el teléfono ni el coche pueden pedir otra cosa.

- `builtin`: algo de Vega: dormir/despertar, cambiar de IA, pausa, siguiente, lista de avisos del
  PC o estado del PC.
- `action`: un botón de `config/actions.toml` (por su id). Los que piden confirmación la piden
  también en el coche.
"""

from __future__ import annotations

import contextlib
import tomllib
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from server.core.config import ROOT
from server.ws.protocol import CarItemData, CarLayoutEvent

CAR_FILE = ROOT / "config" / "car.toml"
BUILTINS = ("sleep", "brain", "media", "next", "notifications", "pc", "vehicle")
MAX_ITEMS = 6  # lo que cabe en una cuadrícula de Android Auto mientras se conduce


class CarItem(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str = Field(pattern=r"^[a-z0-9-]{1,30}$")
    label: str = Field(min_length=1, max_length=14)
    kind: Literal["builtin", "action"]
    arg: str = Field(min_length=1)


class CarError(Exception):
    """El archivo no es válido (el mensaje se muestra al usuario)."""


def load_car(path: Path = CAR_FILE, known_actions: set[str] | None = None) -> tuple[str, list[CarItem]]:
    """Lee y valida la pantalla del coche. `known_actions`: ids válidos de los botones del PC."""
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise CarError(f"No pude leer {path.name}: {exc}") from exc
    except tomllib.TOMLDecodeError as exc:
        raise CarError(f"{path.name} no es TOML válido: {exc}") from exc
    title = str(data.get("title", "Vega"))[:20] or "Vega"
    items: list[CarItem] = []
    for raw in data.get("item", []):
        try:
            item = CarItem(**raw)
        except ValidationError as exc:
            first = exc.errors()[0]
            raise CarError(
                f"Casilla «{raw.get('label', raw.get('id'))}»: {first['loc'][0]}: {first['msg']}"
            ) from exc
        if item.kind == "builtin" and item.arg not in BUILTINS:
            raise CarError(f"Casilla «{item.label}»: «{item.arg}» no existe (hay: {', '.join(BUILTINS)}).")
        if item.kind == "action" and known_actions is not None and item.arg not in known_actions:
            raise CarError(f"Casilla «{item.label}»: el botón «{item.arg}» no está en actions.toml.")
        if any(i.id == item.id for i in items):
            raise CarError(f"Hay dos casillas con el id «{item.id}».")
        items.append(item)
    if len(items) > MAX_ITEMS:
        raise CarError(f"Solo caben {MAX_ITEMS} casillas en la pantalla del coche (hay {len(items)}).")
    return title, items


class CarConfig:
    """La pantalla del coche cargada, lista para mandarla; se puede volver a leer sin reiniciar."""

    def __init__(self, path: Path = CAR_FILE, known_actions: set[str] | None = None) -> None:
        self._path = path
        self._known = known_actions
        self.title = "Vega"
        self.items: list[CarItem] = []
        with contextlib.suppress(CarError):  # sin car.toml (o roto) el teléfono usa su pantalla de serie
            self.reload()

    def reload(self) -> None:
        self.title, self.items = load_car(self._path, self._known)

    def event(self) -> CarLayoutEvent:
        return CarLayoutEvent(
            title=self.title,
            items=[CarItemData(id=i.id, label=i.label, kind=i.kind, arg=i.arg) for i in self.items],
        )
