"""Clientes HTTP que no congelan el servidor.

httpx crea un contexto SSL nuevo (lee todos los certificados del sistema) cada vez que se crea un
cliente, incluso para hablar con Ollama por http://127.0.0.1. Es trabajo síncrono de cientos de
milisegundos (segundos con el disco ocupado o el antivirus mirando) y, hecho dentro del bucle de
asyncio, deja a Vega sin atender: el vigilante de cuelgues lo vio muchas veces (Moodle, Ollama…).

Aquí se crea UNA vez, en otro hilo al arrancar (`warm`), y todos los clientes la comparten.
"""

from __future__ import annotations

import asyncio
import ssl
import threading
from typing import Any

import httpx

_context: ssl.SSLContext | None = None
_lock = threading.Lock()


def ssl_context() -> ssl.SSLContext:
    """El contexto SSL compartido (se crea la primera vez que se pide)."""
    global _context
    if _context is None:
        with _lock:
            if _context is None:
                _context = httpx.create_ssl_context()
    return _context


async def warm() -> None:
    """Crea el contexto en otro hilo, para que la primera petición no lo haga dentro del bucle."""
    await asyncio.to_thread(ssl_context)


def async_client(**kwargs: Any) -> httpx.AsyncClient:
    """Como `httpx.AsyncClient(...)`, pero reutilizando el contexto SSL."""
    kwargs.setdefault("verify", ssl_context())
    return httpx.AsyncClient(**kwargs)
