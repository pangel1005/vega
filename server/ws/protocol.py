"""Mensajes JSON del WebSocket `/ws`.

Cliente → servidor:
    {"type": "hello", "token": "...", "role": "phone|desktop"}  primer mensaje obligatorio;
                                         «desktop» (app del PC) ve lo que pasa en el teléfono y
                                         puede controlarlo, pero no habla ni escucha
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
    {"type": "hear", "id": 7, "audio": "<PCM16 mono 16 kHz en base64>"}  (IA en el teléfono) una frase
                                         que el teléfono ya cortó: el PC la transcribe (más rápido que
                                         el teléfono) y contesta con «heard». No se guarda nada
    {"type": "say", "text": "..."}       (IA en el teléfono) el PC lo dice con su voz (Kokoro en
                                         el procesador): suena más natural que la del teléfono
    {"type": "phone_state", "brain": "pc|phone", "sleeping": false, "mic": true, "battery": 91,
     "charging": true, "glass": false, "touch": "soft", "face": "WARM", "version": 428883}
                                         (teléfono) su estado, para que lo vea la app del PC
    {"type": "phone_control", "command": "sleep|wake|brain_pc|brain_phone|glass_on|glass_off|
                                          terminal_on|terminal_off|orb_on|orb_off|wave_next|mic_on|mic_off"}
                                         (app del PC) manda una orden al teléfono
    {"type": "reload_actions"}           vuelve a leer config/actions.toml y reparte los botones
    {"type": "reload_car"}               (app del PC) vuelve a leer config/car.toml y la reparte
    {"type": "sleep", "on": true}        botón de dormir: sin escuchar y sin modelo en la gráfica
                                         (con false se despierta)
    {"type": "maintenance", "op": "status|free_ram|disk_scan|disk_clean", "deep": false, "ids": []}
                                         (app del PC) liberar RAM o limpiar disco; responde con
                                         «maintenance_result» solo a quien lo pidió
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
    {"type": "phone_state", "online": true, ...}  (app del PC) estado del teléfono; online=false
                                         si se desconectó
    {"type": "phone_control", "command": "..."}   (teléfono) orden de la app del PC
    {"type": "car_layout", "title": "Vega", "items": [{"id", "label", "kind", "arg"}]}
                                         las casillas de la pantalla de Vega en Android Auto
                                         (lista vacía: el teléfono usa la suya de serie)
    {"type": "maintenance_result", "op": "status|free_ram|disk_scan|disk_clean", "ok": true,
     "message": "...", "data": {...}}   (app del PC) respuesta a «maintenance»
    {"type": "sleep"}                    «Vega, duérmete»: el teléfono pasa a dormir
    {"type": "phone_reply", "handled": true, "text": "..."}  respuesta a «phone_command»
    {"type": "heard", "id": 7, "ok": true, "text": "..."}  respuesta a «hear» (ok false: no se pudo
                                         transcribir y el teléfono usa su propio oído)
                                         (lo dice el teléfono con su voz)
    {"type": "notifications", "items": [{"id", "app", "title", "body", "at", "icon"}]}
    {"type": "music_spectrum", "bands": [0.1, 0.8, ...]}  (teléfono) cuánto suena cada banda de frecuencias
                                         de Spotify (24 números de 0 a 1, de graves a agudos), ~25 por segundo
    {"type": "music_level", "level": 0.42}  (teléfono) cuánto suena el PC ahora (0 a 1), unas 20 veces por
                                         segundo y solo mientras suena algo: la cara se mueve con la música
"""

from __future__ import annotations

from enum import StrEnum
from typing import Annotated, Any, Literal

