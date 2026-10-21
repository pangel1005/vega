"""Orquestador de una conversación: máquina de estados + historial en memoria.

Estados: idle → thinking → speaking → idle. Si el modelo falla: → error, y el
siguiente mensaje vuelve a empezar desde thinking. `listening` y `acting` se
usarán con la voz (paso 5) y las herramientas (fase 4).
"""

from __future__ import annotations

import logging
import time
from collections.abc import Awaitable, Callable

from server.llm.base import ChatMessage, ChatModel, LlmUnavailable
from server.ws.protocol import DoneEvent, ErrorEvent, ServerEvent, State, StateEvent, TokenEvent

log = logging.getLogger(__name__)

Emit = Callable[[ServerEvent], Awaitable[None]]

# Mensajes (usuario + asistente) que se envían al modelo como contexto.
MAX_HISTORY = 20


class Orchestrator:
    def __init__(self, model: ChatModel, system_prompt: str, emit: Emit) -> None:
        self._model = model
        self._system = ChatMessage("system", system_prompt)
        self._emit = emit
        self._history: list[ChatMessage] = []
        self._state = State.IDLE

    @property
    def state(self) -> State:
        return self._state

    @property
    def history(self) -> list[ChatMessage]:
        return list(self._history)

    async def _set_state(self, state: State) -> None:
        if state != self._state:
            self._state = state
            await self._emit(StateEvent(state=state))

    async def handle_text(self, text: str) -> None:
        question = ChatMessage("user", text)
        context = [self._system, *self._history[-MAX_HISTORY:], question]
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
        self._history += [question, ChatMessage("assistant", reply)]
        log.info(
            "Respuesta de %s: primera palabra %.2f s, total %.2f s, %d caracteres",
            self._model.name,
            first_token or 0.0,
            time.perf_counter() - start,
            len(reply),
        )
        await self._emit(DoneEvent(text=reply))
        await self._set_state(State.IDLE)
