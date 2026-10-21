"""Interfaz común para cualquier modelo de lenguaje.

El resto del servidor solo conoce `ChatModel`; cambiar Ollama por otro motor
consiste en escribir otra clase que cumpla esta interfaz.
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Sequence
from dataclasses import dataclass
from typing import Literal, Protocol

Role = Literal["system", "user", "assistant"]


@dataclass(frozen=True)
class ChatMessage:
    role: Role
    content: str


class LlmUnavailable(Exception):
    """El modelo no puede responder. El mensaje está pensado para mostrarse al usuario."""


class ChatModel(Protocol):
    @property
    def name(self) -> str: ...

    def stream(self, messages: Sequence[ChatMessage]) -> AsyncIterator[str]:
        """Devuelve la respuesta en trozos a medida que el modelo los genera."""
        ...
