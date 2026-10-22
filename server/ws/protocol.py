"""Mensajes JSON del WebSocket `/ws`.

Cliente → servidor:
    {"type": "hello", "token": "..."}   primer mensaje obligatorio
    {"type": "chat", "text": "..."}
    {"type": "reset"}                    empieza una conversación nueva
    {"type": "listen_start"}             empieza a grabar; luego llegan mensajes BINARIOS
                                         con audio PCM16 mono a 16 kHz
    {"type": "listen_stop"}              fin de la grabación: transcribir y responder
    {"type": "listen_cancel"}            descarta la grabación
    {"type": "action", "id": "..."}      pulsa un botón del panel
    {"type": "action_confirm", "nonce": "..."}  confirma una acción sensible
    {"type": "wake_mode", "on": true}    escucha continua: el audio binario llega siempre
                                         y se atienden las frases que empiezan por el nombre
    {"type": "attention"}                (tocar el rostro) la próxima frase es una orden
    {"type": "set_voice", "voice": "..."}  cambia la voz (y la presenta)

Servidor → cliente:
    {"type": "ready", "assistant": "...", "model": "..."}
    {"type": "history", "messages": [{"role": "user|assistant", "content": "..."}]}
    {"type": "actions", "items": [{"id", "label", "jp", "icon", "color", "confirm"}]}
    {"type": "confirm", "nonce": "...", "id": "...", "message": "...", "expires_in": 10}
    {"type": "action_result", "id": "...", "ok": true, "message": "..."}
    {"type": "voices", "items": [{"id", "label"}], "current": "..."}
    {"type": "audio", "seq": 1, "rate": 22050, "text": "..."}  seguido de un mensaje
                                         BINARIO con la voz de esa frase (PCM16 mono)
    {"type": "audio_end"}                no hay más voz para esta respuesta
    {"type": "reminder", "kind": "reminder|timer", "text": "..."}  ha vencido un recordatorio
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


class ActionRequest(_Msg):
    type: Literal["action"] = "action"
    id: str = Field(max_length=40)


class ActionConfirm(_Msg):
    type: Literal["action_confirm"] = "action_confirm"
    nonce: str = Field(max_length=64)


class WakeMode(_Msg):
    type: Literal["wake_mode"] = "wake_mode"
    on: bool


class Attention(_Msg):
    type: Literal["attention"] = "attention"


class PlaybackDone(_Msg):
    """El teléfono terminó de reproducir la voz de la respuesta (el PC no lo sabe por sí solo)."""

    type: Literal["playback_done"] = "playback_done"


class SetVoice(_Msg):
    type: Literal["set_voice"] = "set_voice"
    voice: str = Field(max_length=80)


AnyClientMessage = (
    Hello
    | ChatRequest
    | Reset
    | ListenStart
    | ListenStop
    | ListenCancel
    | ActionRequest
    | ActionConfirm
    | WakeMode
    | Attention
    | SetVoice
    | PlaybackDone
)
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


class ActionsEvent(_Msg):
    type: Literal["actions"] = "actions"
    items: list[dict[str, object]]


class ConfirmEvent(_Msg):
    type: Literal["confirm"] = "confirm"
    nonce: str
    id: str
    message: str
    expires_in: int


class ActionResultEvent(_Msg):
    type: Literal["action_result"] = "action_result"
    id: str
    ok: bool
    message: str


class VoicesEvent(_Msg):
    type: Literal["voices"] = "voices"
    items: list[dict[str, str]]
    current: str


class AudioEvent(_Msg):
    type: Literal["audio"] = "audio"
    seq: int
    rate: int
    text: str


class AudioEnd(_Msg):
    type: Literal["audio_end"] = "audio_end"


class ReminderEvent(_Msg):
    type: Literal["reminder"] = "reminder"
    kind: Literal["reminder", "timer"]
    text: str


class Upcoming(BaseModel):
    kind: Literal["reminder", "timer"]
    text: str
    due_in: int  # segundos que faltan (el teléfono hace la cuenta atrás entre lecturas)


class TelemetryEvent(_Msg):
    """Cada pocos segundos: estado del PC y próximos avisos, para el panel del teléfono."""

    type: Literal["telemetry"] = "telemetry"
    cpu: int
    ram_used: float
    ram_total: float
    gpu_temp: int | None = None
    gpu_util: int | None = None
    vram_used: float | None = None
    vram_total: float | None = None
    upcoming: list[Upcoming] = []


ServerEvent = (
    Ready
    | HistoryEvent
    | StateEvent
    | TranscriptEvent
    | TokenEvent
    | DoneEvent
    | ErrorEvent
    | ActionsEvent
    | ConfirmEvent
    | ActionResultEvent
    | VoicesEvent
    | AudioEvent
    | AudioEnd
    | ReminderEvent
    | TelemetryEvent
)
