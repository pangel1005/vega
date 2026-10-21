"""Recorta la fuente japonesa a los caracteres que usa la interfaz.

La interfaz solo muestra unas pocas palabras en japonés (待機, 審議中, 非常事態…).
La fuente completa pesa más de 1 MB; recortada queda en unos pocos KB, lo que
ayuda a que la página cargue rápido en equipos modestos.

Hay que volver a ejecutarlo si se añade texto japonés nuevo a `ui/src`:

    uv run python scripts/subset_jp_font.py
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

from fontTools import subset

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "ui" / "node_modules" / "@fontsource" / "noto-sans-jp" / "files"
OUTPUT = ROOT / "ui" / "src" / "styles" / "fonts" / "noto-sans-jp-subset-900.woff2"
# Kana, kanji y signos de puntuación japoneses.
CJK = re.compile(r"[　-ヿ一-鿿＀-￯]")


def used_characters() -> str:
    chars: set[str] = set()
    for path in (ROOT / "ui" / "src").rglob("*.ts*"):
        chars.update(CJK.findall(path.read_text(encoding="utf-8")))
    return "".join(sorted(chars))


def main() -> int:
    source = SOURCE / "noto-sans-jp-japanese-900-normal.woff2"
    if not source.exists():
        print(f"No encuentro {source}. Ejecuta `npm install` en ui/.")
        return 1
    text = used_characters()
    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = ["*"]
    font = subset.load_font(str(source), options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(text=text)
    subsetter.subset(font)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    subset.save_font(font, str(OUTPUT), options)
    print(f"{len(text)} caracteres: {text}")
    print(f"{OUTPUT.relative_to(ROOT)}: {OUTPUT.stat().st_size / 1024:.1f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
