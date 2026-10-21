"""Arranque: `uv run python -m server`."""

from __future__ import annotations

import sys

import uvicorn

from server.app import create_app
from server.core.config import ConfigError, load_settings
from server.core.logs import setup_logging


def main() -> int:
    try:
        settings = load_settings()
    except ConfigError as exc:
        print(f"Error de configuración: {exc}", file=sys.stderr)
        return 2
    setup_logging(settings.logging)
    uvicorn.run(
        create_app(settings),
        host=settings.server.host,
        port=settings.server.port,
        log_config=None,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
