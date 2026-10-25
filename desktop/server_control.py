"""Arrancar, parar y reiniciar el servidor de Vega desde la app del PC."""

from __future__ import annotations

import subprocess
import time
import urllib.error
import urllib.request

from desktop.config_edit import ROOT

NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW
TASK = "Vega (asistente)"
BASE = "http://127.0.0.1:8000"


def token() -> str:
    return (ROOT / "config" / "token.txt").read_text(encoding="utf-8").strip()


def is_up(timeout: float = 1.5) -> bool:
    try:
        with urllib.request.urlopen(f"{BASE}/health", timeout=timeout):
            return True
    except (urllib.error.URLError, OSError):
        return False


def start() -> str:
    """Arranca el servidor con la misma tarea que usa Windows al iniciar sesión (Ollama incluido)."""
    if is_up():
        return "El servidor ya estaba en marcha."
    subprocess.run(["schtasks", "/run", "/tn", TASK], capture_output=True, creationflags=NO_WINDOW)
    for _ in range(90):
        if is_up():
            return "Servidor en marcha."
        time.sleep(1)
    return "El servidor no respondió en 90 s: mira Registros."


def stop() -> str:
    """Le pide que se apague solo (como scripts/stop.ps1); si no se cierra, no lo fuerza desde aquí."""
    if not is_up():
        return "El servidor no estaba en marcha."
    req = urllib.request.Request(f"{BASE}/admin/apagar", method="POST", headers={"x-token": token()})
    try:
        urllib.request.urlopen(req, timeout=5).close()
    except (urllib.error.URLError, OSError) as exc:
        return f"No pude pedirle que se apagara: {exc}"
    for _ in range(40):
        if not is_up(0.5):
            return "Servidor parado."
        time.sleep(0.5)
    return "Sigue en marcha. Usa scripts/stop.ps1 para forzarlo."


def restart() -> str:
    first = stop()
    if first.startswith("Sigue") or first.startswith("No pude"):
        return first
    time.sleep(1.5)
    return start()
