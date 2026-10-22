"""Cliente de consola para probar el WebSocket `/ws`.

    uv run python tools/ws_chat.py "¿Qué puedes hacer?"
    uv run python tools/ws_chat.py --wav frase.wav      (WAV PCM16 mono 16 kHz)

Imprime cada trozo de la respuesta según llega y mide los tiempos. Con --wav
envía el audio igual que la interfaz (bloques de 100 ms) y mide la transcripción.
"""

from __future__ import annotations

import json
import sys
import time
import wave
from pathlib import Path

from websockets.sync.client import ClientConnection, connect

from server.core.config import load_settings
from server.core.token import TOKEN_FILE

CHUNK_BYTES = 3200  # 100 ms de PCM16 a 16 kHz


def send_wav(ws: ClientConnection, path: Path) -> None:
    with wave.open(str(path)) as w:
        if (w.getframerate(), w.getsampwidth(), w.getnchannels()) != (16000, 2, 1):
            raise SystemExit("El WAV debe ser PCM16 mono a 16 kHz")
        pcm = w.readframes(w.getnframes())
    ws.send(json.dumps({"type": "listen_start"}))
    # A velocidad real (100 ms por bloque), como cuando alguien habla por el micrófono.
    for i in range(0, len(pcm), CHUNK_BYTES):
        ws.send(pcm[i : i + CHUNK_BYTES])
        time.sleep(0.1)


def main() -> int:
    args = sys.argv[1:]
    wav = Path(args[1]) if args[:1] == ["--wav"] and len(args) > 1 else None
    question = None if wav else (" ".join(args) or "Hola, ¿quién eres?")
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

        if wav:
            print(f"> [audio] {wav.name}")
            send_wav(ws, wav)
            start = time.perf_counter()  # desde que se suelta el botón
            ws.send(json.dumps({"type": "listen_stop"}))
        else:
            print(f"> {question}")
            start = time.perf_counter()
            ws.send(json.dumps({"type": "chat", "text": question}))

        first: float | None = None
        heard: float | None = None
        first_audio: float | None = None
        audio = bytearray()
        rate = 22050
        while True:
            raw = ws.recv()
            if isinstance(raw, bytes):  # voz de una frase (PCM16)
                audio += raw
                continue
            event = json.loads(raw)
            kind = event["type"]
            if kind == "audio":
                rate = event["rate"]
                if first_audio is None:
                    first_audio = time.perf_counter() - start
                print(f"\n[voz {event['seq']} a los {time.perf_counter() - start:.2f} s: «{event['text']}»]", flush=True)
            elif kind == "audio_end":
                print("\n[fin de la voz]", flush=True)
            elif kind == "history":
                print(f"[historial recuperado: {len(event['messages'])} mensajes]")
            elif kind == "transcript":
                heard = time.perf_counter() - start
                print(
                    f"[transcripción: «{event['text']}» · {event['audio_seconds']} s de audio,"
                    f" Whisper {event['elapsed_ms']} ms, recibida a los {heard:.2f} s]"
                )
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
    if first_audio is not None:
        out = Path("logs") / "ultima_voz.wav"
        with wave.open(str(out), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(rate)
            w.writeframes(bytes(audio))
        print(f"Primera voz: {first_audio:.2f} s | {len(audio) / 2 / rate:.1f} s de audio → {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
