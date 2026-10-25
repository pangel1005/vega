"""Lo que comparten todas las páginas: el estado de Vega, la conexión y las imágenes."""

from __future__ import annotations

from typing import Any

from PySide6.QtCore import QObject, Signal

from desktop.client import VegaClient
from desktop.state import DesktopState, Message
from desktop.widgets import Images


class Context(QObject):
    """Une el cliente con el estado. Cada página se suscribe a `changed` y se repinta."""

    changed = Signal()
    event = Signal(dict)  # cada evento tal como llegó (para quien necesite reaccionar a uno concreto)

    def __init__(self, client: VegaClient, images: Images, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self.state = DesktopState()
        self.client = client
        self.images = images
        client.event.connect(self._on_event)
        client.connection.connect(self._on_connection)

    def _on_event(self, event: dict[str, Any]) -> None:
        self.state.apply(event)
        self.event.emit(event)
        self.changed.emit()

    def _on_connection(self, up: bool) -> None:
        if not up:
            self.state.disconnected()
        self.changed.emit()

    # --- Órdenes (todas pasan por el servidor, que las valida) ---

    def send(self, message: dict[str, Any]) -> bool:
        return self.client.send(message)

    def phone_control(self, command: str) -> None:
        self.send({"type": "phone_control", "command": command})

    def press(self, action_id: str) -> None:
        self.send({"type": "action", "id": action_id})

    def confirm(self, nonce: str) -> None:
        self.send({"type": "action_confirm", "nonce": nonce})

    def say_to_vega(self, text: str) -> None:
        """Escribirle a Vega desde el PC (contesta por escrito: el escritorio no habla)."""
        text = text.strip()
        if not text:
            return
        self.state.messages.append(Message("user", text))
        self.send({"type": "chat", "text": text})
        self.changed.emit()
