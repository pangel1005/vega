"""Activa (o quita) la integración oficial de Counter-Strike 2 («Game State Integration») para ver la partida.

Crea un solo archivo, `gamestate_integration_vega.cfg`, en `game/csgo/cfg/` del juego. Con él, CS2
manda a `http://127.0.0.1:<puerto>/app/gsi/cs2` (solo este PC) tu vida, armadura, dinero, K/D/A, arma, ronda y
marcador. Lleva una clave propia (`data/gsi_token.txt`), distinta del token de Vega. No cambia nada más.

    uv run python tools/cs2_gsi.py            # enseña dónde lo pondría y qué diría
    uv run python tools/cs2_gsi.py --apply    # crea el archivo
    uv run python tools/cs2_gsi.py --undo     # lo borra

Después hay que reiniciar Counter-Strike 2.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from server.core.config import ROOT, load_settings
from server.device.cs2 import CFG_NAME, cfg_dir, cfg_text, load_token
from server.device.games import GameWatcher, epic_manifests, steam_root

CS2_APPID = 730


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--apply", action="store_true", help="crea el archivo de integración")
    parser.add_argument("--undo", action="store_true", help="borra el archivo de integración")
    args = parser.parse_args()
    watcher = GameWatcher(steam_root(), epic_manifests(), Path("data") / "games")
    folder = cfg_dir(watcher.steam_install(CS2_APPID))
    if folder is None:
        print("No encuentro Counter-Strike 2 en Steam (game/csgo/cfg).")
        return 1
    target = folder / CFG_NAME
    print(f"{target}\nAhora: {'existe' if target.is_file() else 'no existe'}")
    if args.undo:
        if target.is_file():
            target.unlink()
            print("Borrado. Reinicia Counter-Strike 2.")
        else:
            print("No había nada que borrar.")
        return 0
    text = cfg_text(load_token(ROOT / "data" / "gsi_token.txt"), load_settings().server.port)
    if not args.apply:
        print("Solo lectura: con --apply crearía este archivo (la clave aquí va tapada):\n")
        print(text.replace(load_token(ROOT / "data" / "gsi_token.txt"), "••••••••"))
        return 0
    target.write_text(text, encoding="utf-8")
    print("Creado. Reinicia Counter-Strike 2 para que lo use.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
