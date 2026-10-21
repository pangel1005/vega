"""`ChatModel` sobre la API local de Ollama (`/api/chat` en streaming)."""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator, Sequence

import httpx

from server.llm.base import ChatMessage, LlmUnavailable

log = logging.getLogger(__name__)

# La primera respuesta tras arrancar puede tardar: Ollama tiene que cargar el modelo en la GPU.
_TIMEOUT = httpx.Timeout(connect=3.0, read=120.0, write=10.0, pool=5.0)


class OllamaChat:
    def __init__(
        self,
        url: str,
        model: str,
        keep_alive: str = "10m",
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._url = url.rstrip("/")
        self._model = model
        self._keep_alive = keep_alive
        self._transport = transport

    @property
    def name(self) -> str:
        return self._model

    async def stream(self, messages: Sequence[ChatMessage]) -> AsyncIterator[str]:
        payload = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "stream": True,
            # En voz no queremos que el modelo «piense en voz alta»: retrasa la primera palabra.
            "think": False,
            # Pasado este tiempo sin uso, Ollama libera la memoria de vídeo.
            "keep_alive": self._keep_alive,
        }
        try:
            async with (
                httpx.AsyncClient(timeout=_TIMEOUT, transport=self._transport) as client,
                client.stream("POST", f"{self._url}/api/chat", json=payload) as resp,
            ):
                if resp.status_code == 404:
                    raise LlmUnavailable(
                        f"El modelo «{self._model}» no está descargado. Ejecuta: ollama pull {self._model}"
                    )
                if resp.status_code >= 400:
                    body = (await resp.aread()).decode(errors="replace")[:200]
                    raise LlmUnavailable(f"Ollama devolvió un error {resp.status_code}: {body}")
                async for line in resp.aiter_lines():
                    if not line:
                        continue
                    chunk = json.loads(line)
                    if "error" in chunk:
                        raise LlmUnavailable(f"Ollama devolvió un error: {chunk['error']}")
                    piece = chunk.get("message", {}).get("content", "")
                    if piece:
                        yield piece
                    if chunk.get("done"):
                        return
        except httpx.ConnectError as exc:
            raise LlmUnavailable(
                f"No puedo conectar con Ollama en {self._url}. ¿Está abierto? Búscalo en el menú Inicio."
            ) from exc
        except httpx.TimeoutException as exc:
            raise LlmUnavailable("Ollama tardó demasiado en responder.") from exc
        except httpx.HTTPError as exc:
            log.exception("Fallo de comunicación con Ollama")
            raise LlmUnavailable(f"Se cortó la comunicación con Ollama ({type(exc).__name__}).") from exc
