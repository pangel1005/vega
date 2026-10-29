"""El juego al que se está jugando en el PC, para la sección «Juego» del teléfono.

Se sabe sin instalar nada y casi todo sin internet:
- **Steam**: apunta en el registro (`RunningAppID`) qué juego tiene abierto. El nombre, la carpeta y el
  tamaño salen de sus `appmanifest_*.acf`; las horas jugadas, de `userdata/<usuario>/config/localconfig.vdf`;
  la carátula, de su caché local (`appcache/librarycache`).
- **Epic**: se mira si hay un programa en marcha dentro de la carpeta de instalación de algún juego suyo
  (`ProgramData/Epic/.../Manifests/*.item`).
- La descripción, géneros y nota los da la tienda de Steam (`appdetails`, público): solo se le manda el
  número del juego, nada más, y se guarda en `data/games/` para no volver a pedirlo. Se apaga con
  `[games] store_info = false`.

Nada de esto lee ni guarda lo que haces dentro del juego.
"""

from __future__ import annotations

import html
import io
import json
import logging
import re
import sys
import threading
import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from pathlib import Path

from server.device.match import Match, MatchSource
from server.device.steam_web import Live, SteamWeb

log = logging.getLogger(__name__)

# Lo que Steam abre pero no es un juego (Steamworks, Wallpaper Engine…).
STEAM_TOOLS = frozenset({228980, 431960, 250820, 1493710})
STEAM64_BASE = 76561197960265728  # el SteamID64 menos esto es el número de la carpeta userdata
ART_WIDTH = 640
STORE_URL = "https://store.steampowered.com/api/appdetails"
RETRY_SECONDS = 600  # tras fallar la tienda, no se vuelve a probar hasta pasado este tiempo
REFRESH_SECONDS = 60  # cada cuánto se vuelven a leer los manifiestos y las horas jugadas
SCAN_SECONDS = 5  # cada cuánto se miran los procesos (Epic y hora de inicio)
# Juegos que no están en Steam ni Epic, por el nombre de su programa de partida (no el lanzador):
# clave, nombre y tienda.
KNOWN_GAMES = {
    "league of legends.exe": ("riot:lol", "League of Legends", "Riot"),
    "valorant-win64-shipping.exe": ("riot:valorant", "VALORANT", "Riot"),
    "rainbowsix.exe": ("ubi:r6", "Rainbow Six Siege", "Ubisoft"),
    "rainbowsix_vulkan.exe": ("ubi:r6", "Rainbow Six Siege", "Ubisoft"),
}
STORE_KEYS = (
    "short_description",
    "developers",
    "genres",
    "categories",
    "release_date",
    "metacritic",
    "achievements",
)
TAGS = (
    "Un jugador",
    "Multijugador",
    "JcJ",
    "Cooperativo",
    "Cooperativo online",
    "Mando completo",
)  # lo que se enseña

Processes = Callable[[], list[tuple[str, float]]]  # (ruta del programa, cuándo empezó) de lo que corre ahora


@dataclass(frozen=True)
class Game:
    key: str  # «steam:2767030» o «epic:Sugar»
    name: str
    source: str  # «Steam» o «Epic»
    appid: int | None
    started: float  # segundos desde 1970 (UTC) en que se abrió, o cuando se vio por primera vez
    art_id: str  # vacío si no hay carátula
    hours: float | None  # horas jugadas en total según Steam
    size_gb: float | None  # lo que ocupa en el disco
    played_2w: float | None = None  # horas de las últimas dos semanas según Steam


@dataclass(frozen=True)
class RecentGame:
    """Un juego de los que jugaste hace poco, para cuando no hay ninguno abierto."""

    name: str
    art_id: str
    hours: float | None
    last_played: float  # segundos desde 1970 (UTC)


@dataclass(frozen=True)
class GameInfo:
    summary: str = ""
    genres: tuple[str, ...] = ()
    developer: str = ""
    released: str = ""
    rating: int | None = None  # nota de Metacritic, si la tiene
    tags: tuple[str, ...] = ()
    achievements: int | None = None  # cuántos logros tiene el juego (en total)


def vdf_value(text: str, key: str) -> str:
    """El valor de `"clave" "valor"` en un archivo de Steam (.acf, .vdf); vacío si no está."""
    m = re.search(r'"' + re.escape(key) + r'"\s+"([^"]*)"', text)
    return m.group(1) if m else ""


