"""La cámara del teléfono como cámara de Windows (Discord, el navegador, etc.).

scrcpy (por el cable USB, sin instalar nada en el teléfono) muestra la cámara en una ventana del PC y
OBS Studio captura esa ventana y la publica como «OBS Virtual Camera». El vídeo no sale del PC. La ventana
de scrcpy debe seguir abierta y sin minimizar mientras se usa.

La escena de OBS («Vega»: la escena «Camara» con la ventana de scrcpy) vive en config/obs y se instala
sola en la carpeta de OBS cada vez que se enciende la cámara si falta o está mal. Antes se creó una vez a
mano desde la app de Claude, que la guardó en su carpeta privada: OBS, abierto por Vega o por ti, no la
veía y emitía una escena vacía. Si OBS ya está abierto con otra escena, se reinicia con la buena.
"""

from __future__ import annotations

import argparse
import ctypes
import json
import logging
import os
import shutil
import subprocess
import sys
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any, Literal

log = logging.getLogger(__name__)

Facing = Literal["front", "back"]

OBS_DIR = Path(r"C:\Program Files\obs-studio\bin\64bit")
OBS_TEMPLATE = Path(__file__).resolve().parents[2] / "config" / "obs"  # la escena «Vega» y su perfil
SCENE = "Camara"
# Sin espacios: así llega entero a scrcpy y OBS encuentra la ventana por este nombre.
WINDOW_TITLE = "CamaraRedmi"
_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0
_SW_SHOWNOACTIVATE = (
    4  # la ventana aparece sin quitarle el foco a lo que estés haciendo (un juego, por ejemplo)
)


def tuck_window(title: str) -> bool:
    """Manda la ventana de la cámara al fondo de todas y sin foco. OBS la sigue capturando aunque esté tapada
    (no si está minimizada). Devuelve False si aún no existe.

    No se le cambia el estilo a «ventana de herramientas» para esconderla de la barra de tareas: OBS ignora
    esas ventanas al buscar la que capturar y la cámara puede quedar en negro. Y no se oculta y se vuelve a
    mostrar: scrcpy lo toma como que se cierra y se apaga (esto sí está medido)."""
    if sys.platform != "win32":
        return False
    user32 = ctypes.windll.user32
    hwnd = user32.FindWindowW(None, title)
    if not hwnd:
        return False
    hwnd_bottom, nomove_nosize_noactivate = 1, 0x0001 | 0x0002 | 0x0010
    user32.SetWindowPos(hwnd, hwnd_bottom, 0, 0, 0, 0, nomove_nosize_noactivate)
    return True


def find_scrcpy() -> Path | None:
    found = shutil.which("scrcpy")
    if found:
        return Path(found)
    packages = Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages"
    return next(packages.rglob("scrcpy.exe"), None) if packages.is_dir() else None


def default_obs_home() -> Path:
    return Path(os.environ.get("APPDATA", "")) / "obs-studio"


def _good_scene(path: Path) -> bool:
    """¿Es la colección «Vega» con la escena «Camara» capturando la ventana de scrcpy?"""
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
    except (OSError, ValueError):
        return False
    if not isinstance(data, dict) or data.get("name") != "Vega" or not isinstance(data.get("sources"), list):
        return False
    sources = [s for s in data["sources"] if isinstance(s, dict)]
    names = {s.get("name") for s in sources}
    captures = [
        s for s in sources
        if s.get("id") == "window_capture"
        and WINDOW_TITLE in str((s.get("settings") or {}).get("window", ""))
    ]  # fmt: skip
    return SCENE in names and bool(captures)


def _set_ini(path: Path, section: str, values: dict[str, str]) -> bool:
    """Pone unas claves en una sección de un .ini de OBS sin tocar lo demás. Devuelve True si cambió algo."""
    try:
        old = path.read_text(encoding="utf-8-sig")
    except OSError:
        old = ""
    lines = old.splitlines()
    out: list[str] = []
    pending = dict(values)
    in_section = False
    seen = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("[") and stripped.endswith("]"):
            if in_section:
                out.extend(f"{k}={v}" for k, v in pending.items())
                pending.clear()
            in_section = stripped[1:-1] == section
            seen = seen or in_section
            out.append(line)
            continue
        key = line.split("=", 1)[0].strip() if "=" in line else None
        if in_section and key is not None and key in pending:
            out.append(f"{key}={pending.pop(key)}")
            continue
        out.append(line)
    if pending:
        if not seen:
            if out and out[-1].strip():
                out.append("")
            out.append(f"[{section}]")
        out.extend(f"{k}={v}" for k, v in pending.items())
    new = "\n".join(out) + "\n"
    if new == old or new.strip() == old.strip():
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(new, encoding="utf-8")
    return True


