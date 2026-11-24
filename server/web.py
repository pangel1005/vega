"""Rutas HTTP que usan las apps: la página de inicio, las actualizaciones de la app del teléfono, los modelos
de la IA del teléfono, iconos, carátulas y letras.

(Hasta el 24-11-2026 había aquí una interfaz web de chat, `web/`, sustituida por la app de escritorio.)
"""

from __future__ import annotations

import asyncio
import json
import logging
import re
from collections.abc import Callable
from pathlib import Path

from fastapi import FastAPI, Query, Request
from fastapi.responses import FileResponse, HTMLResponse, Response

from server.core.config import ROOT
from server.device.lyrics import NOT_FOUND, LyricsFinder
from server.tools.app_icons import AppIcons

log = logging.getLogger(__name__)

# Versión optimizada (release) de la app: la que se instala y actualiza en el teléfono.
APK_DIR = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release"
# Modelos de la IA del teléfono (Whisper base y la voz Piper): la app los baja una vez.
PHONE_MODELS = ROOT / "data" / "telefono"

HOME = """<!doctype html><meta charset="utf-8"><title>Vega</title>
<body style="background:#EEE8DB;color:#211D17;font-family:'Segoe UI',sans-serif;padding:2em">
<h1>Vega está en marcha</h1>
<p>Usa la app del teléfono o la de escritorio (<code>scripts\\escritorio.ps1</code>).</p>"""


def register_home(app: FastAPI) -> None:
    """Página sencilla en la raíz (para ver en el navegador que Vega responde) y las actualizaciones."""

    @app.get("/", response_class=HTMLResponse, include_in_schema=False)
    async def home() -> HTMLResponse:
        return HTMLResponse(HOME)

    register_app_updates(app)


def register_app_updates(app: FastAPI, apk_dir: Path = APK_DIR) -> None:
    """La app Android pregunta aquí si hay versión nueva y la descarga (actualización con un toque)."""

    @app.get("/app/version", include_in_schema=False)
    async def version() -> dict[str, object]:
        meta = apk_dir / "output-metadata.json"
        try:
            element = json.loads(meta.read_text(encoding="utf-8"))["elements"][0]
            return {
                "version_code": int(element["versionCode"]),
                "version_name": element.get("versionName", ""),
            }
        except (OSError, ValueError, KeyError, IndexError):
            return {"version_code": 0, "version_name": ""}

    @app.get("/app/latest.apk", include_in_schema=False)
    async def apk() -> Response:
        files = sorted(apk_dir.glob("*.apk"))
        if not files:
            return Response(status_code=404)
        return FileResponse(files[0], media_type="application/vnd.android.package-archive")


def register_phone_models(app: FastAPI, models: Path = PHONE_MODELS) -> None:
    """Modelos de la IA del teléfono: la lista (`/app/models`) y cada archivo. La app baja por
    el cable lo que le falte y después funciona sin el PC."""

    @app.get("/app/models", include_in_schema=False)
    async def manifest() -> Response:
        if not models.is_dir():
            return Response(status_code=404)
        files = [
            {"path": p.relative_to(models).as_posix(), "size": p.stat().st_size}
            for p in sorted(models.rglob("*"))
            if p.is_file()
        ]
        return Response(json.dumps({"files": files}), media_type="application/json")

    @app.get("/app/models/{path:path}", include_in_schema=False)
    async def model_file(path: str) -> Response:
        # Se mira el texto ANTES de construir ninguna ruta: en Windows, `Path(base) / "//equipo/recurso/x"`
        # descarta la base y `resolve()` abriría una conexión de red a ese equipo (se filtraría el hash NTLM).
        if not _MODEL_PATH.fullmatch(path) or ".." in path.split("/"):
            return Response(status_code=404)
        base = models.resolve()
        target = (base / path).resolve()
        # Solo archivos dentro de data/telefono.
        if not target.is_relative_to(base) or not target.is_file():
            return Response(status_code=404)
        return FileResponse(target, media_type="application/octet-stream")


