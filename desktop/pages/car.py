"""Coche: la pantalla de Vega en Android Auto (config/car.toml), con vista previa."""

from __future__ import annotations

from typing import Any

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QAbstractItemView,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from desktop import config_edit, theme
from desktop.config_edit import ConfigError
from desktop.context import Context
from desktop.widgets import Card, pill

BUILTINS = {
    "sleep": "Dormir / despertar a Vega",
    "brain": "Cambiar la IA entre el PC y el móvil",
    "media": "Pausa / play de la música del PC",
    "next": "Siguiente canción",
    "notifications": "Lista de avisos del PC (solo lectura)",
    "pc": "Estado del PC: procesador, gráfica y memoria",
    "vehicle": "Datos del coche por OBD-II (si hay lector)",
}
MAX_ITEMS = 6


class ItemDialog(QDialog):
    """Una casilla: su nombre y qué hace."""

    def __init__(
        self, item: dict[str, Any] | None, actions: list[dict[str, Any]], parent: QWidget | None = None
    ) -> None:
        super().__init__(parent)
        self.setWindowTitle("Casilla" if item else "Casilla nueva")
        self.setMinimumWidth(440)
        a = item or {"kind": "builtin", "arg": "sleep"}
        form = QFormLayout(self)
        form.setSpacing(10)
        self.id = QLineEdit(a.get("id", ""))
        self.id.setPlaceholderText("minúsculas, números y guiones: dormir")
        self.label = QLineEdit(a.get("label", ""))
        self.label.setMaxLength(14)
        self.kind = QComboBox()
        self.kind.addItem("Algo de Vega", "builtin")
        self.kind.addItem("Un botón del PC", "action")
        self.kind.setCurrentIndex(0 if a.get("kind", "builtin") == "builtin" else 1)
        self.arg = QComboBox()
        self.actions = actions
        self._fill_arg(a.get("arg", ""))
        self.kind.currentIndexChanged.connect(lambda _=0: self._fill_arg(""))
        form.addRow("Id", self.id)
        form.addRow("Nombre (14 letras)", self.label)
        form.addRow("Qué es", self.kind)
        form.addRow("Qué hace", self.arg)
        self.error = QLabel()
        self.error.setStyleSheet(f"color: {theme.RED};")
        self.error.setWordWrap(True)
        form.addRow("", self.error)
        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self._accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)
        self.result_item: dict[str, Any] = {}

    def _fill_arg(self, current: str) -> None:
        self.arg.clear()
        if self.kind.currentData() == "builtin":
            for key, text in BUILTINS.items():
                self.arg.addItem(text, key)
        else:
            for act in self.actions:
                self.arg.addItem(act["label"], act["id"])
        i = self.arg.findData(current)
        self.arg.setCurrentIndex(max(0, i))

    def _accept(self) -> None:
        item = {
            "id": self.id.text().strip(),
            "label": self.label.text().strip(),
            "kind": self.kind.currentData(),
            "arg": self.arg.currentData() or "",
        }
        if not item["id"] or not item["label"] or not item["arg"]:
            self.error.setText("Falta el id, el nombre o lo que hace.")
            return
        self.result_item = item
        self.accept()


