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
    phrases = [pcm(Path(p)) for p in sys.argv[1:]]
    t0 = time.perf_counter()
    phrase_end: list[float] = [0.0]  # cuándo terminó de «hablar» la última frase enviada
    first_voice_reported = [True]

    with connect("ws://127.0.0.1:8000/ws") as ws:
        ws.send(json.dumps({"type": "hello", "token": token}))
        ws.send(json.dumps({"type": "wake_mode", "on": True}))

        def reader() -> None:
            try:
                while True:
                    raw = ws.recv()
                    if isinstance(raw, bytes):
                        continue  # audio de la voz
                    event = json.loads(raw)
                    now = time.perf_counter() - t0
                    stamp = f"{now:5.2f} s"
                    kind = event["type"]
                    if kind in ("ready", "history", "actions", "voices"):
                        continue
                    if kind == "token":
                        print(event["text"], end="", flush=True)
                    elif kind == "state":
                        print(f"\n[{stamp}] estado: {event['state']}", flush=True)
                    elif kind == "audio" and not first_voice_reported[0]:
                        first_voice_reported[0] = True
                        print(
                            f"\n[{stamp}] >>> PRIMERA VOZ a los {now - phrase_end[0]:.2f} s de terminar de hablar",
                            flush=True,
                        )
                    elif kind != "audio":
                        print(f"\n[{stamp}] {kind}: { {k: v for k, v in event.items() if k != 'type'} }", flush=True)
            except Exception:
                pass

        threading.Thread(target=reader, daemon=True).start()

        def send(audio: bytes) -> None:
            for i in range(0, len(audio), CHUNK):
                ws.send(audio[i : i + CHUNK])
                time.sleep(0.1)

        send(quiet)
        for phrase in phrases:
            send(phrase)
            phrase_end[0] = time.perf_counter() - t0
            first_voice_reported[0] = False
            print(f"\n[{phrase_end[0]:5.2f} s] (fin de la frase)", flush=True)
            send(quiet * 6)  # 6 s de silencio: tiempo para responder
        time.sleep(3)
    print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
