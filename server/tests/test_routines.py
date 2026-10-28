"""Rutinas: varios botones con una sola orden, sin salirse del registro cerrado de acciones."""

from pathlib import Path

import pytest

from server.tests.test_actions import FakeExecutor
from server.tools.actions import (
    ActionConfigError,
    ActionDef,
    ActionFailed,
    ActionRunner,
    check_routines,
    load_actions,
)


def act(id: str, kind: str = "keys", arg: str = "x", **extra: object) -> ActionDef:
    return ActionDef(id=id, label=id.capitalize(), kind=kind, arg=arg, **extra)  # type: ignore[arg-type]


def routine(id: str, steps: list[str], **extra: object) -> ActionDef:
    return ActionDef(id=id, label=id.capitalize(), kind="routine", steps=steps, **extra)  # type: ignore[arg-type]


class FailOn(FakeExecutor):
    """Falla solo en un botón concreto."""

    def __init__(self, bad: str) -> None:
        super().__init__()
        self.bad = bad

    def run(self, action: ActionDef) -> str:
        if action.id == self.bad:
            raise ActionFailed("no se pudo")
        return super().run(action)


class Clock:
    def __init__(self) -> None:
        self.t = 0.0
        self.slept: list[float] = []

    def now(self) -> float:
        return self.t

    def sleep(self, s: float) -> None:
        self.slept.append(s)
        self.t += s


def runner(
    actions: list[ActionDef], executor: FakeExecutor | None = None
) -> tuple[ActionRunner, FakeExecutor, Clock]:
    executor = executor or FakeExecutor()
    clock = Clock()
    return ActionRunner(actions, executor, clock.now, clock.sleep), executor, clock


# ---------- Validación ----------


def test_una_rutina_necesita_pasos_y_no_lleva_arg() -> None:
    with pytest.raises(ValueError, match="al menos un paso"):
        ActionDef(id="r", label="R", kind="routine")
    with pytest.raises(ValueError, match="no lleva 'arg'"):
        ActionDef(id="r", label="R", kind="routine", steps=["a"], arg="x")
    with pytest.raises(ValueError, match="solo se usa"):
        ActionDef(id="r", label="R", kind="keys", arg="x", steps=["a"])
    with pytest.raises(ValueError, match="falta 'arg'"):
        ActionDef(id="r", label="R", kind="keys")


def test_las_esperas_y_el_largo_tienen_limite() -> None:
    routine("ok", ["a", "wait:10", "b"])
    for bad in ("wait:0", "wait:11", "wait:99"):
        with pytest.raises(ValueError, match="esperas"):
            routine("r", ["a", bad])
    with pytest.raises(ValueError):
        routine("r", ["a"] * 9)  # más de 8 pasos
    with pytest.raises(ValueError, match="a sí misma"):
        routine("r", ["r"])


def test_los_pasos_tienen_que_existir_y_no_se_anidan() -> None:
    a, b = act("a"), act("b")
    check_routines([a, b, routine("r", ["a", "wait:2", "b"])])
    with pytest.raises(ValueError, match="no existe"):
        check_routines([a, routine("r", ["a", "fantasma"])])
    with pytest.raises(ValueError, match="no puede incluir la rutina"):
        check_routines([a, routine("uno", ["a"]), routine("dos", ["uno"])])


def test_la_confirmacion_se_hereda() -> None:
    apagar = act("apagar", "power", "shutdown", confirm=True)
    with pytest.raises(ValueError, match="también debe pedirla"):
        check_routines([apagar, routine("noche", ["apagar"])])
    check_routines([apagar, routine("noche", ["apagar"], confirm=True)])


def test_el_archivo_con_rutinas_se_valida_entero(tmp_path: Path) -> None:
    f = tmp_path / "a.toml"
    f.write_text(
        '[[action]]\nid = "a"\nlabel = "A"\nkind = "keys"\narg = "volume_up"\n\n'
        '[[action]]\nid = "r"\nlabel = "R"\nkind = "routine"\nsteps = ["a", "wait:1", "a"]\nsay = ["modo r"]\n',  # noqa: E501
        encoding="utf-8",
    )
    assert [a.kind for a in load_actions(f)] == ["keys", "routine"]
    f.write_text(f.read_text(encoding="utf-8").replace('"a", "wait:1", "a"', '"a", "nada"'), encoding="utf-8")
    with pytest.raises(ActionConfigError, match="no existe"):
        load_actions(f)


# ---------- Ejecución ----------


def test_los_pasos_se_ejecutan_en_orden_con_sus_esperas() -> None:
    r, ex, clock = runner([act("a"), act("b"), routine("noche", ["a", "wait:3", "b"])])
    out = r.request("noche", "voz")
    assert out.kind == "done" and "2 pasos" in out.message
    assert ex.ran == ["a", "b"]
    assert clock.slept == [3]


def test_si_un_paso_falla_se_para_y_se_dice_cual() -> None:
    r, ex, _ = runner([act("a"), act("b"), act("c"), routine("rut", ["a", "b", "c"])], FailOn("b"))
    out = r.request("rut")
    assert out.kind == "error" and "«B»" in out.message
    assert ex.ran == ["a"]  # «c» ni se intenta


def test_una_rutina_con_confirmacion_pide_una_sola_y_cubre_todos_los_pasos() -> None:
    r, ex, _ = runner(
        [
            act("a"),
            act("apagar", "power", "shutdown", confirm=True),
            routine("noche", ["a", "apagar"], confirm=True),
        ]
    )
    pending = r.request("noche")
    assert pending.kind == "confirm" and ex.ran == []
    assert pending.nonce
    done = r.confirm(pending.nonce)
    assert done.kind == "done" and ex.ran == ["a", "apagar"]  # sin pedir otra vez la del paso de apagar


def test_una_rutina_no_puede_ocupar_el_servidor_mas_de_un_minuto() -> None:
    steps = ["wait:10"] * 7  # 70 s: más que el minuto permitido
    r, _, clock = runner([act("a"), routine("larga", ["a", *steps])])
    out = r.request("larga")
    assert out.kind == "error" and "demasiado" in out.message
    assert sum(clock.slept) <= 60  # ni siquiera espera lo que no va a poder usar


def test_si_el_archivo_cambia_a_mitad_se_para_sin_ejecutar_otra_cosa() -> None:
    a = act("a")
    r, ex, _ = runner([a, routine("rut", ["a", "b"])])
    out = r.request("rut")  # «b» ya no existe
    assert out.kind == "error" and "ya no existe" in out.message
    assert ex.ran == ["a"]


def test_las_rutinas_se_activan_por_voz_como_cualquier_boton() -> None:
    r, _, _ = runner([act("a"), routine("noche", ["a"], say=["modo noche"])])
    match = r.match("Vega, activa el modo noche por favor")
    assert match is not None and match.id == "noche"


def test_el_ejecutor_real_no_conoce_las_rutinas() -> None:
    """La rutina la ejecuta el registro, nunca el ejecutor: no hay forma de colar un paso suelto."""
    from server.tools.windows import WindowsExecutor

    with pytest.raises(ActionFailed):
        WindowsExecutor().run(routine("r", ["a"]))
