"""Mantenimiento: liberar RAM y limpiar disco, viendo antes qué se va a tocar."""

from __future__ import annotations

from typing import Any

from PySide6.QtWidgets import (
    QCheckBox,
    QHBoxLayout,
    QLabel,
    QMessageBox,
    QScrollArea,
    QVBoxLayout,
    QWidget,
)

from desktop.context import Context
from desktop.widgets import Card, DotBar, pill, style


def human(n: float) -> str:
    """Bytes → «1,5 GB» / «380 MB» / «12 KB» (igual que el servidor)."""
    if n >= 2**30:
        return f"{n / 2**30:.1f}".replace(".", ",") + " GB"
    if n >= 2**20:
        return f"{n / 2**20:.0f} MB"
    return f"{max(n, 0) / 2**10:.0f} KB"


def human_mb(mb: float) -> str:
    return human(mb * 2**20)


class MaintenancePage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        self.categories: list[dict[str, Any]] = []
        self.checks: dict[str, QCheckBox] = {}
        outer = QVBoxLayout(self)
        outer.setContentsMargins(0, 0, 0, 0)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        outer.addWidget(scroll)
        inner = QWidget()
        scroll.setWidget(inner)
        lay = QVBoxLayout(inner)
        lay.setContentsMargins(18, 18, 18, 18)
        lay.setSpacing(14)

        intro = QLabel(
            "Aquí limpias el PC sin miedo: antes de borrar nada se ve qué es y cuánto ocupa, y solo se "
            "toca lo que Vega puede volver a crear. No se borran documentos, descargas ni la papelera."
        )
        intro.setObjectName("muted")
        intro.setWordWrap(True)
        lay.addWidget(intro)

        # --- Memoria ---
        self.ram = Card("Memoria (RAM)")
        self.ram_bar = DotBar()
        self.ram_line = QLabel("—")
        self.ram_line.setObjectName("muted")
        self.ram_line.setWordWrap(True)
        self.ram_top = QLabel("")
        self.ram_top.setObjectName("faint")
        self.ram_top.setWordWrap(True)
        self.deep = QCheckBox(
            "A fondo: reducir también las demás aplicaciones y detener Gradle/Kotlin "
            "(se vuelven a llenar o a arrancar solos si los usas)"
        )
        self.free_btn = pill("Liberar RAM", accent=True)
        self.refresh_btn = pill("Actualizar")
        self.ram_result = QLabel("")
        self.ram_result.setWordWrap(True)
        style(self.ram_result, tone="mint")
        row = QHBoxLayout()
        row.addWidget(self.free_btn)
        row.addWidget(self.refresh_btn)
        row.addStretch(1)
        for w in (self.ram_bar, self.ram_line, self.ram_top, self.deep):
            self.ram.body.addWidget(w)
        self.ram.body.addLayout(row)
        self.ram.body.addWidget(self.ram_result)
        lay.addWidget(self.ram)

        # --- Disco ---
        self.disk = Card("Disco")
        self.disk_help = QLabel("Pulsa «Buscar» para ver qué se puede limpiar. No se borra nada todavía.")
        self.disk_help.setObjectName("muted")
        self.disk_help.setWordWrap(True)
        self.disk_list = QVBoxLayout()
        self.disk_list.setSpacing(8)
        self.scan_btn = pill("Buscar")
        self.clean_btn = pill("Limpiar lo marcado", accent=True)
        self.clean_btn.setEnabled(False)
        self.disk_result = QLabel("")
        self.disk_result.setWordWrap(True)
        style(self.disk_result, tone="mint")
        drow = QHBoxLayout()
        drow.addWidget(self.scan_btn)
        drow.addWidget(self.clean_btn)
        drow.addStretch(1)
        self.disk.body.addWidget(self.disk_help)
        self.disk.body.addLayout(self.disk_list)
        self.disk.body.addLayout(drow)
        self.disk.body.addWidget(self.disk_result)
        lay.addWidget(self.disk)
        lay.addStretch(1)

        self.free_btn.clicked.connect(self.free_ram)
        self.refresh_btn.clicked.connect(self.refresh)
        self.scan_btn.clicked.connect(self.scan)
        self.clean_btn.clicked.connect(self.clean)
        ctx.event.connect(self._on_event)
        ctx.changed.connect(self._connection)
        self._connection()

    # --- Órdenes al servidor ---

    def refresh(self) -> None:
        self.ctx.send({"type": "maintenance", "op": "status"})

    def free_ram(self) -> None:
        self.free_btn.setEnabled(False)
        self.ram_result.setText("Liberando…")
        self.ctx.send({"type": "maintenance", "op": "free_ram", "deep": self.deep.isChecked()})

    def scan(self) -> None:
        self.scan_btn.setEnabled(False)
        self.disk_result.setText("")
        self.disk_help.setText("Midiendo… (si hay mucho puede tardar unos segundos)")
        self.ctx.send({"type": "maintenance", "op": "disk_scan"})

    def selected(self) -> list[str]:
        return [i for i, box in self.checks.items() if box.isChecked()]

    def clean(self) -> None:
        ids = self.selected()
        if not ids:
            return
        total = sum(c["size"] for c in self.categories if c["id"] in ids)
        names = "\n".join(f"• {c['label']} ({human(c['size'])})" for c in self.categories if c["id"] in ids)
        answer = QMessageBox.question(
            self,
            "Limpiar disco",
            f"Se borrará esto (unos {human(total)}):\n\n{names}\n\n¿Seguro?",
        )
        if answer != QMessageBox.StandardButton.Yes:
            return
        self.clean_btn.setEnabled(False)
        self.disk_result.setText("Limpiando…")
        self.ctx.send({"type": "maintenance", "op": "disk_clean", "ids": ids})

    # --- Respuestas ---

    def _connection(self) -> None:
        up = self.ctx.state.connected
        for b in (self.free_btn, self.refresh_btn, self.scan_btn):
            b.setEnabled(up)
        if up and not self.ram_line.property("asked"):
            self.ram_line.setProperty("asked", True)
            self.refresh()

    def _on_event(self, event: dict[str, Any]) -> None:
        if event.get("type") != "maintenance_result":
            return
        op, ok, data = event.get("op"), event.get("ok", True), event.get("data", {})
        message = str(event.get("message", ""))
        if op == "status" and ok:
            self._paint_ram(data)
        elif op == "free_ram":
            self.free_btn.setEnabled(self.ctx.state.connected)
            style(self.ram_result, tone="mint" if ok else "red")
            self.ram_result.setText(message)
            self.refresh()
        elif op == "disk_scan":
            self.scan_btn.setEnabled(self.ctx.state.connected)
            if ok:
                self._paint_disk(data.get("categories", []))
            else:
                self.disk_help.setText(message)
        elif op == "disk_clean":
            style(self.disk_result, tone="mint" if ok else "red")
            self.disk_result.setText(message)
            if ok:
                self.scan()

    def _paint_ram(self, d: dict[str, Any]) -> None:
        total, free = d["total_mb"], d["free_mb"]
        self.ram_bar.set_value((total - free) / total if total else 0)
        models = ", ".join(d["ollama_models"]) or "ninguno cargado"
        self.ram_line.setText(
            f"Libres {human_mb(free)} de {human_mb(total)}.  Vega: {human_mb(d['vega_mb'])}.  "
            f"Ollama: {human_mb(d['ollama_mb'])} (modelo: {models})."
        )
        self.ram_top.setText(
            "Lo que más ocupa: " + " · ".join(f"{p['name']} {human_mb(p['mb'])}" for p in d["top"])
        )

    def _paint_disk(self, categories: list[dict[str, Any]]) -> None:
        self.categories = categories
        self.checks = {}
        while self.disk_list.count():
            item = self.disk_list.takeAt(0)
            widget = item.widget() if item else None
            if widget is not None:
                widget.setParent(None)
        if not categories:
            self.disk_help.setText("No hay nada que limpiar: todo está al día.")
            self.clean_btn.setEnabled(False)
            return
        total = sum(c["size"] for c in categories)
        self.disk_help.setText(
            f"Se puede liberar hasta {human(total)}. Va marcado lo que se regenera al momento; "
            "lo demás (compilaciones, caché de Gradle) tarda en volver a crearse: tú decides."
        )
        for c in categories:
            box = QCheckBox(f"{c['label']} — {human(c['size'])} en {c['files']} archivos")
            box.setChecked(bool(c["default"]))
            box.toggled.connect(self._toggled)
            note = QLabel(f"{c['note']}  Ej.: {', '.join(c['examples'])}")
            note.setObjectName("faint")
            note.setWordWrap(True)
            note.setContentsMargins(26, 0, 0, 0)
            holder = QWidget()
            v = QVBoxLayout(holder)
            v.setContentsMargins(0, 0, 0, 0)
            v.setSpacing(0)
            v.addWidget(box)
            v.addWidget(note)
            self.disk_list.addWidget(holder)
            self.checks[c["id"]] = box
        self._toggled()

    def _toggled(self) -> None:
        self.clean_btn.setEnabled(bool(self.selected()) and self.ctx.state.connected)