from pydantic import BaseModel, ConfigDict, Field, TypeAdapter, model_validator


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
    role: Literal["phone", "desktop"] = "phone"


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
    """Pausa/continúa, siguiente o anterior en lo que suena en el PC; «seek» salta a `position` (segundos)."""

    type: Literal["media_control"] = "media_control"
    command: Literal["play_pause", "next", "previous", "seek"]
    position: float | None = Field(default=None, ge=0, le=86_400)

    @model_validator(mode="after")
    def _seek_needs_a_position(self) -> MediaControl:
        if self.command == "seek" and self.position is None:
            raise ValueError("«seek» necesita `position`")
        return self


class PlaybackDone(_Msg):
    """El teléfono terminó de reproducir la voz de la respuesta (el PC no lo sabe por sí solo)."""

    type: Literal["playback_done"] = "playback_done"


class PhoneCommand(_Msg):
    """IA en el teléfono: una orden ya transcrita. El PC solo usa habilidades y botones."""

    type: Literal["phone_command"] = "phone_command"
    text: str = Field(min_length=1, max_length=1000)


# Una frase de voz dura como mucho 12 s en el teléfono (Segmenter): 12 s · 32 000 B/s en base64 son ~512 000
# caracteres. Se deja holgura y el límite real en segundos lo pone `speech.max_seconds`.
MAX_HEAR_CHARS = 2_000_000


class Hear(_Msg):
    """IA en el teléfono: una frase de voz (PCM16 mono 16 kHz, base64) que el PC debe transcribir."""

    type: Literal["hear"] = "hear"
    id: int
    audio: str = Field(min_length=1, max_length=MAX_HEAR_CHARS)


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


class PhoneState(_Msg):
    """Estado del teléfono, para que la app del PC lo vea."""

    type: Literal["phone_state"] = "phone_state"
    brain: Literal["pc", "phone"] = "pc"
    sleeping: bool = False
    mic: bool = False
    battery: int = -1
    charging: bool = False
    glass: bool = False
    touch: str = "soft"
    face: str = ""
    version: int = 0


PhoneCommandName = Literal[
    "sleep",
    "wake",
    "brain_pc",
    "brain_phone",
    "glass_on",
    "glass_off",
    "terminal_on",
    "terminal_off",
    "orb_on",
    "orb_off",
    "wave_next",
    "mic_on",
    "mic_off",
]


class PhoneControl(_Msg):
    """La app del PC manda una orden al teléfono."""

    type: Literal["phone_control"] = "phone_control"
    command: PhoneCommandName


class ReloadActions(_Msg):
    """Vuelve a leer config/actions.toml (la app del PC lo edita) y reparte los botones."""

    type: Literal["reload_actions"] = "reload_actions"


class ReloadCar(_Msg):
    """Vuelve a leer config/car.toml (la app del PC lo edita) y reparte la pantalla del coche."""

    type: Literal["reload_car"] = "reload_car"


class MaintenanceRequest(_Msg):
    """(app del PC) Mantenimiento: estado de la RAM, liberarla, y buscar y limpiar el disco."""

    type: Literal["maintenance"] = "maintenance"
    op: Literal["status", "free_ram", "disk_scan", "disk_clean"]
    deep: bool = False  # free_ram: también reduce las demás aplicaciones y detiene Gradle/Kotlin
    ids: list[Annotated[str, Field(max_length=40)]] = Field(default_factory=list, max_length=10)  # disk_clean


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
    | Hear
    | MemoryImport
    | Say
    | SleepMode
    | PhoneState
    | PhoneControl
    | ReloadActions
    | ReloadCar
    | MaintenanceRequest
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


class MusicLevelEvent(_Msg):
    """Volumen real de lo que suena por los altavoces del PC (pico de 0 a 1), solo mientras suena algo."""

    type: Literal["music_level"] = "music_level"
    level: float


class MusicSpectrumEvent(_Msg):
    """Cuánto suena cada banda de frecuencias de Spotify (de graves a agudos), solo mientras suena."""

    type: Literal["music_spectrum"] = "music_spectrum"
    bands: list[float]


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


class GameNews(_Msg):
    title: str
    at: float  # segundos desde 1970 (UTC)


