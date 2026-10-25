from pathlib import Path

import pytest

from server.core.car import BUILTINS, CarConfig, CarError, load_car

REAL = Path(__file__).resolve().parents[2] / "config" / "car.toml"
ITEM = '[[item]]\nid = "{id}"\nlabel = "{label}"\nkind = "{kind}"\narg = "{arg}"\n'


def item(id: str = "a", label: str = "A", kind: str = "builtin", arg: str = "sleep") -> str:
    return ITEM.format(id=id, label=label, kind=kind, arg=arg)


def write(tmp_path: Path, text: str) -> Path:
    p = tmp_path / "car.toml"
    p.write_text(text, encoding="utf-8")
    return p


def test_the_shipped_car_screen_is_valid() -> None:
    title, items = load_car(REAL)
    assert title == "Vega" and 1 <= len(items) <= 6
    assert all(i.kind == "builtin" and i.arg in BUILTINS for i in items)


def test_actions_must_exist(tmp_path: Path) -> None:
    p = write(tmp_path, item("vol", "Volumen", "action", "volumen-mas"))
    assert load_car(p)[1][0].arg == "volumen-mas"  # sin lista de botones conocida, no se comprueba
    assert load_car(p, {"volumen-mas"})[1][0].label == "Volumen"
    with pytest.raises(CarError, match="no está en actions.toml"):
        load_car(p, {"otro"})


@pytest.mark.parametrize(
    ("text", "message"),
    [
        (item(arg="hackear"), "no existe"),
        (item(id="A B"), "id"),
        (item(label="Una etiqueta demasiado larga"), "label"),
        (item(kind="shell"), "kind"),
        (item(label="A") + item(label="B", arg="next"), "dos casillas"),
        ("[[item]\n", "TOML"),
        ("".join(item(id=f"i{n}", label=f"I{n}") for n in range(7)), "Solo caben 6"),
    ],
)
def test_invalid_screens_are_refused(tmp_path: Path, text: str, message: str) -> None:
    with pytest.raises(CarError, match=message):
        load_car(write(tmp_path, text))


def test_config_keeps_the_last_good_screen_when_a_reload_fails(tmp_path: Path) -> None:
    p = write(tmp_path, 'title = "Mi coche"\n' + item("z", "Dormir"))
    car = CarConfig(p)
    assert car.event().title == "Mi coche" and [i.id for i in car.event().items] == ["z"]
    p.write_text("[[item]\n", encoding="utf-8")
    with pytest.raises(CarError):
        car.reload()
    assert [i.id for i in car.event().items] == ["z"]  # lo roto no tira lo que ya funcionaba
    assert CarConfig(tmp_path / "no-existe.toml").event().items == []  # sin archivo: el teléfono usa el suyo
