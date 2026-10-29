"""Ruta WebSocket `/ws`: autenticación con token y bucle de mensajes."""

from __future__ import annotations

import asyncio
import base64
import binascii
import contextlib
import logging
import secrets
from collections.abc import Awaitable, Callable
from functools import partial

from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from server.core.car import CarConfig, CarError
from server.core.config import Settings
from server.core.hub import Hub
from server.core.maintenance import Maintenance
from server.core.orchestrator import Orchestrator
from server.llm.base import ChatModel
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.skills.semantic import SemanticIntents
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SpeechToText, SttUnavailable
from server.speech.tts import TextToSpeech
from server.tools.actions import ActionRunner, load_actions
from server.ws.protocol import (
    ActionConfirm,
    ActionRequest,
    ActionResultEvent,
    Attention,
    Brain,
    ChatRequest,
    ConfirmEvent,
    DoneEvent,
    ErrorEvent,
    Hear,
    HeardEvent,
    Hello,
    ListenCancel,
    ListenStart,
    ListenStop,
    MaintenanceEvent,
    MaintenanceRequest,
    MediaControl,
    MemoryImport,
    NotificationDismiss,
    PhoneCommand,
    PhoneControl,
    PhoneState,
    PlaybackDone,
    Ready,
    ReloadActions,
    ReloadCar,
    Reset,
    Say,
    ServerEvent,
    SetVoice,
    SleepMode,
    StateEvent,
    TokenEvent,
    TranscriptEvent,
    WakeMode,
    client_message,
)

log = logging.getLogger(__name__)

