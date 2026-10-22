"""Botones del panel: registro cerrado de acciones, confirmación y auditoría.

Reglas de seguridad (las aplica este código, nunca el modelo ni el teléfono):
- Solo existen las acciones de `config/actions.toml`; se piden por `id`.
- Las marcadas `confirm = true` exigen un segundo mensaje con un código de un solo
  uso que caduca a los 10 s.
- Pulsaciones repetidas del mismo botón en menos de 1 s se ignoran.
- Cada intento queda registrado en `logs/audit.log` (una línea JSON por evento).
"""

from __future__ import annotations

import json
import logging
import secrets
import time
import tomllib
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, ValidationError, model_validator

from server.core.config import ROOT
from server.core.text import normalize
from server.tools.keys import unknown_keys

log = logging.getLogger(__name__)
audit = logging.getLogger("audit")

ACTIONS_FILE = ROOT / "config" / "actions.toml"
CONFIRM_SECONDS = 10.0
DEBOUNCE_SECONDS = 0.3  # evita un doble disparo accidental; con 1 s se perdían pulsaciones rápidas (volumen)

PowerArg = Literal["shutdown", "restart", "sleep", "lock", "cancel"]


class ActionConfigError(Exception):
    """El archivo de acciones no existe o no es válido."""


class ActionFailed(Exception):
    """La acción no se pudo ejecutar. El mensaje está pensado para el usuario."""


class ActionDef(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)

    id: str = Field(pattern=r"^[a-z0-9-]{1,40}$")
    label: str = Field(min_length=1, max_length=24)
    jp: str = Field(default="", max_length=6)
    icon: str = "app"
    color: Literal["orange", "red", "amber", "green", "teal"] = "orange"
    kind: Literal["power", "keys", "launch"]
    arg: str = Field(min_length=1)
    args: list[str] = Field(default_factory=list)
    confirm: bool = False
    quick: bool = False  # aparece en el panal principal (los demás, en «MÁS»)
    say: list[str] = Field(default_factory=list)  # frases que lo activan por voz

    @model_validator(mode="after")
    def _check_arg(self) -> ActionDef:
        if self.kind == "power" and self.arg not in ("shutdown", "restart", "sleep", "lock", "cancel"):
            raise ValueError(f"acción de energía desconocida: {self.arg!r}")
        if self.kind == "keys" and (bad := unknown_keys(self.arg)):
            raise ValueError(f"teclas desconocidas en {self.arg!r}: {', '.join(bad)}")
        if self.kind != "launch" and self.args:
            raise ValueError("'args' solo se usa con kind = \"launch\"")
        return self

    def public(self) -> dict[str, object]:
        """Lo que se envía al teléfono (sin rutas ni argumentos)."""
        return {
            "id": self.id,
            "label": self.label,
            "jp": self.jp,
            "icon": self.icon,
            "color": self.color,
            "confirm": self.confirm,
            "quick": self.quick,
            # Las apps tienen icono propio en /app/icons/<id>.png (si el PC lo puede sacar del .exe).
            "app_icon": self.kind == "launch",
            # shutdown/restart/sleep/lock/cancel: la app sabe que el PC se va a apagar y Vega «se duerme».
            "power": self.arg if self.kind == "power" else "",
        }


class _ActionsFile(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: list[ActionDef] = Field(default_factory=list)


def load_actions(path: Path = ACTIONS_FILE) -> list[ActionDef]:
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError:
        log.warning("No hay %s: el panel no tendrá botones", path)
        return []
    except tomllib.TOMLDecodeError as exc:
        raise ActionConfigError(f"{path} tiene un error de formato: {exc}") from exc
    try:
        actions = _ActionsFile.model_validate(data).action
    except ValidationError as exc:
        problems = "; ".join(f"{'.'.join(str(p) for p in e['loc'])}: {e['msg']}" for e in exc.errors())
        raise ActionConfigError(f"Acciones no válidas en {path}: {problems}") from exc
    ids = [a.id for a in actions]
    if dupes := sorted({i for i in ids if ids.count(i) > 1}):
        raise ActionConfigError(f"Acciones repetidas en {path}: {', '.join(dupes)}")
    return actions


class Executor(Protocol):
    def run(self, action: ActionDef) -> str:
        """Ejecuta la acción y devuelve un mensaje corto para el usuario. Lanza ActionFailed."""
        ...


@dataclass(frozen=True)
class Outcome:
    """Resultado de pedir una acción."""

    kind: Literal["done", "confirm", "ignored", "error"]
    message: str
    nonce: str | None = None
    action: ActionDef | None = None


class ActionRunner:
    def __init__(
        self, actions: list[ActionDef], executor: Executor, now: Callable[[], float] = time.monotonic
    ) -> None:
        self._actions = {a.id: a for a in actions}
        self._order = actions
        self._executor = executor
        self._now = now
        self._pending: dict[str, tuple[str, float]] = {}  # nonce → (id, caduca)
        self._last_press: dict[str, float] = {}

    @property
    def actions(self) -> list[ActionDef]:
        return list(self._order)

    def match(self, text: str) -> ActionDef | None:
        """Busca un botón cuya frase aparezca en lo dicho («apaga el pc por favor» → apagar).
        Si varias encajan, gana la frase más larga (la más específica)."""
        said = f" {normalize(text)} "
        best: tuple[int, ActionDef] | None = None
        for action in self._order:
            for phrase in action.say:
                p = normalize(phrase)
                if p and f" {p} " in said and (best is None or len(p) > best[0]):
                    best = (len(p), action)
        return best[1] if best else None

    def request(self, action_id: str, client: str = "") -> Outcome:
        action = self._actions.get(action_id)
        if action is None:
            self._audit("rechazada", action_id, client, "no existe")
            return Outcome("error", "Esa acción no está permitida.")
        now = self._now()
        if now - self._last_press.get(action_id, -1e9) < DEBOUNCE_SECONDS:
            return Outcome("ignored", "")
        self._last_press[action_id] = now
        if action.confirm:
            self._expire(now)
            nonce = secrets.token_urlsafe(12)
            self._pending[nonce] = (action_id, now + CONFIRM_SECONDS)
            self._audit("confirmación pedida", action_id, client)
            return Outcome("confirm", f"¿Seguro? Pulsa de nuevo para {action.label.lower()}.", nonce, action)
        return self._execute(action, client)

    def confirm(self, nonce: str, client: str = "") -> Outcome:
        now = self._now()
        self._expire(now)
        entry = self._pending.pop(nonce, None)
        if entry is None:
            self._audit("confirmación rechazada", "?", client, "código caducado o desconocido")
            return Outcome("error", "La confirmación caducó. Vuelve a pulsar el botón.")
        return self._execute(self._actions[entry[0]], client)

    def _expire(self, now: float) -> None:
        for nonce in [n for n, (_, until) in self._pending.items() if until < now]:
            del self._pending[nonce]

    def _execute(self, action: ActionDef, client: str) -> Outcome:
        try:
            message = self._executor.run(action)
        except ActionFailed as exc:
            self._audit("falló", action.id, client, str(exc))
            return Outcome("error", str(exc), action=action)
        self._audit("ejecutada", action.id, client, message)
        return Outcome("done", message, action=action)

    @staticmethod
    def _audit(event: str, action_id: str, client: str, detail: str = "") -> None:
        audit.info(
            json.dumps(
                {"evento": event, "accion": action_id, "cliente": client, "detalle": detail},
                ensure_ascii=False,
            )
        )
