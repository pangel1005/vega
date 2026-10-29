"""Lo que Steam sabe por internet del juego que tienes abierto: cuánta gente juega ahora, sus noticias y, si
conectas tu cuenta, tus logros y qué amigos están jugando lo mismo.

- **Sin cuenta** (público, sin clave): jugadores conectados ahora y últimas noticias del juego.
- **Con tu cuenta**: pones tu «Web API key» de Steam (gratis en https://steamcommunity.com/dev/apikey) en
  `config/steam_key.txt`. Es tuya y se queda en ese archivo (fuera de git): no se muestra, no se escribe en
  los registros y solo se manda a api.steampowered.com. Con ella se leen tus logros del juego y a tus amigos
  que lo están jugando. Para tus propios datos, Steam pide que «Detalles del juego» de tu perfil sea público.

Nada de esto espera: se devuelve lo que ya se tiene y lo caducado se pide aparte (la pantalla no se retrasa).
"""

from __future__ import annotations

import logging
import re
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

log = logging.getLogger(__name__)

API = "https://api.steampowered.com"
TTL = {"players": 60.0, "news": 1800.0, "achievements": 120.0, "friends": 60.0}
RETRY_SECONDS = 300.0  # tras fallar una petición, no se repite hasta pasado este tiempo
KEY_PATTERN = re.compile(r"[0-9A-Fa-f]{32}")

Params = dict[str, str | int]
Get = Callable[[str, Params], dict[str, object] | None]


@dataclass(frozen=True)
class News:
    title: str
    at: float  # segundos desde 1970 (UTC)


@dataclass(frozen=True)
class Achievements:
    unlocked: int
    total: int
    recent: tuple[str, ...]  # los últimos desbloqueados, el más reciente primero


@dataclass(frozen=True)
class Live:
    players: int | None = None
    news: tuple[News, ...] = ()
    achievements: Achievements | None = None
    friends: tuple[str, ...] = ()  # amigos que ahora mismo juegan a esto


def read_key(text: str) -> str | None:
    """La clave si tiene el formato de Steam (32 hexadecimales); si no, None (sin decir cuál era)."""
    key = text.strip()
    return key if KEY_PATTERN.fullmatch(key) else None


def http_get(url: str, params: Params) -> dict[str, object] | None:
    import httpx

    try:
        reply = httpx.get(url, params=params, timeout=6.0)
        reply.raise_for_status()
        body = reply.json()
    except (httpx.HTTPError, ValueError) as exc:
        # Solo el tipo de fallo: el texto de la excepción lleva la dirección completa, y en ella va la clave.
        log.debug("Steam no contestó (%s)", type(exc).__name__)
        return None
    return body if isinstance(body, dict) else None


def _dict(value: object) -> dict[str, object]:
    return value if isinstance(value, dict) else {}


def parse_players(body: dict[str, object]) -> int | None:
    count = _dict(body.get("response")).get("player_count")
    return count if isinstance(count, int) else None


def parse_news(body: dict[str, object]) -> tuple[News, ...]:
    items = _dict(body.get("appnews")).get("newsitems")
    out: list[News] = []
    for item in items if isinstance(items, list) else []:
        if isinstance(item, dict) and item.get("title"):
            date = item.get("date")
            out.append(
                News(
                    re.sub(r"\s+", " ", str(item["title"])).strip()[:90],
                    float(date) if isinstance(date, int) else 0.0,
                )
            )
    return tuple(out[:3])


def parse_achievements(body: dict[str, object]) -> Achievements | None:
    stats = _dict(body.get("playerstats"))
    items = stats.get("achievements")
    if not stats.get("success") or not isinstance(items, list) or not items:
        return None
    done = [a for a in items if isinstance(a, dict) and a.get("achieved") == 1]
    done.sort(key=lambda a: int(a.get("unlocktime") or 0), reverse=True)
    names = tuple(str(a.get("name") or a.get("apiname") or "")[:60] for a in done[:3])
    return Achievements(unlocked=len(done), total=len(items), recent=tuple(n for n in names if n))


