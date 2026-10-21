"""Orquestador de una conversación: máquina de estados + historial persistente.

Estados: idle → thinking → speaking → idle. Si el modelo falla: → error, y el
siguiente mensaje vuelve a empezar desde thinking. `listening` y `acting` se
usarán con la voz (paso 5) y las herramientas (fase 4).

El historial vive en SQLite y se lee en cada turno, así sobrevive a reinicios y
todos los clientes conectados comparten la misma conversación.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Awaitable, Callable

from server.llm.base import ChatMessage, ChatModel, LlmUnavailable
from server.memory.store import ConversationStore
from server.speech.base import BYTES_PER_SAMPLE, SAMPLE_RATE, SpeechToText, SttUnavailable
from server.tools.actions import CONFIRM_SECONDS, ActionRunner, Outcome
from server.ws.protocol import (
    ActionResultEvent,
    ActionsEvent,
    ConfirmEvent,
    DoneEvent,
    ErrorEvent,
    HistoryEvent,
    HistoryItem,
    ServerEvent,
    State,
    StateEvent,
    TokenEvent,
    TranscriptEvent,
)

log = logging.getLogger(__name__)

Emit = Callable[[ServerEvent], Awaitable[None]]

# Mensajes (usuario + asistente) que se envían al modelo como contexto.
MAX_HISTORY = 20


class Orchestrator:
    def __init__(
        self,
        model: ChatModel,
        system_prompt: str,
        store: ConversationStore,
        emit: Emit,
        stt: SpeechToText | None = None,
        max_audio_seconds: int = 30,
        actions: ActionRunner | None = None,
    ) -> None:
        self._actions = actions
        self._model = model
        self._system = ChatMessage("system", system_prompt)
        self._store = store
        self._emit = emit
        self._stt = stt
        self._max_audio_bytes = max_audio_seconds * SAMPLE_RATE * BYTES_PER_SAMPLE
        self._audio: bytearray | None = None  # None = no se está grabando
        self._preload: asyncio.Task[None] | None = None
        self._state = State.IDLE

    @property
    def state(self) -> State:
        return self._state

    async def _set_state(self, state: State) -> None:
        if state != self._state:
            self._state = state
            await self._emit(StateEvent(state=state))

    async def send_history(self) -> None:
        conversation = await asyncio.to_thread(self._store.latest_conversation)
        stored = await asyncio.to_thread(self._store.recent, conversation, MAX_HISTORY)
        await self._emit(HistoryEvent(messages=[HistoryItem(role=m.role, content=m.content) for m in stored]))

    async def reset(self) -> None:
        """Empieza una conversación nueva; la anterior queda guardada."""
        await asyncio.to_thread(self._store.new_conversation)
        await self._emit(HistoryEvent(messages=[]))
        await self._set_state(State.IDLE)

    # --- Voz ---

    async def start_listening(self) -> None:
        if self._stt is None:
            await self._emit(ErrorEvent(message="El reconocimiento de voz no está disponible."))
            return
        self._audio = bytearray()
        # Mientras el usuario habla, se va cargando el modelo de lenguaje si estaba descargado
        # de la GPU: así la respuesta no espera a la carga al terminar la transcripción.
        self._preload = asyncio.create_task(self._model.preload())
        await self._set_state(State.LISTENING)

    def add_audio(self, chunk: bytes) -> None:
        """Añade audio recibido. Se ignora si no se está grabando o si ya se llegó al máximo."""
        if self._audio is None or len(chunk) % BYTES_PER_SAMPLE:
            return
        room = self._max_audio_bytes - len(self._audio)
        if room > 0:
            self._audio += chunk[:room]

    async def cancel_listening(self) -> None:
        self._audio = None
        await self._set_state(State.IDLE)

    async def stop_listening(self) -> None:
        """Transcribe lo grabado y, si se entendió algo, responde como a un mensaje escrito."""
        audio, self._audio = self._audio, None
        if audio is None or self._stt is None:
            return
        await self._set_state(State.THINKING)
        try:
            result = await asyncio.to_thread(self._stt.transcribe, bytes(audio))
        except SttUnavailable as exc:
            await self._emit(ErrorEvent(message=str(exc)))
            await self._set_state(State.ERROR)
            return
        await self._emit(
            TranscriptEvent(
                text=result.text,
                audio_seconds=round(result.audio_seconds, 2),
                elapsed_ms=round(result.elapsed_seconds * 1000),
            )
        )
        if not result.text:
            await self._set_state(State.IDLE)
            return
        await self.handle_text(result.text)

    # --- Botones del panel ---

    async def send_actions(self) -> None:
        if self._actions is not None:
            await self._emit(ActionsEvent(items=[a.public() for a in self._actions.actions]))

    async def request_action(self, action_id: str) -> None:
        if self._actions is not None:
            await self._handle_outcome(
                await asyncio.to_thread(self._actions.request, action_id, "ws"), action_id
            )

    async def confirm_action(self, nonce: str) -> None:
        if self._actions is not None:
            await self._handle_outcome(await asyncio.to_thread(self._actions.confirm, nonce, "ws"), "")

    async def _handle_outcome(self, outcome: Outcome, requested_id: str) -> None:
        action_id = outcome.action.id if outcome.action else requested_id
        if outcome.kind == "ignored":
            return
        if outcome.kind == "confirm" and outcome.nonce:
            await self._emit(
                ConfirmEvent(
                    nonce=outcome.nonce,
                    id=action_id,
                    message=outcome.message,
                    expires_in=int(CONFIRM_SECONDS),
                )
            )
            return
        await self._set_state(State.ACTING)
        await self._emit(ActionResultEvent(id=action_id, ok=outcome.kind == "done", message=outcome.message))
        await self._set_state(State.IDLE)

    # --- Texto ---

    async def handle_text(self, text: str) -> None:
        question = ChatMessage("user", text)
        conversation = await asyncio.to_thread(self._store.latest_conversation)
        previous = await asyncio.to_thread(self._store.recent, conversation, MAX_HISTORY)
        context = [self._system, *(m.as_chat() for m in previous), question]
        await self._set_state(State.THINKING)

        start = time.perf_counter()
        first_token: float | None = None
        parts: list[str] = []
        try:
            async for piece in self._model.stream(context):
                if first_token is None:
                    first_token = time.perf_counter() - start
                    await self._set_state(State.SPEAKING)
                parts.append(piece)
                await self._emit(TokenEvent(text=piece))
        except LlmUnavailable as exc:
            log.warning("El modelo no respondió: %s", exc)
            await self._emit(ErrorEvent(message=str(exc)))
            await self._set_state(State.ERROR)
            return

        reply = "".join(parts).strip()
        # Pregunta y respuesta se guardan juntas: una pregunta sin respuesta no entra al historial.
        await asyncio.to_thread(
            self._store.add_messages, conversation, [question, ChatMessage("assistant", reply)]
        )
        log.info(
            "Respuesta de %s: primera palabra %.2f s, total %.2f s, %d caracteres",
            self._model.name,
            first_token or 0.0,
            time.perf_counter() - start,
            len(reply),
        )
        await self._emit(DoneEvent(text=reply))
        await self._set_state(State.IDLE)
