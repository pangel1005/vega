"""Prepara el puente con Telegram: guarda el token de tu bot y apunta TU cuenta como la única autorizada.

Antes (una vez, en Telegram): abre @BotFather, escribe /newbot, elige un nombre y copia el token que te da.

    python -m server.tools.telegram_setup

Pide el token (no se ve al escribirlo ni queda en el historial de la consola), comprueba que vale, te pide
que le escribas cualquier cosa al bot desde tu cuenta, te enseña quién eres y, si dices que sí, escribe
`config/telegram.json` (fuera de git). Luego activa `[telegram] enabled = true` en config/assistant.toml y
reinicia.
"""

from __future__ import annotations

import getpass
import json
import sys
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

from server.core.config import ROOT

API = "https://api.telegram.org"
PATH = ROOT / "config" / "telegram.json"
WAIT_SECONDS = 180


class SetupError(Exception):
    """Un fallo explicable al usuario (sin la dirección, que lleva el token)."""


def call(client: httpx.Client, token: str, method: str, payload: dict[str, Any] | None = None) -> Any:
    try:
        response = client.post(f"{API}/bot{token}/{method}", json=payload or {}, timeout=40)
    except httpx.HTTPError as exc:
        raise SetupError(f"No pude hablar con Telegram ({type(exc).__name__}). ¿Hay internet?") from None
    if response.status_code in (401, 404):
        raise SetupError("Telegram dice que ese token no vale. Cópialo otra vez desde @BotFather.")
    if response.status_code != 200:
        raise SetupError(f"Telegram contestó con el error {response.status_code}.")
    data = response.json()
    if not data.get("ok"):
        raise SetupError("Telegram no aceptó la petición.")
    return data["result"]


def first_private_message(updates: list[dict[str, Any]]) -> tuple[dict[str, Any], int] | None:
    """La primera persona (no un bot) que escribió al bot en privado, y el número de la actualización."""
    for update in updates:
        message = update.get("message") or {}
        chat, sender = message.get("chat") or {}, message.get("from") or {}
        if (
            chat.get("type") == "private"
            and not sender.get("is_bot", False)
            and isinstance(chat.get("id"), int)
        ):
            return sender, int(update["update_id"])
    return None


def write_config(path: Path, token: str, chat_id: int) -> None:
    """Guarda el token y la cuenta (se suma a las que ya hubiera)."""
    ids: set[int] = {chat_id}
    try:
        old = json.loads(path.read_text(encoding="utf-8-sig"))
        ids |= {int(i) for i in old.get("chat_ids") or []}
    except (OSError, ValueError, TypeError, AttributeError):
        pass
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"token": token, "chat_ids": sorted(ids)}, indent=2) + "\n", encoding="utf-8")


def run(
    client: httpx.Client,
    token: str,
    ask: Callable[[str], str] = input,
    say: Callable[[str], None] = print,
    path: Path = PATH,
    wait: int = WAIT_SECONDS,
) -> bool:
    bot = call(client, token, "getMe")
    say(f"Bot encontrado: @{bot.get('username', '?')}")
    say("Ahora abre Telegram y escríbele cualquier cosa a ese bot DESDE TU CUENTA (te espero 3 minutos)…")
    deadline = time.monotonic() + wait
    found: tuple[dict[str, Any], int] | None = None
    while found is None and time.monotonic() < deadline:
        found = first_private_message(
            call(client, token, "getUpdates", {"timeout": 20, "allowed_updates": ["message"]})
        )
    if found is None:
        say("No me llegó ningún mensaje. Vuelve a ejecutarlo cuando estés listo.")
        return False
    sender, update_id = found
    name = " ".join(x for x in (sender.get("first_name"), sender.get("last_name")) if x) or "(sin nombre)"
    handle = f" @{sender['username']}" if sender.get("username") else ""
    say(f"Te ha escrito: {name}{handle} (número {sender['id']}).")
    if ask("¿Autorizar SOLO a esta cuenta para hablar con Vega? [s/N] ").strip().lower() not in (
        "s",
        "si",
        "sí",
        "y",
    ):
        say("Vale, no he guardado nada.")
        return False
    write_config(path, token, int(sender["id"]))
    call(client, token, "getUpdates", {"offset": update_id + 1, "timeout": 0})  # ese mensaje ya está leído
    say(
        f"Guardado en {path.name}. Falta activar «[telegram] enabled = true» en config/assistant.toml "
        "y reiniciar Vega."
    )
    return True


def main() -> None:
    token = getpass.getpass("Token del bot (no se ve al escribirlo): ").strip()
    if not token:
        sys.exit("No has puesto ningún token.")
    try:
        with httpx.Client() as client:
            if not run(client, token):
                sys.exit(1)
    except SetupError as exc:
        sys.exit(str(exc))


if __name__ == "__main__":
    main()