class RecentGameItem(_Msg):
    name: str
    art: str = ""
    hours: float | None = None
    last_played: float = 0.0  # segundos desde 1970 (UTC)


class GameStat(_Msg):
    label: str
    value: str


class MatchEvent(_Msg):
    """La partida en curso (League of Legends, Rocket League…). Con solo `hint`: qué falta para verla."""

    title: str = ""
    score: str = ""
    clock: float | None = None  # segundos de partida al mandarlo
    clock_step: int = 0  # +1 sube, −1 baja, 0 parado: el teléfono lo sigue contando
    stats: list[GameStat] = []
    feed: list[str] = []
    hint: str = ""
    colors: list[str] = []  # un color (#RRGGBB) por cada lado del marcador


class GameEvent(_Msg):
    """El juego al que se juega en el PC (o `active = false`). La carátula está en /app/games/art/<art>.jpg;
    los segundos de la sesión los sigue contando el teléfono desde `played`."""

    type: Literal["game"] = "game"
    active: bool
    key: str = ""
    name: str = ""
    source: str = ""
    played: float = 0.0  # segundos que lleva abierto, al mandarlo
    hours: float | None = None  # horas jugadas en total (según Steam)
    size_gb: float | None = None
    art: str = ""
    summary: str = ""
    genres: list[str] = []
    developer: str = ""
    released: str = ""
    rating: int | None = None
    tags: list[str] = []
    achievements: int | None = None  # logros que tiene el juego
    played_2w: float | None = None  # horas de las últimas dos semanas
    players: int | None = None  # gente jugando ahora mismo
    news: list[GameNews] = []
    ach_unlocked: int | None = None  # tus logros (solo con la cuenta de Steam conectada)
    ach_recent: list[str] = []
    friends: list[str] = []  # amigos que juegan a esto ahora (solo con cuenta)
    account: bool = False  # ¿hay una cuenta de Steam conectada?
    recent: list[RecentGameItem] = []  # los últimos juegos, para cuando no hay ninguno abierto
    match: MatchEvent | None = None  # la partida en curso, si el juego tiene una API local


class PhoneStateEvent(PhoneState):
    """Estado del teléfono para la app del PC; `online` es false si el teléfono no está conectado."""

    online: bool = True


class PhoneControlEvent(_Msg):
    """Orden de la app del PC para el teléfono."""

    type: Literal["phone_control"] = "phone_control"
    command: PhoneCommandName


class CarItemData(_Msg):
    id: str
    label: str
    kind: Literal["builtin", "action"]
    arg: str


class CarLayoutEvent(_Msg):
    """Las casillas de la pantalla de Vega en Android Auto."""

    type: Literal["car_layout"] = "car_layout"
    title: str = "Vega"
    items: list[CarItemData] = Field(default_factory=list)


class MaintenanceEvent(_Msg):
    """Respuesta a «maintenance» (solo a quien la pidió): `data` según `op`."""

    type: Literal["maintenance_result"] = "maintenance_result"
    op: str
    ok: bool = True
    message: str = ""
    data: dict[str, Any] = Field(default_factory=dict)


class SleepEvent(_Msg):
    """Alguien le dijo a Vega que se durmiera: el teléfono pasa a dormir."""

    type: Literal["sleep"] = "sleep"


class PhoneReplyEvent(_Msg):
    type: Literal["phone_reply"] = "phone_reply"
    handled: bool
    text: str = ""


class HeardEvent(_Msg):
    """Lo que el PC entendió de una frase del teléfono. Sin texto y `ok` falso: que lo intente el teléfono."""

    type: Literal["heard"] = "heard"
    id: int
    ok: bool = True
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
    | GameEvent
    | MusicLevelEvent
    | MusicSpectrumEvent
    | NotificationsEvent
    | PhoneReplyEvent
    | HeardEvent
    | SleepEvent
    | PhoneStateEvent
    | PhoneControlEvent
    | CarLayoutEvent
    | MaintenanceEvent
)
