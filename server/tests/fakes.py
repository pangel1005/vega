"""Dobles de prueba. Solo se usan en los tests, nunca en el servidor real."""

from __future__ import annotations

from collections.abc import AsyncIterator, Sequence

from server.llm.base import ChatMessage, LlmUnavailable


class FakeChat:
    """Modelo que responde con trozos fijos y recuerda lo que recibió."""

    def __init__(self, pieces: Sequence[str] = ("Hola", ", ", "¿qué tal?"), fail: str | None = None) -> None:
        self.pieces = list(pieces)
        self.fail = fail
        self.calls: list[list[ChatMessage]] = []

    @property
    def name(self) -> str:
        return "fake"

    async def stream(self, messages: Sequence[ChatMessage]) -> AsyncIterator[str]:
        self.calls.append(list(messages))
        if self.fail:
            raise LlmUnavailable(self.fail)
        for piece in self.pieces:
            yield piece
