"""Vigilante USB: mantiene el enlace con el teléfono sin tocar nada.

Cada 2 segundos pregunta a adb qué teléfonos hay (`adb devices`). Es más simple y
robusto que escuchar `adb track-devices`, cuyo formato cambió tras una reconexión y
dejaba a adb colgado. Cuando un teléfono queda listo («device»):
1. aplica `adb reverse tcp:8000 tcp:8000` (el teléfono llega al PC por el cable);
2. abre la app del asistente en el teléfono, pasándole el token.

Si adb falla varias veces seguidas, se reinicia (kill-server + start-server). Corre en
un hilo propio dentro del servidor: arrancar el servidor basta para que el cable
funcione solo.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

log = logging.getLogger(__name__)

# La app que se abre en el teléfono al enlazarlo. Para abrir otra basta con VEGA_APP_PACKAGE; la clase de la
# actividad es la misma. (com.asistente.personal.rediseno fue el paquete de pruebas de Vega 2.)
APP_PACKAGE = os.environ.get("VEGA_APP_PACKAGE", "com.asistente.personal")
APP_ACTIVITY = f"{APP_PACKAGE}/com.asistente.personal.MainActivity"
# Versiones de Vega que pueden estar instaladas a la vez. Solo debe estar abierta una: si no, se pelean
# por el micrófono (Android se lo silencia a la de atrás, que reintenta) y se conectan dos veces al servidor.
KNOWN_APPS = frozenset({"com.asistente.personal", "com.asistente.personal.rediseno"})
_NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0


def find_adb() -> str | None:
    """adb del SDK de Android, el de winget o el del PATH (en ese orden)."""
    home = Path.home()
    candidates = [
        Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools" / "adb.exe",
        # El SDK que instala Android Studio
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        home / "Android" / "Sdk" / "platform-tools" / "adb.exe",
        *(Path(os.environ.get("LOCALAPPDATA", "")) / "Microsoft" / "WinGet" / "Packages").glob(
            "Google.PlatformTools*/platform-tools/adb.exe"
        ),
    ]
    for path in candidates:
        if path.is_file():
            return str(path)
    return shutil.which("adb")


POLL_SECONDS = 2.0
RESTART_AFTER_FAILURES = 3
LOST_PHONE_SECONDS = 15.0  # teléfono desaparecido tanto tiempo → reiniciar adb
MIN_RESTART_GAP = 30.0  # como mucho un reinicio de adb cada 30 s


def parse_device_list(payload: str) -> dict[str, str]:
    """Salida de `adb devices` («serie\\testado» por línea) → {serie: estado}."""
    devices: dict[str, str] = {}
    for line in payload.splitlines():
        serial, _, state = line.partition("\t")
        if serial and state and not serial.startswith(("*", "List of")):
            devices[serial.strip()] = state.strip()
    return devices


@dataclass
class PhoneStatus:
    """Lo que el servidor sabe del teléfono (se muestra en /health)."""

    adb: bool = False
    devices: dict[str, str] = field(default_factory=dict)
    linked: set[str] = field(default_factory=set)  # teléfonos con el enlace aplicado

    def summary(self) -> dict[str, object]:
        return {
            "adb": self.adb,
            "telefonos": [
                {"serie": s, "estado": st, "enlazado": s in self.linked}
                for s, st in sorted(self.devices.items())
            ],
        }


Runner = Callable[[list[str]], subprocess.CompletedProcess[str]]


def _run(cmd: list[str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(cmd, capture_output=True, text=True, timeout=15, creationflags=_NO_WINDOW)


class UsbWatcher:
    def __init__(
        self,
        adb: str,
        port: int,
        token: str,
        open_app: bool = True,
        run: Runner = _run,
        memory_file: Path | None = None,
    ) -> None:
        # Si este PC ya tuvo un teléfono enlazado (aunque sea en otra sesión), cuando no
        # lo vea se reinicia adb: tras reconectar el cable, a veces adb no lo vuelve a ver.
        self._memory = memory_file
        self._adb = adb
        self._port = port
        self._token = token
        self._open_app = open_app
        self._run = run
        self.status = PhoneStatus()
        self._stop = threading.Event()
        self._failures = 0
        self._seen_phone = memory_file is not None and memory_file.exists()
        self._empty_since: float | None = None
        self._last_restart = -MIN_RESTART_GAP

    def start(self) -> None:
        threading.Thread(target=self._loop, name="vigilante-usb", daemon=True).start()

    def stop(self) -> None:
        self._stop.set()

    # --- Reacción a los cambios ---

    def on_devices(self, devices: dict[str, str]) -> None:
        """Llamado con la lista completa de teléfonos cada vez que algo cambia."""
        before = self.status.devices
        self.status.devices = devices
        self.status.linked &= {s for s, st in devices.items() if st == "device"}
        for serial, state in devices.items():
            if state == "device" and serial not in self.status.linked:
                self._link(serial)
            elif state != before.get(serial):
                hint = {
                    "unauthorized": "acepta «Permitir depuración USB» en la pantalla del teléfono",
                    "offline": "desconecta y vuelve a conectar el cable",
                }.get(state, "")
                log.warning("Teléfono %s: %s %s", serial, state, f"({hint})" if hint else "")
        for serial in set(before) - set(devices):
            log.info("Teléfono %s desconectado", serial)

    def _link(self, serial: str) -> None:
        port = str(self._port)
        result = self._run([self._adb, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}"])
        if result.returncode != 0:
            log.warning(
                "No pude enlazar el teléfono %s: %s", serial, (result.stderr or result.stdout).strip()
            )
            return
        self.status.linked.add(serial)
        if self._memory is not None and not self._memory.exists():
            self._memory.parent.mkdir(parents=True, exist_ok=True)
            self._memory.write_text(serial, encoding="utf-8")
        log.info("Teléfono %s conectado por USB y enlazado (puerto %s)", serial, port)
        if self._open_app:
            # Cierra las otras versiones de Vega que estuvieran abiertas (no las desinstala ni las desactiva).
            for other in sorted(KNOWN_APPS - {APP_PACKAGE}):
                self._run([self._adb, "-s", serial, "shell", "am", "force-stop", other])
            # Abre la app (o la trae al frente) y le entrega el token por el cable.
            self._run(
                [
                    self._adb,
                    "-s",
                    serial,
                    "shell",
                    "am",
                    "start",
                    "-n",
                    APP_ACTIVITY,
                    "--es",
                    "token",
                    self._token,
                ]
            )

    def battery(self) -> int | None:
        """Batería del teléfono enlazado (0-100), o None si no hay ninguno."""
        serial = next(iter(sorted(self.status.linked)), None)
        if serial is None:
            return None
        try:
            out = self._run([self._adb, "-s", serial, "shell", "dumpsys", "battery"]).stdout
        except (OSError, subprocess.SubprocessError):
            return None
        for line in out.splitlines():
            key, _, value = line.strip().partition(":")
            if key == "level" and value.strip().isdigit():
                return int(value)
        return None

    def verify_links(self) -> None:
        """Comprueba que el reenvío de puerto de cada teléfono enlazado sigue puesto y lo repone si no:
        se pierde al reiniciar adb o al soltarse el cable un instante, y entonces Vega se queda
        «reconectando» aunque el teléfono siga en la lista. Solo repone el reenvío (no reabre la app)."""
        port = str(self._port)
        for serial in sorted(self.status.linked):
            try:
                result = self._run([self._adb, "-s", serial, "reverse", "--list"])
            except (OSError, subprocess.SubprocessError) as exc:
                log.debug("adb reverse --list falló: %s", exc)
                continue
            if result.returncode != 0 or f"tcp:{port}" in result.stdout:
                continue
            fix = self._run([self._adb, "-s", serial, "reverse", f"tcp:{port}", f"tcp:{port}"])
            if fix.returncode == 0:
                log.warning("Teléfono %s: el reenvío del puerto %s se había perdido; lo repuse", serial, port)
            else:
                log.warning(
                    "Teléfono %s: no pude reponer el reenvío (%s)", serial, (fix.stderr or fix.stdout).strip()
                )
                self.status.linked.discard(serial)  # se reintentará entero en la próxima lista

    # --- Bucle con adb ---

    def poll_once(self) -> bool:
        """Una consulta a adb. Devuelve False si adb falló."""
        try:
            result = self._run([self._adb, "devices"])
        except (OSError, subprocess.SubprocessError) as exc:
            log.debug("adb devices falló: %s", exc)
            return False
        if result.returncode != 0:
            return False
        self.on_devices(parse_device_list(result.stdout))
        return True

    def should_restart(self, ok: bool, now: float) -> bool:
        """¿Toca reiniciar adb? Si falla varias veces seguidas, o si el teléfono que había
        desapareció hace rato (tras una reconexión, este Redmi no vuelve a aparecer en adb
        hasta reiniciarlo, aunque adb responda con la lista vacía)."""
        self._failures = 0 if ok else self._failures + 1
        if self.status.devices:
            self._seen_phone = True
            self._empty_since = None
        elif ok and self._seen_phone and self._empty_since is None:  # el teléfono no está
            self._empty_since = now
        if now - self._last_restart < MIN_RESTART_GAP:
            return False
        lost = self._empty_since is not None and now - self._empty_since >= LOST_PHONE_SECONDS
        return self._failures >= RESTART_AFTER_FAILURES or lost

    def _restart_adb(self, now: float) -> None:
        log.warning("adb no ve el teléfono o no responde: lo reinicio")
        self._last_restart = now
        self._failures = 0
        self._empty_since = None
        self.on_devices({})
        for cmd in ("kill-server", "start-server"):
            try:
                self._run([self._adb, cmd])
            except (OSError, subprocess.SubprocessError) as exc:
                log.warning("adb %s falló: %s", cmd, exc)

    def _loop(self) -> None:
        while not self._stop.is_set():
            ok = self.poll_once()
            self.status.adb = ok
            if ok:
                self.verify_links()
            now = time.monotonic()
            if self.should_restart(ok, now):
                self._restart_adb(now)
            self._stop.wait(POLL_SECONDS)