def ensure_obs_config(home: Path, template: Path = OBS_TEMPLATE) -> bool:
    """Deja en la carpeta de OBS la colección «Vega», su perfil y que OBS la abra por defecto.

    Devuelve True si cambió algo."""
    changed = False
    scene = home / "basic" / "scenes" / "Vega.json"
    if not _good_scene(scene) and (template / "Vega.json").is_file():
        scene.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(template / "Vega.json", scene)
        changed = True
    profile = home / "basic" / "profiles" / "Vega" / "basic.ini"
    if not profile.is_file() and (template / "basic.ini").is_file():
        profile.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(template / "basic.ini", profile)
        changed = True
    # Que OBS abierto a mano (sin argumentos) también cargue la escena buena.
    defaults = {
        "Profile": "Vega",
        "ProfileDir": "Vega",
        "SceneCollection": "Vega",
        "SceneCollectionFile": "Vega.json",
    }
    changed = _set_ini(home / "user.ini", "Basic", defaults) or changed
    if changed:
        log.info("Escena de OBS «Vega» instalada en %s", home)
    return changed


def obs_scene(home: Path) -> str | None:
    """La escena que tiene puesta el OBS abierto (la última que anotó en su registro); None si no se sabe."""
    try:
        newest = max((home / "logs").glob("*.txt"), key=lambda f: f.stat().st_mtime)
        text = newest.read_text(encoding="utf-8", errors="replace")
    except (OSError, ValueError):
        return None
    marker = "Switched to scene " + chr(39)
    i = text.rfind(marker)
    if i < 0:
        return None
    return text[i + len(marker) :].split(chr(39), 1)[0]


def _hidden_startup() -> Any:
    """Arranque de la ventana sin quitar el foco (solo en Windows)."""
    if sys.platform != "win32":
        return None
    info = subprocess.STARTUPINFO()
    info.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    info.wShowWindow = _SW_SHOWNOACTIVATE
    return info


