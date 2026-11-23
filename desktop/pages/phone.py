"""Teléfono: cómo está el Redmi por cable (adb) y cómo dejarlo solo para Vega."""

from __future__ import annotations

import subprocess
import sys
import threading

from PySide6.QtCore import QObject, Signal
from PySide6.QtGui import QFont
from PySide6.QtWidgets import QHBoxLayout, QLabel, QPlainTextEdit, QVBoxLayout, QWidget

from desktop.config_edit import ROOT
from desktop.widgets import Card, pill, style

NO_WINDOW = 0x08000000  # CREATE_NO_WINDOW
APP = "com.asistente.personal"


def adb_path() -> str | None:
    from server.device.usb import find_adb

    return find_adb()


def run(cmd: list[str], timeout: float = 120) -> str:
    out = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
        creationflags=NO_WINDOW,
    )
    return (out.stdout + out.stderr).strip()


def phone_status() -> str:
    """Un resumen legible del teléfono conectado (o por qué no se ve)."""
    adb = adb_path()
    if adb is None:
        return "No encuentro adb. Instala las platform-tools de Android."
    devices = [line for line in run([adb, "devices", "-l"]).splitlines()[1:] if line.strip()]
    if not devices:
        return "No hay ningún teléfono conectado por USB."
    ready = [d for d in devices if "\tdevice" in d or " device " in d]
    if not ready:
        return (
            "Teléfono detectado, pero no autorizado: acepta «Depurar por USB» en la pantalla.\n"
            + "\n".join(devices)
        )
    model = run([adb, "shell", "getprop", "ro.product.model"])
    android = run([adb, "shell", "getprop", "ro.build.version.release"])
    pkg = run([adb, "shell", "dumpsys", "package", APP])
    version = next(
        (line.split("=")[1].split()[0] for line in pkg.splitlines() if "versionCode=" in line), "no instalada"
    )
    mem = {
        k: int(v.split()[0])
        for k, v in (
            line.split(":")
            for line in run([adb, "shell", "cat", "/proc/meminfo"]).splitlines()
            if ":" in line
        )
    }
    free = mem.get("MemAvailable", 0) / 1024
    swap = (mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)) / 1024
    off = run([adb, "shell", "pm", "list", "packages", "-d"]).count("package:")
    return (
        f"{model} · Android {android}\n"
        f"Vega instalada: versión {version}\n"
        f"RAM libre: {free:.0f} MB · swap en uso: {swap:.0f} MB\n"
        f"Apps apagadas: {off}"
    )


class Worker(QObject):
    """Ejecuta algo lento fuera de la ventana y devuelve el texto cuando termina."""

    done = Signal(str)

    def start(self, job: object) -> None:
        def target() -> None:
            try:
                text = job()  # type: ignore[operator]
            except Exception as exc:
                text = f"Error: {exc}"
            self.done.emit(text)

        threading.Thread(target=target, daemon=True).start()


class PhonePage(QWidget):
    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.worker = Worker(self)
        self.worker.done.connect(self._finished)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 18, 18, 18)
        lay.setSpacing(14)
        card = Card("Teléfono por cable")
        self.status = QLabel("Sin comprobar.")
        self.status.setObjectName("muted")
        self.status.setWordWrap(True)
        card.body.addWidget(self.status)
        row = QHBoxLayout()
        self.refresh_btn = pill("Comprobar")
        self.open_btn = pill("Abrir Vega")
        self.restart_btn = pill("Reiniciar la app")
        for b in (self.refresh_btn, self.open_btn, self.restart_btn):
            row.addWidget(b)
        row.addStretch(1)
        card.body.addLayout(row)
        lay.addWidget(card)

        card2 = Card("Dedicar el teléfono a Vega")
        hint = QLabel(
            "Quita lo que sobra (juegos, redes sociales, la nube de Xiaomi y de Google, Play Services…) "
            "y le da prioridad a "
            "Vega. No se borra nada del teléfono: «Deshacer» lo repone todo."
        )
        hint.setObjectName("muted")
        hint.setWordWrap(True)
        card2.body.addWidget(hint)
        row2 = QHBoxLayout()
        self.dedicate_btn = pill("Dedicar a Vega", accent=True)
        self.undo_btn = pill("Deshacer")
        row2.addWidget(self.dedicate_btn)
        row2.addWidget(self.undo_btn)
        row2.addStretch(1)
        card2.body.addLayout(row2)
        lay.addWidget(card2)

        self.console = QPlainTextEdit()
        self.console.setReadOnly(True)
        mono = QFont("Cascadia Mono", 9)
        mono.setStyleHint(QFont.StyleHint.Monospace)
        self.console.setFont(mono)
        style(self.console, tone="soft")
        lay.addWidget(self.console, 1)

        self.refresh_btn.clicked.connect(self.check)
        self.open_btn.clicked.connect(
            lambda: self._run(
                "Abriendo Vega…", self._adb_job("shell", "am", "start", "-n", f"{APP}/.MainActivity")
            )
        )
        self.restart_btn.clicked.connect(self._restart)
        self.dedicate_btn.clicked.connect(
            lambda: self._run("Dedicando el teléfono a Vega…", self._script(""))
        )
        self.undo_btn.clicked.connect(lambda: self._run("Deshaciendo…", self._script("--undo")))

    def check(self) -> None:
        self.status.setText("Comprobando…")
        self.worker.start(phone_status)
        self._mode = "status"

    _mode = "console"

    def showEvent(self, event: object) -> None:
        super().showEvent(event)  # type: ignore[arg-type]
        self.check()

    def _adb_job(self, *args: str) -> object:
        def job() -> str:
            adb = adb_path()
            return run([adb, *args]) if adb else "No encuentro adb."

        return job

    def _script(self, flag: str) -> object:
        def job() -> str:
            cmd = [sys.executable, str(ROOT / "tools" / "phone_dedicate.py")] + ([flag] if flag else [])
            return run(cmd, timeout=600)

        return job

    def _restart(self) -> None:
        def job() -> str:
            adb = adb_path()
            if not adb:
                return "No encuentro adb."
            run([adb, "shell", "am", "force-stop", APP])
            return run([adb, "shell", "am", "start", "-n", f"{APP}/.MainActivity"])

        self._run("Reiniciando la app del teléfono…", job)

    def _run(self, title: str, job: object) -> None:
        self._mode = "console"
        self.console.setPlainText(title)
        for b in (self.dedicate_btn, self.undo_btn, self.open_btn, self.restart_btn):
            b.setEnabled(False)
        self.worker.start(job)

    def _finished(self, text: str) -> None:
        if self._mode == "status":
            self.status.setText(text)
            return
        self.console.setPlainText(text or "Hecho.")
        for b in (self.dedicate_btn, self.undo_btn, self.open_btn, self.restart_btn):
            b.setEnabled(True)
        self.check()