def steam_id64(steam: Path | None) -> int | None:
    """El SteamID64 de la cuenta con la que se entró la última vez en Steam (de config/loginusers.vdf)."""
    if steam is None:
        return None
    try:
        text = (steam / "config" / "loginusers.vdf").read_text(encoding="utf-8", errors="ignore")
    except OSError:
        return None
    for steam_id, block in re.findall(r'"(\d{17})"\s*\{([^{}]*)\}', text):
        if vdf_value(block, "MostRecent") == "1":
            return int(steam_id)
    return None


def read_running_appid() -> int:
    """El juego que Steam tiene abierto ahora (0 = ninguno). Solo en Windows."""
    if sys.platform != "win32":
        return 0
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as key:
            return int(winreg.QueryValueEx(key, "RunningAppID")[0])
    except OSError:
        return 0


def steam_root() -> Path | None:
    if sys.platform != "win32":
        return None
    import winreg

    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Valve\Steam") as key:
            path = Path(str(winreg.QueryValueEx(key, "SteamPath")[0]))
    except OSError:
        return None
    return path if path.is_dir() else None


def epic_manifests() -> Path | None:
    path = Path(r"C:\ProgramData\Epic\EpicGamesLauncher\Data\Manifests")
    return path if path.is_dir() else None


def list_processes() -> list[tuple[str, float]]:
    import psutil

    found: list[tuple[str, float]] = []
    for proc in psutil.process_iter(["exe", "create_time"]):
        exe = proc.info.get("exe")
        if exe:
            found.append((exe, float(proc.info.get("create_time") or 0)))
    return found


def fetch_store(appid: int) -> dict[str, object] | None:
    """Pide a la tienda de Steam los datos públicos del juego (solo viaja el número). None = no se pudo."""
    import httpx

    try:
        reply = httpx.get(
            STORE_URL,
            params={
                "appids": appid,
                "l": "spanish",
            },
            timeout=6.0,
        )
        reply.raise_for_status()
        body = reply.json()[str(appid)]
    except (httpx.HTTPError, ValueError, KeyError):
        return None
    data = body.get("data") if body.get("success") else {}
    if not isinstance(data, dict):
        return {}
    # Solo lo que se enseña: la respuesta entera pesa cientos de KB y no hace falta guardarla.
    return {k: data[k] for k in STORE_KEYS if k in data}


def parse_info(data: dict[str, object]) -> GameInfo:
    """De la respuesta de la tienda a lo que se enseña (textos limpios y cortos)."""

    def text(value: object) -> str:
        return re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", html.unescape(str(value or "")))).strip()

    def names(value: object) -> list[str]:
        items = value if isinstance(value, list) else []
        return [text(i.get("description")) for i in items if isinstance(i, dict) and i.get("description")]

    developers = data.get("developers")
    release = data.get("release_date")
    metacritic = data.get("metacritic")
    score = metacritic.get("score") if isinstance(metacritic, dict) else None
    categories = names(data.get("categories"))
    ach = data.get("achievements")
    total = ach.get("total") if isinstance(ach, dict) else None
    return GameInfo(
        summary=text(data.get("short_description"))[:320],
        genres=tuple(names(data.get("genres"))[:4]),
        developer=text(developers[0]) if isinstance(developers, list) and developers else "",
        released=text(release.get("date"))
        if isinstance(release, dict) and not release.get("coming_soon")
        else "",
        rating=int(score) if isinstance(score, int) else None,
        tags=tuple(t for t in TAGS if t in categories)[:3],
        achievements=total if isinstance(total, int) and total > 0 else None,
    )


