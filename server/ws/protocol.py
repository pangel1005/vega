"""Mensajes JSON del WebSocket `/ws`.

Cliente → servidor:
    {"type": "hello", "token": "..."}   primer mensaje obligatorio
    {"type": "chat", "text": "..."}

Servidor → cliente:
    {"type": "ready", "assistant": "...", "model": "..."}
    {"type": "state", "state": "idle|listening|thinking|speaking|acting|error"}
    {"type": "token", "text": "..."}     trozo de la respuesta, en cuanto llega
    {"type": "done", "text": "..."}      respuesta completa
    {"type": "error", "message": "..."}  texto pensado para mostrarse al usuario
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter


class State(StrEnum):
    IDLE = "idle"
    LISTENING = "listening"
    THINKING = "thinking"
    SPEAKING = "speaking"
    ACTING = "acting"
    ERROR = "error"


class _Msg(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


# --- Cliente → servidor ---


class Hello(_Msg):
    type: Literal["hello"] = "hello"
    token: str = Field(max_length=200)


class ChatRequest(_Msg):
    type: Literal["chat"] = "chat"
    text: str = Field(min_length=1, max_length=4000)


ClientMessage = Annotated[Hello | ChatRequest, Field(discriminator="type")]
client_message: TypeAdapter[Hello | ChatRequest] = TypeAdapter(ClientMessage)


# --- Servidor → cliente ---


class Ready(_Msg):
    type: Literal["ready"] = "ready"
    assistant: str
    model: str


class StateEvent(_Msg):
    type: Literal["state"] = "state"
    state: State


class TokenEvent(_Msg):
    type: Literal["token"] = "token"
    text: str


class DoneEvent(_Msg):
    type: Literal["done"] = "done"
    text: str


class ErrorEvent(_Msg):
    type: Literal["error"] = "error"
    message: str


ServerEvent = Ready | StateEvent | TokenEvent | DoneEvent | ErrorEvent
