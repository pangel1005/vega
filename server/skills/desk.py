"""«Vega manda en tu PC»: órdenes de voz para lo que tienes instalado, sin el modelo de lenguaje.

- Fondos de Wallpaper Engine: «pon un fondo de anime», «uno tranquilo», «otro fondo», «pausa el fondo»,
  «qué fondo tengo».
- Volumen por programa: «baja Discord», «sube Spotify un poco», «Spotify al 30», «silencia el navegador».
- Juegos: «abre Fortnite», «lanza Counter-Strike».

Cada habilidad devuelve el texto que se dice, o None si la frase no es suya (entonces sigue el resto del
enrutador: botones, modelo…).
"""

from __future__ import annotations

import re
from collections.abc import Callable
from typing import Any

from server.core.text import normalize
from server.device.mixer import ALIASES, Mixer, resolve
from server.device.wallpaper import WallpaperEngine

_FILLER_GAME = {
    "el", "la", "los", "las", "un", "una", "a", "al", "de", "del", "juego", "partida", "mi", "por",
    "favor", "ya", "vamos", "quiero", "ahora", "me", "te",
}  # fmt: skip
# Cómo se dicen en voz alta algunos juegos que se escriben de otra forma.
GAME_ALIASES = {
    "cs2": "counter strike 2",
    "cs": "counter strike",
    "gta": "grand theft auto",
    "rl": "rocket league",
    "r6": "rainbow six",
    "lol": "league of legends",
}
_OPEN = re.compile(
    r"\b(abre|abrir|lanza|lanzar|inicia|iniciar|arranca|arrancar|ejecuta|ejecutar|juega|jugar|jugamos)\b"
)
_UP = re.compile(r"\b(sube|subele|subir|aumenta|aumentale)\b")
_DOWN = re.compile(r"\b(baja|bajale|bajar|reduce|reducele|disminuye)\b")
_MUTE = re.compile(r"\b(silencia|silenciar|mutea|mutear|calla|quita el sonido)\b")
_UNMUTE = re.compile(
    r"\b(desmutea|desmutear|desilencia|quita el silencio|activa el sonido|devuelve el sonido)\b"
)
_SET = re.compile(r"\b(pon|ponme|deja|ajusta|pone)\b|\bvolumen\b|\bal\s+(\d|mitad|maximo)")
_PERCENT = re.compile(r"\b(\d{1,3})\s*(?:por\s*ciento|%)?")
_WALLPAPER = re.compile(r"\b(fondo|fondos|wallpaper)\b")
_WALLPAPER_VERB = re.compile(
    r"\b(pon|ponme|pone|poner|cambia|cambiame|cambiar|cambialo|quiero|activa|sorprendeme|otro|otra|siguiente)\b"
)


def _name(word: str) -> str:
    return word[:1].upper() + word[1:]


# Los programas que salen en la «Mesa» del teléfono, en este orden: (clave, nombre, programas de Windows).
PANEL_APPS: tuple[tuple[str, str, tuple[str, ...]], ...] = (
    ("discord", "Discord", ("discord.exe",)),
    ("spotify", "Spotify", ("spotify.exe",)),
    ("navegador", "Navegador", ("opera.exe", "chrome.exe", "msedge.exe", "firefox.exe", "brave.exe")),
    ("whatsapp", "WhatsApp", ("whatsapp.root.exe", "whatsapp.exe")),
    ("obs", "OBS", ("obs64.exe",)),
    ("vlc", "VLC", ("vlc.exe",)),
)
# Programas que abren una sesión de audio pero no son «algo que suena» para el usuario.
PANEL_IGNORED = frozenset({
    "wallpaper32.exe", "wallpaper64.exe", "steam.exe", "steamwebhelper.exe", "audiodg.exe",
    "razerappengine.exe", "nvidia overlay.exe", "searchhost.exe", "msedgewebview2.exe",
})  # fmt: skip
PANEL_MAX_APPS = 6
_KEY = re.compile(r"^[a-z0-9._-]{1,64}$")


