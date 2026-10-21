"""Aplicación FastAPI."""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass

import httpx
from fastapi import FastAPI

from server import __version__
from server.core.config import Settings
from server.llm.base import ChatModel
from server.llm.ollama import OllamaChat
from server.ws.routes import register_ws

log = logging.getLogger(__name__)


@dataclass(frozen=True)
class LlmStatus:
    reachable: bool
    detail: str


LlmProbe = Callable[[], Awaitable[LlmStatus]]


def ollama_probe(url: str) -> LlmProbe:
    """Comprueba si Ollama responde, sin cargar ningún modelo."""

    async def probe() -> LlmStatus:
        try:
            async with httpx.AsyncClient(timeout=2.0) as client:
                resp = await client.get(f"{url}/api/version")
                resp.raise_for_status()
                return LlmStatus(True, f"Ollama {resp.json().get('version', '?')}")
        except (httpx.HTTPError, ValueError) as exc:
            return LlmStatus(False, f"Ollama no responde en {url} ({type(exc).__name__})")

    return probe


def create_app(
    settings: Settings,
    token: str,
    llm_probe: LlmProbe | None = None,
    chat_model: ChatModel | None = None,
) -> FastAPI:
    probe = llm_probe or ollama_probe(settings.llm.url)
    model = chat_model or OllamaChat(settings.llm.url, settings.llm.model, settings.llm.keep_alive)
    app = FastAPI(title=settings.assistant.name, version=__version__)
    app.state.settings = settings
    register_ws(app, settings, model, token)

    @app.get("/health")
    async def health() -> dict[str, object]:
        llm = await probe()
        if not llm.reachable:
            log.warning("health: %s", llm.detail)
        return {
            "status": "ok" if llm.reachable else "degraded",
            "assistant": settings.assistant.name,
            "version": __version__,
            "llm": {"model": settings.llm.model, "reachable": llm.reachable, "detail": llm.detail},
        }

    log.info(
        "%s %s listo en http://%s:%d",
        settings.assistant.name,
        __version__,
        settings.server.host,
        settings.server.port,
    )
    return app
