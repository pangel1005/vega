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
from server.ws.protocol import (
    DoneEvent,
    ErrorEvent,
    HistoryEvent,
    HistoryItem,
    ServerEvent,
    State,
    StateEvent,
    TokenEvent,
)

log = logging.getLogger(__name__)

Emit = Callable[[ServerEvent], Awaitable[None]]

# Mensajes (usuario + asistente) que se envían al modelo como contexto.
MAX_HISTORY = 20


class Orchestrator:
    def __init__(self, model: ChatModel, system_prompt: str, store: ConversationStore, emit: Emit) -> None:
        self._model = model
        self._system = ChatMessage("system", system_prompt)
        self._store = store
        self._emit = emit
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