# Letras, cifras, «.», «-», «_», espacio y «!» (hay archivos así en espeak-ng-data); nada de «\», «:» ni «//».
_MODEL_PATH = re.compile(r"[A-Za-z0-9._ !-]+(?:/[A-Za-z0-9._ !-]+)*")


def register_notification_icons(app: FastAPI, icon: Callable[[str], bytes | None]) -> None:
    """Icono de la app que mandó cada notificación del PC (PNG)."""

    @app.get("/app/notifications/icon/{icon_id}.png", include_in_schema=False)
    async def notification_icon(icon_id: str) -> Response:
        data = icon(icon_id)
        if data is None:
            return Response(status_code=404)
        return Response(data, media_type="image/png", headers={"Cache-Control": "max-age=86400"})


def register_media_art(app: FastAPI, art: Callable[[str], bytes | None]) -> None:
    """Carátula de lo que suena en el PC (JPEG), para la pantalla «Sonando» del teléfono."""

    @app.get("/app/media/art/{art_id}.jpg", include_in_schema=False)
    async def media_art(art_id: str) -> Response:
        data = art(art_id)
        if data is None:
            return Response(status_code=404)
        return Response(data, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})


def register_gsi(app: FastAPI, feed: Callable[[dict[str, object]], bool]) -> None:
    """Recibe los avisos de «Game State Integration» de Counter-Strike 2 (solo con su clave)."""

    @app.post("/app/gsi/cs2", include_in_schema=False)
    async def gsi_cs2(request: Request) -> Response:
        if int(request.headers.get("content-length") or 0) > 200_000:
            return Response(status_code=413)
        try:
            body = await request.json()
        except ValueError:
            return Response(status_code=400)
        ok = isinstance(body, dict) and feed(body)
        return Response(status_code=200 if ok else 403)


def register_game_art(app: FastAPI, art: Callable[[str], bytes | None]) -> None:
    """Carátula del juego al que se juega en el PC (JPEG), para la sección «Juego» del teléfono."""

    @app.get("/app/games/art/{art_id}.jpg", include_in_schema=False)
    async def game_art(art_id: str) -> Response:
        data = art(art_id)
        if data is None:
            return Response(status_code=404)
        return Response(data, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})


def register_wallpaper_art(app: FastAPI, art: Callable[[str], bytes | None]) -> None:
    """Miniatura de un fondo de Wallpaper Engine (JPEG), para la «Mesa» del teléfono."""

    @app.get("/app/wallpaper/{wallpaper_id}.jpg", include_in_schema=False)
    async def wallpaper_art(wallpaper_id: str) -> Response:
        if not wallpaper_id.isdigit() or len(wallpaper_id) > 20:
            return Response(status_code=404)
        data = await asyncio.to_thread(art, wallpaper_id)
        if data is None:
            return Response(status_code=404)
        return Response(data, media_type="image/jpeg", headers={"Cache-Control": "max-age=86400"})


def register_lyrics(app: FastAPI, finder: LyricsFinder) -> None:
    """Letra de una canción (sincronizada si se puede) para el modo letra del teléfono."""

    @app.get("/app/media/lyrics", include_in_schema=False)
    async def lyrics(
        artist: str = Query("", max_length=200),
        title: str = Query("", max_length=200),
        album: str = Query("", max_length=200),
        duration: float = 0,
    ) -> dict[str, object]:
        # Sin artista, «lo que suena» suele ser la pestaña de un navegador («Historias • Instagram»): ni se
        # busca ni se manda a un tercero.
        found = await finder.find(artist, title, album, duration) if artist.strip() else NOT_FOUND
        return {
            "synced": found.synced,
            "instrumental": found.instrumental,
            "lines": [{"t": line.time, "text": line.text} for line in found.lines],
        }


def register_app_icons(app: FastAPI, icons: AppIcons) -> None:
    """Icono real de cada app del panel (PNG), para las teclas del teléfono."""

    @app.get("/app/icons/{action_id}.png", include_in_schema=False)
    async def icon(action_id: str) -> Response:
        data = await asyncio.to_thread(icons.png, action_id)
        if data is None:
            return Response(status_code=404)
        return Response(data, media_type="image/png", headers={"Cache-Control": "max-age=86400"})
