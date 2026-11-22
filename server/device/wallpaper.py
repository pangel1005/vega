"""Los fondos de pantalla de Wallpaper Engine, por voz.

Wallpaper Engine guarda cada fondo del Workshop de Steam en
`steamapps/workshop/content/431960/<id>/project.json` (título, etiquetas y tipo) y se maneja por línea de
comandos con `wallpaper64.exe -control …` (https://help.wallpaperengine.io/en/functionality/cli.html): hace
falta que el programa esté abierto.

Aquí solo se leen esos archivos y se manda la orden de abrir un fondo. No se toca nada de Wallpaper Engine
ni de Steam, y no se sale a internet.
"""

from __future__ import annotations

import json
import logging
import random
import re
import subprocess
import sys
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

from server.core.text import normalize

log = logging.getLogger(__name__)

WORKSHOP_APP = "431960"  # el número de Wallpaper Engine en Steam
RELOAD_SECONDS = 600.0
_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0

# Lo que se dice en español → la etiqueta de Wallpaper Engine (están en inglés).
SPANISH_TAGS: dict[str, tuple[str, ...]] = {
    "anime": ("anime",),
    "juego": ("game",),
    "juegos": ("game",),
    "videojuego": ("game",),
    "videojuegos": ("game",),
    "paisaje": ("landscape", "nature"),
    "paisajes": ("landscape", "nature"),
    "naturaleza": ("nature", "landscape"),
    "pixel": ("pixel art",),
    "pixelart": ("pixel art",),
    "tecnologia": ("technology",),
    "tecnologico": ("technology",),
    "dibujos": ("cartoon",),
    "caricatura": ("cartoon",),
    "coche": ("vehicle",),
    "coches": ("vehicle",),
    "carro": ("vehicle",),
    "carros": ("vehicle",),
    "vehiculo": ("vehicle",),
    "ciberpunk": ("cyberpunk",),
    "cyberpunk": ("cyberpunk",),
    "fantasia": ("fantasy",),
    "tranquilo": ("relaxing",),
    "relajante": ("relaxing",),
    "relax": ("relaxing",),
    "calmado": ("relaxing",),
    "animal": ("animal",),
    "animales": ("animal",),
    "ciencia": ("sci-fi",),
    "futurista": ("sci-fi", "technology"),
    "espacio": ("sci-fi", "space"),
    "oscuro": ("dark",),
    "noche": ("night", "dark"),
    "ciudad": ("urban", "city"),
    "lluvia": ("rain",),
}
_FILLER = {
    "un", "una", "el", "la", "los", "las", "de", "del", "con", "que", "sea", "para", "mi", "pantalla",
    "fondo", "fondos", "wallpaper", "algo", "uno", "otro", "tipo", "estilo", "tema", "pon", "ponme",
    "cambia", "cambiame", "ponga", "a", "por", "en", "al", "lo", "me",
}  # fmt: skip
_RANDOM_WORDS = {
    "azar",
    "aleatorio",
    "aleatoria",
    "cualquiera",
    "sorprendeme",
    "sorpresa",
    "random",
    "cualquier",
}


@dataclass(frozen=True)
class Wallpaper:
    id: str
    title: str
    tags: tuple[str, ...]
    kind: str  # scene, video, web…
    project: Path  # su project.json: es lo que se le da a Wallpaper Engine

    def words(self) -> set[str]:
        return set(normalize(self.title).split())


def find_wallpaper_engine(steam_libraries: list[Path]) -> tuple[Path | None, Path | None]:
    """(programa, carpeta del Workshop) en la primera biblioteca de Steam que tenga Wallpaper Engine."""
    for lib in steam_libraries:  # cada `lib` es una carpeta `steamapps`
        base = lib / "common" / "wallpaper_engine"
        exe = next((base / n for n in ("wallpaper64.exe", "wallpaper32.exe") if (base / n).is_file()), None)
        if exe is not None:
            workshop = lib / "workshop" / "content" / WORKSHOP_APP
            return exe, workshop if workshop.is_dir() else None
    return None, None


