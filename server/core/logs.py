"""Registros en consola y en archivo rotativo (`logs/server.log`)."""

from __future__ import annotations

import logging
from logging.handlers import RotatingFileHandler

from server.core.config import LoggingSettings

FORMAT = "%(asctime)s %(levelname)-7s %(name)s: %(message)s"
LOG_FILE = "server.log"
AUDIT_FILE = "audit.log"


class _HideQuery(logging.Filter):
    """Quita de las líneas de acceso de uvicorn lo que va tras «?» (títulos de canciones, artistas…)."""

    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if isinstance(args, tuple) and len(args) >= 3 and isinstance(args[2], str) and "?" in args[2]:
            record.args = (*args[:2], args[2].split("?", 1)[0] + "?…", *args[3:])
        return True


def setup_logging(settings: LoggingSettings) -> None:
    settings.path.mkdir(parents=True, exist_ok=True)
    formatter = logging.Formatter(FORMAT)

    file_handler = RotatingFileHandler(
        settings.path / LOG_FILE, maxBytes=1_000_000, backupCount=5, encoding="utf-8"
    )
    console = logging.StreamHandler()
    for handler in (file_handler, console):
        handler.setFormatter(formatter)

    root = logging.getLogger()
    root.handlers.clear()
    root.addHandler(file_handler)
    root.addHandler(console)
    root.setLevel(settings.level)

    # Auditoría de acciones del panel: archivo propio (además del registro general).
    audit_handler = RotatingFileHandler(
        settings.path / AUDIT_FILE, maxBytes=1_000_000, backupCount=10, encoding="utf-8"
    )
    audit_handler.setFormatter(logging.Formatter("%(asctime)s %(message)s"))
    audit = logging.getLogger("audit")
    audit.handlers.clear()
    audit.addHandler(audit_handler)
    audit.setLevel(logging.INFO)

    # Uvicorn trae sus propios manejadores; los redirigimos a los nuestros.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        lg = logging.getLogger(name)
        lg.handlers.clear()
        lg.propagate = True
    logging.getLogger("uvicorn.access").addFilter(_HideQuery())
    logging.getLogger("httpx").setLevel(
        logging.WARNING
    )  # a INFO escribe cada URL, con el título de lo que suena
