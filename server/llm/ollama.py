"""`ChatModel` sobre la API local de Ollama (`/api/chat` en streaming)."""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator, Sequence

import httpx

from server.core.http import async_client
from server.llm.base import ChatMessage, LlmUnavailable

log = logging.getLogger(__name__)

# La primera respuesta tras arrancar puede tardar: Ollama tiene que cargar el modelo (de 10 a
# 20 s en la CPU; luego se queda en memoria el tiempo de `keep_alive`).
_TIMEOUT = httpx.Timeout(connect=3.0, read=120.0, write=10.0, pool=5.0)


class OllamaChat:
    def __init__(
        self,
        url: str,
        model: str,
        keep_alive: str = "10m",
        transport: httpx.AsyncBaseTransport | None = None,
        gpu_layers: int = 1,
        threads: int = 8,
        context: int = 2048,
        temperature: float | None = None,
    ) -> None:
        self._url = url.rstrip("/")
        self._model = model
        self._keep_alive = keep_alive
        self._transport = transport
        # Reparto entre la gráfica y el procesador (medido en la RTX 4060 Ti + Ryzen 7 5700X):
        #   gpu_layers = 1 (por defecto): ~650 MB de vídeo; la gráfica solo lee la pregunta
        #     (primera palabra en ~0,45 s) y el procesador escribe (~10 palabras/s).
        #   gpu_layers = 0: nada en la gráfica, pero la primera palabra tarda ~2,3 s.
        #   gpu_layers = -1: todo en la gráfica (~3,7 GB), lo más rápido.
        # El contexto (2048) reserva menos memoria que el de serie y sobra para charlas cortas.
        self._context = context
        self._temperature = temperature
        self._threads = threads
        self._layers = gpu_layers
        self._options = self._make_options(gpu_layers)
        self._active = 0  # respuestas en curso (no se cambia de sitio el modelo a mitad de una)

    def _make_options(self, gpu_layers: int) -> dict[str, float]:
        options: dict[str, float] = {"num_ctx": self._context}
        if self._temperature is not None:
            options["temperature"] = self._temperature
        if gpu_layers >= 0:
            options |= {"num_gpu": gpu_layers, "num_thread": self._threads}
        return options

    @property
    def name(self) -> str:
        return self._model

    @property
    def gpu_layers(self) -> int:
        return self._layers

    @property
    def busy(self) -> bool:
        return self._active > 0

    async def vram_mib(self) -> int | None:
        """Vídeo que ocupa el modelo ahora mismo (MiB), o None si no está cargado (o Ollama no responde)."""
        try:
            async with async_client(timeout=_TIMEOUT, transport=self._transport) as client:
                resp = await client.get(f"{self._url}/api/ps")
            for item in resp.json().get("models", []):
                if item.get("name") == self._model or item.get("model") == self._model:
                    return int(item.get("size_vram", 0)) // (1024 * 1024)
        except (httpx.HTTPError, ValueError):
            log.debug("No pude preguntar a Ollama qué hay cargado", exc_info=True)
        return None

    async def place(self, gpu_layers: int, *, reload: bool = True) -> None:
        """Cambia cuántas capas van en la gráfica. Si el modelo estaba cargado se recarga ya con el
        reparto nuevo (si no, se aplica la próxima vez que se cargue: dormida, o con la IA en el teléfono).
        Con `reload` falso solo se libera la gráfica y no se vuelve a cargar."""
        if gpu_layers == self._layers:
            return
        was_loaded = await self.vram_mib() is not None
        self._layers = gpu_layers
        self._options = self._make_options(gpu_layers)
        if was_loaded:
            await self.unload()
            if reload:
                await self.preload()
        log.info("Modelo de lenguaje con %s capas en la gráfica", "todas" if gpu_layers < 0 else gpu_layers)

    async def preload(self) -> None:
        # Una petición sin texto solo carga el modelo en memoria (o renueva su keep_alive).
        try:
            async with async_client(timeout=_TIMEOUT, transport=self._transport) as client:
                await client.post(
                    f"{self._url}/api/generate",
                    json={"model": self._model, "keep_alive": self._keep_alive, "options": self._options},
                )
        except httpx.HTTPError as exc:
            log.debug("No se pudo precargar el modelo: %s", exc)

    async def unload(self) -> None:
        # keep_alive 0: Ollama libera el modelo en cuanto termina esta petición.
        try:
            async with async_client(timeout=_TIMEOUT, transport=self._transport) as client:
                await client.post(f"{self._url}/api/generate", json={"model": self._model, "keep_alive": 0})
        except httpx.HTTPError as exc:
            log.debug("No se pudo descargar el modelo de la memoria: %s", exc)

    async def stream(self, messages: Sequence[ChatMessage]) -> AsyncIterator[str]:
        self._active += 1
        try:
            async for piece in self._stream(messages):
                yield piece
        finally:
            self._active -= 1

    async def _stream(self, messages: Sequence[ChatMessage]) -> AsyncIterator[str]:
        payload = {
            "model": self._model,
            "messages": [{"role": m.role, "content": m.content} for m in messages],
            "stream": True,
            # En voz no queremos que el modelo «piense en voz alta»: retrasa la primera palabra.
            "think": False,
            # Pasado este tiempo sin uso, Ollama libera la memoria.
            "keep_alive": self._keep_alive,
            "options": self._options,
        }
        try:
            async with (
                async_client(timeout=_TIMEOUT, transport=self._transport) as client,
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
