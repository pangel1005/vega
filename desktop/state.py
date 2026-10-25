"""Lo que sabe la app del PC de Vega, construido a partir de los eventos del servidor.

Sin Qt: es un diccionario de lo que llega por el WebSocket (los mismos eventos que ve el teléfono,
más `phone_state` con cómo está el teléfono). Así se puede probar sin ventanas.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

MAX_MESSAGES = 200


@dataclass
class Phone:
    online: bool = False
    brain: str = "pc"  # «pc» o «phone»: dónde piensa Vega
    sleeping: bool = False
    mic: bool = False
    battery: int = -1
    charging: bool = False
    glass: bool = False
    touch: str = "soft"
    face: str = ""
    version: int = 0


@dataclass
class Message:
    role: str  # «user», «assistant», «error» o «note»
    text: str
    streaming: bool = False


@dataclass
class DesktopState:
    connected: bool = False
    assistant: str = "Vega"
    model: str = ""
    state: str = "idle"  # idle, listening, thinking, speaking, acting, error
    phone: Phone = field(default_factory=Phone)
    telemetry: dict[str, Any] | None = None
    media: dict[str, Any] | None = None
    notifications: list[dict[str, Any]] = field(default_factory=list)
    actions: list[dict[str, Any]] = field(default_factory=list)
    voices: list[dict[str, str]] = field(default_factory=list)
    voice: str = ""
    pending: dict[str, Any] | None = None  # una acción que pide confirmación
    messages: list[Message] = field(default_factory=list)
    notice: str = ""  # último aviso corto (resultado de un botón, error…)

    def disconnected(self) -> None:
        """Sin enlace con el servidor, lo que mostraba ya no es verdad."""
        self.connected = False
        self.state = "idle"
        self.phone = Phone()
        self.telemetry = None
        self.media = None
        self.pending = None

    @property
    def mood(self) -> str:
        """Qué cara pone Vega (igual que en el teléfono, más «offline» y «sleep»)."""
        if not self.connected:
            return "offline"
        if self.phone.sleeping:
            return "sleep"
        if self.pending is not None:
            return "alert"
        return {
            "listening": "listen",
            "thinking": "think",
            "speaking": "talk",
            "acting": "talk",
            "error": "error",
        }.get(self.state, "idle")

    def apply(self, event: dict[str, Any]) -> None:
        kind = event.get("type")
        handler = getattr(self, f"_on_{kind}", None)
        if handler is not None:
            handler(event)

    # --- Cada evento ---

    def _on_ready(self, e: dict[str, Any]) -> None:
        self.connected = True
        self.assistant = e.get("assistant", self.assistant)
        self.model = e.get("model", "")

    def _on_history(self, e: dict[str, Any]) -> None:
        self.messages = [Message(m["role"], m["content"]) for m in e.get("messages", [])]

    def _on_state(self, e: dict[str, Any]) -> None:
        self.state = e["state"]

    def _on_transcript(self, e: dict[str, Any]) -> None:
        if e.get("text"):
            self._push(Message("user", e["text"]))

    def _on_token(self, e: dict[str, Any]) -> None:
        last = self.messages[-1] if self.messages else None
        if last is not None and last.streaming:
            last.text += e["text"]
        else:
            self._push(Message("assistant", e["text"], streaming=True))

    def _on_done(self, e: dict[str, Any]) -> None:
        last = self.messages[-1] if self.messages else None
        if last is not None and last.streaming:
            last.text, last.streaming = e["text"], False
        elif e.get("text"):
            self._push(Message("assistant", e["text"]))

    def _on_error(self, e: dict[str, Any]) -> None:
        self.notice = e["message"]
        self._push(Message("error", e["message"]))

    def _on_reminder(self, e: dict[str, Any]) -> None:
        self.notice = e["text"]
        self._push(Message("note", e["text"]))

    def _on_actions(self, e: dict[str, Any]) -> None:
        self.actions = e["items"]

    def _on_voices(self, e: dict[str, Any]) -> None:
        self.voices = e["items"]
        self.voice = e["current"]

    def _on_confirm(self, e: dict[str, Any]) -> None:
        self.pending = e

    def _on_action_result(self, e: dict[str, Any]) -> None:
        self.pending = None
        self.notice = e["message"]

    def _on_telemetry(self, e: dict[str, Any]) -> None:
        self.telemetry = e

    def _on_media(self, e: dict[str, Any]) -> None:
        self.media = e if e.get("active") else None

    def _on_notifications(self, e: dict[str, Any]) -> None:
        self.notifications = e["items"]

    def _on_phone_state(self, e: dict[str, Any]) -> None:
        self.phone = Phone(
            online=e.get("online", True),
            brain=e.get("brain", "pc"),
            sleeping=e.get("sleeping", False),
            mic=e.get("mic", False),
            battery=e.get("battery", -1),
            charging=e.get("charging", False),
            glass=e.get("glass", False),
            touch=e.get("touch", "soft"),
            face=e.get("face", ""),
            version=e.get("version", 0),
        )

    def _push(self, message: Message) -> None:
        self.messages.append(message)
        del self.messages[:-MAX_MESSAGES]
