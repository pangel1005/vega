"""Conexión de la app del PC con el servidor de Vega (WebSocket local, rol «desktop»)."""

from __future__ import annotations

import contextlib
import json
from typing import Any

from PySide6.QtCore import QObject, QTimer, QUrl, Signal
from PySide6.QtWebSockets import QWebSocket

RETRY_MIN_MS = 1_000
RETRY_MAX_MS = 5_000


class VegaClient(QObject):
    """Se conecta solo y se reconecta si el servidor se cae. Emite cada evento ya decodificado."""

    event = Signal(dict)
    connection = Signal(bool)

    def __init__(self, url: str, token: str, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._url = QUrl(url)
        self._token = token
        self._retry = RETRY_MIN_MS
        self._wanted = False
        self._socket = QWebSocket()
        self._socket.connected.connect(self._on_connected)
        self._socket.disconnected.connect(self._on_disconnected)
        self._socket.textMessageReceived.connect(self._on_text)
        self._timer = QTimer(self)
        self._timer.setSingleShot(True)
        self._timer.timeout.connect(self._open)

    @property
    def is_open(self) -> bool:
        return self._socket.isValid()

    def start(self) -> None:
        self._wanted = True
        self._open()

    def stop(self) -> None:
        self._wanted = False
        self._timer.stop()
        self._socket.close()

    def send(self, message: dict[str, Any]) -> bool:
        if not self.is_open:
            return False
        self._socket.sendTextMessage(json.dumps(message))
        return True

    # --- Interno ---

    def _open(self) -> None:
        if self._wanted and not self.is_open:
            self._socket.open(self._url)

    def _on_connected(self) -> None:
        self._retry = RETRY_MIN_MS
        self._socket.sendTextMessage(json.dumps({"type": "hello", "token": self._token, "role": "desktop"}))
        self.connection.emit(True)

    def _on_disconnected(self) -> None:
        self.connection.emit(False)
        if self._wanted:
            self._timer.start(self._retry)
            self._retry = min(self._retry * 2, RETRY_MAX_MS)

    def _on_text(self, text: str) -> None:
        with contextlib.suppress(ValueError):  # un mensaje que no es JSON no debe romper la ventana
            self.event.emit(json.loads(text))
