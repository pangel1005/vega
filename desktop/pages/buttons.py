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
    QMenu,
    QPlainTextEdit,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from desktop import config_edit, slots
from desktop.config_edit import ConfigError
from desktop.context import Context
from desktop.widgets import Card, pill, style

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
KINDS = ("keys", "launch", "power", "maintenance", "routine")
KIND_HELP = {
    "keys": "Combinación de teclas, p. ej. «win+alt+r» o «volume_up».",
    "launch": "Ruta del programa, p. ej. «C:\\Program Files\\...\\app.exe».",
    "power": "shutdown, restart, sleep, lock o cancel.",
    "maintenance": "free-ram (liberar RAM) o clean-disk (limpiar lo que se regenera solo).",
    "routine": "Varios botones seguidos con una sola orden («modo noche»). Elige los pasos abajo.",
}
COLUMNS = ("Panel", "Nombre", "Tipo", "Argumento", "Confirma", "Frases de voz")


class ActionDialog(QDialog):
    """Un botón: todos sus campos. Se valida con las mismas reglas que el servidor."""

    def __init__(
        self,
        action: dict[str, Any] | None,
        parent: QWidget | None = None,
        others: list[dict[str, Any]] | None = None,
    ) -> None:
        super().__init__(parent)
        self.others = [
            o
            for o in (others or [])
            if o.get("kind") != "routine" and o.get("id") != (action or {}).get("id")
        ]
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
        self.steps = QPlainTextEdit(
            "\n".join(f"espera {s[5:]}" if s.startswith("wait:") else s for s in a.get("steps", []))
        )
        self.steps.setPlaceholderText("Un paso por línea: el id de un botón (p. ej. silenciar) o «espera 3»")
        self.steps.setFixedHeight(110)
        self.step_pick = QComboBox()
        for o in self.others:
            self.step_pick.addItem(f"{o['label']}  ({o['id']})", o["id"])
        add_step = QPushButton("Añadir este botón")
        add_wait = QPushButton("Añadir espera de 2 s")
        add_step.clicked.connect(lambda: self._add_step(str(self.step_pick.currentData() or "")))
        add_wait.clicked.connect(lambda: self._add_step("espera 2"))
        step_tools = QHBoxLayout()
        for w in (self.step_pick, add_step, add_wait):
            step_tools.addWidget(w)
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
        form.addRow("Pasos", self.steps)
        form.addRow("", step_tools)
        form.addRow("Icono", self.icon)
        form.addRow("Color", self.color)
        form.addRow("", self.quick)
        form.addRow("", self.confirm)
        form.addRow("Frases de voz", self.say)
        self.kind.currentTextChanged.connect(self._sync_kind)
        self._form = form
        self._sync_kind(self.kind.currentText())
        self.error = QLabel()
        style(self.error, tone="red")
        self.error.setWordWrap(True)
        form.addRow("", self.error)
        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self._accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)
        self.result_action: dict[str, Any] = {}

    def _add_step(self, step: str) -> None:
        if step:
            text = self.steps.toPlainText().rstrip("\n")
            self.steps.setPlainText((text + "\n" if text else "") + step)

    def _sync_kind(self, kind: str) -> None:
        """Una rutina lleva pasos; los demás botones, un argumento."""
        routine = kind == "routine"
        for widget, shown in (
            (self.arg, not routine),
            (self.args, not routine),
            (self.steps, routine),
            (self.step_pick, routine),
        ):  # fmt: skip
            self._form.setRowVisible(widget, shown)
        self._form.setRowVisible(self.step_pick, routine)

    def _steps(self) -> list[str]:
        out = []
        for line in self.steps.toPlainText().splitlines():
            line = line.strip()
            if not line:
                continue
            word, _, rest = line.partition(" ")
            out.append(f"wait:{rest.strip()}" if word.lower() in ("espera", "wait") else line)
        return out

    def _accept(self) -> None:
        routine = self.kind.currentText() == "routine"
        a: dict[str, Any] = {
            "id": self.id.text().strip(),
            "label": self.label.text().strip(),
            "icon": self.icon.currentText(),
            "color": self.color.currentText(),
            "kind": self.kind.currentText(),
            "arg": "" if routine else self.arg.text().strip(),
            "args": self.args.text().split() if self.kind.currentText() == "launch" else [],
            "steps": self._steps() if routine else [],
            "confirm": self.confirm.isChecked(),
            "quick": self.quick.isChecked(),
            "say": [s.strip() for s in self.say.toPlainText().splitlines() if s.strip()],
        }
        try:
            others = [o for o in self.others if o.get("id") != a["id"]]
            config_edit.validate_actions([*others, a] if routine else [a])
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
            "Elige arriba los 6 de la fila principal del teléfono; los demás salen en «Más»."
        )
        intro.setObjectName("muted")
        intro.setWordWrap(True)
        lay.addWidget(intro)
        lay.addWidget(self._build_strip())
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

    # --- Fila principal del teléfono: 6 huecos ---

    def _build_strip(self) -> QWidget:
        card = Card("Fila principal del teléfono")
        hint = QLabel(
            "Así se ven los botones de abajo en el teléfono, de izquierda a derecha. Pulsa un hueco para "
            "elegir su botón; las flechas lo mueven y la cruz lo manda a «Más»."
        )
        hint.setObjectName("muted")
        hint.setWordWrap(True)
        card.body.addWidget(hint)
        row = QHBoxLayout()
        row.setSpacing(10)
        self.slot_main: list[QPushButton] = []
        self.slot_tools: list[tuple[QPushButton, QPushButton, QPushButton]] = []
        for n in range(slots.MAX_SLOTS):
            col = QVBoxLayout()
            col.setSpacing(4)
            main = QPushButton()
            main.setCursor(Qt.CursorShape.PointingHandCursor)
            main.setMinimumHeight(58)
            main.clicked.connect(lambda _=False, i=n: self.pick(i))
            tools = QHBoxLayout()
            tools.setSpacing(4)
            left, right, drop = pill("‹"), pill("›"), pill("×", danger=True)
            left.setToolTip("Mover a la izquierda")
            right.setToolTip("Mover a la derecha")
            drop.setToolTip("Mandar a «Más»")
            left.clicked.connect(lambda _=False, i=n: self.move_slot(i, -1))
            right.clicked.connect(lambda _=False, i=n: self.move_slot(i, 1))
            drop.clicked.connect(lambda _=False, i=n: self.clear_slot(i))
            tools.addStretch(1)
            for t in (left, right, drop):
                t.setFixedSize(34, 26)
                tools.addWidget(t)
            tools.addStretch(1)
            col.addWidget(main, 1)
            col.addLayout(tools)
            row.addLayout(col, 1)
            self.slot_main.append(main)
            self.slot_tools.append((left, right, drop))
        more = QLabel("Más")
        more.setObjectName("faint")
        more.setAlignment(Qt.AlignmentFlag.AlignCenter)
        more.setFixedWidth(54)
        row.addWidget(more)
        card.body.addLayout(row)
        self.slot_warn = QLabel("")
        style(self.slot_warn, tone="sun")
        self.slot_warn.setWordWrap(True)
        card.body.addWidget(self.slot_warn)
        return card

    def _paint_slots(self) -> None:
        current = slots.slots(self.rows)
        for n, a in enumerate(current):
            main = self.slot_main[n]
            main.setText(a["label"] if a else "+ Vacío")
            style(main, on=a is not None)
            main.setToolTip(f"{a['kind']}: {a['arg']}" if a else "Elegir un botón para este hueco")
            left, right, drop = self.slot_tools[n]
            left.setEnabled(a is not None and n > 0)
            right.setEnabled(a is not None and n + 1 < slots.MAX_SLOTS and current[n + 1] is not None)
            drop.setEnabled(a is not None)
        extra = slots.overflow(self.rows)
        self.slot_warn.setText(
            f"Hay {extra} botones más marcados como «panel» y no caben: solo se ven los 6 primeros."
            if extra
            else ""
        )

    def pick(self, slot: int) -> None:
        """Menú con los botones que aún no están en el panel."""
        menu = QMenu(self)
        in_panel = set(slots.quick_rows(self.rows))
        for i, a in enumerate(self.rows):
            if i not in in_panel:
                menu.addAction(a["label"], lambda i=i: self.put(slot, i))
        if slots.slots(self.rows)[slot] is not None:
            menu.addSeparator()
            menu.addAction("Dejar vacío (mandar a «Más»)", lambda: self.clear_slot(slot))
        if menu.isEmpty():
            menu.addAction("No quedan botones: añade uno abajo").setEnabled(False)
        menu.exec(self.slot_main[slot].mapToGlobal(self.slot_main[slot].rect().bottomLeft()))

    def put(self, slot: int, index: int) -> None:
        slots.assign(self.rows, slot, index)
        self._touched()

    def move_slot(self, slot: int, delta: int) -> None:
        slots.swap(self.rows, slot, slot + delta)
        self._touched()

    def clear_slot(self, slot: int) -> None:
        slots.clear(self.rows, slot)
        self._touched()

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
                f"{len(a.get('steps', []))} pasos" if a["kind"] == "routine" else a["arg"],
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
        self._paint_slots()
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
        dialog = ActionDialog(None, self, self.rows)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            self.rows.append(dialog.result_action)
            self._touched(len(self.rows) - 1)

    def edit(self) -> None:
        r = self._current()
        if r is None:
            return
        dialog = ActionDialog(self.rows[r], self, self.rows)
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
