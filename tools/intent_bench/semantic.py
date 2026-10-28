"""El entendimiento por significado de producción (server/skills/semantic.py), con la forma que usa el banco."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from server.skills.semantic import FastEmbedder, SemanticIntents

ROOT = Path(__file__).resolve().parents[2]


class SemanticRouter:
    def __init__(self, actions: list[Any]) -> None:
        self._intents = SemanticIntents(lambda: actions, lambda: FastEmbedder(ROOT / "models" / "embeddings"))
        self._intents.warm()

    def decide(self, phrase: str) -> tuple[str, str, float]:
        m = self._intents.match(phrase)
        if m is None:
            return "chat", "chat", 0.0
        return f"{m.kind}:{m.id}", m.mode, m.score

    def route(self, phrase: str) -> str:
        label, mode, _ = self.decide(phrase)
        return label if mode == "act" else "chat"