class CameraController:
    def __init__(
        self,
        adb: str,
        *,
        scrcpy: Path | None = None,
        obs_dir: Path = OBS_DIR,
        rotate: int = 180,
        size: str = "1280x720",
        facing: Facing = "front",
        popen: Callable[..., Any] = subprocess.Popen,
        run: Callable[..., Any] = subprocess.run,
        sleep: Callable[[float], None] = time.sleep,
        tuck: Callable[[str], bool] = tuck_window,
        sentinel_dir: Path | None = None,
        keep_obs: bool = True,
        obs_home: Path | None = None,
        obs_template: Path = OBS_TEMPLATE,
    ) -> None:
        self._home = obs_home if obs_home is not None else default_obs_home()
        self._template = obs_template
        self._adb = adb
        self._scrcpy = scrcpy if scrcpy is not None else find_scrcpy()
        self._obs_dir = obs_dir
        self._rotate = rotate
        self._size = size
        self.facing: Facing = facing
        self._popen = popen
        self._run = run
        self._sleep = sleep
        self._tuck = tuck
        self._keep_obs = keep_obs
        # OBS anota aquí que está abierto; si lo cierran a la fuerza, al abrir pregunta «¿modo seguro?».
        self._sentinel = sentinel_dir if sentinel_dir is not None else self._home / ".sentinel"

    @property
    def available(self) -> bool:
        """¿Están instalados scrcpy y OBS? Sin ellos no hay cámara."""
        return self._scrcpy is not None and (self._obs_dir / "obs64.exe").exists()

    def _running(self, image: str) -> bool:
        try:
            out = self._run(
                ["tasklist", "/FI", f"IMAGENAME eq {image}", "/NH"],
                capture_output=True,
                text=True,
                timeout=10,
                check=False,
                creationflags=_NO_WINDOW,
            ).stdout
        except (OSError, subprocess.SubprocessError):
            return False
        return image.lower() in str(out).lower()

    def is_on(self) -> bool:
        return self._running("scrcpy.exe")

    def start(self, facing: Facing | None = None) -> None:
        """Enciende la cámara (o la cambia de trasera a frontal). Bloquea unos segundos: llamar en un hilo."""
        if not self.available or self._scrcpy is None:
            raise RuntimeError(
                "Faltan scrcpy u OBS Studio (winget install Genymobile.scrcpy OBSProject.OBSStudio)."
            )
        if facing is not None:
            self.facing = facing
        self._kill("scrcpy.exe")
        env = {**os.environ, "ADB": self._adb}
        args = [
            str(self._scrcpy),
            "--video-source=camera",
            f"--camera-facing={self.facing}",
            f"--camera-size={self._size}",
            "--camera-fps=30",
            "--no-audio",
            f"--window-title={WINDOW_TITLE}",
            # El tamaño real de la ventana es el de la imagen que sale por la cámara virtual: no se encoge.
            f"--window-width={self._size.split('x')[0]}",
            "--window-borderless",
            f"--capture-orientation={self._rotate}",
        ]
        self._popen(args, env=env, creationflags=_NO_WINDOW, startupinfo=_hidden_startup())
        # La escena buena en la carpeta de OBS (si falta o está mal). Si OBS ya está abierto con otra
        # escena (lo abrieron a mano, o arrancó sin encontrarla), se cierra para abrirlo con la buena.
        try:
            ensure_obs_config(self._home, self._template)
        except OSError:
            log.warning("No pude preparar la escena de OBS", exc_info=True)
        current = obs_scene(self._home) if self._running("obs64.exe") else None
        if current not in (None, SCENE):
            log.warning("OBS estaba abierto con la escena «%s»: lo reinicio con «%s»", current, SCENE)
            self._close_obs()
        if not self._running("obs64.exe"):
            self._clear_sentinel()
            self._popen(
                [
                    str(self._obs_dir / "obs64.exe"),
                    "--profile",
                    "Vega",
                    "--collection",
                    "Vega",
                    "--startvirtualcam",
                    "--minimize-to-tray",
                    "--disable-updater",
                    "--disable-shutdown-check",
                ],
                cwd=str(self._obs_dir),
                creationflags=_NO_WINDOW,
            )
        for _ in range(20):  # la ventana tarda un par de segundos en aparecer
            if self._tuck(WINDOW_TITLE):
                break
            self._sleep(0.5)
        log.info("Cámara %s del teléfono encendida", "frontal" if self.facing == "front" else "trasera")

    def _clear_sentinel(self) -> None:
        """Sin OBS abierto, sus marcas de «sigo abierto» son de un cierre forzado: se quitan."""
        if self._sentinel.is_dir():
            for mark in self._sentinel.glob("run_*"):
                mark.unlink(missing_ok=True)

    def stop(self) -> None:
        self._kill("scrcpy.exe")
        if self._keep_obs:
            log.info("Cámara del teléfono apagada (OBS se queda en la bandeja)")
            return
        self._close_obs()
        log.info("Cámara del teléfono apagada")

    def _close_obs(self) -> None:
        """OBS primero se cierra con educación (suelta bien la cámara virtual); si no, a la fuerza."""
        self._run(
            ["taskkill", "/IM", "obs64.exe"], capture_output=True, check=False, creationflags=_NO_WINDOW
        )
        for _ in range(10):
            if not self._running("obs64.exe"):
                return
            self._sleep(0.5)
        self._kill("obs64.exe")

    def _kill(self, image: str) -> None:
        try:
            self._run(
                ["taskkill", "/F", "/IM", image], capture_output=True, check=False, creationflags=_NO_WINDOW
            )
        except (OSError, subprocess.SubprocessError):
            log.debug("No pude cerrar %s", image, exc_info=True)


def main(argv: list[str] | None = None) -> int:
    """`python -m server.device.camera on [--facing front|back]` · `off` · `status`."""
    from server.core.config import load_settings
    from server.device.usb import find_adb

    parser = argparse.ArgumentParser(prog="camara", description="La cámara del Redmi como cámara de Windows.")
    parser.add_argument("action", choices=["on", "off", "status"])
    parser.add_argument("--facing", choices=["front", "back"])
    args = parser.parse_args(argv)
    cam = load_settings().camera
    ctl = CameraController(
        find_adb() or "adb", rotate=cam.rotate, size=cam.size, facing=cam.facing, keep_obs=cam.keep_obs
    )
    if args.action == "on":
        ctl.start(args.facing)
        side = "frontal" if ctl.facing == "front" else "trasera"
        print(f"Cámara {side} encendida: elige «OBS Virtual Camera».")
    elif args.action == "off":
        ctl.stop()
        print("Cámara apagada.")
    else:
        print("encendida" if ctl.is_on() else "apagada")
    return 0


if __name__ == "__main__":
    sys.exit(main())
