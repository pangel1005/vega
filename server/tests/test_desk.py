"""«Vega manda en tu PC»: fondos de Wallpaper Engine, volumen por programa y juegos, con datos de mentira."""

from __future__ import annotations

import json
import random
import subprocess
from pathlib import Path
from typing import Any

from server.core.text import normalize
from server.device.mixer import Mixer
from server.device.wallpaper import WallpaperEngine, read_wallpapers
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.desk import Desk
from server.skills.router import SkillRouter

WALLPAPERS = [
    ("1001", "Akane in the rain", ["Anime", "Relaxing"]),
    ("1002", "Fortnite Chapter 5", ["Game"]),
    ("1003", "Mountain sunrise", ["Landscape", "Relaxing"]),
    ("1004", "Neon street", ["Cyberpunk", "Anime"]),
    ("1005", "Pixel forest", ["Pixel art", "Landscape"]),
]


def make_engine(tmp: Path, installed: bool = True) -> tuple[WallpaperEngine, list[list[str]]]:
    workshop = tmp / "workshop"
    for wid, title, tags in WALLPAPERS:
        (workshop / wid).mkdir(parents=True)
        (workshop / wid / "project.json").write_text(
            json.dumps({"title": title, "tags": tags, "type": "scene"})
        )
    calls: list[list[str]] = []

    def run(cmd: list[str], **_: Any) -> subprocess.CompletedProcess[bytes]:
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0)

    exe = tmp / "wallpaper64.exe"
    return WallpaperEngine(
        exe if installed else None, workshop if installed else None, run=run, rng=random.Random(3)
    ), calls


class FakeAudio:
    def __init__(self, name: str, volume: float, muted: bool = False) -> None:
        self.name, self.volume, self.muted = name, volume, muted

    def get_volume(self) -> float:
        return self.volume

    def set_volume(self, level: float) -> None:
        self.volume = level

    def get_mute(self) -> bool:
        return self.muted

    def set_mute(self, muted: bool) -> None:
        self.muted = muted


def make_desk(tmp: Path, *, playing: str | None = None) -> tuple[Desk, dict[str, Any]]:
    engine, wp_calls = make_engine(tmp)
    sessions = [
        FakeAudio("discord.exe", 0.8),
        FakeAudio("spotify.exe", 0.5),
        FakeAudio("opera.exe", 1.0),
        FakeAudio("fortniteclient-win64-shipping.exe", 0.6),
    ]
    launched: list[str] = []
    games = [
        ("Fortnite", "com.epicgames.launcher://apps/Fortnite?action=launch&silent=true"),
        ("Counter-Strike 2", "steam://rungameid/730"),
        ("Rocket League", "steam://rungameid/252950"),
        ("Counter-Strike: Global Offensive", "steam://rungameid/730000"),
    ]
    desk = Desk(
        wallpaper=engine,
        mixer=Mixer(lambda: list(sessions)),
        games=lambda: games,
        launch=launched.append,
        running_game=lambda: playing,
    )
    return desk, {
        "engine": engine,
        "wp_calls": wp_calls,
        "sessions": {s.name: s for s in sessions},
        "launched": launched,
    }


def say(desk: Desk, text: str) -> str | None:
    return desk.handle(text, normalize(text))


# ---------- fondos ----------


def test_reads_titles_and_tags(tmp_path: Path) -> None:
    engine, _ = make_engine(tmp_path)
    assert {w.title for w in read_wallpapers(tmp_path / "workshop")} == {t for _, t, _ in WALLPAPERS}
    assert engine.available


