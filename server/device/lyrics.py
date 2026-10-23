"""Letras de canciones, sincronizadas línea a línea, para el modo letra del teléfono.

Vienen de LRCLIB (https://lrclib.net), una base de letras libre y gratuita, sin cuenta.
Es lo único de Vega que usa internet: se le manda **solo** el título, el artista, el
álbum y la duración de la canción. Se desactiva con `[media] lyrics = false`.

Cada letra se busca una vez y se guarda en data/lyrics/ (también «no hay letra», para no
preguntar otra vez cada vez que suena la misma canción).
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import asdict, dataclass, field
from pathlib import Path

import httpx

log = logging.getLogger(__name__)

API = "https://lrclib.net/api"
USER_AGENT = "Vega asistente personal (https://github.com/pangel1005/vega-asistente)"
_TIMEOUT = httpx.Timeout(8.0)
_LRC = re.compile(r"^\[(\d+):(\d+(?:\.\d+)?)\]\s?(.*)$")


@dataclass(frozen=True)
class Line:
    time: float  # segundos desde el inicio; -1 si la letra no está sincronizada
    text: str


@dataclass(frozen=True)
class Lyrics:
    synced: bool
    lines: list[Line] = field(default_factory=list)
    instrumental: bool = False

    @property
    def found(self) -> bool:
        return self.instrumental or bool(self.lines)


NOT_FOUND = Lyrics(synced=False)


def parse_lrc(text: str) -> list[Line]:
    """«[00:31.84] Some people…» → Line(31.84, "Some people…"). Las líneas vacías se
    quedan (son pausas en la canción); las que no tienen tiempo se ignoran."""
    out: list[Line] = []
    for raw in text.splitlines():
        m = _LRC.match(raw.strip())
        if m:
            out.append(Line(int(m[1]) * 60 + float(m[2]), m[3].strip()))
    return sorted(out, key=lambda line: line.time)


def from_api(data: dict[str, object]) -> Lyrics:
    if data.get("instrumental"):
        return Lyrics(synced=False, instrumental=True)
    synced = data.get("syncedLyrics")
    if isinstance(synced, str) and synced.strip():
        lines = parse_lrc(synced)
        if lines:
            return Lyrics(synced=True, lines=lines)
    plain = data.get("plainLyrics")
    if isinstance(plain, str) and plain.strip():
        return Lyrics(synced=False, lines=[Line(-1, t.strip()) for t in plain.splitlines()])
    return NOT_FOUND


class LyricsFinder:
    def __init__(
        self, cache_dir: Path, enabled: bool = True, transport: httpx.AsyncBaseTransport | None = None
    ) -> None:
        self._dir = cache_dir
        self._enabled = enabled
        self._transport = transport

    def _cache_file(self, artist: str, title: str) -> Path:
        key = hashlib.sha1(f"{artist.lower().strip()}|{title.lower().strip()}".encode()).hexdigest()[:16]
        return self._dir / f"{key}.json"

    async def find(self, artist: str, title: str, album: str = "", duration: float = 0) -> Lyrics:
        if not self._enabled or not title:
            return NOT_FOUND
        cache = self._cache_file(artist, title)
        try:
            data = json.loads(cache.read_text(encoding="utf-8"))
            return Lyrics(
                synced=data["synced"],
                lines=[Line(**line) for line in data["lines"]],
                instrumental=data.get("instrumental", False),
            )
        except (OSError, ValueError, KeyError, TypeError):
            pass
        lyrics = await self._fetch(artist, title, album, duration)
        if lyrics is not None:  # None = fallo de red: no se guarda, se reintentará
            self._dir.mkdir(parents=True, exist_ok=True)
            cache.write_text(json.dumps(asdict(lyrics), ensure_ascii=False), encoding="utf-8")
            return lyrics
        return NOT_FOUND

    async def _fetch(self, artist: str, title: str, album: str, duration: float) -> Lyrics | None:
        params: dict[str, str | int] = {"artist_name": artist, "track_name": title}
        if album:
            params["album_name"] = album
        if duration > 0:
            params["duration"] = round(duration)
        try:
            async with httpx.AsyncClient(
                timeout=_TIMEOUT, transport=self._transport, headers={"User-Agent": USER_AGENT}
            ) as client:
                resp = await client.get(f"{API}/get", params=params)
                if resp.status_code == 200:
                    return from_api(resp.json())
                if resp.status_code != 404:
                    return None
                # Sin coincidencia exacta (otro álbum, otra duración): búsqueda más amplia.
                resp = await client.get(f"{API}/search", params={"artist_name": artist, "track_name": title})
                if resp.status_code != 200:
                    return None
                results = resp.json()
                best = next((r for r in results if r.get("syncedLyrics")), results[0] if results else None)
                return from_api(best) if best else NOT_FOUND
        except (httpx.HTTPError, ValueError) as exc:
            log.info("No pude buscar la letra de «%s»: %s", title, exc)
            return None
