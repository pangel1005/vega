"""Conecta Vega con tu Moodle y te enseña qué entregas ve.

Hay tres maneras (con la conexión real, el calendario queda de reserva en el archivo):

  python -m server.tools.moodle_setup --login
      LA CONEXIÓN REAL: pides un token con tu usuario y contraseña de Moodle (la contraseña no se guarda ni
      se ve). Con él Vega sabe qué entregas siguen por hacer y deja de avisar de lo que ya entregaste.
  python -m server.tools.moodle_setup --token
      Lo mismo si ya tienes el token (Moodle → Preferencias → Seguridad → «Claves de seguridad»).
  python -m server.tools.moodle_setup
      Solo el enlace del calendario exportado (Calendario → Importar o exportar → «Obtener URL del
      calendario»): no sabe qué ya entregaste.

Todo se comprueba antes de guardar y se guarda en `config/moodle.json` (fuera de git). Luego activa
`[moodle] enabled = true` en config/assistant.toml y reinicia.
"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import sys
from collections.abc import Callable
from datetime import datetime
from pathlib import Path

from server.core.config import ROOT
from server.skills.moodle import (
    MoodleError,
    Task,
    clock,
    fetch_calendar,
    load_config,
    parse_ics,
    write_config,
)
from server.skills.moodle_api import MoodleApi, request_token

PATH = ROOT / "config" / "moodle.json"
YES = ("s", "si", "sí", "y")


def _show(tasks: list[Task], say: Callable[[str], None], now: datetime) -> None:
    say(f"Entregas por hacer: {len(tasks)}.")
    for task in tasks[:6]:
        course = f" ({task.course})" if task.course else ""
        say(f"  · {task.title}{course} — {clock(task.due, now)}")


def run(
    url: str,
    ask: Callable[[str], str] = input,
    say: Callable[[str], None] = print,
    path: Path = PATH,
    fetch: Callable[[str], str] | None = None,
    now: Callable[[], datetime] = datetime.now,
) -> bool:
    """Solo el calendario exportado."""
    if not url.lower().startswith(("https://", "http://")):
        say("Eso no es una dirección web (debe empezar por https://).")
        return False
    text = fetch(url) if fetch is not None else asyncio.run(fetch_calendar(url))
    tasks = [t for t in parse_ics(text) if t.due > now()]
    say("Moodle respondió bien.")
    _show(tasks, say, now())
    if not tasks:
        say("(No veo ninguna entrega futura: ¿elegiste «Todos los eventos» y un periodo de 60 días?)")
    if ask("¿Guardar este enlace para que Vega vigile tus entregas? [s/N] ").strip().lower() not in YES:
        say("Vale, no he guardado nada.")
        return False
    write_config(path, url=url)
    say(f"Guardado en {path.name}. Falta «[moodle] enabled = true» en config/assistant.toml y reiniciar.")
    return True


async def verify(api: MoodleApi, now: datetime) -> tuple[str, list[Task]]:
    """Comprueba el token (quién eres) y lee la cronología."""
    info = await api.site_info()
    return str(info.get("fullname") or info.get("username") or "?"), await api.pending(now)


def run_connected(
    site: str,
    token: str,
    ask: Callable[[str], str] = input,
    say: Callable[[str], None] = print,
    path: Path = PATH,
    now: Callable[[], datetime] = datetime.now,
    api: MoodleApi | None = None,
) -> bool:
    """La conexión real: comprueba el token, enseña lo pendiente y lo guarda."""
    if not site.lower().startswith(("https://", "http://")):
        say("La dirección de Moodle debe empezar por https://.")
        return False
    api = api or MoodleApi(site, token)
    name, tasks = asyncio.run(verify(api, now()))
    say(f"Conectado como {name}.")
    _show(tasks, say, now())
    if ask("¿Guardar esta conexión para que Vega vigile tus entregas? [s/N] ").strip().lower() not in YES:
        say("Vale, no he guardado nada.")
        return False
    write_config(path, site=site.rstrip("/"), token=token)
    say(f"Guardado en {path.name}. Falta «[moodle] enabled = true» en config/assistant.toml y reiniciar.")
    return True


def _site(path: Path, ask: Callable[[str], str]) -> str:
    known = load_config(path)
    if known is not None and known.site:
        return known.site
    return ask("Dirección de tu Moodle (https://…): ").strip()


def main() -> None:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--login", action="store_true", help="pedir el token con tu usuario y contraseña")
    mode.add_argument("--token", action="store_true", help="pegar un token que ya tengas")
    args = parser.parse_args()
    try:
        if args.login:
            site = _site(PATH, input)
            username = input("Usuario de Moodle (tu matrícula): ").strip()
            password = getpass.getpass("Contraseña (no se ve al escribirla ni se guarda): ")
            token = asyncio.run(request_token(site, username, password))
            if not run_connected(site, token):
                sys.exit(1)
        elif args.token:
            site = _site(PATH, input)
            token = getpass.getpass("Token (no se ve al escribirlo): ").strip()
            if not token or not run_connected(site, token):
                sys.exit(1)
        else:
            url = getpass.getpass("Enlace del calendario de Moodle (no se ve al escribirlo): ").strip()
            if not url or not run(url):
                sys.exit(1)
    except MoodleError as exc:
        sys.exit(str(exc))


if __name__ == "__main__":
    main()
