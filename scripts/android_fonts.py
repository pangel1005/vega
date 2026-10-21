"""Copia las fuentes de la interfaz web a la app Android (Android necesita TTF, no WOFF2).

    uv run python scripts/android_fonts.py

Usa los mismos archivos que la web (ui/node_modules/@fontsource y la fuente
japonesa recortada), así las dos interfaces se ven igual.
"""

from __future__ import annotations

import sys
from pathlib import Path

from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[1]
FONTSOURCE = ROOT / "ui" / "node_modules" / "@fontsource"
OUT = ROOT / "android" / "app" / "src" / "main" / "res" / "font"

# nombre en Android (solo minúsculas y _) → origen
FONTS = {
    "barlow_condensed_regular": FONTSOURCE / "barlow-condensed/files/barlow-condensed-latin-400-normal.woff2",
    "barlow_condensed_bold": FONTSOURCE / "barlow-condensed/files/barlow-condensed-latin-700-normal.woff2",
    "share_tech_mono": FONTSOURCE / "share-tech-mono/files/share-tech-mono-latin-400-normal.woff2",
    "shippori_mincho_extrabold": FONTSOURCE / "shippori-mincho-b1/files/shippori-mincho-b1-latin-800-normal.woff2",
    "noto_sans_jp_black": ROOT / "ui/src/styles/fonts/noto-sans-jp-subset-900.woff2",
}


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, src in FONTS.items():
        if not src.exists():
            print(f"Falta {src}. Ejecuta `npm install` en ui/.")
            return 1
        font = TTFont(str(src))
        font.flavor = None  # WOFF2 → TTF
        dest = OUT / f"{name}.ttf"
        font.save(str(dest))
        print(f"{dest.relative_to(ROOT)}: {dest.stat().st_size / 1024:.0f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
