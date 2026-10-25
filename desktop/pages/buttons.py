"""Botones: editar config/actions.toml (los del panel, «Más» y las frases de voz) sin tocar el archivo."""

from __future__ import annotations

from typing import Any

from PySide6.QtCore import Qt
from PySide6.QtWidgets import (
    QAbstractItemView,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QPlainTextEdit,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from desktop import config_edit, theme
from desktop.config_edit import ConfigError
from desktop.context import Context
from desktop.widgets import pill

ICONS = (
    "power",
    "restart",
    "sleep",
    "lock",
    "cancel",
    "record",
    "mic",
    "volume-up",
    "volume-down",
    "mute",
    "play",
    "next",
    "prev",
    "app",
    "screenshot",
)
COLORS = ("orange", "red", "amber", "green", "teal")
KINDS = ("keys", "launch", "power")
KIND_HELP = {
    "keys": "Combinación de teclas, p. ej. «win+alt+r» o «volume_up».",
    "launch": "Ruta del programa, p. ej. «C:\\Program Files\\...\\app.exe».",
    "power": "shutdown, restart, sleep, lock o cancel.",
}
COLUMNS = ("Panel", "Nombre", "Tipo", "Argumento", "Confirma", "Frases de voz")


class ActionDialog(QDialog):
    """Un botón: todos sus campos. Se valida con las mismas reglas que el servidor."""

    def __init__(self, action: dict[str, Any] | None, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setWindowTitle("Botón" if action else "Botón nuevo")
        self.setMinimumWidth(460)
        a = action or {"kind": "keys", "icon": "app", "color": "orange"}
        form = QFormLayout(self)
        form.setSpacing(10)
        self.id = QLineEdit(a.get("id", ""))
        self.id.setPlaceholderText("minúsculas, números y guiones: mi-boton")
        self.label = QLineEdit(a.get("label", ""))
        self.label.setMaxLength(24)
        self.kind = QComboBox()
        self.kind.addItems(KINDS)
        self.kind.setCurrentText(a.get("kind", "keys"))
        self.arg = QLineEdit(a.get("arg", ""))
        self.hint = QLabel(KIND_HELP[self.kind.currentText()])
        self.hint.setObjectName("faint")
        self.kind.currentTextChanged.connect(lambda k: self.hint.setText(KIND_HELP[k]))
        self.args = QLineEdit(" ".join(a.get("args", [])))
        self.args.setPlaceholderText("argumentos del programa (solo «launch»), separados por espacios")
        self.icon = QComboBox()
        self.icon.addItems(ICONS)
        self.icon.setCurrentText(a.get("icon", "app"))
        self.color = QComboBox()
        self.color.addItems(COLORS)
        self.color.setCurrentText(a.get("color", "orange"))
        self.quick = QCheckBox("Aparece en el panel principal (máximo 6)")
        self.quick.setChecked(bool(a.get("quick")))
        self.confirm = QCheckBox("Pide confirmación (un toque más o decir «sí»)")
        self.confirm.setChecked(bool(a.get("confirm")))
        self.say = QPlainTextEdit("\n".join(a.get("say", [])))
        self.say.setPlaceholderText("Una frase por línea: «abre la calculadora»")
        self.say.setFixedHeight(90)
        form.addRow("Id", self.id)
        form.addRow("Nombre", self.label)
        form.addRow("Tipo", self.kind)
        form.addRow("Argumento", self.arg)
        form.addRow("", self.hint)
        form.addRow("Argumentos", self.args)
        form.addRow("Icono", self.icon)
        form.addRow("Color", self.color)
        form.addRow("", self.quick)
        form.addRow("", self.confirm)
        form.addRow("Frases de voz", self.say)
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
        self.result_action: dict[str, Any] = {}

    def _accept(self) -> None:
        a: dict[str, Any] = {
            "id": self.id.text().strip(),
            "label": self.label.text().strip(),
            "icon": self.icon.currentText(),
            "color": self.color.currentText(),
            "kind": self.kind.currentText(),
            "arg": self.arg.text().strip(),
            "args": self.args.text().split() if self.kind.currentText() == "launch" else [],
            "confirm": self.confirm.isChecked(),
            "quick": self.quick.isChecked(),
            "say": [s.strip() for s in self.say.toPlainText().splitlines() if s.strip()],
        }
        try:
            config_edit.validate_actions([a])
        except ConfigError as exc:
            self.error.setText(str(exc))
            return
        self.result_action = a
        self.accept()


class ButtonsPage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        self.rows: list[dict[str, Any]] = []
        self.dirty = False
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 18, 18, 18)
        lay.setSpacing(12)
        intro = QLabel(
            "Estos son los únicos botones que existen: el servidor solo ejecuta lo que está aquí. "
            "Los de «Panel» salen en la fila principal del teléfono; los demás, en «Más»."
        )
        intro.setObjectName("muted")
        intro.setWordWrap(True)
        lay.addWidget(intro)
        self.table = QTableWidget(0, len(COLUMNS))
        self.table.setHorizontalHeaderLabels(COLUMNS)
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QAbstractItemView.SelectionMode.SingleSelection)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.setAlternatingRowColors(True)
        self.table.verticalHeader().hide()
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.doubleClicked.connect(self.edit)
        lay.addWidget(self.table, 1)
        row = QHBoxLayout()
        self.add_btn, self.edit_btn, self.del_btn = (
            pill("Añadir"),
            pill("Editar"),
            pill("Quitar", danger=True),
        )
        self.up_btn, self.down_btn = pill("Subir"), pill("Bajar")
        self.save_btn = pill("Guardar y aplicar", accent=True)
        self.discard_btn = pill("Descartar cambios")
        for b in (self.add_btn, self.edit_btn, self.del_btn, self.up_btn, self.down_btn):
            row.addWidget(b)
        row.addStretch(1)
        row.addWidget(self.discard_btn)
        row.addWidget(self.save_btn)
        lay.addLayout(row)
        self.status = QLabel()
        self.status.setObjectName("muted")
        self.status.setWordWrap(True)
        lay.addWidget(self.status)
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
            self.rows = config_edit.read_actions()
        except Exception as exc:
            self.rows = []
            self.status.setText(f"No pude leer config/actions.toml: {exc}")
        else:
            self.status.setText("")
        self.dirty = False
        self._paint()

    def _paint(self, select: int | None = None) -> None:
        self.table.setRowCount(len(self.rows))
        for r, a in enumerate(self.rows):
            cells = (
                "sí" if a.get("quick") else "",
                a["label"],
                a["kind"],
                a["arg"],
                "sí" if a.get("confirm") else "",
                " · ".join(a.get("say", [])),
            )
            for c, text in enumerate(cells):
                item = QTableWidgetItem(text)
                if c in (0, 4):
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                self.table.setItem(r, c, item)
        self.table.resizeColumnsToContents()
        self.table.horizontalHeader().setStretchLastSection(True)
        if select is not None and 0 <= select < len(self.rows):
            self.table.selectRow(select)
        self.save_btn.setEnabled(self.dirty)
        self.discard_btn.setEnabled(self.dirty)

    def _current(self) -> int | None:
        r = self.table.currentRow()
        return r if 0 <= r < len(self.rows) else None

    def _touched(self, select: int | None = None) -> None:
        self.dirty = True
        self.status.setText("Hay cambios sin guardar.")
        self._paint(select)

    # --- Acciones ---

    def add(self) -> None:
        dialog = ActionDialog(None, self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.rows.append(dialog.result_action)
            self._touched(len(self.rows) - 1)

    def edit(self) -> None:
        r = self._current()
        if r is None:
            return
        dialog = ActionDialog(self.rows[r], self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.rows[r] = dialog.result_action
            self._touched(r)

    def remove(self) -> None:
        r = self._current()
        if r is not None:
            del self.rows[r]
            self._touched(min(r, len(self.rows) - 1))

    def move(self, delta: int) -> None:
        r = self._current()
        if r is None or not 0 <= r + delta < len(self.rows):
            return
        self.rows[r], self.rows[r + delta] = self.rows[r + delta], self.rows[r]
        self._touched(r + delta)

    def save(self) -> None:
        try:
            config_edit.write_actions(self.rows)
        except ConfigError as exc:
            self.status.setText(f"No se guardó: {exc}")
            return
        self.dirty = False
        self._paint()
        if self.ctx.send({"type": "reload_actions"}):
            self.status.setText("Guardado y repartido: el teléfono y el panel ya tienen los botones nuevos.")
        else:
            self.status.setText("Guardado. El servidor no está en marcha: se aplicará al arrancarlo.")
