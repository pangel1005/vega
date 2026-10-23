"""Ruta WebSocket `/ws`: autenticación con token y bucle de mensajes."""

from __future__ import annotations

import asyncio
import logging
import secrets

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from server.core.config import Settings
from server.core.hub import Hub
from server.core.orchestrator import Orchestrator
from server.llm.base import ChatModel
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.speech.base import SpeechToText
from server.speech.tts import TextToSpeech
from server.tools.actions import ActionRunner
from server.ws.protocol import (
    ActionConfirm,
    ActionRequest,
    Attention,
    ChatRequest,
    ErrorEvent,
    Hello,
    ListenCancel,
    ListenStart,
    ListenStop,
    MediaControl,
    PlaybackDone,
    Ready,
    Reset,
    ServerEvent,
    SetVoice,
    WakeMode,
    client_message,
)

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
    app: FastAPI,
    settings: Settings,
    model: ChatModel,
    store: ConversationStore,
    token: str,
    stt: SpeechToText | None = None,
    actions: ActionRunner | None = None,
    tts: TextToSpeech | None = None,
    skills: SkillRouter | None = None,
    memory: PersonalMemory | None = None,
    hub: Hub | None = None,
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

        # Un solo candado para todo lo que se envía: así la cabecera de una frase de voz y su
        # audio salen juntos, sin que otro mensaje se cuele entre medias.
        send_lock = asyncio.Lock()

        async def emit(event: ServerEvent) -> None:
            async with send_lock:
                await websocket.send_text(event.model_dump_json())

        async def emit_audio(header: ServerEvent, pcm: bytes) -> None:
            async with send_lock:
                await websocket.send_text(header.model_dump_json())
                await websocket.send_bytes(pcm)

        try:
            raw = await asyncio.wait_for(websocket.receive_text(), HELLO_TIMEOUT_S)
            hello = client_message.validate_json(raw)
            if not isinstance(hello, Hello) or not secrets.compare_digest(hello.token, token):
                raise ValueError("token incorrecto")
        except (TimeoutError, ValidationError, ValueError, KeyError):  # KeyError: llegó binario
            log.warning("WebSocket rechazado: falta el token o es incorrecto")
            await emit(ErrorEvent(message="Acceso denegado: token incorrecto."))
            await websocket.close(code=CLOSE_UNAUTHORIZED)
            return
        except WebSocketDisconnect:
            return

        log.info("Cliente conectado (%s)", origin or "sin origen")
        await emit(Ready(assistant=settings.assistant.name, model=model.name))
        orchestrator = Orchestrator(
            model,
            settings.system_prompt,
            store,
            emit,
            stt=stt,
            max_audio_seconds=settings.speech.max_seconds,
            actions=actions,
            name=settings.assistant.name,
            tts=tts,
            emit_audio=emit_audio,
            skills=skills,
            memory=memory,
        )
        if hub is not None:
            hub.add(orchestrator)
        try:
            await orchestrator.send_actions()
            await orchestrator.send_voices()
            await orchestrator.send_history()
            while True:
                message = await websocket.receive()
                if message["type"] == "websocket.disconnect":
                    raise WebSocketDisconnect(message.get("code", 1000))
                # Los mensajes binarios son audio del micrófono.
                if (chunk := message.get("bytes")) is not None:
                    orchestrator.add_audio(chunk)
                    continue
                try:
                    msg = client_message.validate_json(message.get("text") or "")
                except ValidationError:
                    await emit(ErrorEvent(message="Mensaje no válido."))
                    continue
                if isinstance(msg, ChatRequest):
                    await orchestrator.handle_command(msg.text)
                elif isinstance(msg, Reset):
                    await orchestrator.reset()
                elif isinstance(msg, ListenStart):
                    await orchestrator.start_listening()
                elif isinstance(msg, ListenStop):
                    await orchestrator.stop_listening()
                elif isinstance(msg, ListenCancel):
                    await orchestrator.cancel_listening()
                elif isinstance(msg, ActionRequest):
                    await orchestrator.request_action(msg.id)
                elif isinstance(msg, ActionConfirm):
                    await orchestrator.confirm_action(msg.nonce)
                elif isinstance(msg, WakeMode):
                    await orchestrator.set_wake_mode(msg.on)
                elif isinstance(msg, Attention):
                    await orchestrator.attention()
                elif isinstance(msg, SetVoice):
                    await orchestrator.set_voice(msg.voice)
                elif isinstance(msg, PlaybackDone):
                    await orchestrator.playback_done()
                elif isinstance(msg, MediaControl) and hub is not None:
                    await hub.media_control(msg.command)
        except WebSocketDisconnect:
            log.info("Cliente desconectado")
        finally:
            if hub is not None:
                hub.remove(orchestrator)
            await orchestrator.close()