class CarPage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        self.title = "Vega"
        self.items: list[dict[str, Any]] = []
        self.dirty = False
        lay = QHBoxLayout(self)
        lay.setContentsMargins(18, 18, 18, 18)
        lay.setSpacing(16)

        left = QVBoxLayout()
        intro = QLabel(
            "Esto es lo que enseña Vega en la pantalla del coche (Android Auto). Caben 6 casillas. "
            "Vega no escribe nada en el coche: solo decide qué casillas dibuja Android Auto "
            "y qué hace cada una."
        )
        intro.setObjectName("muted")
        intro.setWordWrap(True)
        left.addWidget(intro)
        row = QHBoxLayout()
        row.addWidget(QLabel("Título"))
        self.title_edit = QLineEdit()
        self.title_edit.setMaxLength(20)
        self.title_edit.textEdited.connect(self._title_changed)
        row.addWidget(self.title_edit, 1)
        left.addLayout(row)
        self.table = QTableWidget(0, 3)
        self.table.setHorizontalHeaderLabels(("Casilla", "Es", "Qué hace"))
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().hide()
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.doubleClicked.connect(self.edit)
        left.addWidget(self.table, 1)
        buttons = QHBoxLayout()
        self.add_btn, self.edit_btn, self.del_btn = (
            pill("Añadir"),
            pill("Editar"),
            pill("Quitar", danger=True),
        )
        self.up_btn, self.down_btn = pill("Subir"), pill("Bajar")
        for b in (self.add_btn, self.edit_btn, self.del_btn, self.up_btn, self.down_btn):
            buttons.addWidget(b)
        buttons.addStretch(1)
        left.addLayout(buttons)
        save = QHBoxLayout()
        self.discard_btn = pill("Descartar cambios")
        self.save_btn = pill("Guardar y enviar al coche", accent=True)
        save.addStretch(1)
        save.addWidget(self.discard_btn)
        save.addWidget(self.save_btn)
        left.addLayout(save)
        self.status = QLabel()
        self.status.setObjectName("muted")
        self.status.setWordWrap(True)
        left.addWidget(self.status)
        lay.addLayout(left, 3)

        right = Card("Vista previa")
        self.preview_title = QLabel()
        self.preview_title.setStyleSheet("font-size: 15px; font-weight: 700;")
        right.body.addWidget(self.preview_title)
        self.preview = QGridLayout()
        self.preview.setSpacing(8)
        right.body.addLayout(self.preview)
        right.body.addStretch(1)
        lay.addWidget(right, 2)

        self.add_btn.clicked.connect(self.add)
        self.edit_btn.clicked.connect(self.edit)
        self.del_btn.clicked.connect(self.remove)
        self.up_btn.clicked.connect(lambda: self.move(-1))
        self.down_btn.clicked.connect(lambda: self.move(1))
        self.save_btn.clicked.connect(self.save)
        self.discard_btn.clicked.connect(self.load)
        self.load()

    # --- Datos ---

    def load(self) -> None:
        try:
            self.title, self.items = config_edit.read_car()
            self.status.setText("")
        except Exception as exc:
            self.title, self.items = "Vega", []
            self.status.setText(f"No pude leer config/car.toml: {exc}")
        self.dirty = False
        self.title_edit.setText(self.title)
        self._paint()

    def _paint(self, select: int | None = None) -> None:
        self.table.setRowCount(len(self.items))
        actions = {a["id"]: a["label"] for a in self._actions()}
        for r, it in enumerate(self.items):
            what = (
                BUILTINS.get(it["arg"], it["arg"])
                if it["kind"] == "builtin"
                else f"Botón del PC: {actions.get(it['arg'], it['arg'])}"
            )
            for c, text in enumerate((it["label"], "Vega" if it["kind"] == "builtin" else "PC", what)):
                self.table.setItem(r, c, QTableWidgetItem(text))
        self.table.resizeColumnsToContents()
        self.table.horizontalHeader().setStretchLastSection(True)
        if select is not None and 0 <= select < len(self.items):
            self.table.selectRow(select)
        self.save_btn.setEnabled(self.dirty)
        self.discard_btn.setEnabled(self.dirty)
        self.add_btn.setEnabled(len(self.items) < MAX_ITEMS)
        self._paint_preview()

    def _paint_preview(self) -> None:
        while self.preview.count():
            w = self.preview.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        self.preview_title.setText(self.title or "Vega")
        for i, it in enumerate(self.items[:MAX_ITEMS]):
            tile = QLabel(it["label"])
            tile.setAlignment(Qt.AlignmentFlag.AlignCenter)
            tile.setWordWrap(True)
            tile.setMinimumHeight(70)
            tile.setStyleSheet(
                f"background: {theme.CARD_HI}; border: 1px solid {theme.LINE}; "
                "border-radius: 12px; font-weight: 700;"
            )
            self.preview.addWidget(tile, i // 3, i % 3)

    def _actions(self) -> list[dict[str, Any]]:
        try:
            return config_edit.read_actions()
        except Exception:
            return []

    def _title_changed(self, text: str) -> None:
        self.title = text
        self._touched()

    def _current(self) -> int | None:
        r = self.table.currentRow()
        return r if 0 <= r < len(self.items) else None

    def _touched(self, select: int | None = None) -> None:
        self.dirty = True
        self.status.setText("Hay cambios sin guardar.")
        self._paint(select)

    # --- Acciones ---

    def add(self) -> None:
        dialog = ItemDialog(None, self._actions(), self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.items.append(dialog.result_item)
            self._touched(len(self.items) - 1)

    def edit(self) -> None:
        r = self._current()
        if r is None:
            return
        dialog = ItemDialog(self.items[r], self._actions(), self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.items[r] = dialog.result_item
            self._touched(r)

    def remove(self) -> None:
        r = self._current()
        if r is not None:
            del self.items[r]
            self._touched(min(r, len(self.items) - 1))

    def move(self, delta: int) -> None:
        r = self._current()
        if r is None or not 0 <= r + delta < len(self.items):
            return
        self.items[r], self.items[r + delta] = self.items[r + delta], self.items[r]
        self._touched(r + delta)

    def save(self) -> None:
        try:
            config_edit.write_car(self.title, self.items)
        except ConfigError as exc:
            self.status.setText(f"No se guardó: {exc}")
            return
        self.dirty = False
        self._paint()
        if self.ctx.send({"type": "reload_car"}):
            self.status.setText("Guardado y enviado: el teléfono ya tiene la pantalla nueva para el coche.")
        else:
            self.status.setText("Guardado. El servidor no está en marcha: se enviará al arrancarlo.")
