"""Ruta WebSocket `/ws`: autenticación con token y bucle de mensajes."""

from __future__ import annotations

import asyncio
import logging
import secrets

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from server.core.config import Settings
from server.core.orchestrator import Orchestrator
from server.llm.base import ChatModel
from server.memory.store import ConversationStore
from server.ws.protocol import ChatRequest, ErrorEvent, Hello, Ready, Reset, ServerEvent, client_message

log = logging.getLogger(__name__)

HELLO_TIMEOUT_S = 5.0
CLOSE_UNAUTHORIZED = 4401
CLOSE_FORBIDDEN_ORIGIN = 4403
# Puerto del servidor de desarrollo de Vite (paso 4).
VITE_DEV_PORT = 5173


def allowed_origins(settings: Settings) -> set[str]:
    ports = (settings.server.port, VITE_DEV_PORT)
    return {f"http://{host}:{port}" for host in ("127.0.0.1", "localhost") for port in ports}


def register_ws(
    app: FastAPI, settings: Settings, model: ChatModel, store: ConversationStore, token: str
) -> None:
    origins = allowed_origins(settings)

    @app.websocket("/ws")
    async def ws(websocket: WebSocket) -> None:
        # Los navegadores siempre envían Origin; si no viene, es un cliente local (script o prueba).
        origin = websocket.headers.get("origin")
        if origin is not None and origin not in origins:
            log.warning("WebSocket rechazado por origen no permitido: %s", origin)
            await websocket.close(code=CLOSE_FORBIDDEN_ORIGIN)
            return
        await websocket.accept()

        async def emit(event: ServerEvent) -> None:
            await websocket.send_text(event.model_dump_json())

        try:
            raw = await asyncio.wait_for(websocket.receive_text(), HELLO_TIMEOUT_S)
            hello = client_message.validate_json(raw)
            if not isinstance(hello, Hello) or not secrets.compare_digest(hello.token, token):
                raise ValueError("token incorrecto")
        except (TimeoutError, ValidationError, ValueError):
            log.warning("WebSocket rechazado: falta el token o es incorrecto")
            await emit(ErrorEvent(message="Acceso denegado: token incorrecto."))
            await websocket.close(code=CLOSE_UNAUTHORIZED)
            return
        except WebSocketDisconnect:
            return

        log.info("Cliente conectado (%s)", origin or "sin origen")
        await emit(Ready(assistant=settings.assistant.name, model=model.name))
        orchestrator = Orchestrator(model, settings.system_prompt, store, emit)
        try:
            await orchestrator.send_history()
            while True:
                raw = await websocket.receive_text()
                try:
                    msg = client_message.validate_json(raw)
                except ValidationError:
                    await emit(ErrorEvent(message="Mensaje no válido."))
                    continue
                if isinstance(msg, ChatRequest):
                    await orchestrator.handle_text(msg.text)
                elif isinstance(msg, Reset):
                    await orchestrator.reset()
        except WebSocketDisconnect:
            log.info("Cliente desconectado")