def test_wallpaper_by_tag_in_spanish(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    reply = say(desk, "Vega, pon un fondo de paisaje")
    assert reply is not None and reply.startswith("Listo: «")
    cmd = ctx["wp_calls"][-1]
    assert cmd[1:4] == ["-control", "openWallpaper", "-file"]
    assert cmd[4].endswith("project.json")
    assert ctx["engine"].current.title in {"Mountain sunrise", "Pixel forest"}


def test_calm_wallpaper_uses_the_relaxing_tag(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    say(desk, "pon un fondo tranquilo")
    assert ctx["engine"].current.title in {"Akane in the rain", "Mountain sunrise"}


def test_wallpaper_by_a_word_of_the_title(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "pon un fondo de fortnite") == "Listo: «Fortnite Chapter 5»."
    assert ctx["engine"].current.title == "Fortnite Chapter 5"


def test_unknown_wallpaper_says_what_there_is(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    reply = say(desk, "pon un fondo de submarinos")
    assert reply is not None and reply.startswith("No encuentro un fondo de eso. Tienes de:")
    assert ctx["wp_calls"] == []


def test_random_wallpaper_and_another_one(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "cambia el fondo") is not None
    first = ctx["engine"].current
    assert say(desk, "otro fondo") is not None
    assert ctx["engine"].current != first


def test_another_stays_in_the_group_you_asked_for(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    say(desk, "pon un fondo de anime")
    for _ in range(3):
        say(desk, "otro fondo")
        assert "anime" in ctx["engine"].current.tags


def test_what_wallpaper_and_pause(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert "No he cambiado" in (say(desk, "qué fondo tengo") or "")
    say(desk, "pon un fondo de fortnite")
    assert say(desk, "qué fondo tengo") == "Tienes puesto «Fortnite Chapter 5»."
    assert say(desk, "pausa el fondo") == "Fondo en pausa."
    assert ctx["wp_calls"][-1][1:] == ["-control", "pause"]
    assert say(desk, "reanuda el fondo") == "Fondo en marcha."


def test_without_wallpaper_engine_it_says_so(tmp_path: Path) -> None:
    engine, _ = make_engine(tmp_path, installed=False)
    assert say(Desk(wallpaper=engine), "pon un fondo de anime") == "No encuentro Wallpaper Engine en este PC."


def test_other_phrases_are_not_theirs(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    for phrase in (
        "sube el volumen",
        "qué hora es",
        "apaga el pc",
        "abre spotify",
        "pon un temporizador de 5 minutos",
    ):
        assert say(desk, phrase) is None


# ---------- volumen por programa ----------


def test_volume_down_and_up_a_program(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "baja Discord") == "Discord al 65 %."
    assert ctx["sessions"]["discord.exe"].volume == 0.65
    assert say(desk, "sube Spotify un poco") == "Spotify al 58 %."
    assert say(desk, "sube mucho Discord") == "Discord al 95 %."


def test_volume_to_a_level(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "pon Discord al 30") == "Discord al 30 %."
    assert ctx["sessions"]["discord.exe"].volume == 0.3
    assert say(desk, "Spotify al 40 por ciento") == "Spotify al 40 %."
    assert say(desk, "deja Spotify a la mitad") == "Spotify al 50 %."


def test_mute_and_unmute_the_browser(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "silencia el navegador") == "Navegador silenciado."
    assert ctx["sessions"]["opera.exe"].muted
    assert say(desk, "quita el silencio al navegador") == "Navegador con sonido otra vez."
    assert not ctx["sessions"]["opera.exe"].muted


def test_raising_the_volume_of_a_muted_program_unmutes_it(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    say(desk, "silencia discord")
    say(desk, "sube discord")
    assert not ctx["sessions"]["discord.exe"].muted


def test_a_game_is_found_by_the_start_of_its_program_name(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "baja fortnite") == "Fortnite al 45 %."
    assert ctx["sessions"]["fortniteclient-win64-shipping.exe"].volume == 0.45


def test_a_program_that_is_not_playing_is_explained(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    assert say(desk, "baja whatsapp") == "No oigo sonar a Whatsapp ahora mismo."


def test_the_general_volume_buttons_keep_their_phrases(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    assert say(desk, "sube el volumen") is None
    assert say(desk, "baja el volumen") is None
    assert say(desk, "silencia el pc") is None


# ---------- abrir juegos ----------


def test_open_an_epic_game(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "abre Fortnite") == "Abriendo Fortnite."
    assert ctx["launched"] == ["com.epicgames.launcher://apps/Fortnite?action=launch&silent=true"]


def test_open_a_steam_game_by_its_spoken_name_and_alias(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "lanza counter strike") == "Abriendo Counter-Strike 2."  # el más corto de los parecidos
    assert say(desk, "abre cs2") == "Abriendo Counter-Strike 2."
    assert ctx["launched"] == ["steam://rungameid/730", "steam://rungameid/730"]
    assert say(desk, "quiero jugar a rocket league") == "Abriendo Rocket League."


def test_open_does_not_steal_the_buttons_or_unknown_things(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path)
    assert say(desk, "abre spotify") is None
    assert say(desk, "abre el bloc de notas") is None
    assert say(desk, "abre minecraft") is None
    assert ctx["launched"] == []


def test_if_a_game_is_already_open_it_does_not_launch_another(tmp_path: Path) -> None:
    desk, ctx = make_desk(tmp_path, playing="Fortnite")
    assert say(desk, "abre rocket league") == "Ya tienes abierto Fortnite."
    assert ctx["launched"] == []


# ---------- dentro del enrutador ----------


def test_the_router_runs_the_desk_skills_and_leaves_the_rest(tmp_path: Path) -> None:
    desk, _ = make_desk(tmp_path)
    router = SkillRouter(PersonalMemory(ConversationStore(":memory:")))
    assert router.handle("pon un fondo de anime") is None  # sin Desk no hay fondos
    router.attach_desk(desk)
    reply = router.handle("pon un fondo de anime")
    assert reply is not None and reply.text.startswith("Listo: «")
    assert router.handle("baja Discord") is not None
    assert router.handle("recuerda que me gusta el café") is not None  # las demás habilidades siguen
    assert router.handle("¿cuál es la capital de Francia?") is None
