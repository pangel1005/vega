"""La partida en curso de los juegos que enseñan su estado por una API oficial local (solo lectura).

- **League of Legends**: la «Live Client Data API» de Riot, en https://127.0.0.1:2999/liveclientdata/. Solo
  existe mientras hay una partida y no pide clave (su certificado es propio, por eso no se valida: solo se
  habla con 127.0.0.1). Se enseñan *tus* datos (nivel, bajas/muertes/asistencias, súbditos, oro) y lo que ya
  ves en la partida (el marcador de bajas y los objetivos). No se muestra nada que el juego te oculte
  (objetos, hechizos o recargas del equipo rival).
- **Rocket League**: la «Stats API» de Psyonix, un socket TCP en 127.0.0.1 (puerto 49123 por defecto) que el
  juego abre solo si en `TAGame/Config/DefaultStatsAPI.ini` pones `PacketSendRate` por encima de 0 (viene en
  0) y reinicias el juego. Manda el marcador, el tiempo y las estadísticas de cada jugador.

Nada se guarda: solo se mira lo último que dijo el juego, y se olvida al acabar la partida.
"""

from __future__ import annotations

import json
import logging
import re
import socket
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

LOL_URL = "https://127.0.0.1:2999/liveclientdata/allgamedata"
LOL_EVERY = 2.0  # segundos entre lecturas de League of Legends
ROCKET_PORT = 49123
STALE_SECONDS = 6.0  # si Rocket League lleva más de esto sin decir nada, se da por perdida la conexión
FEED_MAX = 4

# Mapas y modos de League of Legends con nombre en español (el resto se enseña tal cual lo manda el juego).
LOL_MAPS = {"Map11": "Grieta del Invocador", "Map12": "Abismo de los Lamentos", "Map21": "Nexus Blitz"}
LOL_MODES = {
    "CLASSIC": "Clásica",
    "ARAM": "ARAM",
    "URF": "URF",
    "ONEFORALL": "Uno para todos",
    "TUTORIAL": "Tutorial",
}


@dataclass(frozen=True)
class Match:
    """Lo que se enseña de la partida. Con `hint` y sin datos: qué falta para verla."""

    title: str = ""
    score: str = ""  # «12 – 9»
    clock: float | None = None  # segundos de partida en el momento de leerla
    clock_step: int = 0  # +1 si el reloj sube (LoL), −1 si baja (Rocket League), 0 si está parado
    stats: tuple[tuple[str, str], ...] = ()
    feed: tuple[str, ...] = ()  # lo último que pasó, lo más nuevo primero
    hint: str = ""


def _number(value: object) -> int:
    return int(value) if isinstance(value, int | float) and not isinstance(value, bool) else 0


def _dict(value: object) -> dict[str, Any]:
    return value if isinstance(value, dict) else {}


def _list(value: object) -> list[Any]:
    return value if isinstance(value, list) else []


def _thousands(n: int) -> str:
    return f"{n:,}".replace(",", " ")


# ---------------------------------------------------------------- League of Legends


def _lol_event(event: dict[str, Any]) -> str | None:
    kind = str(event.get("EventName") or "")
    if kind == "ChampionKill":
        return f"{event.get('KillerName', '?')} eliminó a {event.get('VictimName', '?')}"
    if kind == "DragonKill":
        return "Dragón"
    if kind == "BaronKill":
        return "Barón Nashor"
    if kind == "HeraldKill":
        return "Heraldo de la Grieta"
    if kind == "TurretKilled":
        return "Torre destruida"
    if kind == "InhibKilled":
        return "Inhibidor destruido"
    if kind == "Ace":
        return "¡Ace!"
    if kind == "Multikill":
        return f"{event.get('KillerName', '?')}: racha de {_number(event.get('KillStreak'))}"
    return None


