"""Arranque: `uv run python -m server`."""

from __future__ import annotations

import logging
import sys
import threading

import uvicorn

from server.app import create_app
from server.core.config import ROOT, ConfigError, Settings, load_settings
from server.core.logs import setup_logging
from server.core.token import load_or_create_token
from server.device.media import MediaWatcher
from server.device.usb import UsbWatcher, find_adb
from server.speech.base import SttUnavailable
from server.speech.tts import KokoroTTS, PiperTTS, TtsUnavailable, VoiceRouter
from server.speech.whisper import WhisperSTT
from server.tools.actions import ActionConfigError, ActionRunner, load_actions
from server.tools.app_icons import AppIcons
from server.tools.windows import WindowsExecutor

log = logging.getLogger("server")


def build_stt(settings: Settings) -> WhisperSTT:
    s = settings.speech
    stt = WhisperSTT(
        s.stt_model,
        s.stt_device,
        s.stt_compute,
        settings.assistant.language,
        s.models_path,
    )

    # Se carga en segundo plano: el servidor atiende ya mientras tanto, y la primera
    # frase hablada no tiene que esperar a que el modelo se cargue.
    def warm() -> None:
        try:
            stt.warmup()
        except SttUnavailable as exc:
            log.error("%s", exc)

    threading.Thread(target=warm, name="stt-warmup", daemon=True).start()
    return stt


def build_tts(settings: Settings) -> VoiceRouter | None:
    s = settings.speech
    if not s.tts_enabled:
        return None
    prefs = ROOT / "data" / "prefs.json"
    tts = VoiceRouter(
        KokoroTTS(ROOT / "models" / "kokoro", speed=s.tts_speed, accent=s.tts_accent),
        PiperTTS(s.voices_path, "es_MX-claude-high", prefs),
        s.tts_voice,
        prefs,
    )
    if not tts.voices():
        log.warning("No hay voces en %s: el asistente responderá solo por escrito", s.voices_path)
        return None

    def warm() -> None:
        try:
            tts.warmup()
        except TtsUnavailable as exc:
            log.error("%s", exc)

    threading.Thread(target=warm, name="tts-warmup", daemon=True).start()
    return tts


def build_usb_watcher(settings: Settings, token: str) -> UsbWatcher | None:
    d = settings.device
    if not d.usb_watcher:
        return None
    adb = d.adb_path or find_adb()
    if not adb:
        log.warning("No encuentro adb: el teléfono no se enlazará solo por USB")
        return None
    watcher = UsbWatcher(
        adb,
        settings.server.port,
        token,
        open_app=d.open_app_on_connect,
        memory_file=ROOT / "data" / "telefono.txt",
    )
    watcher.start()
    log.info("Vigilante USB activo (%s)", adb)
    return watcher


def main() -> int:
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"Error de configuración: {exc}", file=sys.stderr)
        return 2
    setup_logging(settings.logging)
    try:
        actions = ActionRunner(load_actions(), WindowsExecutor())
    except ActionConfigError as exc:
        print(f"Error en los botones: {exc}", file=sys.stderr)
        return 2
    log.info("Panel con %d botones", len(actions.actions))
    token = load_or_create_token()
    usb = build_usb_watcher(settings, token)
    uvicorn.run(
        create_app(
            settings,
            token=token,
            stt=build_stt(settings),
            actions=actions,
            tts=build_tts(settings),
            phone=usb.status if usb else None,
            phone_battery=usb.battery if usb else (lambda: None),
            app_icons=AppIcons(actions.actions, ROOT / "data" / "icons"),
            media=MediaWatcher(),
        ),
        host=settings.server.host,
        port=settings.server.port,
        log_config=None,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
