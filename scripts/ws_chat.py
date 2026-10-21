"""Cliente de consola para probar el WebSocket `/ws` (prueba de aceptación del paso 2).

    uv run python scripts/ws_chat.py "¿Qué puedes hacer?"

Imprime cada trozo de la respuesta según llega y mide la primera palabra.
"""

from __future__ import annotations

import json
import sys
import time

from websockets.sync.client import connect

from server.core.config import load_settings
from server.core.token import TOKEN_FILE


def main() -> int:
    question = " ".join(sys.argv[1:]) or "Hola, ¿quién eres?"
    settings = load_settings()
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    url = f"ws://{settings.server.host}:{settings.server.port}/ws"

    with connect(url) as ws:
        ws.send(json.dumps({"type": "hello", "token": token}))
        ready = json.loads(ws.recv())
        if ready["type"] != "ready":
            print(f"Rechazado: {ready}")
            return 1
        print(f"Conectado con {ready['assistant']} (modelo {ready['model']})")
        print(f"> {question}")

        start = time.perf_counter()
        first: float | None = None
        ws.send(json.dumps({"type": "chat", "text": question}))
        while True:
            event = json.loads(ws.recv())
            kind = event["type"]
            if kind == "history":
                print(f"[historial recuperado: {len(event['messages'])} mensajes]")
            elif kind == "state":
                print(f"\n[estado: {event['state']}]", flush=True)
                if event["state"] in ("idle", "error"):
                    break
            elif kind == "token":
                if first is None:
                    first = time.perf_counter() - start
                print(event["text"], end="", flush=True)
            elif kind == "error":
                print(f"\nERROR: {event['message']}")
    if first is not None:
        print(f"Primera palabra: {first:.2f} s | total: {time.perf_counter() - start:.2f} s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
