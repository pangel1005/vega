"""Prueba de la escucha continua con audio real: simula un micrófono que no para.

    uv run python scripts/wake_test.py frase1.wav frase2.wav ...

Envía silencio + cada WAV + silencio a velocidad real (como el teléfono) y muestra
lo que hace el servidor. Las acciones se ejecutan de verdad: usa frases inofensivas.
"""

from __future__ import annotations

import json
import sys
import threading
import time
import wave
from pathlib import Path

from websockets.sync.client import connect

from server.core.token import TOKEN_FILE

CHUNK = 3200  # 100 ms


def pcm(path: Path) -> bytes:
    with wave.open(str(path)) as w:
        return w.readframes(w.getnframes())


def main() -> int:
    token = TOKEN_FILE.read_text(encoding="utf-8").strip()
    quiet = bytes(CHUNK * 10)  # 1 s de silencio
    audio = quiet + b"".join(pcm(Path(p)) + quiet + quiet for p in sys.argv[1:]) + quiet * 3
    t0 = time.perf_counter()

    with connect("ws://127.0.0.1:8000/ws") as ws:
        ws.send(json.dumps({"type": "hello", "token": token}))
        for _ in range(3):
            ws.recv()
        ws.send(json.dumps({"type": "wake_mode", "on": True}))

        def reader() -> None:
            try:
                while True:
                    event = json.loads(ws.recv())
                    stamp = f"{time.perf_counter() - t0:5.2f} s"
                    kind = event["type"]
                    if kind == "token":
                        print(event["text"], end="", flush=True)
                    elif kind == "state":
                        print(f"\n[{stamp}] estado: {event['state']}", flush=True)
                    else:
                        print(f"\n[{stamp}] {kind}: { {k: v for k, v in event.items() if k != 'type'} }", flush=True)
            except Exception:
                pass

        threading.Thread(target=reader, daemon=True).start()
        for i in range(0, len(audio), CHUNK):
            ws.send(audio[i : i + CHUNK])
            time.sleep(0.1)
        time.sleep(3)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