def read_wallpapers(workshop: Path) -> list[Wallpaper]:
    found: list[Wallpaper] = []
    for folder in sorted(workshop.iterdir() if workshop.is_dir() else []):
        project = folder / "project.json"
        try:
            data = json.loads(project.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            continue
        title = str(data.get("title") or "").strip()
        if not title:
            continue
        tags = tuple(str(t).lower() for t in data.get("tags") or [] if isinstance(t, str))
        found.append(Wallpaper(folder.name, title, tags, str(data.get("type") or "").lower(), project))
    return found


class WallpaperEngine:
    def __init__(
        self,
        exe: Path | None,
        workshop: Path | None,
        *,
        run: Callable[..., object] = subprocess.run,
        rng: random.Random | None = None,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._exe = exe
        self._workshop = workshop
        self._run = run
        self._rng = rng or random.Random()
        self._clock = clock
        self._items: list[Wallpaper] = []
        self._loaded_at = float("-inf")
        self.current: Wallpaper | None = None
        self._last_matches: list[Wallpaper] = []
        self.suggestions: list[Wallpaper] = []
        self.paused = False
        self._thumbs: dict[str, bytes] = {}

    @property
    def available(self) -> bool:
        return self._exe is not None and self._workshop is not None

    def items(self) -> list[Wallpaper]:
        if self._workshop is not None and self._clock() - self._loaded_at >= RELOAD_SECONDS:
            self._items = read_wallpapers(self._workshop)
            self._loaded_at = self._clock()
        return self._items

    def by_id(self, wallpaper_id: str) -> Wallpaper | None:
        return next((w for w in self.items() if w.id == wallpaper_id), None)

    def suggest(self, count: int = 3) -> list[Wallpaper]:
        """Unos cuantos fondos distintos para elegir (sin el que está puesto); cada llamada saca otros."""
        pool = [w for w in self.items() if self.current is None or w.id != self.current.id]
        self.suggestions = self._rng.sample(pool, min(count, len(pool)))
        return self.suggestions

    def thumbnail(self, wallpaper_id: str) -> bytes | None:
        """Miniatura (JPEG de 256×144 como mucho) de un fondo, a partir de su imagen de vista previa."""
        if wallpaper_id in self._thumbs:
            return self._thumbs[wallpaper_id]
        wallpaper = self.by_id(wallpaper_id)
        if wallpaper is None:
            return None
        folder = wallpaper.project.parent
        try:
            preview = str(json.loads(wallpaper.project.read_text(encoding="utf-8-sig")).get("preview") or "")
        except (OSError, ValueError):
            preview = ""
        names = [n for n in (preview, "preview.jpg", "preview.png", "preview.gif") if n]
        source = next(
            (
                folder / n
                for n in names
                if (folder / n).is_file() and (folder / n).resolve().parent == folder.resolve()
            ),
            None,
        )
        if source is None:
            return None
        try:
            from io import BytesIO

            from PIL import Image

            with Image.open(source) as opened:
                opened.seek(0)  # el primer cuadro si es un GIF
                small = opened.convert("RGB")
            small.thumbnail((256, 144))
            out = BytesIO()
            small.save(out, "JPEG", quality=82)
        except Exception:  # una imagen que no se puede leer: sin miniatura
            log.debug("No pude hacer la miniatura de %s", wallpaper_id, exc_info=True)
            return None
        if len(self._thumbs) > 200:
            self._thumbs.clear()
        self._thumbs[wallpaper_id] = out.getvalue()
        return self._thumbs[wallpaper_id]

    def tag_summary(self, top: int = 5) -> list[str]:
        """Las etiquetas más comunes, en español cuando hay traducción: «anime, juegos, paisaje…»."""
        counts: dict[str, int] = {}
        for w in self.items():
            for t in w.tags:
                counts[t] = counts.get(t, 0) + 1
        back = {en: es for es, ens in reversed(list(SPANISH_TAGS.items())) for en in ens}
        names = [back.get(t, t) for t, _ in sorted(counts.items(), key=lambda kv: -kv[1])]
        return list(dict.fromkeys(names))[:top]

    def search(self, query: str) -> list[Wallpaper]:
        """Los fondos que encajan con lo pedido, los mejores primero. Vacío si nada encaja."""
        words = [w for w in normalize(query).split() if w not in _FILLER]
        if not words:
            return []
        scored: list[tuple[int, Wallpaper]] = []
        for w in self.items():
            title = w.words()
            score = 0
            for word in words:
                wanted = SPANISH_TAGS.get(word, (word,))
                if any(tag in w.tags for tag in wanted):
                    score += 3
                if word in title or (len(word) >= 4 and any(t.startswith(word) for t in title)):
                    score += 2
            if score and (score >= 3 or len(words) == 1):
                scored.append((score, w))
        if not scored:
            return []
        best = max(s for s, _ in scored)
        # Los mejores, y si son pocos, algunos casi mejores: así «otro» tiene de dónde sacar.
        return [w for s, w in sorted(scored, key=lambda sw: -sw[0]) if s >= max(1, best - 1)]

    def is_random_request(self, query: str) -> bool:
        said = set(normalize(query).split())
        return not [w for w in said if w not in _FILLER] or bool(said & _RANDOM_WORDS)

    def pick(self, query: str) -> tuple[Wallpaper | None, int]:
        """Elige un fondo para lo pedido: (fondo, cuántos encajaban). Sin pedido o «al azar», cualquiera."""
        if self.is_random_request(query):
            pool = [w for w in self.items() if self.current is None or w.id != self.current.id]
        else:
            pool = self.search(query)
        self._last_matches = pool
        if not pool:
            return None, 0
        return self._rng.choice(pool), len(pool)

    def another(self) -> tuple[Wallpaper | None, int]:
        """Otro del mismo grupo que el último pedido (o uno cualquiera si no había)."""
        pool = [
            w for w in (self._last_matches or self.items()) if self.current is None or w.id != self.current.id
        ]
        if not pool:
            return None, 0
        return self._rng.choice(pool), len(pool)

    def apply(self, wallpaper: Wallpaper) -> bool:
        """Le dice a Wallpaper Engine que ponga este fondo. False si no se pudo."""
        if self._exe is None:
            return False
        try:
            result = self._run(
                [str(self._exe), "-control", "openWallpaper", "-file", str(wallpaper.project)],
                capture_output=True,
                timeout=15,
                check=False,
                creationflags=_NO_WINDOW,
            )
        except (OSError, subprocess.SubprocessError):
            log.warning("No pude hablar con Wallpaper Engine", exc_info=True)
            return False
        if getattr(result, "returncode", 0) not in (0, None):
            return False
        self.current = wallpaper
        return True

    def control(self, command: str) -> bool:
        """`pause`, `play`, `mute`… (órdenes sin argumentos de Wallpaper Engine)."""
        self_paused = command == "pause" if command in ("pause", "play") else None
        if self._exe is None or not re.fullmatch(r"[a-zA-Z]+", command):
            return False
        try:
            self._run(
                [str(self._exe), "-control", command],
                capture_output=True,
                timeout=15,
                check=False,
                creationflags=_NO_WINDOW,
            )
        except (OSError, subprocess.SubprocessError):
            return False
        if self_paused is not None:
            self.paused = self_paused
        return True
