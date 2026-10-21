"""Carga y valida `config/assistant.toml`.

Los valores sensibles para la seguridad (como la dirección en la que escucha el
servidor) se validan aquí, en el código, y no dependen de que el archivo esté bien.
"""

from __future__ import annotations

import os
import tomllib
from pathlib import Path
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONFIG = ROOT / "config" / "assistant.toml"
CONFIG_ENV_VAR = "ASISTENTE_CONFIG"

# El teléfono llega por `adb reverse`, así que el servidor nunca necesita escuchar en la red.
ALLOWED_HOST = "127.0.0.1"


class ConfigError(Exception):
    """La configuración no existe o no es válida."""


class _Section(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class AssistantSettings(_Section):
    name: str = Field(min_length=1, max_length=40)
    language: str = "es"


class ServerSettings(_Section):
    host: str = ALLOWED_HOST
    port: int = Field(default=8000, ge=1024, le=65535)

    @field_validator("host")
    @classmethod
    def _only_loopback(cls, value: str) -> str:
        if value != ALLOWED_HOST:
            raise ValueError(f"el servidor solo puede escuchar en {ALLOWED_HOST}, no en {value!r}")
        return value


class LlmSettings(_Section):
    url: str = "http://127.0.0.1:11434"
    model: str = Field(min_length=1)
    keep_alive: str = Field(default="10m", pattern=r"^\d+[smh]$")
    system_prompt: str = "Eres {name}, un asistente personal. Responde en español, breve y claro."


class LoggingSettings(_Section):
    level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    dir: Path = Path("logs")

    @property
    def path(self) -> Path:
        """Carpeta de registros; si es relativa, se toma desde la raíz del proyecto."""
        return self.dir if self.dir.is_absolute() else ROOT / self.dir


class MemorySettings(_Section):
    file: Path = Path("data/history.sqlite")

    @property
    def path(self) -> Path:
        """Archivo de historial; si es relativo, se toma desde la raíz del proyecto."""
        return self.file if self.file.is_absolute() else ROOT / self.file


class Settings(_Section):
    assistant: AssistantSettings
    server: ServerSettings = ServerSettings()
    llm: LlmSettings
    logging: LoggingSettings = LoggingSettings()
    memory: MemorySettings = MemorySettings()

    @property
    def system_prompt(self) -> str:
        return self.llm.system_prompt.strip().replace("{name}", self.assistant.name)


def load_settings(path: Path | None = None) -> Settings:
    """Lee la configuración de `path`, de la variable ASISTENTE_CONFIG o del archivo por defecto."""
    if path is None:
        path = Path(os.environ.get(CONFIG_ENV_VAR, DEFAULT_CONFIG))
    try:
        # utf-8-sig: algunos editores de Windows guardan el archivo con BOM y tomllib no lo acepta.
        data = tomllib.loads(path.read_text(encoding="utf-8-sig"))
    except FileNotFoundError as exc:
        raise ConfigError(f"No encuentro el archivo de configuración: {path}") from exc
    except tomllib.TOMLDecodeError as exc:
        raise ConfigError(f"El archivo {path} tiene un error de formato: {exc}") from exc
    try:
        return Settings.model_validate(data)
    except ValidationError as exc:
        problems = "; ".join(f"{'.'.join(str(p) for p in err['loc'])}: {err['msg']}" for err in exc.errors())
        raise ConfigError(f"Configuración no válida en {path}: {problems}") from exc
