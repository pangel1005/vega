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
    {"type": "notification_dismiss", "id": 123}  quita una notificación del PC (sin id: todas)
    {"type": "phone_command", "text": "..."}  (IA en el teléfono) una orden ya entendida:
                                         el PC prueba sus habilidades y botones, nunca el
                                         modelo; contesta con «phone_reply»
    {"type": "memory_import", "facts": [...], "notes": [...], "reminders": [{"kind",
     "text", "due_at"}]}                 lo que el teléfono guardó sin PC
    {"type": "say", "text": "..."}       (IA en el teléfono) el PC lo dice con su voz (Kokoro en
                                         el procesador): suena más natural que la del teléfono
    {"type": "sleep", "on": true}        botón de dormir: sin escuchar y sin modelo en la gráfica
                                         (con false se despierta)
    {"type": "brain", "where": "pc|phone"}  dónde piensa Vega: con «phone» el teléfono
                                         escucha y contesta solo, y el PC suelta el
                                         modelo de la gráfica (para jugar o trabajar)

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
    {"type": "sleep"}                    «Vega, duérmete»: el teléfono pasa a dormir
    {"type": "phone_reply", "handled": true, "text": "..."}  respuesta a «phone_command»
                                         (lo dice el teléfono con su voz)
    {"type": "notifications", "items": [{"id", "app", "title", "body", "at", "icon"}]}
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


class MediaControl(_Msg):
    """Pausa/continúa, siguiente o anterior en lo que suena en el PC."""

    type: Literal["media_control"] = "media_control"
    command: Literal["play_pause", "next", "previous"]


class PlaybackDone(_Msg):
    """El teléfono terminó de reproducir la voz de la respuesta (el PC no lo sabe por sí solo)."""

    type: Literal["playback_done"] = "playback_done"


class PhoneCommand(_Msg):
    """IA en el teléfono: una orden ya transcrita. El PC solo usa habilidades y botones."""

    type: Literal["phone_command"] = "phone_command"
    text: str = Field(min_length=1, max_length=1000)


class ImportedReminder(_Msg):
    kind: Literal["reminder", "timer"]
    text: str = Field(max_length=500)
    due_at: str = Field(max_length=40)  # hora local, ISO (2026-10-24T18:30:00)


class MemoryImport(_Msg):
    """Lo que el teléfono guardó mientras pensaba sin el PC; aquí se suma a la memoria."""

    type: Literal["memory_import"] = "memory_import"
    facts: list[Annotated[str, Field(max_length=500)]] = Field(default_factory=list, max_length=200)
    notes: list[Annotated[str, Field(max_length=500)]] = Field(default_factory=list, max_length=200)
    reminders: list[ImportedReminder] = Field(default_factory=list, max_length=200)


class Say(_Msg):
    """El teléfono pide que el PC diga un texto con su voz (más natural que la del teléfono)."""

    type: Literal["say"] = "say"
    text: str = Field(min_length=1, max_length=2000)


class SleepMode(_Msg):
    """Botón de dormir del teléfono: el PC deja de escuchar y suelta el modelo de la gráfica."""

    type: Literal["sleep"] = "sleep"
    on: bool


class NotificationDismiss(_Msg):
    """Quita una notificación del centro de notificaciones del PC (sin `id`, todas)."""

    type: Literal["notification_dismiss"] = "notification_dismiss"
    id: int | None = None


class Brain(_Msg):
    """Dónde piensa Vega: en el PC (como siempre) o en el teléfono (el PC libera la gráfica)."""

    type: Literal["brain"] = "brain"
    where: Literal["pc", "phone"]


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
    | MediaControl
    | Brain
    | NotificationDismiss
    | PhoneCommand
    | MemoryImport
    | Say
    | SleepMode
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


class MediaEvent(_Msg):
    """Lo que suena en el PC (o `active = false` si nada). La carátula está en
    /app/media/art/<art>.jpg; la posición la avanza el teléfono mientras suena."""

    type: Literal["media"] = "media"
    active: bool
    app: str = ""
    title: str = ""
    artist: str = ""
    album: str = ""
    playing: bool = False
    position: float = 0.0
    duration: float = 0.0
    art: str = ""
    can_previous: bool = False
    can_next: bool = False


class SleepEvent(_Msg):
    """Alguien le dijo a Vega que se durmiera: el teléfono pasa a dormir."""

    type: Literal["sleep"] = "sleep"


class PhoneReplyEvent(_Msg):
    type: Literal["phone_reply"] = "phone_reply"
    handled: bool
    text: str = ""


class NotificationItem(_Msg):
    id: int
    app: str
    title: str
    body: str = ""
    at: float  # segundos desde 1970 (UTC)
    icon: str = ""  # /app/notifications/icon/<icon>.png


class NotificationsEvent(_Msg):
    """Las notificaciones del PC, de la más nueva a la más vieja (cuando cambian)."""

    type: Literal["notifications"] = "notifications"
    items: list[NotificationItem]


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
    | MediaEvent
    | NotificationsEvent
    | PhoneReplyEvent
    | SleepEvent
)
