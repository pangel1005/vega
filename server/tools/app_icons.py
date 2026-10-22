"""Icono real de cada app del panel (Spotify, Discord…), sacado de su .exe.

Solo para los botones `kind = "launch"`. El icono se extrae una vez del ejecutable
(recurso ICO de Windows), se convierte a PNG de 96 × 96 y se guarda en data/icons/;
el teléfono lo descarga de /app/icons/<id>.png. Si no se puede (la app no está
instalada, el .exe no trae icono), el botón sigue con su dibujo de siempre.
"""

from __future__ import annotations

import io
import logging
import os
from pathlib import Path

from server.tools.actions import ActionDef

log = logging.getLogger(__name__)

SIZE = 96


def extract_png(exe: Path, size: int = SIZE) -> bytes | None:
    """PNG cuadrado con el icono más grande del ejecutable, o None si no tiene."""
    try:
        from icoextract import IconExtractor  # type: ignore[import-untyped]
        from PIL import Image

        ico = IconExtractor(str(exe)).get_icon()  # el primer grupo de iconos: el de la app
        with Image.open(ico) as img:
            sizes = img.info.get("sizes") or {img.size}
            img.size = max(sizes)  # type: ignore[misc]  # el ICO trae varios tamaños: el mayor es el más nítido
            rgba = img.convert("RGBA")
        if rgba.size != (size, size):
            rgba = rgba.resize((size, size), Image.Resampling.LANCZOS)
        out = io.BytesIO()
        rgba.save(out, "PNG")
        return out.getvalue()
    except Exception as exc:  # sin icono, .exe raro, o no es Windows
        log.debug("Sin icono para %s: %s", exe, exc)
        return None


def icon_source(action: ActionDef) -> Path:
    """El .exe del que sacar el icono. Las apps con instalador Squirrel (Discord) se abren con
    `Update.exe --processStart Discord.exe`, y Update.exe no trae el icono de la app: se usa el
    Discord.exe de la versión más nueva (carpetas app-1.0.9264…)."""
    exe = Path(os.path.expandvars(action.arg))
    if "--processStart" in action.args:
        i = action.args.index("--processStart")
        if i + 1 < len(action.args):
            found = sorted(exe.parent.glob(f"app-*/{action.args[i + 1]}"), key=lambda p: p.stat().st_mtime)
            if found:
                return found[-1]
    return exe


class AppIcons:
    """Iconos de las apps del panel, extraídos al pedirlos y guardados en disco."""

    def __init__(self, actions: list[ActionDef], cache_dir: Path) -> None:
        self._exes = {a.id: icon_source(a) for a in actions if a.kind == "launch"}
        self._dir = cache_dir

    def ids(self) -> set[str]:
        """Botones que tienen icono (los que se pueden extraer ahora mismo)."""
        return {i for i in self._exes if self.png(i) is not None}

    def png(self, action_id: str) -> bytes | None:
        exe = self._exes.get(action_id)
        if exe is None:
            return None
        cached = self._dir / f"{action_id}.png"
        try:
            # Se rehace si el programa se actualizó después de guardar el icono.
            if cached.exists() and exe.exists() and cached.stat().st_mtime >= exe.stat().st_mtime:
                return cached.read_bytes()
        except OSError:
            pass
        if not exe.exists():
            return None
        data = extract_png(exe)
        if data is not None:
            self._dir.mkdir(parents=True, exist_ok=True)
            cached.write_bytes(data)
        return data
