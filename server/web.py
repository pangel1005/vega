"""Entrega la interfaz compilada (`ui/dist`) con el token metido en la página."""

from __future__ import annotations

import html
import json
import logging
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse, HTMLResponse, Response
from fastapi.staticfiles import StaticFiles

from server.core.config import ROOT

log = logging.getLogger(__name__)

UI_DIST = ROOT / "ui" / "dist"
# Versión optimizada (release) de la app: la que se instala y actualiza en el teléfono.
APK_DIR = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release"
TOKEN_PLACEHOLDER = "__ASSISTANT_TOKEN__"
# Archivos sueltos de ui/public que se sirven en la raíz.
ROOT_FILES = ("manifest.webmanifest", "icon.svg")

MISSING_UI = """<!doctype html><meta charset="utf-8"><title>Interfaz sin compilar</title>
<body style="background:#060606;color:#f3ede4;font-family:sans-serif;padding:2em">
<h1 style="color:#ff7a18">La interfaz todavía no está compilada</h1>
<p>Ejecuta <code>npm run build</code> dentro de la carpeta <code>ui</code> y recarga.</p>"""


def register_ui(app: FastAPI, token: str, dist: Path = UI_DIST) -> None:
    index = dist / "index.html"

    @app.get("/", response_class=HTMLResponse, include_in_schema=False)
    async def home() -> HTMLResponse:
        if not index.exists():
            return HTMLResponse(MISSING_UI, status_code=503)
        page = index.read_text(encoding="utf-8").replace(TOKEN_PLACEHOLDER, html.escape(token, quote=True))
        # Sin caché: la página lleva el token y siempre debe salir del servidor.
        return HTMLResponse(page, headers={"Cache-Control": "no-store"})

    assets = dist / "assets"
    if assets.is_dir():
        app.mount("/assets", StaticFiles(directory=assets), name="assets")
    else:
        log.warning("No encuentro %s: ejecuta `npm run build` en ui/", assets)

    for name in ROOT_FILES:
        _serve_file(app, dist / name, f"/{name}")

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


def _serve_file(app: FastAPI, path: Path, route: str) -> None:
    @app.get(route, include_in_schema=False)
    async def file() -> Response:
        return FileResponse(path) if path.exists() else Response(status_code=404)
