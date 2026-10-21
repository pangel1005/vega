"""Arranque: `uv run python -m server`."""

from __future__ import annotations

import logging
import sys
import threading

import uvicorn

from server.app import create_app
from server.core.config import ConfigError, Settings, load_settings
from server.core.logs import setup_logging
from server.core.token import load_or_create_token
from server.speech.base import SttUnavailable
from server.speech.whisper import WhisperSTT

log = logging.getLogger("server")


def build_stt(settings: Settings) -> WhisperSTT:
    s = settings.speech
    stt = WhisperSTT(s.stt_model, s.stt_device, s.stt_compute, settings.assistant.language, s.models_path)

    # Se carga en segundo plano: el servidor atiende ya mientras tanto, y la primera
    # frase hablada no tiene que esperar a que el modelo se cargue.
    def warm() -> None:
        try:
            stt.warmup()
        except SttUnavailable as exc:
            log.error("%s", exc)

    threading.Thread(target=warm, name="stt-warmup", daemon=True).start()
    return stt


def main() -> int:
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"Error de configuración: {exc}", file=sys.stderr)
        return 2
    setup_logging(settings.logging)
    uvicorn.run(
        create_app(settings, token=load_or_create_token(), stt=build_stt(settings)),
        host=settings.server.host,
        port=settings.server.port,
        log_config=None,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