class SteamWeb:
    def __init__(
        self,
        key: str | None,
        steam_id: int | None,
        *,
        friends: bool = True,
        get: Get = http_get,
        now: Callable[[], float] = time.time,
        background: bool = True,
    ) -> None:
        self._key = key
        self._steam_id = steam_id
        self._friends = friends
        self._get = get
        self._now = now
        self._background = background  # False en las pruebas: se pide en el mismo hilo
        self._cache: dict[tuple[str, int], tuple[object, float]] = {}
        self._asked: set[tuple[str, int]] = set()
        self._lock = threading.Lock()

    @property
    def connected(self) -> bool:
        """¿Hay una cuenta conectada (clave y usuario de Steam)?"""
        return self._key is not None and self._steam_id is not None

    def live(self, appid: int) -> Live:
        """Lo que se sabe ahora del juego; lo caducado se pide aparte y llega en la próxima lectura."""
        players = self._value("players", appid, self._players)
        news = self._value("news", appid, self._news)
        ach = self._value("achievements", appid, self._achievements) if self.connected else None
        friends = (
            self._value("friends", appid, self._friends_playing) if self.connected and self._friends else None
        )
        return Live(
            players=players if isinstance(players, int) else None,
            news=news if isinstance(news, tuple) else (),
            achievements=ach if isinstance(ach, Achievements) else None,
            friends=friends if isinstance(friends, tuple) else (),
        )

    def _value(self, kind: str, appid: int, fetch: Callable[[int], object]) -> object:
        slot = (kind, appid)
        with self._lock:
            cached = self._cache.get(slot)
            fresh = cached is not None and self._now() - cached[1] < (
                TTL[kind] if cached[0] is not None else RETRY_SECONDS
            )
            if fresh or slot in self._asked:
                return cached[0] if cached else None
            self._asked.add(slot)
        if self._background:
            threading.Thread(target=self._refresh, args=(slot, fetch), daemon=True).start()
        else:
            self._refresh(slot, fetch)
        with self._lock:
            return self._cache[slot][0] if slot in self._cache else None

    def _refresh(self, slot: tuple[str, int], fetch: Callable[[int], object]) -> None:
        try:
            value = fetch(slot[1])
        except Exception:  # una respuesta rara de Steam no debe tirar nada
            log.debug("No pude leer %s de Steam", slot[0], exc_info=False)
            value = None
        with self._lock:
            old = self._cache.get(slot)
            # Si falla, se conserva lo último bueno (pero se anota el intento para no insistir cada segundo).
            self._cache[slot] = (value if value is not None else (old[0] if old else None), self._now())
            self._asked.discard(slot)

    # --- peticiones ---

    def _players(self, appid: int) -> int | None:
        body = self._get(f"{API}/ISteamUserStats/GetNumberOfCurrentPlayers/v1/", {"appid": appid})
        return parse_players(body) if body else None

    def _news(self, appid: int) -> tuple[News, ...] | None:
        body = self._get(
            f"{API}/ISteamNews/GetNewsForApp/v2/",
            {"appid": appid, "count": 3, "maxlength": 1, "l": "spanish"},
        )
        return parse_news(body) if body else None

    def _achievements(self, appid: int) -> Achievements | None:
        body = self._get(
            f"{API}/ISteamUserStats/GetPlayerAchievements/v1/",
            {"appid": appid, "key": self._key or "", "steamid": self._steam_id or 0, "l": "spanish"},
        )
        return parse_achievements(body) if body else None

    def _friends_playing(self, appid: int) -> tuple[str, ...] | None:
        listing = self._get(
            f"{API}/ISteamUser/GetFriendList/v1/",
            {"key": self._key or "", "steamid": self._steam_id or 0, "relationship": "friend"},
        )
        friends = _dict(listing.get("friendslist")).get("friends") if listing else None
        ids = (
            [str(f.get("steamid")) for f in friends if isinstance(f, dict)]
            if isinstance(friends, list)
            else []
        )
        if not ids:
            return None if listing is None else ()
        names: list[str] = []
        for start in range(0, len(ids), 100):
            body = self._get(
                f"{API}/ISteamUser/GetPlayerSummaries/v2/",
                {"key": self._key or "", "steamids": ",".join(ids[start : start + 100])},
            )
            players = _dict(body.get("response")).get("players") if body else None
            for p in players if isinstance(players, list) else []:
                if isinstance(p, dict) and str(p.get("gameid")) == str(appid) and p.get("personaname"):
                    names.append(str(p["personaname"])[:24])
        return tuple(names[:5])