class Desk:
    def __init__(
        self,
        *,
        wallpaper: WallpaperEngine | None = None,
        mixer: Mixer | None = None,
        games: Callable[[], list[tuple[str, str]]] | None = None,
        launch: Callable[[str], None] = lambda uri: None,
        running_game: Callable[[], str | None] = lambda: None,
    ) -> None:
        self._wallpaper = wallpaper
        self._mixer = mixer
        self._games = games
        self._launch = launch
        self._running_game = running_game

    def handle(self, text: str, said: str) -> str | None:
        for skill in (self._wallpaper_skill, self._volume_skill, self._game_skill):
            reply = skill(said)
            if reply is not None:
                return reply
        return None

    # --- Fondos de pantalla ---

    def _wallpaper_skill(self, said: str) -> str | None:
        if self._wallpaper is None or not _WALLPAPER.search(said):
            return None
        engine = self._wallpaper
        if not engine.available:
            return "No encuentro Wallpaper Engine en este PC."
        if re.search(r"\b(que|cual) (fondo|wallpaper) (tengo|hay|es|esta|llevo)\b", said):
            now = engine.current
            return f"Tienes puesto «{now.title}»." if now else "No he cambiado el fondo desde que arranqué."
        if re.search(r"\b(que|cuales|cuantos) (tipos de )?fondos (tengo|hay)\b", said):
            return f"Tienes {len(engine.items())} fondos, sobre todo de: {', '.join(engine.tag_summary())}."
        if re.search(r"\b(pausa|pausar|congela|congelar|deten|detener)\b", said):
            return "Fondo en pausa." if engine.control("pause") else "No pude pausar el fondo."
        if re.search(r"\b(reanuda|reanudar|continua|quita la pausa)\b", said):
            return "Fondo en marcha." if engine.control("play") else "No pude reanudar el fondo."
        if not _WALLPAPER_VERB.search(said):
            return None
        after = said.split("fondos", 1)[-1] if "fondos" in said else said.split("fondo", 1)[-1]
        if "wallpaper" in said and "fondo" not in said:
            after = said.split("wallpaper", 1)[-1]
        query = after.strip()
        wants_another = bool(re.search(r"\b(otro|otra|siguiente|cambialo)\b", said))
        if wants_another and engine.is_random_request(query.replace("otro", "")):
            chosen, count = engine.another()
        else:
            chosen, count = engine.pick(query)
        if chosen is None:
            if count == 0 and not engine.is_random_request(query):
                tags = ", ".join(engine.tag_summary())
                return f"No encuentro un fondo de eso. Tienes de: {tags}."
            return "No tengo fondos que poner."
        if not engine.apply(chosen):
            return "No pude cambiar el fondo: ¿está abierto Wallpaper Engine?"
        more = f" Había {count} que encajaban; di «otro fondo» para cambiar." if count > 1 else ""
        return f"Listo: «{chosen.title}».{more}"

    # --- Volumen por programa ---

    def _volume_skill(self, said: str) -> str | None:
        if self._mixer is None:
            return None
        verb = (
            "unmute" if _UNMUTE.search(said)
            else "mute" if _MUTE.search(said)
            else "down" if _DOWN.search(said)
            else "up" if _UP.search(said)
            else "set" if _SET.search(said)
            else None
        )  # fmt: skip
        if verb is None:
            return None
        words = said.split()
        active = self._mixer.active() if any(w not in ALIASES for w in words) else []
        target = next(((w, resolve(w, active)) for w in words if resolve(w, active)), None)
        if target is None:
            return None  # sin nombrar un programa es del botón de volumen general («sube el volumen»)
        word, names = target
        label = _name(word)
        if verb in ("mute", "unmute"):
            if not self._mixer.mute(names, verb == "mute"):
                return f"No oigo sonar a {label} ahora mismo."
            return f"{label} silenciado." if verb == "mute" else f"{label} con sonido otra vez."
        if verb == "set":
            level = self._level(said, word)
            if level is None:
                return None
            if not self._mixer.set_volume(names, level):
                return f"No oigo sonar a {label} ahora mismo."
            return f"{label} al {round(level * 100)} %."
        step = (
            0.08
            if re.search(r"\bun poco\b|\bpoquito\b", said)
            else 0.3
            if re.search(r"\bmucho\b|\bbastante\b", said)
            else 0.15
        )
        new = self._mixer.change(names, step if verb == "up" else -step)
        if new is None:
            return f"No oigo sonar a {label} ahora mismo."
        return f"{label} al {round(new * 100)} %."

    @staticmethod
    def _level(said: str, app_word: str) -> float | None:
        if re.search(r"\bmitad\b", said):
            return 0.5
        if re.search(r"\bmaximo\b|\bal tope\b", said):
            return 1.0
        rest = said.replace(app_word, " ")
        found = _PERCENT.search(rest)
        if not found:
            return None
        return max(0, min(100, int(found.group(1)))) / 100

    # --- Abrir juegos ---

    def _game_skill(self, said: str) -> str | None:
        if self._games is None or not _OPEN.search(said):
            return None
        query = _OPEN.split(said, maxsplit=1)[-1]
        words = [GAME_ALIASES.get(w, w) for w in query.split() if w not in _FILLER_GAME]
        want = [w for phrase in words for w in phrase.split()]
        if not want or any(w in ALIASES or w in {"fondo", "volumen"} for w in want):
            return None  # «abre Spotify» es un botón; «baja Discord»…, del mezclador
        best: tuple[int, str, str] | None = None
        for name, uri in self._games():
            title = normalize(name).split()
            if all(any(t == w or (len(w) >= 3 and t.startswith(w)) for t in title) for w in want):
                extra = len(title) - len(want)  # el más parecido: el que menos palabras de más tiene
                if best is None or extra < best[0]:
                    best = (extra, name, uri)
        if best is None:
            return None
        _extra, name, uri = best
        playing = self._running_game()
        if playing:
            return f"Ya tienes abierto {playing}."
        try:
            self._launch(uri)
        except OSError:
            return f"No pude abrir {name}."
        return f"Abriendo {name}."

    # --- La «Mesa» del teléfono: el estado a la vista y los controles con el dedo ---

    def _panel_apps(self) -> list[tuple[str, str, tuple[str, ...], float, bool]]:
        """(clave, nombre, programas, volumen, silenciado) de lo que tiene audio ahora."""
        if self._mixer is None:
            return []
        sessions = self._mixer.sessions()
        found: list[tuple[str, str, tuple[str, ...], float, bool]] = []
        taken: set[str] = set()
        for key, label, exes in PANEL_APPS:
            mine = [s for s in sessions if s[0] in exes]
            if mine:
                found.append((key, label, exes, max(s[1] for s in mine), all(s[2] for s in mine)))
                taken.update(exes)
        playing = (self._running_game() or "").strip()
        for name, volume, muted in sessions:
            if name in taken or name in PANEL_IGNORED or len(found) >= PANEL_MAX_APPS:
                continue
            stem = re.split(r"[^a-z0-9]", name.removesuffix(".exe"))[0]
            label = playing if playing and normalize(playing).startswith(stem[:5]) else stem.capitalize()
            found.append((name.removesuffix(".exe"), label, (name,), volume, muted))
            taken.add(name)
        return found[:PANEL_MAX_APPS]

    def panel(self) -> dict[str, Any]:
        """Todo lo que enseña la Mesa: volumen por programa y fondo de pantalla."""
        engine = self._wallpaper
        wallpaper: dict[str, Any] = {
            "available": bool(engine and engine.available),
            "current": "",
            "paused": False,
            "suggestions": [],
        }
        if engine is not None and engine.available:
            if not engine.suggestions:
                engine.suggest()
            wallpaper.update(
                current=engine.current.title if engine.current else "",
                paused=engine.paused,
                suggestions=[{"id": w.id, "title": w.title} for w in engine.suggestions],
            )
        apps = [
            {"key": k, "label": label, "volume": round(v, 2), "muted": m}
            for k, label, _e, v, m in self._panel_apps()
        ]
        return {"apps": apps, "wallpaper": wallpaper}

    def _names_of(self, key: str) -> tuple[str, ...]:
        if not _KEY.match(key) or self._mixer is None:
            return ()
        for k, _label, exes, _v, _m in self._panel_apps():
            if k == key:
                return exes
        return ()

    def panel_volume(self, key: str, level: float) -> bool:
        names = self._names_of(key)
        return bool(
            names and self._mixer is not None and self._mixer.set_volume(names, max(0.0, min(1.0, level)))
        )

    def panel_mute(self, key: str, muted: bool) -> bool:
        names = self._names_of(key)
        return bool(names and self._mixer is not None and self._mixer.mute(names, muted))

    def panel_wallpaper(self, wallpaper_id: str) -> str | None:
        """Pone el fondo con ese número y devuelve su título; None si no existe o no contestó."""
        engine = self._wallpaper
        chosen = engine.by_id(wallpaper_id) if engine is not None else None
        return chosen.title if engine is not None and chosen is not None and engine.apply(chosen) else None

    def panel_wallpaper_random(self) -> str | None:
        engine = self._wallpaper
        chosen, _count = engine.pick("") if engine is not None else (None, 0)
        return chosen.title if engine is not None and chosen is not None and engine.apply(chosen) else None

    def panel_wallpaper_more(self) -> None:
        if self._wallpaper is not None:
            self._wallpaper.suggest()

    def panel_wallpaper_pause(self, paused: bool) -> bool:
        return self._wallpaper is not None and self._wallpaper.control("pause" if paused else "play")

    def wallpaper_thumbnail(self, wallpaper_id: str) -> bytes | None:
        return self._wallpaper.thumbnail(wallpaper_id) if self._wallpaper is not None else None
