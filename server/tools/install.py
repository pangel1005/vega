"""La parte del instalador que no es de Windows: ajustes según tu PC, modelo, voces y token.

La llama `scripts\\instalar.ps1` (después de instalar uv, Ollama y las dependencias); se puede repetir sin
miedo: lo que ya está hecho se salta.

    uv run python -m server.tools.install [--no-model] [--no-voice]

1. Tus ajustes: si config/assistant.toml no existe, nace de config/assistant.example.toml y se ajusta a tu
   tarjeta gráfica (sin NVIDIA: voz a texto en el procesador con un modelo más ligero y la IA sin gráfica).
2. El modelo de lenguaje de tus ajustes, con Ollama (unos 3 GB la primera vez).
3. Las voces de Kokoro (unos 120 MB) en models/kokoro.
4. El token que enlaza la app del teléfono con el servidor (config/token.txt).
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from server.core.config import DEFAULT_CONFIG, ensure_user_config, load_settings
from server.core.token import load_or_create_token

ROOT = Path(__file__).resolve().parents[2]
KOKORO_DIR = ROOT / "models" / "kokoro"
KOKORO_URL = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/"
# Nombre en disco → nombre en la descarga. La versión ligera (int8): 88 MB en vez de 310 MB y un 30–40 %
# más rápida en el procesador; el servidor la prefiere si está.
KOKORO_FILES = {"kokoro-v1.0.int8.onnx": "kokoro-v1.0.int8.onnx", "voices-v1.0.bin": "voices-v1.0.bin"}
_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0


def say(text: str) -> None:
    print(text, flush=True)


# --- 1. Ajustes según el PC ---


def nvidia_vram_mib(run: Callable[..., Any] = subprocess.run) -> int | None:
    """Memoria de vídeo de la tarjeta NVIDIA (MiB), o None si no hay (o no hay controlador)."""
    exe = shutil.which("nvidia-smi")
    if exe is None:
        return None
    try:
        out = run(
            [exe, "--query-gpu=memory.total", "--format=csv,noheader,nounits"],
            capture_output=True,
            text=True,
            timeout=10,
            creationflags=_NO_WINDOW,
        )
    except (OSError, subprocess.SubprocessError):
        return None
    values = [int(v) for v in re.findall(r"\d+", out.stdout or "")]
    return max(values) if values and out.returncode == 0 else None


def set_values(text: str, values: dict[tuple[str, str], str]) -> str:
    """Cambia `clave = valor` en su [sección] sin tocar comentarios ni lo demás (valores ya en TOML)."""
    lines = text.split("\n")
    section = ""
    for i, line in enumerate(lines):
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            section = stripped[1:-1]
            continue
        m = re.match(r"^(\s*)([A-Za-z_][\w-]*)(\s*=\s*)", line)
        if m and (section, m.group(2)) in values:
            lines[i] = f"{m.group(1)}{m.group(2)}{m.group(3)}{values[(section, m.group(2))]}"
    return "\n".join(lines)


def tuned_for(vram_mib: int | None) -> dict[tuple[str, str], str]:
    """Lo que cambia respecto a lo de serie según la tarjeta gráfica."""
    if vram_mib is None:  # sin NVIDIA: todo en el procesador
        return {
            ("speech", "stt_model"): '"base"',
            ("speech", "stt_device"): '"cpu"',
            ("speech", "stt_compute"): '"int8"',
            ("llm", "gpu_layers"): "0",
            ("llm", "follow_game"): "false",
        }
    if vram_mib < 6000:  # 4 GB: la voz a texto en la gráfica y el modelo casi todo en el procesador
        return {("llm", "gpu_layers_free"): "8", ("llm", "gpu_free_mib"): "2500"}
    return {}


def prepare_config(path: Path = DEFAULT_CONFIG, vram: Callable[[], int | None] = nvidia_vram_mib) -> str:
    """Crea tus ajustes si no existen y los adapta al PC. Los que ya tenías no se tocan."""
    if not ensure_user_config(path):
        return f"Tus ajustes ya existían ({path.name}): no los toco."
    mib = vram()
    changes = tuned_for(mib)
    if changes:
        path.write_text(set_values(path.read_text(encoding="utf-8"), changes), encoding="utf-8")
    load_settings(path)  # que el resultado sea válido
    if mib is None:
        return "Ajustes creados para un PC sin tarjeta NVIDIA (todo en el procesador: más lento, pero va)."
    return f"Ajustes creados para tu tarjeta NVIDIA ({mib / 1024:.0f} GB de vídeo)."


# --- 2. El modelo de lenguaje ---


def pull_model(url: str, model: str, client: httpx.Client | None = None) -> str:
    """Baja el modelo con Ollama (enseñando el progreso). Si ya está, no baja nada."""
    http = client or httpx.Client(timeout=None)
    try:
        tags = http.get(f"{url}/api/tags", timeout=10).json()
    except (httpx.HTTPError, ValueError):
        return f"Ollama no responde en {url}: arráncalo y vuelve a ejecutar el instalador."
    names = {m.get("name") for m in tags.get("models", [])}
    if model in names or f"{model}:latest" in names:
        return f"El modelo {model} ya está."
    say(f"Bajando el modelo {model} (unos minutos la primera vez)...")
    last = -1
    with http.stream("POST", f"{url}/api/pull", json={"model": model}) as response:
        for line in response.iter_lines():
            if not line:
                continue
            data = json.loads(line)
            if "error" in data:
                return f"Ollama no pudo bajar {model}: {data['error']}"
            total, done = data.get("total"), data.get("completed")
            if total and done:
                pct = int(done * 100 / total)
                if pct >= last + 10:
                    last = pct
                    say(f"  {pct} %")
    return f"Modelo {model} listo."


# --- 3. Las voces ---


def download_voices(dest: Path = KOKORO_DIR, client: httpx.Client | None = None) -> str:
    missing = {name: src for name, src in KOKORO_FILES.items() if not (dest / name).is_file()}
    if not missing:
        return "Las voces ya están."
    dest.mkdir(parents=True, exist_ok=True)
    http = client or httpx.Client(timeout=None, follow_redirects=True)
    for name, src in missing.items():
        say(f"Bajando la voz: {src}...")
        tmp = dest / (name + ".part")
        with http.stream("GET", KOKORO_URL + src) as response:
            response.raise_for_status()
            with tmp.open("wb") as out:
                for chunk in response.iter_bytes(1 << 20):
                    out.write(chunk)
        tmp.replace(dest / name)
    return "Voces listas."


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Prepara Vega en este PC.")
    parser.add_argument("--no-model", action="store_true", help="no bajar el modelo de lenguaje")
    parser.add_argument("--no-voice", action="store_true", help="no bajar las voces")
    args = parser.parse_args(argv)

    say(prepare_config())
    settings = load_settings()
    if not args.no_model:
        say(pull_model(settings.llm.url, settings.llm.model))
    if not args.no_voice:
        try:
            say(download_voices())
        except httpx.HTTPError as exc:
            say(f"No pude bajar las voces ({type(exc).__name__}): hasta tenerlas, Vega solo escribe.")
    load_or_create_token()
    say("Token del teléfono listo (config/token.txt).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
