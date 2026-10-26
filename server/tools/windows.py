"""Ejecutor real de acciones en Windows (energía, teclas y abrir programas).

No hay terminal libre: cada tipo de acción tiene su implementación fija y solo
recibe parámetros que ya validó `ActionDef`.
"""

from __future__ import annotations

import ctypes
import logging
import os
import subprocess
import sys
from pathlib import Path

from server.core.maintenance import Maintenance
from server.tools.actions import ActionDef, ActionFailed
from server.tools.keys import EXTENDED, VK

log = logging.getLogger(__name__)

SHUTDOWN_DELAY_S = 15

_KEYEVENTF_EXTENDEDKEY = 0x1
_KEYEVENTF_KEYUP = 0x2
_NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW
_DETACHED = 0x00000008 | 0x00000200  # DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP


def parse_keys(combo: str) -> list[int]:
    try:
        return [VK[k] for k in combo.split("+")]
    except KeyError as exc:
        raise ActionFailed(f"Tecla desconocida: {exc.args[0]}") from exc


class WindowsExecutor:
    def __init__(self, maintenance: Maintenance | None = None) -> None:
        self._maintenance = maintenance

    def run(self, action: ActionDef) -> str:
        if sys.platform != "win32":
            raise ActionFailed("Esta acción solo funciona en Windows.")
        if action.kind == "power":
            return self._power(action.arg)
        if action.kind == "keys":
            return self._keys(action)
        if action.kind == "maintenance":
            return self._clean(action.arg)
        return self._launch(action)

    # --- Mantenimiento ---

    def _clean(self, what: str) -> str:
        """Los botones del teléfono y la voz solo hacen lo seguro: nada de lo que no se regenera."""
        if self._maintenance is None:
            raise ActionFailed("El mantenimiento no está disponible.")
        if what == "free-ram":
            return self._maintenance.free_ram().message()
        safe = [c.id for c in self._maintenance.disk_scan() if c.default]  # solo lo marcado de serie
        return self._maintenance.disk_clean(safe).message()

    # --- Energía ---

    def _power(self, what: str) -> str:
        if what == "shutdown":
            self._run(
                ["shutdown", "/s", "/t", str(SHUTDOWN_DELAY_S), "/c", "Apagado pedido desde el asistente."]
            )
            return f"El PC se apagará en {SHUTDOWN_DELAY_S} s. Pulsa «Cancelar apagado» para evitarlo."
        if what == "restart":
            self._run(
                ["shutdown", "/r", "/t", str(SHUTDOWN_DELAY_S), "/c", "Reinicio pedido desde el asistente."]
            )
            return f"El PC se reiniciará en {SHUTDOWN_DELAY_S} s. Pulsa «Cancelar apagado» para evitarlo."
        if what == "cancel":
            if subprocess.run(["shutdown", "/a"], capture_output=True, creationflags=_NO_WINDOW).returncode:
                raise ActionFailed("No había ningún apagado pendiente.")
            return "Apagado cancelado."
        if what == "lock":
            if not ctypes.windll.user32.LockWorkStation():
                raise ActionFailed("Windows no dejó bloquear la sesión.")
            return "PC bloqueado."
        if what == "sleep":
            # Se espera 2 s para que la respuesta llegue al teléfono antes de dormir.
            # SetSuspendState('Suspend') suspende de verdad aunque la hibernación esté activa.
            script = (
                "Start-Sleep 2; Add-Type -AssemblyName System.Windows.Forms; "
                "[System.Windows.Forms.Application]::SetSuspendState('Suspend', $false, $false)"
            )
            subprocess.Popen(["powershell", "-NoProfile", "-Command", script], creationflags=_NO_WINDOW)
            return "Suspendiendo el PC…"
        raise ActionFailed(f"Acción de energía desconocida: {what}")

    @staticmethod
    def _run(cmd: list[str]) -> None:
        result = subprocess.run(cmd, capture_output=True, text=True, creationflags=_NO_WINDOW)
        if result.returncode:
            detail = (result.stderr or result.stdout).strip()
            raise ActionFailed(f"Windows no aceptó la orden ({detail or result.returncode}).")

    # --- Teclas ---

    @staticmethod
    def _keys(action: ActionDef) -> str:
        codes = parse_keys(action.arg)
        user32 = ctypes.windll.user32
        for vk in codes:
            user32.keybd_event(vk, 0, _KEYEVENTF_EXTENDEDKEY if vk in EXTENDED else 0, 0)
        for vk in reversed(codes):
            flags = _KEYEVENTF_KEYUP | (_KEYEVENTF_EXTENDEDKEY if vk in EXTENDED else 0)
            user32.keybd_event(vk, 0, flags, 0)
        return f"{action.label}: hecho."

    # --- Programas ---

    @staticmethod
    def _launch(action: ActionDef) -> str:
        path = Path(os.path.expandvars(action.arg))
        if not path.is_file():
            raise ActionFailed(f"No encuentro {action.label} en {path}.")
        try:
            subprocess.Popen(
                [str(path), *action.args],
                cwd=path.parent,
                creationflags=_DETACHED,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                close_fds=True,
            )
        except OSError as exc:
            raise ActionFailed(f"No pude abrir {action.label}: {exc.strerror}") from exc
        return f"Abriendo {action.label}."
