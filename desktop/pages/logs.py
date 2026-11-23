"""Registros: lo que apunta el servidor, el vigilante y el chivato de cuelgues."""

from __future__ import annotations

from pathlib import Path

from PySide6.QtCore import QTimer
from PySide6.QtGui import QFont
from PySide6.QtWidgets import QCheckBox, QComboBox, QHBoxLayout, QPlainTextEdit, QVBoxLayout, QWidget

from desktop.config_edit import ROOT
from desktop.widgets import style

LOGS = ROOT / "logs"
TAIL_BYTES = 200_000
FILES = (
    ("Servidor", "server.log"),
    ("Arranque", "arranque.log"),
    ("Vigilante", "vigilante.log"),
    ("Botones pulsados", "audit.log"),
)


def tail(path: Path, max_bytes: int = TAIL_BYTES) -> str:
    """El final de un archivo (lo último que pasó), sin leerlo entero."""
    try:
        with path.open("rb") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(max(0, size - max_bytes))
            data = f.read()
    except OSError:
        return ""
    return data.decode("utf-8", errors="replace").lstrip("﻿")


def important(text: str) -> str:
    """Solo las líneas con aviso, error o cuelgue."""
    return "\n".join(
        line
        for line in text.splitlines()
        if any(k in line for k in ("WARNING", "ERROR", "CRITICAL", "Traceback"))
    )


class LogsPage(QWidget):
    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 18, 18, 18)
        top = QHBoxLayout()
        self.which = QComboBox()
        self.only_problems = QCheckBox("Solo avisos y errores")
        top.addWidget(self.which)
        top.addWidget(self.only_problems)
        top.addStretch(1)
        lay.addLayout(top)
        self.view = QPlainTextEdit()
        self.view.setReadOnly(True)
        self.view.setLineWrapMode(QPlainTextEdit.LineWrapMode.NoWrap)
        mono = QFont("Cascadia Mono", 9)
        mono.setStyleHint(QFont.StyleHint.Monospace)
        self.view.setFont(mono)
        style(self.view, tone="soft")
        lay.addWidget(self.view, 1)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self.refresh)
        self.which.currentIndexChanged.connect(self.refresh)
        self.only_problems.toggled.connect(self.refresh)
        self._fill()

    def _fill(self) -> None:
        current = self.which.currentData()
        self.which.blockSignals(True)
        self.which.clear()
        for label, name in FILES:
            self.which.addItem(label, LOGS / name)
        for dump in sorted((LOGS / "cuelgues").glob("cuelgue-*.txt"), reverse=True)[:20]:
            self.which.addItem(f"Cuelgue {dump.stem.removeprefix('cuelgue-')}", dump)
        if current is not None:
            i = self.which.findData(current)
            self.which.setCurrentIndex(max(0, i))
        self.which.blockSignals(False)

    def showEvent(self, event: object) -> None:
        super().showEvent(event)  # type: ignore[arg-type]
        self._fill()
        self.refresh()
        self._timer.start(2000)

    def hideEvent(self, event: object) -> None:
        super().hideEvent(event)  # type: ignore[arg-type]
        self._timer.stop()

    def refresh(self) -> None:
        path = self.which.currentData()
        text = tail(path) if path else ""
        if self.only_problems.isChecked():
            text = important(text)
        bar = self.view.verticalScrollBar()
        at_end = bar.value() >= bar.maximum() - 8
        if text != self.view.toPlainText():
            self.view.setPlainText(text or "(vacío)")
            if at_end:
                bar.setValue(bar.maximum())
