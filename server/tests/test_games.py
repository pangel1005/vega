"""El juego al que se juega: Steam (registro y manifiestos), Epic (programas en marcha) y su información."""

import asyncio
import json
from io import BytesIO
from pathlib import Path

from PIL import Image

from server.core.hub import Hub
from server.device.games import GameInfo, GameWatcher, parse_info, vdf_value
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.ws.protocol import GameEvent

STORE: dict[str, object] = {
    "short_description": "Un juego de <b>superhéroes</b> &quot;por equipos&quot;.",
    "developers": ["NetEase Games"],
    "genres": [{"id": "1", "description": "Acción"}, {"id": "25", "description": "Aventura"}],
    "categories": [
        {"id": 1, "description": "Multijugador"},
        {"id": 2, "description": "Logros"},
        {"id": 3, "description": "JcJ"},
    ],
    "release_date": {"coming_soon": False, "date": "5 DIC 2024"},
    "metacritic": {"score": 79},
}
ME = 76561198093287018  # SteamID64 del usuario más reciente; su carpeta es ME − 76561197960265728


def library(tmp: Path) -> Path:
    """Una carpeta de Steam como la de verdad: un juego (2767030), una herramienta y dos usuarios."""
    steam = tmp / "Steam"
    apps = steam / "steamapps"
    apps.mkdir(parents=True)
    (apps / "appmanifest_2767030.acf").write_text(
        '"AppState"\n{\n\t"appid"\t\t"2767030"\n\t"name"\t\t"Marvel Rivals"\n'
        '\t"installdir"\t\t"MarvelRivals"\n\t"SizeOnDisk"\t\t"92066650775"\n}\n',
        encoding="utf-8",
    )
    (apps / "appmanifest_431960.acf").write_text(
        '"AppState" { "appid" "431960" "name" "Wallpaper Engine" "installdir" "we" }', encoding="utf-8"
    )
    (steam / "config").mkdir()
    (steam / "config" / "loginusers.vdf").write_text(
        f'"users" {{ "{ME}" {{ "MostRecent" "1" }} "76561197960265730" {{ "MostRecent" "0" }} }}',
        encoding="utf-8",
    )
    mine = steam / "userdata" / str(ME - 76561197960265728) / "config"
    mine.mkdir(parents=True)
    (mine / "localconfig.vdf").write_text('"apps" { "2767030" { "Playtime" "16090" } }', encoding="utf-8")
    other = steam / "userdata" / "2" / "config"
    other.mkdir(parents=True)
    (other / "localconfig.vdf").write_text('"apps" { "2767030" { "Playtime" "169" } }', encoding="utf-8")
    art = steam / "appcache" / "librarycache" / "2767030" / "abc"
    art.mkdir(parents=True)
    Image.new("RGB", (920, 430), (200, 30, 30)).save(art / "library_header.jpg")
    return steam


def watcher(
    tmp: Path,
    appid: int = 0,
    procs: list[tuple[str, float]] | None = None,
    epic: Path | None = None,
    ignore: tuple[int, ...] = (),
) -> GameWatcher:
    steam = tmp / "Steam"
    return GameWatcher(
        steam if steam.exists() else library(tmp),
        epic,
        tmp / "games",
        ignore=ignore,
        running_appid=lambda: appid,
        processes=lambda: procs or [],
        fetch=lambda _id: STORE,
        now=lambda: 1000.0,
        background=False,
    )


def test_steam_game_has_name_hours_size_and_art(tmp_path: Path) -> None:
    game = watcher(tmp_path, 2767030).current()
    assert game is not None
    assert (game.name, game.source, game.appid, game.key) == (
        "Marvel Rivals",
        "Steam",
        2767030,
        "steam:2767030",
    )
    assert game.hours is not None and round(game.hours, 1) == 268.2  # 16 090 min del usuario más reciente
    assert game.size_gb == 92.1
    assert game.art_id == "steam-2767030"


def test_nothing_running_or_a_steam_tool_is_not_a_game(tmp_path: Path) -> None:
    assert watcher(tmp_path, 0).current() is None
    assert watcher(tmp_path, 431960).current() is None  # Wallpaper Engine
    assert watcher(tmp_path, 2767030, ignore=(2767030,)).current() is None


def test_session_starts_when_the_game_process_started(tmp_path: Path) -> None:
    exe = tmp_path / "Steam" / "steamapps" / "common" / "MarvelRivals" / "Marvel.exe"
    w = watcher(tmp_path, 2767030, [(str(exe), 400.0), (r"C:\Windows\x.exe", 5.0)])
    game = w.current()
    assert game is not None and game.started == 400.0  # no cuándo lo vio Vega, sino cuándo se abrió de verdad


