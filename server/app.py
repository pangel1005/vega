"""Aplicación FastAPI."""

from __future__ import annotations

import asyncio
import logging
import secrets
import signal
import threading
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from pathlib import Path

import httpx
from fastapi import FastAPI, Request, Response
from starlette.middleware.trustedhost import TrustedHostMiddleware

from server import __version__
from server.core.car import CarConfig
from server.core.config import ROOT, Settings
from server.core.hub import Hub
from server.core.maintenance import Maintenance
from server.core.stallwatch import StallWatch
from server.device.audio_capture import SpotifyTap
from server.device.audio_meter import AudioMeter
from server.device.games import GameWatcher, epic_manifests, steam_id64, steam_root
from server.device.lyrics import LyricsFinder
from server.device.media import MediaWatcher
from server.device.notifications import NotificationReader
from server.device.steam_web import SteamWeb, read_key
from server.device.usb import PhoneStatus
from server.llm.base import ChatModel
from server.llm.ollama import OllamaChat
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.skills.semantic import FastEmbedder, SemanticIntents
from server.skills.weather import WeatherService
from server.speech.base import SpeechToText
from server.speech.tts import TextToSpeech
from server.tools.actions import ActionRunner
from server.tools.app_icons import AppIcons
from server.web import (
    UI_DIST,
    register_app_icons,
    register_game_art,
    register_lyrics,
    register_media_art,
    register_notification_icons,
    register_phone_models,
    register_ui,
)
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
    notifications: NotificationReader | None = None,
    car: CarConfig | None = None,
    maintenance: Maintenance | None = None,
    weather_transport: httpx.AsyncBaseTransport | None = None,
    weather_refresh: bool = True,
    weather_cache: Path | None = None,
    semantic: SemanticIntents | None = None,
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
    # Las notificaciones las lee el centro de avisos (`hub`, justo debajo).
    weather = (
        WeatherService(
            settings.weather.latitude or 0.0,
            settings.weather.longitude or 0.0,
            settings.weather.city,
            weather_cache or ROOT / "data" / "weather.json",
            transport=weather_transport,
        )
        if settings.weather.ready
        else None
    )
    skills = SkillRouter(
        memory,
        phone_battery=phone_battery,
        notifications=lambda: hub.latest_notifications(),
        weather=weather.snapshot if weather else None,
        weather_ready=weather is not None,
    )
    if semantic is None and settings.intents.semantic and actions is not None:
        run = actions
        semantic = SemanticIntents(
            lambda: run.actions,
            lambda: FastEmbedder(ROOT / "models" / "embeddings"),
            act=settings.intents.act,
            ask=settings.intents.ask,
            margin=settings.intents.margin,
        )
    games = None
    if settings.games.enabled and media is not None:  # sin «media» (pruebas) no hay nada del PC que mirar
        steam = steam_root()
        key_file = ROOT / "config" / "steam_key.txt"
        key = read_key(key_file.read_text(encoding="utf-8")) if key_file.is_file() else None
        if key_file.is_file() and key is None:
            log.warning(
                "config/steam_key.txt no tiene una clave de Steam válida (32 caracteres hexadecimales)"
            )
        games = GameWatcher(
            steam,
            epic_manifests(),
            ROOT / "data" / "games",
            store_info=settings.games.store_info,
            ignore=settings.games.ignore,
            web=SteamWeb(key, steam_id64(steam), friends=settings.games.friends)
            if settings.games.store_info
            else None,
        )
    hub = Hub(
        memory,
        games=games,
        media=media,
        notifications=notifications if settings.notifications.enabled else None,
        level=AudioMeter().poll if media is not None else None,
        spectrum=SpotifyTap().poll if media is not None and settings.media.spectrum else None,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        hub.start()  # revisa los recordatorios cada segundo
        watch = StallWatch(ROOT / "logs" / "cuelgues")
        watch.start(asyncio.get_running_loop())
        if semantic is not None:  # el modelo se carga en segundo plano: el arranque no espera
            asyncio.get_running_loop().run_in_executor(None, semantic.warm)
        weather_task = asyncio.create_task(weather.run()) if weather and weather_refresh else None
        yield
        if weather_task:
            weather_task.cancel()
        watch.stop()
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
    register_ws(
        app,
        settings,
        model,
        history,
        token,
        stt,
        actions,
        tts,
        skills,
        memory,
        hub,
        car,
        maintenance,
        semantic,
    )
    register_ui(app, token, ui_dist)
    if app_icons is not None:
        register_app_icons(app, app_icons)
    register_media_art(app, hub.media_art)
    register_game_art(app, hub.game_art)
    register_phone_models(app)
    register_notification_icons(app, hub.notification_icon)
    register_lyrics(app, LyricsFinder(ROOT / "data" / "lyrics", enabled=settings.media.lyrics))

    @app.post("/admin/apagar", include_in_schema=False)
    async def shutdown(request: Request) -> Response:
        """Apagado limpio (lo usa scripts/stop.ps1): cerrar a la fuerza dejó una vez el proceso
        medio muerto 2 minutos, con el puerto ocupado. Pide el token del servidor."""
        if not secrets.compare_digest(request.headers.get("x-token", "").encode("latin-1"), token.encode()):
            return Response(status_code=403)
        log.info("Apagado pedido por scripts/stop.ps1")
        # Un poco después, para que la respuesta llegue antes de cerrar (Ctrl+C: uvicorn se apaga ordenado).
        threading.Timer(0.2, signal.raise_signal, [signal.SIGINT]).start()
        return Response(status_code=202)

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
