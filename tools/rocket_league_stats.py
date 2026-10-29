"""Activa (o desactiva) la Stats API de Rocket League para ver la partida en el teléfono.

Rocket League trae su API local desactivada (`PacketSendRate=0` en
`TAGame/Config/DefaultStatsAPI.ini`). Este script
solo cambia esa línea; sin opciones solo enseña qué haría.

    uv run python tools/rocket_league_stats.py            # enseña el archivo y lo que cambiaría
    uv run python tools/rocket_league_stats.py --apply    # pone PacketSendRate=10
    uv run python tools/rocket_league_stats.py --undo     # lo deja en 0 (desactivado)

Después hay que reiniciar Rocket League. La API solo escucha en 127.0.0.1 y no manda nada fuera del PC.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from server.device.games import GameWatcher, epic_manifests, steam_root

LINE = re.compile(r"^(\s*PacketSendRate\s*=\s*)(\d+)", re.MULTILINE)


def find_ini() -> Path | None:
    return GameWatcher(steam_root(), epic_manifests(), Path("data") / "games").rocket_ini()


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--apply", action="store_true", help="pone PacketSendRate=10")
    parser.add_argument("--undo", action="store_true", help="pone PacketSendRate=0")
    args = parser.parse_args()
    ini = find_ini()
    if ini is None:
        print("No encuentro Rocket League (Epic o Steam) con su DefaultStatsAPI.ini.")
        return 1
    text = ini.read_text(encoding="utf-8", errors="ignore")
    match = LINE.search(text)
    if match is None:
        print(f"{ini}\nNo tiene la línea PacketSendRate: no la toco.")
        return 1
    print(f"{ini}\nPacketSendRate ahora: {match.group(2)}")
    wanted = "10" if args.apply else "0" if args.undo else None
    if wanted is None:
        print("Solo lectura: usa --apply para activarla (PacketSendRate=10) o --undo para desactivarla.")
        return 0
    if match.group(2) == wanted:
        print("Ya está así: no cambio nada.")
        return 0
    ini.write_text(LINE.sub(rf"\g<1>{wanted}", text, count=1), encoding="utf-8")
    print(f"Cambiado a PacketSendRate={wanted}. Reinicia Rocket League para que lo use.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
