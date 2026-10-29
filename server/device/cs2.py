"""Counter-Strike 2 por su «Game State Integration» (GSI), la API oficial de Valve.

El juego manda a una dirección local (aquí, `http://127.0.0.1:<puerto>/app/gsi/cs2`) un JSON cada vez que
algo cambia, si existe un archivo `gamestate_integration_*.cfg` en `game/csgo/cfg/` de su carpeta (lo crea
`tools/cs2_gsi.py`, con tu permiso). Cada aviso lleva una clave propia (`data/gsi_token.txt`, distinta del
token de Vega) que se comprueba: una web cualquiera que escriba a 127.0.0.1 no puede colar datos.

Solo se enseñan *tus* datos (vida, armadura, dinero, bajas/muertes/asistencias, arma) cuando el aviso es de
tu propio jugador; si estás mirando a otro (muerto, espectando), solo salen el mapa y el marcador. Nada se
guarda.
"""

from __future__ import annotations

import secrets
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from server.device.match import Match

FRESH_SECONDS = 30.0  # el juego avisa al menos cada 10 s (heartbeat); más tiempo sin noticias = en el menú
CT_COLOR, T_COLOR = "#5C9DFF", "#F0B34B"
CFG_NAME = "gamestate_integration_vega.cfg"

MODES = {
    "competitive": "Competitivo",
    "casual": "Informal",
    "deathmatch": "Combate a muerte",
    "wingman": "Dos contra dos",
    "scrimcomp2v2": "Dos contra dos",
    "gungameprogressive": "Carrera de armamento",
}
WEAPONS = {
    "ak47": "AK-47", "m4a1": "M4A4", "m4a1_silencer": "M4A1-S", "awp": "AWP", "deagle": "Desert Eagle",
    "glock": "Glock-18", "usp_silencer": "USP-S", "hkp2000": "P2000", "knife": "Cuchillo",
    "knife_t": "Cuchillo",
    "famas": "FAMAS", "galilar": "Galil AR", "ssg08": "SSG 08", "mp9": "MP9", "mac10": "MAC-10",
    "p250": "P250", "fiveseven": "Five-SeveN", "tec9": "Tec-9", "bizon": "PP-Bizon", "c4": "Bomba",
}  # fmt: skip


def load_token(path: Path) -> str:
    """La clave de la integración: se crea una vez (aleatoria) y se reutiliza."""
    try:
        token = path.read_text(encoding="utf-8").strip()
        if len(token) >= 16:
            return token
    except OSError:
        pass
    token = secrets.token_hex(16)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(token, encoding="utf-8")
    return token


def cfg_text(token: str, port: int) -> str:
    """El archivo de configuración que activa la integración (formato de Valve)."""
    return (
        '"Vega Game State Integration"\n{\n'
        f' "uri" "http://127.0.0.1:{port}/app/gsi/cs2"\n'
        ' "timeout" "5.0"\n "buffer" "0.1"\n "throttle" "0.5"\n "heartbeat" "10.0"\n'
        f' "auth"\n {{\n  "token" "{token}"\n }}\n'
        ' "data"\n {\n'
        '  "provider" "1"\n  "map" "1"\n  "round" "1"\n  "player_id" "1"\n  "player_state" "1"\n'
        '  "player_weapons" "1"\n  "player_match_stats" "1"\n }\n}\n'
    )


def cfg_dir(install: Path | None) -> Path | None:
    """La carpeta `game/csgo/cfg` de Counter-Strike 2 dentro de su instalación (si existe)."""
    path = install / "game" / "csgo" / "cfg" if install else None
    return path if path is not None and path.is_dir() else None


def cfg_installed(install: Path | None) -> bool:
    folder = cfg_dir(install)
    return folder is not None and (folder / CFG_NAME).is_file()


def _map_name(raw: str) -> str:
    for prefix in ("de_", "cs_", "ar_", "dz_"):
        if raw.startswith(prefix):
            raw = raw[len(prefix) :]
    return raw.replace("_", " ").title()


def _int(value: object) -> int:
    return int(value) if isinstance(value, int | float) and not isinstance(value, bool) else 0


def _dict(value: object) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


class Cs2State:
    """Lo último que dijo Counter-Strike 2."""

    def __init__(self, token: str, now: Callable[[], float] = time.monotonic) -> None:
        self._token = token
        self._now = now
        self.payload: dict[str, Any] = {}
        self.heard_at = -1e9

    def feed(self, payload: dict[str, Any]) -> bool:
        """Guarda un aviso del juego si lleva la clave correcta; False si no (se ignora)."""
        sent = _dict(payload.get("auth")).get("token")
        if not isinstance(sent, str) or not secrets.compare_digest(sent.encode(), self._token.encode()):
            return False
        self.payload, self.heard_at = payload, self._now()
        return True

    def fresh(self) -> bool:
        return self._now() - self.heard_at < FRESH_SECONDS

    def match(self) -> Match | None:
        if not self.fresh():
            return None
        data = self.payload
        game = _dict(data.get("map"))
        if not game.get("name"):
            return None  # en el menú
        player = _dict(data.get("player"))
        mine = bool(player.get("steamid")) and player.get("steamid") == _dict(data.get("provider")).get(
            "steamid"
        )
        ct, t = _int(_dict(game.get("team_ct")).get("score")), _int(_dict(game.get("team_t")).get("score"))
        side = str(player.get("team") or "") if mine else ""
        ours_first = side == "CT" or side == ""
        score = f"{ct} – {t}" if ours_first else f"{t} – {ct}"
        colors = (CT_COLOR, T_COLOR) if ours_first else (T_COLOR, CT_COLOR)
        mode = str(game.get("mode") or "")
        title = " · ".join(x for x in (MODES.get(mode, mode.title()), _map_name(str(game["name"]))) if x)
        if game.get("phase") == "gameover":
            title += " · Fin"
        stats: list[tuple[str, str]] = []
        if mine:
            state, totals = _dict(player.get("state")), _dict(player.get("match_stats"))
            stats = [
                ("Vida", str(_int(state.get("health")))),
                ("Armadura", str(_int(state.get("armor")))),
                ("Dinero", f"${_int(state.get('money'))}"),
                ("K / D / A", " / ".join(str(_int(totals.get(k))) for k in ("kills", "deaths", "assists"))),
                ("MVP", str(_int(totals.get("mvps")))),
            ]
            held = next(
                (w for w in _dict(player.get("weapons")).values() if _dict(w).get("state") == "active"), None
            )
            if held:
                raw = str(_dict(held).get("name") or "").removeprefix("weapon_")
                stats.append(("Arma", WEAPONS.get(raw, raw.replace("_", " ").title())))
        feed: list[str] = []
        bomb = {"planted": "¡Bomba plantada!", "exploded": "La bomba explotó", "defused": "Bomba desactivada"}
        phase = _dict(data.get("round")).get("bomb")
        if isinstance(phase, str) and phase in bomb:
            feed.append(bomb[phase])
        round_phase = {"freezetime": "tiempo de compra", "live": "en juego", "over": "terminada"}
        now = round_phase.get(str(_dict(data.get("round")).get("phase")), "")
        if now:
            feed.append(f"Ronda {_int(game.get('round')) + 1} · {now}")
        return Match(title=title, score=score, stats=tuple(stats), feed=tuple(feed), colors=colors)
