"""Los 6 huecos de la fila principal del teléfono (lógica pura, sin ventana)."""

import pytest

from desktop import slots


def rows(*spec: str) -> list[dict[str, object]]:
    """«a*» = botón con `quick` (en el panel); «b» = en «Más»."""
    return [{"id": s.rstrip("*"), "label": s.rstrip("*").upper(), "quick": s.endswith("*")} for s in spec]


def panel(r: list[dict[str, object]]) -> list[str | None]:
    return [None if a is None else str(a["id"]) for a in slots.slots(r)]  # type: ignore[arg-type]


def test_los_huecos_siguen_el_orden_del_archivo() -> None:
    r = rows("a", "b*", "c", "d*", "e*")
    assert panel(r) == ["b", "d", "e", None, None, None]
    assert slots.quick_rows(r) == [1, 3, 4]


def test_poner_un_boton_en_un_hueco_ocupado_manda_el_anterior_a_mas() -> None:
    r = rows("a", "b*", "c", "d*")
    slots.assign(r, 0, 2)  # «c» ocupa el hueco de «b»
    assert panel(r)[:2] == ["c", "d"]
    assert [a["id"] for a in r if not a["quick"]] == ["a", "b"]
    assert len(r) == 4  # nadie se pierde ni se duplica


def test_poner_un_boton_en_un_hueco_vacio_lo_deja_el_ultimo() -> None:
    r = rows("a", "b*", "c", "d*")
    slots.assign(r, 2, 0)  # «a» va al final del panel
    assert panel(r)[:3] == ["b", "d", "a"]
    assert [a["id"] for a in r] == ["b", "c", "d", "a"]  # «c» no se mueve de sitio


def test_poner_en_un_panel_vacio() -> None:
    r = rows("a", "b")
    slots.assign(r, 0, 1)
    assert panel(r)[0] == "b" and r[0]["quick"] is True


def test_un_boton_que_ya_esta_en_el_panel_no_se_duplica() -> None:
    r = rows("a*", "b*")
    slots.assign(r, 1, 0)
    assert panel(r)[:2] == ["a", "b"]


def test_quitar_y_mover() -> None:
    r = rows("a*", "b*", "c*")
    slots.swap(r, 0, 2)
    assert panel(r)[:3] == ["c", "b", "a"]
    slots.swap(r, 0, 5)  # un hueco vacío: no hace nada
    assert panel(r)[:3] == ["c", "b", "a"]
    slots.clear(r, 1)
    assert panel(r)[:3] == ["c", "a", None]
    assert [a["id"] for a in r if not a["quick"]] == ["b"]


def test_mas_de_seis_en_el_panel_se_avisa() -> None:
    r = rows(*[f"x{i}*" for i in range(8)])
    assert slots.overflow(r) == 2
    assert len([a for a in slots.slots(r) if a]) == 6


def test_fuera_de_rango_se_rechaza() -> None:
    r = rows("a", "b")
    with pytest.raises(IndexError):
        slots.assign(r, 6, 0)
    with pytest.raises(IndexError):
        slots.assign(r, 0, 9)
