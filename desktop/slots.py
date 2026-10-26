"""Los 6 huecos de la fila principal del teléfono, sobre la lista de botones de `actions.toml`.

El teléfono (y el panel del PC) enseñan en esa fila los botones con `quick = true`, en el orden en
que aparecen en el archivo; el resto va a «Más». Estas funciones cambian qué botón ocupa cada hueco
sin mover los demás de sitio: dos botones solo se intercambian.
"""

from __future__ import annotations

from typing import Any

MAX_SLOTS = 6


def quick_rows(rows: list[dict[str, Any]]) -> list[int]:
    """Índices (en `rows`) de los botones con `quick`, en el orden del panel."""
    return [i for i, a in enumerate(rows) if a.get("quick")]


def slots(rows: list[dict[str, Any]]) -> list[dict[str, Any] | None]:
    """Lo que hay en cada uno de los 6 huecos (None = vacío)."""
    chosen = [rows[i] for i in quick_rows(rows)][:MAX_SLOTS]
    return chosen + [None] * (MAX_SLOTS - len(chosen))


def overflow(rows: list[dict[str, Any]]) -> int:
    """Cuántos botones marcados como «panel» sobran (no caben en los 6 huecos)."""
    return max(len(quick_rows(rows)) - MAX_SLOTS, 0)


def assign(rows: list[dict[str, Any]], slot: int, index: int) -> None:
    """Pone el botón `rows[index]` en el hueco `slot`; el que estaba pasa a «Más»."""
    if not 0 <= slot < MAX_SLOTS or not 0 <= index < len(rows):
        raise IndexError("hueco o botón fuera de rango")
    quick = quick_rows(rows)
    if index in quick:
        return  # ya está en el panel: para moverlo, `swap`
    if slot < len(quick):
        old = quick[slot]
        rows[old], rows[index] = rows[index], rows[old]  # el nuevo toma el sitio del que sale
        rows[old]["quick"] = True
        rows[index]["quick"] = False
        return
    # Hueco vacío: va al final del panel (justo detrás del último botón del panel).
    row = rows.pop(index)
    row["quick"] = True
    last = max((i for i, a in enumerate(rows) if a.get("quick")), default=-1)
    rows.insert(last + 1, row)


def clear(rows: list[dict[str, Any]], slot: int) -> None:
    """Quita el botón de ese hueco del panel (pasa a «Más»)."""
    quick = quick_rows(rows)
    if 0 <= slot < len(quick):
        rows[quick[slot]]["quick"] = False


def swap(rows: list[dict[str, Any]], a: int, b: int) -> None:
    """Intercambia dos huecos ocupados."""
    quick = quick_rows(rows)
    if not (0 <= a < len(quick) and 0 <= b < len(quick)) or a == b:
        return
    ia, ib = quick[a], quick[b]
    rows[ia], rows[ib] = rows[ib], rows[ia]