def parse_lol(data: dict[str, Any]) -> Match | None:
    """De `allgamedata` a lo que se enseña, o None si no hay partida en marcha."""
    game = _dict(data.get("gameData"))
    active = _dict(data.get("activePlayer"))
    if not game or not active:
        return None
    players = [p for p in _list(data.get("allPlayers")) if isinstance(p, dict)]
    name = active.get("riotId") or active.get("summonerName")
    me = next(
        (
            p
            for p in players
            if name and name in (p.get("riotId"), p.get("summonerName"), p.get("riotIdGameName"))
        ),
        None,
    )
    kills = {"ORDER": 0, "CHAOS": 0}
    for p in players:
        team = str(p.get("team") or "")
        if team in kills:
            kills[team] += _number(_dict(p.get("scores")).get("kills"))
    mine = str(me.get("team")) if me else "ORDER"
    other = "CHAOS" if mine == "ORDER" else "ORDER"
    seconds = float(game.get("gameTime") or 0)
    stats: list[tuple[str, str]] = []
    if me:
        scores = _dict(me.get("scores"))
        cs = _number(scores.get("creepScore"))
        per_minute = f" · {cs / (seconds / 60):.1f}/min".replace(".", ",") if seconds >= 120 else ""
        stats += [
            ("Campeón", str(me.get("championName") or "?")),
            ("Nivel", str(_number(me.get("level")))),
            ("K / D / A", " / ".join(str(_number(scores.get(k))) for k in ("kills", "deaths", "assists"))),
            ("Súbditos", f"{cs}{per_minute}"),
        ]
    gold = active.get("currentGold")
    if isinstance(gold, int | float):
        stats.append(("Oro", _thousands(int(gold))))
    events = _list(_dict(data.get("events")).get("Events"))
    feed = [t for e in events if isinstance(e, dict) and (t := _lol_event(e))]
    mode = str(game.get("gameMode") or "")
    map_name = str(game.get("mapName") or "")
    where = LOL_MAPS.get(map_name, map_name)
    return Match(
        title=" · ".join(x for x in (LOL_MODES.get(mode, mode.title()), where) if x),
        score=f"{kills[mine]} – {kills[other]}",
        clock=seconds,
        clock_step=1,
        stats=tuple(stats),
        feed=tuple(reversed(feed[-FEED_MAX:])),
    )


def fetch_lol() -> dict[str, Any] | None:
    import httpx

    try:
        # Certificado propio de Riot y solo 127.0.0.1: no hay nadie a quien suplantar.
        reply = httpx.get(LOL_URL, timeout=1.0, verify=False)  # noqa: S501
        reply.raise_for_status()
        body = reply.json()
    except (httpx.HTTPError, ValueError):
        return None  # sin partida, el puerto rechaza la conexión
    return body if isinstance(body, dict) else None


# ---------------------------------------------------------------- Rocket League


class JsonStream:
    """Junta lo que llega por el socket y saca los mensajes JSON completos, vengan pegados, separados por
    saltos de línea o cortados a medias entre un paquete y otro."""

    def __init__(self) -> None:
        self._buffer = ""
        self._decoder = json.JSONDecoder()

    def feed(self, chunk: bytes) -> list[dict[str, Any]]:
        self._buffer += chunk.decode("utf-8", errors="ignore")
        out: list[dict[str, Any]] = []
        while True:
            text = self._buffer.lstrip()
            if not text:
                self._buffer = ""
                return out
            try:
                value, end = self._decoder.raw_decode(text)
            except json.JSONDecodeError:
                # Falta el resto del mensaje (se espera) o hay basura delante (se salta hasta la próxima «{»).
                if not text.startswith("{"):
                    skip = text.find("{")
                    self._buffer = "" if skip < 0 else text[skip:]
                    continue
                self._buffer = text if len(text) < 2_000_000 else ""
                return out
            self._buffer = text[end:]
            if isinstance(value, dict):
                out.append(value)


def _rocket_data(message: dict[str, Any]) -> dict[str, Any]:
    data = message.get("Data")
    if isinstance(data, str):  # según la versión, `Data` llega como texto JSON o como objeto
        try:
            data = json.loads(data)
        except ValueError:
            return {}
    return _dict(data)


class RocketState:
    """Lo último que dijo Rocket League (marcador, tiempo, jugadores y últimos sucesos)."""

    def __init__(self, now: Callable[[], float] = time.monotonic) -> None:
        self._now = now
        self.update: dict[str, Any] = {}
        self.feed: list[str] = []
        self.heard_at = -1e9
        self.ended = False

    def apply(self, message: dict[str, Any]) -> None:
        event = str(message.get("Event") or "")
        data = _rocket_data(message)
        self.heard_at = self._now()
        if event == "UpdateState":
            self.update = data
        elif event in ("MatchCreated", "MatchInitialized"):
            self.feed, self.ended = [], False
        elif event == "MatchEnded":
            self.ended = True
            self.feed.insert(0, "Fin de la partida")
        elif event == "GoalScored":
            scorer = _dict(data.get("Scorer")).get("Name")
            self.feed.insert(0, f"¡Gol de {scorer}!" if scorer else "¡Gol!")
        elif event == "StatfeedEvent":
            kind = data.get("EventName") or data.get("Type")
            who = _dict(data.get("MainTarget")).get("Name")
            if kind and who:
                self.feed.insert(0, f"{who}: {kind}")
        del self.feed[FEED_MAX:]

    def fresh(self) -> bool:
        return self._now() - self.heard_at < STALE_SECONDS

    def match(self) -> Match | None:
        game = _dict(self.update.get("Game"))
        players = [p for p in _list(self.update.get("Players")) if isinstance(p, dict)]
        if not game and not players:
            return None
        teams = sorted(
            (t for t in _list(game.get("Teams")) if isinstance(t, dict)),
            key=lambda t: _number(t.get("TeamNum")),
        )
        score = " – ".join(str(_number(t.get("Score"))) for t in teams[:2])
        target = _dict(game.get("Target"))
        me = next((p for p in players if target.get("Name") and p.get("Name") == target.get("Name")), None)
        stats: list[tuple[str, str]] = []
        if me:
            stats = [
                ("Puntos", str(_number(me.get("Score")))),
                ("Goles", str(_number(me.get("Goals")))),
                ("Asistencias", str(_number(me.get("Assists")))),
                ("Paradas", str(_number(me.get("Saves")))),
                ("Tiros", str(_number(me.get("Shots")))),
                ("Demoliciones", str(_number(me.get("Demos")))),
            ]
        running = not self.ended and not game.get("bReplay") and self.fresh()
        return Match(
            title="Fin de la partida"
            if self.ended
            else ("Prórroga" if game.get("bOverTime") else "Partida en curso"),
            score=score,
            clock=float(game["TimeSeconds"]) if isinstance(game.get("TimeSeconds"), int | float) else None,
            clock_step=-1 if running else 0,
            stats=tuple(stats),
            feed=tuple(self.feed),
        )


