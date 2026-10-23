"""Aplicación FastAPI."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import httpx
from fastapi import FastAPI
from starlette.middleware.trustedhost import TrustedHostMiddleware

from server import __version__
from server.core.config import Settings
from server.core.hub import Hub
from server.device.media import MediaWatcher
from server.device.usb import PhoneStatus
from server.llm.base import ChatModel
from server.llm.ollama import OllamaChat
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.speech.base import SpeechToText
from server.speech.tts import TextToSpeech
from server.tools.actions import ActionRunner
from server.tools.app_icons import AppIcons
from server.web import UI_DIST, register_app_icons, register_media_art, register_ui
from server.ws.routes import register_ws

log = logging.getLogger(__name__)

TRUSTED_HOSTS = ("127.0.0.1", "localhost")


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
    store: ConversationStore | None = None,
    ui_dist: Path = UI_DIST,
    stt: SpeechToText | None = None,
    actions: ActionRunner | None = None,
    tts: TextToSpeech | None = None,
    phone: PhoneStatus | None = None,
    phone_battery: Callable[[], int | None] = lambda: None,
    app_icons: AppIcons | None = None,
    media: MediaWatcher | None = None,
) -> FastAPI:
    """Crea la aplicación. Sin `stt` no hay voz; sin `actions`, el panel no tiene botones."""
    probe = llm_probe or ollama_probe(settings.llm.url)
    model = chat_model or OllamaChat(
        settings.llm.url,
        settings.llm.model,
        settings.llm.keep_alive,
        gpu_layers=settings.llm.gpu_layers,
        threads=settings.llm.threads,
        context=settings.llm.context,
    )
    history = store or ConversationStore(settings.memory.path)
    memory = PersonalMemory(history)
    skills = SkillRouter(memory, phone_battery=phone_battery)
    hub = Hub(memory, media=media)

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        hub.start()  # revisa los recordatorios cada segundo
        yield
        await hub.stop()

    # Sin páginas de documentación automática: menos superficie expuesta.
    app = FastAPI(
        title=settings.assistant.name, version=__version__, docs_url=None, redoc_url=None, lifespan=lifespan
    )
    app.state.hub = hub
    app.state.settings = settings
    # Solo se atienden peticiones dirigidas a 127.0.0.1/localhost. Esto frena el
    # «DNS rebinding»: una web maliciosa que apunta su dominio a 127.0.0.1 para leer el token.
    app.add_middleware(TrustedHostMiddleware, allowed_hosts=list(TRUSTED_HOSTS))
    register_ws(app, settings, model, history, token, stt, actions, tts, skills, memory, hub)
    register_ui(app, token, ui_dist)
    if app_icons is not None:
        register_app_icons(app, app_icons)
    register_media_art(app, hub.media_art)

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
            "stt": stt.name if stt else None,
            "tts": tts.voice if tts else None,
            "telefono": phone.summary() if phone else None,
        }

    log.info(
        "%s %s listo en http://%s:%d",
        settings.assistant.name,
        __version__,
        settings.server.host,
        settings.server.port,
    )
    return app