# Lo que pasa en el teléfono y se ve también en la app del PC.
MIRRORED = (StateEvent, TranscriptEvent, TokenEvent, DoneEvent, ErrorEvent, ConfirmEvent, ActionResultEvent)

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
    car: CarConfig | None = None,
    maintenance: Maintenance | None = None,
    semantic: SemanticIntents | None = None,
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
        role = "phone"  # se sabe tras el saludo

        async def emit(event: ServerEvent) -> None:
            async with send_lock:
                await websocket.send_text(event.model_dump_json())
            if role == "phone" and hub is not None and isinstance(event, MIRRORED):
                await hub.mirror(event)

        async def emit_audio(header: ServerEvent, pcm: bytes) -> None:
            async with send_lock:
                await websocket.send_text(header.model_dump_json())
                await websocket.send_bytes(pcm)

        try:
            raw = await asyncio.wait_for(websocket.receive_text(), HELLO_TIMEOUT_S)
            hello = client_message.validate_json(raw)
            if not isinstance(hello, Hello) or not secrets.compare_digest(
                hello.token.encode(), token.encode()
            ):
                raise ValueError("token incorrecto")
        except (TimeoutError, ValidationError, ValueError, KeyError):  # KeyError: llegó binario
            log.warning("WebSocket rechazado: falta el token o es incorrecto")
            with contextlib.suppress(Exception):  # si el cliente ya se fue, no hay a quién avisar
                await emit(ErrorEvent(message="Acceso denegado: token incorrecto."))
                await websocket.close(code=CLOSE_UNAUTHORIZED)
            return
        except WebSocketDisconnect:
            return

        role = hello.role
        log.info("Cliente conectado (%s, %s)", role, origin or "sin origen")
        await emit(Ready(assistant=settings.assistant.name, model=model.name))
        # La app del PC solo lee y escribe: no oye ni habla (eso es del teléfono).
        desktop = role == "desktop"
        orchestrator = Orchestrator(
            model,
            settings.system_prompt,
            store,
            emit,
            stt=None if desktop else stt,
            max_audio_seconds=settings.speech.max_seconds,
            actions=actions,
            name=settings.assistant.name,
            tts=None if desktop else tts,
            emit_audio=emit_audio,
            skills=skills,
            memory=memory,
            semantic=semantic,
        )
        if hub is not None:
            hub.add(orchestrator, role)

        # Lo lento (contestar con el modelo, transcribir, hablar) va en una cola aparte, una cosa tras otra:
        # así el bucle de lectura sigue atendiendo lo urgente (un botón, «Cancelar apagado») mientras Vega
        # contesta. Medido: «Cancelar apagado», pedido a los 0,3 s, se ejecutaba a los 3,1 s.
        slow: asyncio.Queue[Callable[[], Awaitable[None]]] = asyncio.Queue()

        async def slow_worker() -> None:
            while True:
                job = await slow.get()
                try:
                    await job()
                except Exception:  # un fallo no debe parar la cola ni cerrar la conexión
                    log.exception("Falló una orden lenta")
                    with contextlib.suppress(Exception):
                        await emit(ErrorEvent(message="No pude hacer eso; mira los registros."))

        async def hear(msg: Hear) -> None:
            """Transcribe una frase que cortó el teléfono. Nunca se guarda ni se escribe en los registros."""
            if stt is None:
                await emit(HeardEvent(id=msg.id, ok=False))
                return
            try:
                pcm = base64.b64decode(msg.audio, validate=True)
                pcm = pcm[: len(pcm) - len(pcm) % BYTES_PER_SAMPLE]
                if not pcm or len(pcm) > settings.speech.max_seconds * SAMPLE_RATE * BYTES_PER_SAMPLE:
                    raise ValueError("audio vacío o demasiado largo")
                result = await asyncio.to_thread(stt.transcribe, pcm)
            except (binascii.Error, ValueError, SttUnavailable) as exc:
                log.warning("No pude transcribir la frase del teléfono (%s)", type(exc).__name__)
                await emit(HeardEvent(id=msg.id, ok=False))
                return
            await emit(HeardEvent(id=msg.id, text=result.text))

        worker = asyncio.create_task(slow_worker())
        try:
            if desktop and hub is not None:
                await hub.send_phone_state(orchestrator)
            if car is not None:
                await emit(car.event())
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
                try:
                    if isinstance(msg, ChatRequest):
                        slow.put_nowait(partial(orchestrator.handle_command, msg.text))
                    elif isinstance(msg, Reset):
                        await orchestrator.reset()
                    elif isinstance(msg, ListenStart):
                        await orchestrator.start_listening()
                    elif isinstance(msg, ListenStop):
                        slow.put_nowait(orchestrator.stop_listening)
                    elif isinstance(msg, ListenCancel):
                        await orchestrator.cancel_listening()
                    elif isinstance(msg, ActionRequest):
                        # Sin esperar: «Cancelar apagado» no puede quedarse detrás de la respuesta del modelo.
                        orchestrator.spawn(orchestrator.request_action(msg.id))
                    elif isinstance(msg, ActionConfirm):
                        orchestrator.spawn(orchestrator.confirm_action(msg.nonce))
                    elif isinstance(msg, WakeMode):
                        await orchestrator.set_wake_mode(msg.on)
                    elif isinstance(msg, Attention):
                        await orchestrator.attention()
                    elif isinstance(msg, SetVoice):
                        await orchestrator.set_voice(msg.voice)
                    elif isinstance(msg, PlaybackDone):
                        await orchestrator.playback_done()
                    elif isinstance(msg, PhoneCommand):
                        slow.put_nowait(partial(orchestrator.phone_command, msg.text))
                    elif isinstance(msg, Hear) and role == "phone":
                        # Sin pasar por la cola lenta: el teléfono espera la transcripción y la voz que
                        # esté diciendo el PC no debe retrasarla.
                        orchestrator.spawn(hear(msg))
                    elif isinstance(msg, MemoryImport):
                        await orchestrator.import_memory(msg)
                    elif isinstance(msg, PhoneState) and hub is not None and role == "phone":
                        await hub.set_phone_state(msg)
                    elif isinstance(msg, PhoneControl) and hub is not None and desktop:
                        await hub.control_phone(msg.command)
                    elif isinstance(msg, ReloadActions) and actions is not None and desktop:
                        try:
                            actions.replace(load_actions())
                        except Exception as exc:  # un actions.toml mal escrito no debe tirar nada
                            await emit(ErrorEvent(message=f"config/actions.toml no es válido: {exc}"))
                        else:
                            if hub is not None:
                                await hub.refresh_actions()
                    elif isinstance(msg, MaintenanceRequest) and desktop:
                        await emit(await run_maintenance(maintenance, msg))
                    elif isinstance(msg, ReloadCar) and car is not None and desktop:
                        try:
                            car.reload()
                        except CarError as exc:
                            await emit(ErrorEvent(message=str(exc)))
                        else:
                            if hub is not None:
                                await hub.broadcast_all(car.event())
                    elif isinstance(msg, SleepMode):
                        await orchestrator.set_sleep(msg.on)
                    elif isinstance(msg, Say):
                        slow.put_nowait(partial(orchestrator.say_for_phone, msg.text))
                    elif isinstance(msg, Brain):
                        await orchestrator.set_brain(msg.where)
                    elif isinstance(msg, NotificationDismiss) and hub is not None:
                        await hub.dismiss_notification(msg.id)
                    elif isinstance(msg, MediaControl) and hub is not None:
                        await hub.media_control(msg.command, msg.position)
                except WebSocketDisconnect:
                    raise
                except Exception:  # un mensaje que falla no debe cerrar la conexión del teléfono
                    log.exception("Falló al atender «%s»", getattr(msg, "type", "?"))
                    with contextlib.suppress(Exception):
                        await emit(ErrorEvent(message="No pude hacer eso; mira los registros."))
        except WebSocketDisconnect:
            log.info("Cliente desconectado")
        finally:
            worker.cancel()
            if hub is not None:
                gone = hub.remove(orchestrator)
                if gone is not None:
                    await gone
            await orchestrator.close()


async def run_maintenance(maintenance: Maintenance | None, msg: MaintenanceRequest) -> MaintenanceEvent:
    """Ejecuta el mantenimiento en un hilo (mide y borra en disco: no debe frenar el servidor)."""
    if maintenance is None:
        return MaintenanceEvent(op=msg.op, ok=False, message="El mantenimiento no está disponible.")
    try:
        if msg.op == "status":
            return MaintenanceEvent(
                op=msg.op, data=(await asyncio.to_thread(maintenance.ram_status)).public()
            )
        if msg.op == "free_ram":
            result = await asyncio.to_thread(maintenance.free_ram, msg.deep)
            return MaintenanceEvent(op=msg.op, message=result.message(), data=result.public())
        if msg.op == "disk_scan":
            found = await asyncio.to_thread(maintenance.disk_scan)
            return MaintenanceEvent(op=msg.op, data={"categories": [c.public() for c in found]})
        cleaned = await asyncio.to_thread(maintenance.disk_clean, msg.ids)
        return MaintenanceEvent(op=msg.op, message=cleaned.message(), data=cleaned.public())
    except ValueError as exc:  # categoría desconocida
        return MaintenanceEvent(op=msg.op, ok=False, message=str(exc))
    except Exception:  # un fallo del sistema no debe tirar la conexión de la app
        log.exception("Falló el mantenimiento (%s)", msg.op)
        return MaintenanceEvent(op=msg.op, ok=False, message="No pude completarlo; mira los registros.")
