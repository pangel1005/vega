"""Copia las fuentes de la interfaz web a la app Android (Android necesita TTF, no WOFF2).

    uv run python scripts/android_fonts.py

Las toma de ui/node_modules/@fontsource (`npm install` en ui/). Ambas tienen licencia
libre (SIL Open Font License).
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
    # Estilo «Vega · noche»: puntos para números y titulares, Space Grotesk para el texto.
    "doto_black": FONTSOURCE / "doto/files/doto-latin-900-normal.woff2",
    "space_grotesk_regular": FONTSOURCE / "space-grotesk/files/space-grotesk-latin-400-normal.woff2",
    "space_grotesk_medium": FONTSOURCE / "space-grotesk/files/space-grotesk-latin-500-normal.woff2",
    "space_grotesk_bold": FONTSOURCE / "space-grotesk/files/space-grotesk-latin-700-normal.woff2",
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
