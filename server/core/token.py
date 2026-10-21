"""Token de acceso al WebSocket.

Se genera una vez y se guarda en `config/token.txt` (fuera de git). Aunque el
servidor solo escucha en 127.0.0.1, el token impide que una página web
cualquiera abierta en el PC hable con el asistente.
"""

from __future__ import annotations

import secrets
from pathlib import Path

from server.core.config import ROOT

TOKEN_FILE = ROOT / "config" / "token.txt"


def load_or_create_token(path: Path = TOKEN_FILE) -> str:
    if path.exists():
        token = path.read_text(encoding="utf-8").strip()
        if len(token) >= 32:
            return token
    token = secrets.token_urlsafe(32)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(token + "\n", encoding="utf-8")
    return token
