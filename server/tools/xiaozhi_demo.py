"""Prueba del servidor «xiaozhi» de Vega sin placa: hace de ESP32, manda una frase y guarda la respuesta.

    python -m server.tools.xiaozhi_demo --text "¿qué hora es?"
    python -m server.tools.xiaozhi_demo --wav frase.wav --mode auto

Hace falta `[xiaozhi] enabled = true` en config/assistant.toml y el servidor en marcha. Con `--text` la
frase se sintetiza con la voz de Vega (Kokoro/Piper, en el procesador); con `--wav`, un WAV mono de
16 kHz. La respuesta (voz) se guarda en `--out` y se muestran los mensajes del protocolo con el tiempo
que tardó cada uno.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import wave
from pathlib import Path

import numpy as np
import websockets

from server.core.config import load_settings
from server.speech.opus import RATE, OpusDecoder, OpusEncoder

ROOT = Path(__file__).resolve().parents[2]


def read_wav(path: Path) -> bytes:
    with wave.open(str(path), "rb") as f:
        if (f.getframerate(), f.getnchannels(), f.getsampwidth()) != (RATE, 1, 2):
            raise SystemExit("El WAV debe ser mono, 16 bits y 16 kHz.")
        return f.readframes(f.getnframes())


def synthesize(text: str) -> bytes:
    """La frase dicha con la voz de Vega, a 16 kHz."""
    from server.__main__ import build_tts

    tts = build_tts(load_settings())
    if tts is None:
        raise SystemExit("No hay voz en este PC para sintetizar la frase: usa --wav.")
    pcm = tts.synthesize(text)
    return _to_16k(pcm, tts.sample_rate)


def _to_16k(pcm: bytes, rate: int) -> bytes:
    if rate == RATE:
        return pcm
    import av

    resampler = av.AudioResampler(format="s16", layout="mono", rate=RATE)
    frame = av.AudioFrame.from_ndarray(
        np.frombuffer(pcm, dtype=np.int16).reshape(1, -1), format="s16", layout="mono"
    )
    frame.sample_rate = rate
    out = b"".join(r.to_ndarray().tobytes() for r in [*resampler.resample(frame), *resampler.resample(None)])
    return out


async def talk(url: str, token: str, pcm: bytes, mode: str) -> list[bytes]:
    headers = {"Authorization": f"Bearer {token}", "Protocol-Version": "1", "Device-Id": "prueba-sin-placa"}
    start = time.monotonic()

    def say(what: str) -> None:
        print(f"[{time.monotonic() - start:6.2f} s] {what}")

    reply: list[bytes] = []
    async with websockets.connect(url, additional_headers=headers, max_size=None) as ws:
        await ws.send(
            json.dumps(
                {
                    "type": "hello",
                    "version": 1,
                    "transport": "websocket",
                    "features": {"mcp": True},
                    "audio_params": {
                        "format": "opus",
                        "sample_rate": RATE,
                        "channels": 1,
                        "frame_duration": 60,
                    },
                }
            )
        )
        say(f"servidor: {json.loads(await ws.recv())}")
        await ws.send(json.dumps({"type": "listen", "state": "start", "mode": mode}))
        encoder = OpusEncoder()
        packets = encoder.encode(pcm + (b"\x00\x00" * RATE if mode != "manual" else b"")) + encoder.flush()
        for packet in packets:
            await ws.send(packet)
            if mode != "manual":
                await asyncio.sleep(0.06)  # como una placa de verdad: en tiempo real
        if mode == "manual":
            await ws.send(json.dumps({"type": "listen", "state": "stop"}))
        say(f"enviados {len(packets)} paquetes Opus ({len(pcm) / 2 / RATE:.1f} s de voz)")
        frames = 0
        while True:
            message = await asyncio.wait_for(ws.recv(), 120)
            if isinstance(message, bytes):
                reply.append(message)
                frames += 1
                continue
            item = json.loads(message)
            if frames:
                say(f"({frames} paquetes de voz)")
                frames = 0
            say(str(item))
            if item.get("type") == "tts" and item.get("state") == "stop":
                return reply
            if item.get("type") == "alert":
                return reply


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--text", help="frase que se sintetiza y se manda")
    parser.add_argument("--wav", type=Path, help="WAV mono 16 kHz que se manda")
    parser.add_argument("--mode", choices=["manual", "auto"], default="manual")
    parser.add_argument("--url", default="ws://127.0.0.1:8000/xiaozhi/v1/")
    parser.add_argument("--out", type=Path, default=ROOT / "data" / "xiaozhi_respuesta.wav")
    args = parser.parse_args()
    if bool(args.text) == bool(args.wav):
        raise SystemExit("Da una frase con --text o un audio con --wav.")
    token = (ROOT / "config" / "token.txt").read_text(encoding="utf-8").strip()
    pcm = read_wav(args.wav) if args.wav else synthesize(args.text)
    reply = asyncio.run(talk(args.url, token, pcm, args.mode))
    decoder = OpusDecoder()
    audio = b"".join(decoder.decode(p) for p in reply)
    if not audio:
        print("La respuesta no trajo voz.")
        sys.exit(1)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(args.out), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(RATE)
        f.writeframes(audio)
    print(f"Respuesta de {len(audio) / 2 / RATE:.1f} s guardada en {args.out}")


if __name__ == "__main__":
    main()