class GameWatcher:
    def __init__(
        self,
        steam: Path | None,
        epic: Path | None,
        cache: Path,
        *,
        store_info: bool = True,
        ignore: Iterable[int] = (),
        running_appid: Callable[[], int] = read_running_appid,
        processes: Processes = list_processes,
        fetch: Callable[[int], dict[str, object] | None] = fetch_store,
        now: Callable[[], float] = time.time,
        background: bool = True,
        web: SteamWeb | None = None,
        matches: MatchSource | None = None,
    ) -> None:
        self._web = web
        self._matches = matches
        self._last_played: dict[int, float] = {}
        self._playtime_2w: dict[int, float] = {}
        self._steam = steam
        self._epic = epic
        self._cache = cache
        self._store_info = store_info
        self._ignore = frozenset(ignore) | STEAM_TOOLS
        self._running_appid = running_appid
        self._processes = processes
        self._fetch = fetch
        self._now = now
        self._background = background  # False en las pruebas: la tienda se pide en el mismo hilo
        self._lock = threading.Lock()
        self._seen: dict[str, float] = {}
        self._steam_apps: dict[
            int, tuple[str, str, int, Path]
        ] = {}  # appid → (nombre, carpeta, bytes, steamapps)
        self._epic_apps: list[tuple[str, str, str]] = []  # (AppName, nombre, carpeta de instalación)
        self._playtime: dict[int, float] = {}
        self._loaded_at = -1e9
        self._procs: list[tuple[str, float]] = []
        self._procs_at = -1e9
        self._info: dict[int, GameInfo] = {}
        self._asked: dict[int, float] = {}
        self._art: dict[str, bytes | None] = {}
        self._exe_art: dict[str, Path] = {}  # id de carátula → programa del que sacar el icono

    # --- lectura de Steam y Epic ---

    def _libraries(self) -> list[Path]:
        if self._steam is None:
            return []
        libs = [self._steam / "steamapps"]
        try:
            text = (self._steam / "steamapps" / "libraryfolders.vdf").read_text(
                encoding="utf-8", errors="ignore"
            )
        except OSError:
            return libs
        for raw in re.findall(r'"path"\s+"([^"]*)"', text):
            path = Path(raw.replace("\\\\", "\\")) / "steamapps"
            if path not in libs and path.is_dir():
                libs.append(path)
        return libs

    def _steam_user_dir(self) -> Path | None:
        if self._steam is None:
            return None
        users = self._steam / "userdata"
        try:
            text = (self._steam / "config" / "loginusers.vdf").read_text(encoding="utf-8", errors="ignore")
            for steam_id, block in re.findall(r'"(\d{17})"\s*\{([^{}]*)\}', text):
                if vdf_value(block, "MostRecent") == "1":
                    return users / str(int(steam_id) - STEAM64_BASE)
        except OSError:
            pass
        return None

    def _refresh(self) -> None:
        if self._now() - self._loaded_at < REFRESH_SECONDS:
            return
        self._loaded_at = self._now()
        apps: dict[int, tuple[str, str, int, Path]] = {}
        for lib in self._libraries():
            for acf in lib.glob("appmanifest_*.acf"):
                try:
                    text = acf.read_text(encoding="utf-8", errors="ignore")
                    appid = int(vdf_value(text, "appid"))
                    apps[appid] = (
                        vdf_value(text, "name"),
                        vdf_value(text, "installdir"),
                        int(vdf_value(text, "SizeOnDisk") or 0),
                        lib,
                    )
                    last = vdf_value(text, "LastPlayed")
                    if last.isdigit() and int(last) > 0:
                        self._last_played[appid] = float(last)
                except (OSError, ValueError):
                    continue
        self._steam_apps = apps
        self._playtime, self._playtime_2w = self._read_playtime()
        epic: list[tuple[str, str, str]] = []
        if self._epic is not None:
            for item in self._epic.glob("*.item"):
                try:
                    data = json.loads(item.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    continue
                where = str(data.get("InstallLocation") or "")
                if where and data.get(
                    "LaunchExecutable"
                ):  # sin ejecutable son contenidos extra (Fortnite: LEGO…)
                    epic.append((str(data.get("AppName")), str(data.get("DisplayName") or ""), where))
        self._epic_apps = epic

    def _read_playtime(self) -> tuple[dict[int, float], dict[int, float]]:
        """Horas jugadas por juego, en total y en las dos últimas semanas, según Steam (localconfig.vdf)."""
        user = self._steam_user_dir()
        files = [user / "config" / "localconfig.vdf"] if user else []
        if not files and self._steam is not None:  # sin saber cuál es el usuario: el que más haya jugado
            files = list((self._steam / "userdata").glob("*/config/localconfig.vdf"))
        best: dict[int, float] = {}
        recent: dict[int, float] = {}
        for path in files:
            try:
                text = path.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            for appid, block in re.findall(r'"(\d+)"\s*\{([^{}]*)\}', text):
                minutes = vdf_value(block, "Playtime")
                if minutes.isdigit():
                    best[int(appid)] = max(best.get(int(appid), 0.0), int(minutes) / 60)
                    fortnight = vdf_value(block, "Playtime2wks")
                    if fortnight.isdigit():
                        recent[int(appid)] = max(recent.get(int(appid), 0.0), int(fortnight) / 60)
        return best, recent

    def _scan(self) -> list[tuple[str, float]]:
        if self._now() - self._procs_at >= SCAN_SECONDS:
            self._procs_at = self._now()
            try:
                self._procs = self._processes()
            except Exception:  # un proceso que desaparece o sin permiso no debe parar nada
                log.debug("No pude leer los procesos", exc_info=True)
                self._procs = []
        return self._procs

    # --- el juego de ahora ---

    def _known_game(self) -> Game | None:
        """Riot y Ubisoft: solo cuentan mientras hay partida (el lanzador abierto no es una partida)."""
        for exe, when in self._scan():
            found = KNOWN_GAMES.get(exe.lower().rsplit("\\", 1)[-1])
            if found is None:
                continue
            key, name, store = found
            self._exe_art[f"exe-{key.split(':')[1]}"] = Path(exe)
            return Game(
                key=key,
                name=name,
                source=store,
                appid=None,
                started=when if when > 0 else self._seen.setdefault(key, self._now()),
                art_id=f"exe-{key.split(':')[1]}",
                hours=None,
                size_gb=None,
            )
        return None

    def current(self) -> Game | None:
        """El juego en marcha, o None. Barato: se puede llamar cada segundo."""
        with self._lock:
            self._refresh()
            game = self._steam_game() or self._epic_game() or self._known_game()
            keys = {game.key} if game else set()
            for gone in set(self._seen) - keys:
                del self._seen[gone]  # al cerrarlo, la próxima vez cuenta desde cero
            return game

    def _steam_game(self) -> Game | None:
        appid = self._running_appid()
        if appid <= 0 or appid in self._ignore:
            return None
        name, folder, size, lib = self._steam_apps.get(appid, ("", "", 0, Path()))
        started = self._seen.setdefault(f"steam:{appid}", self._now())
        if folder:
            inside = str(lib / "common" / folder).lower() + "\\"
            starts = [t for exe, t in self._scan() if exe.lower().startswith(inside) and t > 0]
            if starts:
                started = min(starts)
        return Game(
            key=f"steam:{appid}",
            name=name or f"Juego de Steam {appid}",
            source="Steam",
            appid=appid,
            started=started,
            art_id=f"steam-{appid}" if self._steam_art(appid) else "",
            hours=self._playtime.get(appid),
            size_gb=round(size / 1e9, 1) if size else None,
            played_2w=self._playtime_2w.get(appid),
        )

    def _epic_game(self) -> Game | None:
        if not self._epic_apps:
            return None
        procs = self._scan()
        for app, name, where in self._epic_apps:
            inside = where.rstrip("\\/").lower() + "\\"
            running = [(exe, t) for exe, t in procs if exe.lower().startswith(inside)]
            starts = [t for _exe, t in running]
            if starts:
                key = f"epic:{app}"
                real = [t for t in starts if t > 0]
                art = "exe-" + re.sub(r"[^a-z0-9]+", "-", app.lower()).strip("-")
                self._exe_art[art] = Path(running[0][0])
                return Game(
                    key=key,
                    name=re.sub(r"[®™]", "", name or app).strip(),
                    source="Epic",
                    appid=None,
                    started=min(real) if real else self._seen.setdefault(key, self._now()),
                    art_id=art,
                    hours=None,
                    size_gb=None,
                )
        return None

    # --- lo que no depende de que haya un juego abierto ---

    def recent(self, count: int = 3) -> list[RecentGame]:
        """Los últimos juegos de Steam que jugaste (instalados), del más reciente al más antiguo."""
        with self._lock:
            self._refresh()
            found = [
                (when, appid)
                for appid, when in self._last_played.items()
                if appid in self._steam_apps and appid not in self._ignore
            ]
            found.sort(reverse=True)
            return [
                RecentGame(
                    name=self._steam_apps[appid][0] or f"Juego de Steam {appid}",
                    art_id=f"steam-{appid}" if self._steam_art(appid) else "",
                    hours=self._playtime.get(appid),
                    last_played=when,
                )
                for when, appid in found[:count]
            ]

    def match(self, game: Game | None) -> Match | None:
        """La partida en curso si el juego tiene API local (LoL, Rocket League); sin juego, deja de mirar."""
        if self._matches is None:
            return None
        if game is None:
            self._matches.idle()
            return None
        return self._matches.match(game.name, game.key)

    def steam_install(self, appid: int) -> Path | None:
        """La carpeta donde Steam instaló el juego (p. ej. Counter-Strike 2 = 730), si está instalado."""
        with self._lock:
            self._refresh()
            _name, folder, _size, lib = self._steam_apps.get(appid, ("", "", 0, Path()))
        path = lib / "common" / folder
        return path if folder and path.is_dir() else None

    def rocket_ini(self) -> Path | None:
        """El archivo de configuración de la Stats API de Rocket League, si está instalado (Epic o Steam)."""
        with self._lock:
            self._refresh()
            folders = [
                Path(where) for _app, name, where in self._epic_apps if "rocket league" in name.lower()
            ]
            folders += [
                lib / "common" / folder
                for name, folder, _size, lib in self._steam_apps.values()
                if "rocket league" in name.lower() and folder
            ]
        for folder in folders:
            ini = folder / "TAGame" / "Config" / "DefaultStatsAPI.ini"
            if ini.is_file():
                return ini
        return None

    def live(self, game: Game) -> Live:
        """Jugadores, noticias y (con cuenta) logros y amigos del juego abierto; vacío si no es de Steam."""
        if game.appid is None or self._web is None:
            return Live()
        return self._web.live(game.appid)

    @property
    def account(self) -> bool:
        """¿Hay una cuenta de Steam conectada (para logros y amigos)?"""
        return self._web is not None and self._web.connected

    # --- carátula ---

    def _steam_art(self, appid: int) -> Path | None:
        if self._steam is None:
            return None
        folder = self._steam / "appcache" / "librarycache" / str(appid)
        for pattern in (
            "header.jpg",
            "**/library_header.jpg",
            "**/header.jpg",
            "library_hero.jpg",
            "**/library_hero.jpg",
        ):
            for found in folder.glob(pattern):
                return found
        return None

    def art(self, art_id: str) -> bytes | None:
        """La carátula en JPEG (de la caché de Steam, sin internet), o None."""
        if art_id.startswith("exe-"):  # juegos sin carátula (Epic, Riot): su icono sobre un fondo de su color
            if art_id not in self._art:
                exe = self._exe_art.get(art_id)
                self._art[art_id] = self._render_icon_art(exe) if exe else None
            return self._art[art_id]
        m = re.fullmatch(r"steam-(\d+)", art_id)
        if m is None:
            return None
        if art_id not in self._art:
            self._art[art_id] = self._render_art(self._steam_art(int(m.group(1))))
        return self._art[art_id]

    @staticmethod
    def _render_icon_art(exe: Path) -> bytes | None:
        """El icono del juego en grande sobre un degradado de su color (como una carátula)."""
        from server.tools.app_icons import extract_png

        png = extract_png(exe, 256)
        if png is None:
            return None
        try:
            from PIL import Image

            with Image.open(io.BytesIO(png)) as source:
                icon = source.convert("RGBA")
            tiny = icon.resize((1, 1), Image.Resampling.BOX).getpixel((0, 0))
            red, green, blue = (int(c) for c in tiny[:3]) if isinstance(tiny, tuple) else (60, 60, 80)
            canvas = Image.new("RGB", (ART_WIDTH, 300))
            for y in range(300):  # de su color, oscurecido, hacia casi negro
                shade = 0.55 - 0.45 * y / 300
                canvas.paste(
                    (int(red * shade), int(green * shade), int(blue * shade)), (0, y, ART_WIDTH, y + 1)
                )
            canvas.paste(icon, ((ART_WIDTH - 256) // 2, 22), icon)
            out = io.BytesIO()
            canvas.save(out, "JPEG", quality=88)
            return out.getvalue()
        except (OSError, ValueError):
            return None

    @staticmethod
    def _render_art(path: Path | None) -> bytes | None:
        if path is None:
            return None
        try:
            from PIL import Image

            with Image.open(path) as source:
                image = source.convert("RGB")
            if image.width > ART_WIDTH:
                image = image.resize((ART_WIDTH, round(image.height * ART_WIDTH / image.width)))
            out = io.BytesIO()
            image.save(out, "JPEG", quality=85)
            return out.getvalue()
        except (OSError, ValueError):
            return None

    # --- información de la tienda ---

    def info(self, game: Game) -> GameInfo | None:
        """Lo que se sabe del juego. Si no está guardado y se permite, se pide aparte y llega después."""
        if game.appid is None:
            return None
        appid = game.appid
        if appid in self._info:
            return self._info[appid]
        saved = self._cache / f"{appid}.json"
        try:
            self._info[appid] = parse_info(json.loads(saved.read_text(encoding="utf-8")))
            return self._info[appid]
        except (OSError, ValueError):
            pass
        if self._store_info and self._now() - self._asked.get(appid, -1e9) >= RETRY_SECONDS:
            self._asked[appid] = self._now()
            if self._background:
                threading.Thread(target=self._download, args=(appid,), daemon=True).start()
            else:
                self._download(appid)
        return self._info.get(appid)

    def _download(self, appid: int) -> None:
        data = self._fetch(appid)
        if data is None:
            return
        try:
            self._cache.mkdir(parents=True, exist_ok=True)
            (self._cache / f"{appid}.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
        except OSError:
            log.debug("No pude guardar los datos del juego", exc_info=True)
        self._info[appid] = parse_info(data)