def test_epic_game_is_found_by_its_install_folder(tmp_path: Path) -> None:
    epic = tmp_path / "Manifests"
    epic.mkdir()
    rocket = {
        "AppName": "Sugar",
        "DisplayName": "Rocket League",
        "InstallLocation": r"C:\Epic\rl",
        "LaunchExecutable": "x",
    }
    extra = {
        "AppName": "extra",
        "DisplayName": "Contenido",
        "InstallLocation": r"C:\Epic\fn",
    }  # no se puede lanzar
    (epic / "a.item").write_text(json.dumps(rocket), encoding="utf-8")
    (epic / "b.item").write_text(json.dumps(extra), encoding="utf-8")
    assert watcher(tmp_path, 0, [(r"C:\Epic\fn\x.exe", 10.0)], epic=epic).current() is None
    on = watcher(tmp_path, 0, [(r"C:\Epic\rl\Binaries\RL.exe", 700.0)], epic=epic)
    game = on.current()
    assert game is not None and (game.name, game.source, game.started) == ("Rocket League", "Epic", 700.0)
    assert on.info(game) is None  # sin tienda para Epic: solo el nombre y la sesión


def test_store_info_is_cleaned_cached_and_can_be_switched_off(tmp_path: Path) -> None:
    w = watcher(tmp_path, 2767030)
    game = w.current()
    assert game is not None
    info = w.info(game)
    assert info == GameInfo(
        summary='Un juego de superhéroes "por equipos".',
        genres=("Acción", "Aventura"),
        developer="NetEase Games",
        released="5 DIC 2024",
        rating=79,
        tags=("Multijugador", "JcJ"),
    )
    assert (tmp_path / "games" / "2767030.json").is_file()  # guardado: la próxima vez no se pide
    offline = GameWatcher(tmp_path / "Steam", None, tmp_path / "games", fetch=lambda _i: None)
    assert offline.info(game) == info
    private = GameWatcher(
        tmp_path / "Steam", None, tmp_path / "otra", store_info=False, fetch=lambda _i: STORE
    )
    assert private.info(game) is None  # con store_info apagado no se pide nada


def test_a_failed_store_answer_is_retried_later_not_every_second(tmp_path: Path) -> None:
    calls: list[int] = []
    clock = [1000.0]

    def failing(appid: int) -> dict[str, object] | None:
        calls.append(appid)
        return None

    w = GameWatcher(
        library(tmp_path),
        None,
        tmp_path / "games",
        running_appid=lambda: 2767030,
        fetch=failing,
        now=lambda: clock[0],
        background=False,
    )
    game = w.current()
    assert game is not None
    for _ in range(5):
        assert w.info(game) is None
    assert calls == [2767030]  # una sola vez
    clock[0] += 700
    w.info(game)
    assert calls == [2767030, 2767030]


def test_art_is_a_small_jpeg_from_the_steam_cache(tmp_path: Path) -> None:
    w = watcher(tmp_path, 2767030)
    data = w.art("steam-2767030")
    assert data is not None and data[:2] == b"\xff\xd8"
    assert Image.open(BytesIO(data)).width == 640
    assert w.art("steam-999") is None and w.art("../../etc/passwd") is None


def test_parse_info_and_vdf_helpers() -> None:
    assert parse_info({}) == GameInfo()
    assert vdf_value('"a" "1" "name" "Hola mundo"', "name") == "Hola mundo"
    assert vdf_value('"a" "1"', "zzz") == ""


class Phone:
    def __init__(self) -> None:
        self.events: list[object] = []

    async def send_event(self, event: object) -> None:
        self.events.append(event)


def test_hub_sends_the_game_only_when_it_changes(tmp_path: Path) -> None:
    appid = [0]
    w = GameWatcher(
        library(tmp_path),
        None,
        tmp_path / "games",
        running_appid=lambda: appid[0],
        fetch=lambda _i: STORE,
        background=False,
    )
    hub = Hub(PersonalMemory(ConversationStore(":memory:")), games=w)
    phone = Phone()
    hub.add(phone)  # type: ignore[arg-type]

    async def run() -> None:
        await hub.send_game()  # nada abierto: se avisa una vez
        await hub.send_game()
        appid[0] = 2767030
        await hub.send_game()
        await hub.send_game()  # lo mismo (solo avanzó la sesión): no se repite
        appid[0] = 0
        await hub.send_game()

    asyncio.run(run())
    events = [e for e in phone.events if isinstance(e, GameEvent)]
    assert [e.active for e in events] == [False, True, False]
    first = events[1]
    assert first.name == "Marvel Rivals" and first.genres == ["Acción", "Aventura"] and first.rating == 79
