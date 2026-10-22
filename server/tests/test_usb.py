import subprocess
from pathlib import Path

from server.device.usb import (
    LOST_PHONE_SECONDS,
    MIN_RESTART_GAP,
    RESTART_AFTER_FAILURES,
    PhoneStatus,
    UsbWatcher,
    parse_device_list,
)


def test_parse_device_list() -> None:
    assert parse_device_list("ABC123\tdevice\nXYZ\tunauthorized\n") == {
        "ABC123": "device",
        "XYZ": "unauthorized",
    }
    assert parse_device_list("") == {}
    # Salida real de `adb devices`, con cabecera y avisos del demonio.
    real = "* daemon started successfully\nList of devices attached\nFUHM49\tdevice\n\n"
    assert parse_device_list(real) == {"FUHM49": "device"}


class FakeAdb:
    def __init__(self, fail_reverse: bool = False) -> None:
        self.calls: list[list[str]] = []
        self.fail_reverse = fail_reverse

    def __call__(self, cmd: list[str]) -> subprocess.CompletedProcess[str]:
        self.calls.append(cmd[1:])
        code = 1 if self.fail_reverse and "reverse" in cmd else 0
        return subprocess.CompletedProcess(cmd, code, "", "error: device offline" if code else "")


def test_connecting_phone_is_linked_and_app_opened() -> None:
    adb = FakeAdb()
    w = UsbWatcher("adb", 8000, "secreto", run=adb)
    w.on_devices({"ABC": "device"})
    assert adb.calls == [
        ["-s", "ABC", "reverse", "tcp:8000", "tcp:8000"],
        [
            "-s",
            "ABC",
            "shell",
            "am",
            "start",
            "-n",
            "com.asistente.personal/.MainActivity",
            "--es",
            "token",
            "secreto",
        ],
    ]
    assert w.status.linked == {"ABC"}

    # La misma lista otra vez (adb repite avisos): no se vuelve a enlazar.
    w.on_devices({"ABC": "device"})
    assert len(adb.calls) == 2


def test_reconnecting_relinks() -> None:
    adb = FakeAdb()
    w = UsbWatcher("adb", 8000, "t", open_app=False, run=adb)
    w.on_devices({"ABC": "device"})
    w.on_devices({})  # cable fuera
    assert w.status.linked == set()
    w.on_devices({"ABC": "device"})  # cable dentro
    assert [c[2] for c in adb.calls] == ["reverse", "reverse"]


def test_unauthorized_phone_is_not_linked() -> None:
    adb = FakeAdb()
    w = UsbWatcher("adb", 8000, "t", run=adb)
    w.on_devices({"ABC": "unauthorized"})
    assert adb.calls == [] and w.status.linked == set()
    w.on_devices({"ABC": "device"})  # el usuario aceptó en el teléfono
    assert w.status.linked == {"ABC"}


def test_failed_reverse_is_retried_on_next_notice() -> None:
    adb = FakeAdb(fail_reverse=True)
    w = UsbWatcher("adb", 8000, "t", run=adb)
    w.on_devices({"ABC": "device"})
    assert w.status.linked == set() and len(adb.calls) == 1  # no abre la app si no hay enlace
    adb.fail_reverse = False
    w.on_devices({"ABC": "device"})
    assert w.status.linked == {"ABC"}


def test_status_summary() -> None:
    s = PhoneStatus(adb=True, devices={"ABC": "device", "XYZ": "unauthorized"}, linked={"ABC"})
    assert s.summary() == {
        "adb": True,
        "telefonos": [
            {"serie": "ABC", "estado": "device", "enlazado": True},
            {"serie": "XYZ", "estado": "unauthorized", "enlazado": False},
        ],
    }


class FlakyAdb(FakeAdb):
    """adb que falla en `devices` hasta que se reinicia."""

    def __init__(self) -> None:
        super().__init__()
        self.broken = True

    def __call__(self, cmd: list[str]) -> subprocess.CompletedProcess[str]:
        self.calls.append(cmd[1:])
        if cmd[1:] == ["kill-server"]:
            self.broken = False
        if cmd[1:] == ["devices"]:
            if self.broken:
                return subprocess.CompletedProcess(cmd, 1, "", "error: protocol fault")
            return subprocess.CompletedProcess(cmd, 0, "List of devices attached\nABC\tdevice\n", "")
        return subprocess.CompletedProcess(cmd, 0, "", "")


def test_stuck_adb_is_restarted_and_phone_relinked() -> None:
    adb = FlakyAdb()
    w = UsbWatcher("adb", 8000, "t", open_app=False, run=adb)
    for _ in range(RESTART_AFTER_FAILURES):
        assert not w.poll_once()
    # El bucle reinicia adb tras varios fallos; aquí se simula ese reinicio.
    adb(["adb", "kill-server"])
    assert w.poll_once()
    assert w.status.linked == {"ABC"}


def test_restart_when_phone_vanishes_for_a_while() -> None:
    w = UsbWatcher("adb", 8000, "t", open_app=False, run=FakeAdb())
    t = 1000.0
    w.on_devices({"ABC": "device"})
    assert not w.should_restart(True, t)
    w.on_devices({})  # desaparece (p. ej. tras reconectar el cable)
    assert not w.should_restart(True, t + 2)
    assert w.should_restart(True, t + 2 + LOST_PHONE_SECONDS)


def test_no_restart_loop_without_any_phone() -> None:
    w = UsbWatcher("adb", 8000, "t", open_app=False, run=FakeAdb())
    # Nunca hubo teléfono: no se reinicia adb aunque pase el tiempo.
    assert not any(w.should_restart(True, 1000.0 + i * 10) for i in range(10))


def test_restarts_are_spaced() -> None:
    w = UsbWatcher("adb", 8000, "t", open_app=False, run=FakeAdb())
    t = 1000.0
    for i in range(RESTART_AFTER_FAILURES - 1):
        assert not w.should_restart(False, t + i)
    assert w.should_restart(False, t + RESTART_AFTER_FAILURES)
    w._last_restart = t + RESTART_AFTER_FAILURES
    assert not w.should_restart(False, t + RESTART_AFTER_FAILURES + 1)
    assert w.should_restart(False, t + RESTART_AFTER_FAILURES + MIN_RESTART_GAP + 1)


def test_remembers_phone_between_sessions(tmp_path: Path) -> None:
    memory = tmp_path / "telefono.txt"
    w = UsbWatcher("adb", 8000, "t", open_app=False, run=FakeAdb(), memory_file=memory)
    w.on_devices({"ABC": "device"})
    assert memory.read_text(encoding="utf-8") == "ABC"
    # Otra sesión del servidor que arranca sin ver el teléfono: sí reinicia adb.
    w2 = UsbWatcher("adb", 8000, "t", open_app=False, run=FakeAdb(), memory_file=memory)
    assert not w2.should_restart(True, 1000.0)
    assert w2.should_restart(True, 1000.0 + LOST_PHONE_SECONDS)
