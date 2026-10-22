import asyncio
import json

import httpx
import pytest

from server.llm.base import ChatMessage, LlmUnavailable
from server.llm.ollama import OllamaChat

MESSAGES = [ChatMessage("user", "hola")]


def collect(chat: OllamaChat) -> list[str]:
    async def run() -> list[str]:
        return [piece async for piece in chat.stream(MESSAGES)]

    return asyncio.run(run())


def ndjson(*chunks: dict[str, object]) -> bytes:
    return b"".join(json.dumps(c).encode() + b"\n" for c in chunks)


def test_streams_pieces_and_disables_thinking() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        body = ndjson(
            {"message": {"content": "Ho"}, "done": False},
            {"message": {"content": "la"}, "done": False},
            {"message": {"content": ""}, "done": True},
        )
        return httpx.Response(200, content=body)

    chat = OllamaChat("http://ollama", "qwen3:8b", transport=httpx.MockTransport(handler))
    assert collect(chat) == ["Ho", "la"]
    assert seen["think"] is False and seen["stream"] is True and seen["model"] == "qwen3:8b"
    assert seen["keep_alive"] == "10m"
    # Por defecto, casi nada en la gráfica (1 capa) y un contexto pequeño: poca memoria de vídeo.
    assert seen["options"] == {"num_ctx": 2048, "num_gpu": 1, "num_thread": 8}


def test_gpu_can_be_enabled() -> None:
    seen: dict[str, object] = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(json.loads(request.content))
        return httpx.Response(200, content=ndjson({"message": {"content": "ok"}, "done": True}))

    chat = OllamaChat("http://ollama", "m", transport=httpx.MockTransport(handler), gpu_layers=-1)
    collect(chat)
    assert seen["options"] == {"num_ctx": 2048}  # todas las capas: Ollama las pone en la gráfica


def test_connection_refused_is_clear_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("refused", request=request)

    chat = OllamaChat("http://127.0.0.1:1", "qwen3:8b", transport=httpx.MockTransport(handler))
    with pytest.raises(LlmUnavailable, match="No puedo conectar con Ollama"):
        collect(chat)


def test_missing_model_tells_how_to_download() -> None:
    chat = OllamaChat(
        "http://ollama",
        "modelo-raro",
        transport=httpx.MockTransport(lambda r: httpx.Response(404, json={"error": "not found"})),
    )
    with pytest.raises(LlmUnavailable, match="ollama pull modelo-raro"):
        collect(chat)


def test_error_inside_stream() -> None:
    body = ndjson({"message": {"content": "a"}, "done": False}, {"error": "sin memoria"})
    chat = OllamaChat(
        "http://ollama",
        "qwen3:8b",
        transport=httpx.MockTransport(lambda r: httpx.Response(200, content=body)),
    )
    with pytest.raises(LlmUnavailable, match="sin memoria"):
        collect(chat)
