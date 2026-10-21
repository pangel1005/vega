"""Mensajes JSON del WebSocket `/ws`.

Cliente → servidor:
    {"type": "hello", "token": "..."}   primer mensaje obligatorio
    {"type": "chat", "text": "..."}
    {"type": "reset"}                    empieza una conversación nueva
    {"type": "listen_start"}             empieza a grabar; luego llegan mensajes BINARIOS
                                         con audio PCM16 mono a 16 kHz
    {"type": "listen_stop"}              fin de la grabación: transcribir y responder
    {"type": "listen_cancel"}            descarta la grabación

Servidor → cliente:
    {"type": "ready", "assistant": "...", "model": "..."}
    {"type": "history", "messages": [{"role": "user|assistant", "content": "..."}]}
    {"type": "state", "state": "idle|listening|thinking|speaking|acting|error"}
    {"type": "transcript", "text": "...", "audio_seconds": 3.9, "elapsed_ms": 180}
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


class Reset(_Msg):
    type: Literal["reset"] = "reset"


class ListenStart(_Msg):
    type: Literal["listen_start"] = "listen_start"


class ListenStop(_Msg):
    type: Literal["listen_stop"] = "listen_stop"


class ListenCancel(_Msg):
    type: Literal["listen_cancel"] = "listen_cancel"


AnyClientMessage = Hello | ChatRequest | Reset | ListenStart | ListenStop | ListenCancel
ClientMessage = Annotated[AnyClientMessage, Field(discriminator="type")]
client_message: TypeAdapter[AnyClientMessage] = TypeAdapter(ClientMessage)


# --- Servidor → cliente ---


class Ready(_Msg):
    type: Literal["ready"] = "ready"
    assistant: str
    model: str


class HistoryItem(_Msg):
    role: Literal["user", "assistant", "system"]
    content: str


class HistoryEvent(_Msg):
    type: Literal["history"] = "history"
    messages: list[HistoryItem]


class StateEvent(_Msg):
    type: Literal["state"] = "state"
    state: State


class TranscriptEvent(_Msg):
    type: Literal["transcript"] = "transcript"
    text: str
    audio_seconds: float
    elapsed_ms: int


class TokenEvent(_Msg):
    type: Literal["token"] = "token"
    text: str


class DoneEvent(_Msg):
    type: Literal["done"] = "done"
    text: str


class ErrorEvent(_Msg):
    type: Literal["error"] = "error"
    message: str


ServerEvent = Ready | HistoryEvent | StateEvent | TranscriptEvent | TokenEvent | DoneEvent | ErrorEvent
