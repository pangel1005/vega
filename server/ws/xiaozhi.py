"""Altavoces ESP32 con el protocolo «xiaozhi»: un PROTOTIPO para que Vega tenga satélites de voz baratos.

El proyecto abierto `78/xiaozhi-esp32` es el firmware de muchas placas ESP32 con micrófono, altavoz y
pantalla: detecta la palabra de activación en la propia placa y habla con un servidor por WebSocket. Aquí
está ese servidor, hecho con lo de Vega (Whisper, el modelo del PC, las habilidades y Kokoro) en vez del
servidor en la nube del fabricante.

Protocolo (según su documentación `docs/websocket.md`; no se ha probado con una placa de verdad):
- La placa abre `ws://…/xiaozhi/v1/` con `Authorization: Bearer <token de Vega>` y `Protocol-Version`, y
  manda un `hello` (con `audio_params`); el servidor contesta con otro `hello` (`transport: websocket`,
  `session_id`, `audio_params`).
- Audio: Opus mono a 16 kHz en paquetes de 60 ms, en los dos sentidos, como mensajes binarios. La versión
  del protocolo elige el marco: 1 = Opus sin más; 2 = cabecera de 16 bytes con marca de tiempo; 3 = cabecera
  de 4 bytes.
- La placa manda `listen` (`start` con `mode` auto/manual/realtime, `stop`, `detect`) y `abort`; el
  servidor contesta con `stt` (lo que entendió) y `tts` (`start`, `sentence_start` con el texto, `stop`)
  más el audio de la respuesta.

Lo que NO hace todavía: MCP (los mensajes `mcp` se ignoran), reconocer quién habla ni cancelar el eco en el
servidor. Solo atiende a `127.0.0.1` (como todo Vega): para una placa de verdad en la red de casa habría que
abrir el servidor a la red, y eso es una decisión de seguridad aparte (cifrado y token), no de este
prototipo. Por eso va desactivado: `[xiaozhi]` en `config/assistant.toml`.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import secrets
import struct
import uuid
from dataclasses import dataclass
from typing import Any

from fastapi import FastAPI, WebSocket, WebSocketDisconnect

from server.core.config import Settings
from server.core.orchestrator import Orchestrator
from server.core.placement import PlacementState
from server.llm.base import ChatModel
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.skills.semantic import SemanticIntents
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SpeechToText, SttUnavailable
from server.speech.listener import SpeechSegmenter
from server.speech.opus import FRAME_MS, RATE, OpusDecoder, OpusEncoder
from server.speech.tts import TextToSpeech
from server.tools.actions import ActionRunner
from server.ws.protocol import AudioEnd, AudioEvent, DoneEvent, ErrorEvent, ServerEvent
from server.ws.routes import CLOSE_FORBIDDEN_ORIGIN, CLOSE_UNAUTHORIZED, allowed_origins

log = logging.getLogger(__name__)

PATH = "/xiaozhi/v1/"
HELLO_TIMEOUT_S = 10.0
PREBUFFER_FRAMES = 5  # paquetes que se mandan de golpe para que la placa no se quede sin audio
LATE_AFTER_S = 0.3  # si el envío va así de retrasado, la placa ya se quedó sin audio: se vuelve a empezar

# Marcos de audio por versión del protocolo.
V2 = struct.Struct(">HHIII")  # versión, tipo (0 = Opus, 1 = JSON), reservado, marca de tiempo (ms), tamaño
V3 = struct.Struct(">BBH")  # tipo, reservado, tamaño


def frame(version: int, packet: bytes, timestamp_ms: int = 0) -> bytes:
    """Un paquete Opus listo para enviar a la placa."""
    if version == 2:
        return V2.pack(2, 0, 0, timestamp_ms & 0xFFFFFFFF, len(packet)) + packet
    if version == 3:
        return V3.pack(0, 0, len(packet)) + packet
    return packet


def unframe(version: int, data: bytes) -> bytes:
    """El paquete Opus de un mensaje binario de la placa (b"" si no es audio o está mal formado)."""
    if version == 2:
        if len(data) < V2.size:
            return b""
        _v, kind, _r, _t, size = V2.unpack_from(data)
        payload = data[V2.size : V2.size + size]
        return payload if kind == 0 and len(payload) == size else b""
    if version == 3:
        if len(data) < V3.size:
            return b""
        kind, _r, size = V3.unpack_from(data)
        payload = data[V3.size : V3.size + size]
        return payload if kind == 0 and len(payload) == size else b""
    return data


@dataclass
class Services:
    """Lo que el servidor de Vega le presta a cada placa."""

    settings: Settings
    model: ChatModel
    store: ConversationStore
    token: str
    stt: SpeechToText | None = None
    tts: TextToSpeech | None = None
    actions: ActionRunner | None = None
    skills: SkillRouter | None = None
    memory: PersonalMemory | None = None
    semantic: SemanticIntents | None = None
    placement: PlacementState | None = None


class Turn:
    """Una pregunta y su respuesta. Si se aborta, lo que aún llegue de ella se tira."""

    def __init__(self) -> None:
        self.cancelled = False
        self.spoke = False
        self.reply = ""
        self.encoder: OpusEncoder | None = None


Out = dict[str, Any] | bytes


class Session:
    def __init__(self, websocket: WebSocket, services: Services, version: int) -> None:
        self.ws = websocket
        self.s = services
        self.version = version
        self.decoder = OpusDecoder()
        self.segmenter = SpeechSegmenter()
        self.mode: str | None = None  # auto, manual, realtime; None = no escucha
        self.buffer: bytearray | None = None
        self.out: asyncio.Queue[Out] = asyncio.Queue()
        self.turn: Turn | None = None
        self.turn_task: asyncio.Task[None] | None = None
        self.max_bytes = services.settings.speech.max_seconds * SAMPLE_RATE * BYTES_PER_SAMPLE
        self.session_id = str(uuid.uuid4())

    # --- Envío (todo sale por una cola: así el orden es el correcto y el audio va al ritmo de la voz) ---

    def say_json(self, message: dict[str, Any]) -> None:
        self.out.put_nowait(message)

    async def sender(self) -> None:
        loop = asyncio.get_running_loop()
        play_from = loop.time()
        sent = 0
        while True:
            item = await self.out.get()
            if isinstance(item, bytes):
                now = loop.time()
                if now > play_from + sent * FRAME_MS / 1000 + LATE_AFTER_S:
                    play_from, sent = now, 0  # la placa se quedó sin audio: se cuenta desde ahora
                due = play_from + (sent - PREBUFFER_FRAMES) * FRAME_MS / 1000
                if due > now:
                    await asyncio.sleep(due - now)
                await self.ws.send_bytes(frame(self.version, item, int(sent * FRAME_MS)))
                sent += 1
                continue
            if item.get("type") == "tts" and item.get("state") == "start":
                play_from, sent = loop.time(), 0
            await self.ws.send_text(json.dumps(item, ensure_ascii=False))

    # --- Lo que dice el orquestador, traducido al protocolo de la placa ---

    def listener_for(self, turn: Turn) -> tuple[Any, Any]:
        async def emit(event: ServerEvent) -> None:
            if turn.cancelled:
                return
            if isinstance(event, DoneEvent):
                turn.reply = event.text
            elif isinstance(event, ErrorEvent):
                self.say_json(
                    {"type": "alert", "status": "Error", "message": event.message, "emotion": "sad"}
                )
            elif isinstance(event, AudioEnd) and turn.encoder is not None:
                for packet in turn.encoder.flush():
                    self.out.put_nowait(packet)
                turn.encoder = None
                self.say_json({"type": "tts", "state": "stop"})

        async def emit_audio(header: ServerEvent, pcm: bytes) -> None:
            if turn.cancelled or not isinstance(header, AudioEvent):
                return
            if turn.encoder is None:
                turn.encoder = OpusEncoder()
                turn.spoke = True
                self.say_json({"type": "tts", "state": "start", "session_id": self.session_id})
            self.say_json({"type": "tts", "state": "sentence_start", "text": header.text})
            for packet in await asyncio.to_thread(turn.encoder.encode, pcm, header.rate):
                self.out.put_nowait(packet)

        return emit, emit_audio

    async def run_turn(self, text: str, turn: Turn) -> None:
        emit, emit_audio = self.listener_for(turn)
        sv = self.s
        orchestrator = Orchestrator(
            sv.model,
            sv.settings.system_prompt,
            sv.store,
            emit,
            stt=None,  # la voz se transcribe aquí, no en el orquestador
            actions=sv.actions,
            name=sv.settings.assistant.name,
            tts=sv.tts,
            emit_audio=emit_audio,
            skills=sv.skills,
            memory=sv.memory,
            semantic=sv.semantic,
            placement=sv.placement,
        )
        try:
            await orchestrator.handle_command(text, spoken=True)
        finally:
            await orchestrator.close()
        if (
            not turn.cancelled and not turn.spoke and turn.reply
        ):  # sin voz en el PC: al menos el texto, en pantalla
            self.say_json({"type": "tts", "state": "start", "session_id": self.session_id})
            self.say_json({"type": "tts", "state": "sentence_start", "text": turn.reply})
            self.say_json({"type": "tts", "state": "stop"})

    async def hear(self, pcm: bytes) -> None:
        """Transcribe una frase de la placa y, si se entendió algo, la contesta."""
        if self.s.stt is None:
            self.say_json(
                {
                    "type": "alert",
                    "status": "Error",
                    "message": "Sin reconocimiento de voz.",
                    "emotion": "sad",
                }
            )
            return
        try:
            result = await asyncio.to_thread(self.s.stt.transcribe, pcm)
        except SttUnavailable as exc:
            self.say_json({"type": "alert", "status": "Error", "message": str(exc), "emotion": "sad"})
            return
        text = result.text.strip()
        if not text:
            return
        self.say_json({"type": "stt", "text": text})
        turn = Turn()
        self.turn = turn
        await self.run_turn(text, turn)

    def start_turn_from(self, pcm: bytes) -> None:
        if self.turn_task is not None and not self.turn_task.done():
            log.info("Placa: frase ignorada, aún contesto a la anterior")
            return
        self.turn_task = asyncio.create_task(self.hear(pcm))

    def abort(self) -> None:
        """La placa interrumpe (se habló encima de Vega o se pulsó): lo que quede de la respuesta se tira."""
        if self.turn is not None:
            self.turn.cancelled = True
        if self.turn_task is not None and not self.turn_task.done():
            self.turn_task.cancel()
        while not self.out.empty():
            self.out.get_nowait()
        self.say_json({"type": "tts", "state": "stop"})

    # --- Lo que manda la placa ---

    def on_audio(self, data: bytes) -> None:
        if self.buffer is None:
            return
        pcm = self.decoder.decode(unframe(self.version, data))
        if not pcm:
            return
        if self.mode == "manual":
            self.buffer += pcm[: max(0, self.max_bytes - len(self.buffer))]
            return
        for phrase in self.segmenter.feed(pcm):  # auto y realtime: el servidor corta las frases
            self.buffer = None  # mientras contesta, no se sigue grabando
            self.start_turn_from(phrase)
            return

    def on_listen(self, message: dict[str, Any]) -> None:
        state = message.get("state")
        if state == "start":
            self.mode = str(message.get("mode") or "auto")
            self.buffer = bytearray()
            self.segmenter.reset()
        elif state == "stop":
            audio, self.buffer = self.buffer, None
            if self.mode == "manual" and audio:
                self.start_turn_from(bytes(audio))
        elif state == "detect":
            log.info("Placa: palabra de activación detectada en la placa")

    def on_json(self, message: dict[str, Any]) -> None:
        kind = message.get("type")
        if kind == "listen":
            self.on_listen(message)
        elif kind == "abort":
            self.abort()
        elif kind == "mcp":
            log.debug("Placa: mensaje MCP ignorado (el prototipo no lo usa)")
        else:
            log.debug("Placa: mensaje desconocido «%s»", kind)

    async def run(self) -> None:
        sender = asyncio.create_task(self.sender())
        try:
            while True:
                message = await self.ws.receive()
                if message["type"] == "websocket.disconnect":
                    return
                if (data := message.get("bytes")) is not None:
                    self.on_audio(data)
                    continue
                try:
                    parsed = json.loads(message.get("text") or "")
                except ValueError:
                    continue
                if isinstance(parsed, dict):
                    self.on_json(parsed)
        finally:
            self.abort()
            sender.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await sender


def register_xiaozhi(app: FastAPI, services: Services) -> None:
    origins = allowed_origins(services.settings)

    async def endpoint(websocket: WebSocket) -> None:
        origin = websocket.headers.get("origin")
        if origin is not None and origin not in origins:
            await websocket.close(code=CLOSE_FORBIDDEN_ORIGIN)
            return
        await websocket.accept()
        auth = websocket.headers.get("authorization", "")
        given = auth.removeprefix("Bearer ").strip() or websocket.query_params.get("token", "")
        if not secrets.compare_digest(given.encode(), services.token.encode()):
            log.warning("Placa rechazada: falta el token o es incorrecto")
            await websocket.close(code=CLOSE_UNAUTHORIZED)
            return
        try:
            raw = await asyncio.wait_for(websocket.receive_text(), HELLO_TIMEOUT_S)
            hello = json.loads(raw)
            if not isinstance(hello, dict) or hello.get("type") != "hello":
                raise ValueError("el primer mensaje no es un hello")
        except (TimeoutError, ValueError, KeyError):
            await websocket.close(code=1002)
            return
        except WebSocketDisconnect:
            return
        try:
            version = int(hello.get("version") or websocket.headers.get("protocol-version") or 1)
        except ValueError:
            version = 1
        session = Session(websocket, services, version if version in (1, 2, 3) else 1)
        await websocket.send_text(
            json.dumps(
                {
                    "type": "hello",
                    "transport": "websocket",
                    "session_id": session.session_id,
                    "audio_params": {
                        "format": "opus",
                        "sample_rate": RATE,
                        "channels": 1,
                        "frame_duration": FRAME_MS,
                    },
                }
            )
        )
        log.info(
            "Placa xiaozhi conectada (protocolo %d, dispositivo %s)",
            session.version,
            websocket.headers.get("device-id", "?"),
        )
        with contextlib.suppress(WebSocketDisconnect):
            await session.run()
        log.info("Placa xiaozhi desconectada")

    app.websocket(PATH)(endpoint)
    app.websocket(PATH.rstrip("/"))(endpoint)
