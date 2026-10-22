"""Prueba de aceptación del paso 0: Ollama responde en español.

Mide, para cada modelo indicado:
- tiempo hasta la primera palabra (lo que importa para la voz),
- tiempo total y velocidad en tokens por segundo,
- memoria de vídeo que ocupa el modelo (según Ollama y según nvidia-smi).

Solo usa la biblioteca estándar, para poder ejecutarse antes de instalar nada:

    uv run --python 3.12 --no-project tools/check_ollama.py qwen3:8b
"""

from __future__ import annotations

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
import urllib.response

OLLAMA_URL = "http://127.0.0.1:11434"
# Una pregunta distinta por medición: si se repitiera, Ollama reutilizaría el cálculo
# anterior y el tiempo «en caliente» saldría engañosamente bajo.
PROMPTS = {
    "en frío": "Preséntate en dos frases cortas y dime qué hora del día prefieres para trabajar.",
    "en caliente": "Dame un consejo breve para concentrarme mejor por la tarde.",
}
SYSTEM = "Eres un asistente de voz. Responde siempre en español, de forma breve y natural."
# Palabras muy frecuentes en español: si no aparece ninguna, la respuesta no está en español.
SPANISH_MARKERS = {" el ", " la ", " de ", " que ", " y ", " en ", " para ", " es "}


def _post_stream(path: str, payload: dict) -> urllib.response.addinfourl:
    req = urllib.request.Request(
        OLLAMA_URL + path,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    return urllib.request.urlopen(req, timeout=300)


def _get(path: str) -> dict:
    with urllib.request.urlopen(OLLAMA_URL + path, timeout=10) as resp:
        return json.load(resp)


def _gpu_used_mib() -> int | None:
    try:
        out = subprocess.run(
            ["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        return int(out.strip().splitlines()[0])
    except (OSError, subprocess.CalledProcessError, ValueError):
        return None


def check(model: str) -> bool:
    print(f"\n=== {model} ===")
    # Descarga el modelo de la memoria para medir también la carga en frío.
    with _post_stream("/api/generate", {"model": model, "keep_alive": 0}) as r:
        r.read()

    answers = []
    for run, prompt in PROMPTS.items():
        start = time.perf_counter()
        first: float | None = None
        text = []
        final: dict = {}
        payload = {
            "model": model,
            "messages": [{"role": "system", "content": SYSTEM}, {"role": "user", "content": prompt}],
            "think": False,
            "stream": True,
        }
        with _post_stream("/api/chat", payload) as resp:
            for line in resp:
                chunk = json.loads(line)
                piece = chunk.get("message", {}).get("content", "")
                if piece and first is None:
                    first = time.perf_counter() - start
                text.append(piece)
                if chunk.get("done"):
                    final = chunk
        total = time.perf_counter() - start
        answer = "".join(text).strip()
        tps = final.get("eval_count", 0) / (final.get("eval_duration", 1) / 1e9)
        print(f"[{run}] primera palabra: {first:.2f} s | total: {total:.2f} s | {tps:.0f} tokens/s")
        print(f"  Respuesta: {answer}")
        answers.append(answer)

    loaded = [m for m in _get("/api/ps").get("models", []) if m["name"] == model]
    if loaded:
        m = loaded[0]
        print(
            f"VRAM del modelo (Ollama): {m['size_vram'] / 2**30:.1f} GB de {m['size'] / 2**30:.1f} GB"
            f" ({'todo en GPU' if m['size_vram'] >= m['size'] else 'parte en CPU'})"
        )
    used = _gpu_used_mib()
    if used is not None:
        print(f"VRAM ocupada en total (nvidia-smi): {used / 1024:.1f} GB")

    ok = all(a and any(w in f" {a.lower()} " for w in SPANISH_MARKERS) for a in answers)
    print("RESULTADO:", "OK, responde en español" if ok else "FALLO: la respuesta no parece español")
    return ok


def main() -> int:
    models = sys.argv[1:] or ["qwen3:8b"]
    try:
        version = _get("/api/version")["version"]
    except (urllib.error.URLError, OSError) as exc:
        print(f"Ollama no responde en {OLLAMA_URL}: {exc}. ¿Está abierto Ollama?")
        return 1
    print(f"Ollama {version} en {OLLAMA_URL}")
    results = [check(m) for m in models]
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