class RocketLeagueStats:
    """Se conecta (en segundo plano) a la Stats API de Rocket League y guarda lo último que dice."""

    def __init__(
        self,
        ini: Callable[[], Path | None],
        *,
        port: int = ROCKET_PORT,
        connect: Callable[[int], socket.socket] | None = None,
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        self._ini = ini
        self._port = port
        self._connect = connect or (lambda p: socket.create_connection(("127.0.0.1", p), timeout=1.0))
        self.state = RocketState(now)
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._connected = False

    def start(self) -> None:
        if self._thread is None or not self._thread.is_alive():
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, daemon=True, name="rocket-stats")
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self.state = RocketState(self.state._now)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                sock = self._connect(self._port)
            except OSError:
                self._connected = False
                self._stop.wait(2.0)  # el juego aún no abrió el puerto (o la API está desactivada)
                continue
            self._read(sock)

    def _read(self, sock: socket.socket) -> None:
        stream = JsonStream()
        self._connected = True
        try:
            sock.settimeout(1.0)
            while not self._stop.is_set():
                try:
                    chunk = sock.recv(65_536)
                except TimeoutError:
                    continue
                if not chunk:
                    return
                for message in stream.feed(chunk):
                    self.state.apply(message)
        except OSError:
            return
        finally:
            self._connected = False
            sock.close()

    def enabled(self) -> bool | None:
        """¿Tiene la API activada (`PacketSendRate` > 0 en el .ini)? None si no se encuentra el archivo."""
        path = self._ini()
        if path is None:
            return None
        try:
            text = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            return None
        m = re.search(r"^\s*PacketSendRate\s*=\s*(\d+)", text, re.MULTILINE)
        return bool(m and int(m.group(1)) > 0)

    def read(self) -> Match | None:
        self.start()
        if self._connected and self.state.fresh():
            return self.state.match()
        if self.enabled() is False:
            return Match(
                hint="Para ver la partida de Rocket League, activa su API: pon PacketSendRate=10 en "
                "TAGame/Config/DefaultStatsAPI.ini y reinicia el juego."
            )
        return None


class MatchSource:
    """Elige el lector de partida según el juego abierto."""

    def __init__(
        self,
        rocket: RocketLeagueStats | None = None,
        *,
        lol: Callable[[], dict[str, Any] | None] = fetch_lol,
        now: Callable[[], float] = time.monotonic,
    ) -> None:
        self._rocket = rocket
        self._lol = lol
        self._now = now
        self._lol_cache: tuple[float, Match | None] = (-1e9, None)

    def match(self, name: str, key: str) -> Match | None:
        low = name.lower()
        if key == "riot:lol" or "league of legends" in low:
            return self._league()
        if "rocket league" in low and self._rocket is not None:
            return self._rocket.read()
        self._close_rocket()
        return None

    def _league(self) -> Match | None:
        when, cached = self._lol_cache
        if self._now() - when < LOL_EVERY:
            return cached
        data = self._lol()
        found = parse_lol(data) if data else None
        self._lol_cache = (self._now(), found)
        return found

    def idle(self) -> None:
        """No hay ningún juego abierto: se deja de escuchar el puerto de Rocket League."""
        self._close_rocket()

    def _close_rocket(self) -> None:
        if self._rocket is not None:
            self._rocket.stop()
