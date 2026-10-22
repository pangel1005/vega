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
from server.speech.base import SttUnavailable
from server.speech.tts import PiperTTS, TtsUnavailable
from server.speech.whisper import WhisperSTT
from server.tools.actions import ActionConfigError, ActionRunner, load_actions
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


def build_tts(settings: Settings) -> PiperTTS | None:
    s = settings.speech
    if not s.tts_enabled:
        return None
    tts = PiperTTS(s.voices_path, s.tts_voice, ROOT / "data" / "prefs.json")
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
    uvicorn.run(
        create_app(
            settings,
            token=load_or_create_token(),
            stt=build_stt(settings),
            actions=actions,
            tts=build_tts(settings),
        ),
        host=settings.server.host,
        port=settings.server.port,
        log_config=None,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
