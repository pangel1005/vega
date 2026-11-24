"""Aplicación FastAPI."""

from __future__ import annotations

import asyncio
import logging
import os
import secrets
import signal
import threading
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path

import httpx
from fastapi import FastAPI, Request, Response
from starlette.middleware.trustedhost import TrustedHostMiddleware

from server import __version__
from server.core.car import CarConfig
from server.core.config import ROOT, Settings
from server.core.http import async_client
from server.core.http import warm as warm_http
from server.core.hub import Hub
from server.core.maintenance import Maintenance
from server.core.orchestrator import Orchestrator
from server.core.placement import GpuFollower, PlacementState, nvidia_free_mib
from server.core.stallwatch import StallWatch
from server.core.telegram import (
    Reply,
    TelegramApi,
    TelegramBridge,
    TelegramListener,
    load_config,
    make_pending,
    make_status,
    windows_power,
)
from server.device.audio_capture import SpotifyTap
from server.device.audio_meter import AudioMeter
from server.device.camera import CameraController
from server.device.cs2 import Cs2State, cfg_installed, load_token
from server.device.forza import ForzaTelemetry
from server.device.games import GameWatcher, epic_manifests, steam_id64, steam_libraries, steam_root
from server.device.lyrics import LyricsFinder
from server.device.match import MatchSource, RocketLeagueStats
from server.device.media import MediaWatcher, blocking_pause
from server.device.mixer import Mixer
from server.device.notifications import NotificationReader
from server.device.steam_web import SteamWeb, read_key
from server.device.usb import PhoneStatus
from server.device.wallpaper import WallpaperEngine, find_wallpaper_engine
from server.llm.base import ChatModel
from server.llm.ollama import OllamaChat
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.desk import Desk
from server.skills.moodle import MoodleSync, Task, TaskBook, ical_source
from server.skills.moodle import load_config as load_moodle_config
from server.skills.moodle_api import MoodleApi
from server.skills.router import SkillRouter
from server.skills.semantic import FastEmbedder, SemanticIntents
from server.skills.streak import GymLog
from server.skills.weather import WeatherService
from server.speech.base import SpeechToText
from server.speech.tts import TextToSpeech
from server.speech.whisper import WhisperSTT
from server.tools.actions import ActionRunner
from server.tools.app_icons import AppIcons
from server.web import (
    register_app_icons,
    register_game_art,
    register_gsi,
    register_home,
    register_lyrics,
    register_media_art,
    register_notification_icons,
    register_phone_models,
    register_wallpaper_art,
)
from server.ws.protocol import DoneEvent, ErrorEvent, ServerEvent
from server.ws.routes import register_ws
from server.ws.xiaozhi import Services as XiaozhiServices
from server.ws.xiaozhi import register_xiaozhi

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
            async with async_client(timeout=2.0) as client:
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
    follow_gpu: bool = False,
    placement: PlacementState | None = None,
    camera: CameraController | None = None,
    desk: Desk | None = None,
    telegram: TelegramBridge | None = None,
) -> FastAPI:
    """Crea la aplicación. Sin `stt` no hay voz; sin `actions`, el panel no tiene botones."""
    probe = llm_probe or ollama_probe(settings.llm.url)
    if follow_gpu and settings.llm.follow_game and placement is None:
        placement = PlacementState(hybrid=settings.llm.phone_chat)
    model = chat_model or OllamaChat(
        settings.llm.url,
        settings.llm.model,
        settings.llm.keep_alive,
        gpu_layers=settings.llm.gpu_layers,
        threads=settings.llm.cpu_threads,
        context=settings.llm.context,
        temperature=settings.llm.temperature,
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
    cs2_state: list[Cs2State] = []
    if settings.games.enabled and media is not None:  # sin «media» (pruebas) no hay nada del PC que mirar
        steam = steam_root()
        key_file = ROOT / "config" / "steam_key.txt"
        key = read_key(key_file.read_text(encoding="utf-8")) if key_file.is_file() else None
        if key_file.is_file() and key is None:
            log.warning(
                "config/steam_key.txt no tiene una clave de Steam válida (32 caracteres hexadecimales)"
            )
        # Las APIs locales de las partidas (League of Legends, Rocket League) solo se leen si se permite.
        cs2 = Cs2State(load_token(ROOT / "data" / "gsi_token.txt"))
        cs2_state.append(cs2)
        holder: list[GameWatcher] = []
        rocket = RocketLeagueStats(
            lambda: holder[0].rocket_ini() if holder else None, port=settings.games.rocket_port
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
            matches=MatchSource(
                rocket,
                cs2=cs2,
                cs2_installed=lambda: cfg_installed(holder[0].steam_install(730)),
                forza=ForzaTelemetry(settings.games.forza_port),
            )
            if settings.games.match
            else None,
        )
        holder.append(games)
    if desk is None and settings.desk.enabled and follow_gpu:  # en las pruebas no se toca el PC de verdad
        d = settings.desk
        exe, workshop = find_wallpaper_engine(steam_libraries(steam_root()))
        desk = Desk(
            wallpaper=WallpaperEngine(exe, workshop) if d.wallpaper and exe else None,
            mixer=Mixer() if d.volume else None,
            games=games.installed_games if games is not None and d.games else None,
            launch=os.startfile,  # type: ignore[attr-defined,unused-ignore]
            running_game=lambda: (g.name if (g := games.current()) else None) if games is not None else None,
        )
    if desk is not None:
        skills.attach_desk(desk)
    gym = GymLog(history)
    skills.attach_gym(gym)
    moodle: MoodleSync | None = None
    book: TaskBook | None = None
    if settings.moodle.enabled and follow_gpu:  # en las pruebas no se sale a internet
        moodle_config = load_moodle_config(ROOT / "config" / "moodle.json")
        if moodle_config is not None:
            book = TaskBook(history, memory, alerts=settings.moodle.alerts)
            skills.attach_moodle(book)
            source: Callable[[], Awaitable[list[Task]]]
            if moodle_config.token and moodle_config.site:  # la conexión real: sabe qué ya entregaste
                api = MoodleApi(moodle_config.site, moodle_config.token)

                async def real_source() -> list[Task]:
                    return await api.pending(datetime.now())

                source, real = real_source, True
            else:  # solo el calendario exportado
                assert moodle_config.url is not None
                source = ical_source(moodle_config.url, settings.moodle.only_deadlines)
                real = False
            moodle = MoodleSync(
                book, source, authoritative=real, refresh_s=settings.moodle.refresh_minutes * 60
            )
    hub = Hub(
        memory,
        games=games,
        tasks=book.board if book is not None else None,
        task_done=book.mark_done_uid if book is not None else None,
        gym=gym,
        media=media,
        notifications=notifications if settings.notifications.enabled else None,
        level=AudioMeter().poll if media is not None else None,
        spectrum=SpotifyTap().poll if media is not None and settings.media.spectrum else None,
    )

    bridge = telegram
    if bridge is None and settings.telegram.enabled and follow_gpu:  # en las pruebas no se sale a internet
        telegram_config = load_config(ROOT / "config" / "telegram.json")
        if telegram_config is not None:

            async def telegram_answer(text: str) -> Reply:
                """Una pregunta de Telegram: como escribirle a Vega, pero sin botones del PC ni voz."""
                said: list[str] = []

                async def emit(event: ServerEvent) -> None:
                    if isinstance(event, DoneEvent | ErrorEvent):
                        said.append(event.text if isinstance(event, DoneEvent) else event.message)

                orchestrator = Orchestrator(
                    model,
                    settings.system_prompt,
                    history,
                    emit,
                    name=settings.assistant.name,
                    skills=skills,
                    memory=memory,
                    semantic=semantic,
                    placement=placement,
                )
                try:
                    await orchestrator.handle_command(text, spoken=False)
                finally:
                    await orchestrator.close()
                ask = orchestrator.last_ask
                return Reply(said[-1] if said else "", ask[0] if ask else None)

            async def before_off() -> str:
                """Lo que se perdería al apagar: el juego abierto."""
                game = await asyncio.to_thread(games.current) if games is not None else None
                return f"🎮 Tienes abierto {game.name}: se cerrará." if game else ""

            bridge = TelegramBridge(
                TelegramApi(telegram_config.token),
                telegram_config.chat_ids,
                telegram_answer,
                make_status(
                    hub.telemetry,
                    hub.has_phone,
                    lambda: (g.name if (g := games.current()) else None) if games is not None else None,
                ),
                make_pending(memory),
                # Solo si el botón «Apagar PC» existe en config/actions.toml (si lo quitas, Telegram tampoco).
                power=windows_power if actions is not None and actions.get("apagar") is not None else None,
                before_off=before_off,
            )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        await warm_http()  # el contexto SSL compartido, fuera del bucle (si no, cada cliente lo crea dentro)
        hub.start()  # revisa los recordatorios cada segundo
        if media is not None:  # «buenas noches» pausa la música (las habilidades corren en otro hilo)
            skills.attach_music(blocking_pause(media, asyncio.get_running_loop()))
        watch = StallWatch(ROOT / "logs" / "cuelgues")
        watch.start(asyncio.get_running_loop())
        if semantic is not None:  # el modelo se carga en segundo plano: el arranque no espera
            asyncio.get_running_loop().run_in_executor(None, semantic.warm)
        weather_task = asyncio.create_task(weather.run()) if weather and weather_refresh else None
        follower_task = None
        if follow_gpu and settings.llm.follow_game and isinstance(model, OllamaChat):
            s, sp = settings.llm, settings.speech
            follower = GpuFollower(
                chat=model,
                stt=stt if isinstance(stt, WhisperSTT) else None,
                gaming=lambda: games is not None and games.current() is not None,
                free_mib=nvidia_free_mib,
                light_layers=s.gpu_layers,
                free_layers=s.gpu_layers_free,
                stt_free=(sp.stt_model, sp.stt_device, sp.stt_compute),
                stt_game=(sp.stt_game_model, "cpu", "int8"),
                need_mib=s.gpu_free_mib,
                state=placement,
            )
            follower_task = asyncio.create_task(follower.run())
        moodle_task = asyncio.create_task(moodle.run()) if moodle else None
        telegram_task = None
        if bridge is not None:
            if settings.telegram.reminders != "never":
                hub.add(TelegramListener(bridge, hub.has_phone, settings.telegram.reminders), "telegram")
            telegram_task = asyncio.create_task(bridge.run())
        yield
        if telegram_task:
            telegram_task.cancel()
        if moodle_task:
            moodle_task.cancel()
        if weather_task:
            weather_task.cancel()
        if follower_task:
            follower_task.cancel()
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
        placement,
        camera,
        desk,
    )
    if settings.xiaozhi.enabled:
        register_xiaozhi(
            app,
            XiaozhiServices(
                settings, model, history, token, stt, tts, actions, skills, memory, semantic, placement
            ),
        )
    register_home(app)
    if app_icons is not None:
        register_app_icons(app, app_icons)
    register_media_art(app, hub.media_art)
    register_game_art(app, hub.game_art)
    if desk is not None:
        register_wallpaper_art(app, desk.wallpaper_thumbnail)
    if cs2_state:
        register_gsi(app, cs2_state[0].feed)
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
