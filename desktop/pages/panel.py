"""Panel principal: la cara de Vega, el teléfono, el PC, lo que suena y las notificaciones."""

from __future__ import annotations

from typing import Any

from PySide6.QtCore import Qt
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QMenu,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from desktop import theme
from desktop.context import Context
from desktop.widgets import Card, Dot, DotBar, LedFaceWidget, ago, badge, pill, style

MOOD_LABEL = {
    "idle": "Di «{name}»",
    "listen": "Te escucho",
    "think": "Pensando",
    "talk": "Hablando",
    "alert": "Confirma",
    "error": "Algo falló",
    "sleep": "Dormida",
    "offline": "Sin conexión con el servidor",
}


def _gb(x: float | None) -> str:
    return "—" if x is None else f"{x:.1f} GB".replace(".", ",")


class PanelPage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        self._notes_key: tuple[Any, ...] | None = None
        self._actions_key: tuple[Any, ...] | None = None
        grid = QGridLayout(self)
        grid.setContentsMargins(18, 18, 18, 18)
        grid.setSpacing(14)
        grid.setColumnStretch(0, 9)
        grid.setColumnStretch(1, 4)
        grid.setColumnStretch(2, 4)
        grid.setColumnMinimumWidth(2, 280)
        grid.addWidget(self._face_card(), 0, 0)
        grid.addWidget(self._keys_card(), 1, 0)
        side = QVBoxLayout()
        side.setSpacing(14)
        side.addWidget(self._phone_card())
        side.addWidget(self._pc_card())
        side.addWidget(self._media_card())
        side.addStretch(1)
        grid.addLayout(side, 0, 1, 2, 1)
        grid.addWidget(self._notes_card(), 0, 2, 2, 1)
        grid.setRowStretch(0, 1)
        ctx.changed.connect(self.refresh)
        ctx.images.loaded.connect(
            lambda *_: self._refresh_media(force=True) or self._refresh_notes(force=True)
        )
        self.refresh()

    # ----------------------------- Construcción -----------------------------

    def _face_card(self) -> Card:
        card = Card()
        top = QHBoxLayout()
        self.dot = Dot()
        self.mood_label = QLabel()
        self.mood_label.setObjectName("muted")
        self.name_label = QLabel("VEGA")
        self.name_label.setFont(theme.dots(15))
        self.name_label.setStyleSheet(f"color: {theme.INK3}; letter-spacing: 2px;")
        top.addWidget(self.dot)
        top.addWidget(self.mood_label)
        top.addStretch(1)
        top.addWidget(self.name_label)
        card.body.addLayout(top)
        self.face = LedFaceWidget()
        card.body.addWidget(self.face, 1)
        self.confirm_bar = QWidget()
        cb = QHBoxLayout(self.confirm_bar)
        cb.setContentsMargins(0, 0, 0, 0)
        self.confirm_text = QLabel()
        self.confirm_text.setStyleSheet(f"color: {theme.RED}; font-weight: 700;")
        self.confirm_yes = pill("Confirmar", danger=True)
        self.confirm_no = pill("Cancelar")
        cb.addWidget(self.confirm_text, 1)
        cb.addWidget(self.confirm_yes)
        cb.addWidget(self.confirm_no)
        self.confirm_yes.clicked.connect(
            lambda: self.ctx.state.pending and self.ctx.confirm(self.ctx.state.pending["nonce"])
        )
        self.confirm_no.clicked.connect(self._cancel_pending)
        card.body.addWidget(self.confirm_bar)
        self.asked = QLabel()
        self.asked.setObjectName("faint")
        self.answer = QLabel()
        self.answer.setWordWrap(True)
        self.answer.setStyleSheet("font-size: 16px;")
        self.answer.setMinimumHeight(44)
        card.body.addWidget(self.asked)
        card.body.addWidget(self.answer)
        return card

    def _keys_card(self) -> Card:
        card = Card("Botones")
        self.more_button = pill("Más")
        card.add_header_widget(self.more_button)
        self.keys_row = QGridLayout()  # 3 por fila: así los nombres siempre caben
        self.keys_row.setSpacing(8)
        card.body.addLayout(self.keys_row)
        return card

    def _phone_card(self) -> Card:
        card = Card("Teléfono")
        self.phone_dot = Dot()
        card.add_header_widget(self.phone_dot)
        self.phone_info = QLabel()
        self.phone_info.setObjectName("muted")
        self.phone_info.setWordWrap(True)
        card.body.addWidget(self.phone_info)
        self.battery = DotBar()
        card.body.addWidget(self.battery)
        row = QHBoxLayout()
        self.sleep_btn = pill("Dormir")
        self.brain_btn = pill("IA: PC")
        row.addWidget(self.sleep_btn)
        row.addWidget(self.brain_btn)
        card.body.addLayout(row)
        row2 = QHBoxLayout()
        self.mic_btn = pill("Micro")
        self.glass_btn = pill("Cristal")
        row2.addWidget(self.mic_btn)
        row2.addWidget(self.glass_btn)
        card.body.addLayout(row2)
        self.sleep_btn.clicked.connect(
            lambda: self.ctx.phone_control("wake" if self.ctx.state.phone.sleeping else "sleep")
        )
        self.brain_btn.clicked.connect(
            lambda: self.ctx.phone_control(
                "brain_pc" if self.ctx.state.phone.brain == "phone" else "brain_phone"
            )
        )
        self.mic_btn.clicked.connect(
            lambda: self.ctx.phone_control("mic_off" if self.ctx.state.phone.mic else "mic_on")
        )
        self.glass_btn.clicked.connect(
            lambda: self.ctx.phone_control("glass_off" if self.ctx.state.phone.glass else "glass_on")
        )
        return card

    def _pc_card(self) -> Card:
        card = Card("PC")
        self.rows: dict[str, tuple[QLabel, DotBar]] = {}
        for key, label in (
            ("cpu", "Procesador"),
            ("ram", "Memoria"),
            ("gpu", "Gráfica"),
            ("vram", "Memoria gráfica"),
        ):
            row = QHBoxLayout()
            name = QLabel(label)
            name.setObjectName("muted")
            name.setFixedWidth(110)
            value = QLabel("—")
            value.setFixedWidth(70)
            value.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
            bar = DotBar()
            row.addWidget(name)
            row.addWidget(bar, 1)
            row.addWidget(value)
            card.body.addLayout(row)
            self.rows[key] = (value, bar)
        self.upcoming = QLabel()
        self.upcoming.setStyleSheet(f"color: {theme.AMBER};")
        card.body.addWidget(self.upcoming)
        return card

    def _media_card(self) -> Card:
        card = Card("Sonando")
        row = QHBoxLayout()
        self.art = QLabel()
        self.art.setFixedSize(64, 64)
        self.art.setStyleSheet(f"background: {theme.CARD_HI}; border-radius: 12px;")
        text = QVBoxLayout()
        self.song = QLabel("Nada suena")
        self.song.setStyleSheet("font-weight: 700; font-size: 14px;")
        self.artist = QLabel()
        self.artist.setObjectName("muted")
        text.addWidget(self.song)
        text.addWidget(self.artist)
        row.addWidget(self.art)
        row.addLayout(text, 1)
        card.body.addLayout(row)
        controls = QHBoxLayout()
        self.prev_btn, self.play_btn, self.next_btn = (
            pill("Anterior"),
            pill("Pausa", accent=True),
            pill("Siguiente"),
        )
        for b, cmd in ((self.prev_btn, "previous"), (self.play_btn, "play_pause"), (self.next_btn, "next")):
            b.clicked.connect(lambda _=False, c=cmd: self.ctx.send({"type": "media_control", "command": c}))
            controls.addWidget(b)
        card.body.addLayout(controls)
        return card

    def _notes_card(self) -> Card:
        card = Card("Avisos del PC")
        self.clear_notes = pill("Borrar todo")
        self.clear_notes.clicked.connect(lambda: self.ctx.send({"type": "notification_dismiss"}))
        card.add_header_widget(self.clear_notes)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        scroll.setHorizontalScrollBarPolicy(
            Qt.ScrollBarPolicy.ScrollBarAlwaysOff
        )  # el texto se ajusta, no se desborda
        self.notes_holder = QWidget()
        self.notes_layout = QVBoxLayout(self.notes_holder)
        self.notes_layout.setContentsMargins(0, 0, 0, 0)
        self.notes_layout.setSpacing(6)
        self.notes_layout.addStretch(1)
        scroll.setWidget(self.notes_holder)
        card.body.addWidget(scroll, 1)
        return card

    # ----------------------------- Actualización -----------------------------

    def refresh(self) -> None:
        s = self.ctx.state
        mood = s.mood
        self.face.set_mood(mood, s.phone.face)
        self.mood_label.setText(MOOD_LABEL[mood].format(name=s.assistant))
        self.dot.set_color(theme.MOOD_COLORS.get(mood) or theme.LIGHT)
        self.name_label.setText(s.assistant.upper())
        self._refresh_conversation()
        self._refresh_confirm()
        self._refresh_phone()
        self._refresh_pc()
        self._refresh_media()
        self._refresh_notes()
        self._refresh_keys()

    def _refresh_conversation(self) -> None:
        s = self.ctx.state
        if not s.connected:
            self.asked.setText("")
            self.answer.setText("El servidor no responde. Arráncalo desde Ajustes o con scripts/start.ps1.")
            return
        asked = next((m.text for m in reversed(s.messages) if m.role == "user"), "")
        last = s.messages[-1] if s.messages else None
        self.asked.setText(f"Tú: {asked}" if asked else "")
        if last is None:
            self.answer.setText(f"Di «{s.assistant}» y lo que necesites.")
        elif last.role == "assistant":
            self.answer.setText(last.text + (" ▍" if last.streaming else ""))
        else:
            self.answer.setText(last.text)

    def _refresh_confirm(self) -> None:
        p = self.ctx.state.pending
        self.confirm_bar.setVisible(p is not None)
        if p is not None:
            self.confirm_text.setText(p.get("message", "¿Seguro?"))

    def _cancel_pending(self) -> None:
        self.ctx.state.pending = None
        self.ctx.changed.emit()

    def _refresh_phone(self) -> None:
        ph = self.ctx.state.phone
        online = self.ctx.state.connected and ph.online
        self.phone_dot.set_color(
            theme.MINT if online and not ph.sleeping else theme.AMBER if online else theme.INK3
        )
        if not self.ctx.state.connected:
            info = "Sin servidor."
        elif not ph.online:
            info = "Desconectado. Conéctalo por USB: se enlaza solo."
        else:
            parts = ["Dormida" if ph.sleeping else "Despierta", f"versión {ph.version}" if ph.version else ""]
            parts.append(f"Vega piensa en el {'teléfono' if ph.brain == 'phone' else 'PC'}")
            if ph.battery >= 0:
                parts.append(f"batería {ph.battery} %" + (" · cargando" if ph.charging else ""))
            info = " · ".join(p for p in parts if p)
        self.phone_info.setText(info)
        self.battery.set_value(
            ph.battery / 100 if ph.battery >= 0 else None, invert=True, charging=ph.charging
        )
        self.sleep_btn.setText("Despertar" if ph.sleeping else "Dormir")
        self.brain_btn.setText("IA: teléfono" if ph.brain == "phone" else "IA: PC")
        self.mic_btn.setText("Micro: sí" if ph.mic else "Micro: no")
        self.glass_btn.setText("Cristal" if ph.glass else "Noche")
        style(self.brain_btn, on=ph.brain == "phone")
        style(self.mic_btn, on=ph.mic)
        style(self.glass_btn, on=ph.glass)
        for b in (self.sleep_btn, self.brain_btn, self.mic_btn, self.glass_btn):
            b.setEnabled(online)

    def _refresh_pc(self) -> None:
        t = self.ctx.state.telemetry
        if t is None:
            for value, bar in self.rows.values():
                value.setText("—")
                bar.set_value(None)
            self.upcoming.setText("")
            return
        used, total = t.get("ram_used"), t.get("ram_total")
        gpu_temp, vram_used, vram_total = t.get("gpu_temp"), t.get("vram_used"), t.get("vram_total")
        self.rows["cpu"][0].setText(f"{t['cpu']} %")
        self.rows["cpu"][1].set_value(t["cpu"] / 100)
        self.rows["ram"][0].setText(_gb(used))
        self.rows["ram"][1].set_value(used / total if used is not None and total else None)
        self.rows["gpu"][0].setText("—" if gpu_temp is None else f"{gpu_temp} °C")
        self.rows["gpu"][1].set_value(None if gpu_temp is None else min(1.0, gpu_temp / 90))
        self.rows["vram"][0].setText(_gb(vram_used))
        self.rows["vram"][1].set_value(
            vram_used / vram_total if vram_used is not None and vram_total else None
        )
        up = t.get("upcoming") or []
        if up:
            first = up[0]
            mins, secs = divmod(int(first["due_in"]), 60)
            self.upcoming.setText(
                f"{first['text']} · {mins}:{secs:02d}" + (f"  +{len(up) - 1}" if len(up) > 1 else "")
            )
        else:
            self.upcoming.setText("")

    def _refresh_media(self, force: bool = False) -> bool:
        m = self.ctx.state.media
        for b in (self.prev_btn, self.play_btn, self.next_btn):
            b.setEnabled(m is not None)
        if m is None:
            self.song.setText("Nada suena")
            self.artist.setText("")
            self.art.setPixmap(QPixmap())
            return False
        self.song.setText(m.get("title", ""))
        self.artist.setText(" · ".join(x for x in (m.get("artist", ""), m.get("app", "")) if x))
        self.play_btn.setText("Pausa" if m.get("playing") else "Play")
        if m.get("art"):
            pix = self.ctx.images.get(f"/app/media/art/{m['art']}.jpg")
            if pix is not None:
                self.art.setPixmap(
                    pix.scaled(
                        64,
                        64,
                        Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                        Qt.TransformationMode.SmoothTransformation,
                    )
                )
        return False

    def _refresh_notes(self, force: bool = False) -> bool:
        items = self.ctx.state.notifications
        key = tuple(
            (
                n["id"],
                bool(self.ctx.images.get(f"/app/notifications/icon/{n['icon']}.png"))
                if n.get("icon")
                else False,
            )
            for n in items
        )
        if key == self._notes_key and not force:
            return False
        self._notes_key = key
        while self.notes_layout.count() > 1:
            w = self.notes_layout.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        self.clear_notes.setVisible(bool(items))
        if not items:
            empty = QLabel("Sin avisos")
            empty.setObjectName("faint")
            self.notes_layout.insertWidget(0, empty)
        for i, n in enumerate(items):
            self.notes_layout.insertWidget(i, self._note_row(n))
        return False

    def _note_row(self, n: dict[str, Any]) -> QWidget:
        row = QWidget()
        row.setStyleSheet(f"background: #121214; border: 1px solid {theme.LINE}; border-radius: 12px;")
        lay = QHBoxLayout(row)
        lay.setContentsMargins(10, 8, 6, 8)
        icon: QLabel
        pix = self.ctx.images.get(f"/app/notifications/icon/{n['icon']}.png") if n.get("icon") else None
        if pix is not None:
            icon = QLabel()
            icon.setPixmap(
                pix.scaled(
                    30, 30, Qt.AspectRatioMode.KeepAspectRatio, Qt.TransformationMode.SmoothTransformation
                )
            )
            icon.setFixedSize(30, 30)
        else:
            icon = badge(n["app"])
        icon.setStyleSheet("background: transparent; border: none;" if pix is not None else icon.styleSheet())
        text = QVBoxLayout()
        text.setSpacing(1)
        head = QLabel(f"{n['app']} · {ago(n['at'])}")
        head.setStyleSheet(f"color: {theme.INK2}; font-size: 11px; background: transparent; border: none;")
        title = QLabel(n["title"])
        title.setStyleSheet("font-weight: 700; background: transparent; border: none;")
        body = QLabel(n.get("body", ""))
        body.setWordWrap(True)
        body.setStyleSheet(f"color: {theme.INK2}; background: transparent; border: none;")
        for w in (head, title, body):
            # Sin ancho propio: el texto se parte según el espacio que haya (si no, empuja la fila).
            w.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Minimum)
            w.setMinimumWidth(0)
            text.addWidget(w)
        close = QPushButton("×")
        close.setFixedSize(28, 28)
        close.setStyleSheet(f"border: none; color: {theme.INK2}; font-size: 22px; font-weight: 700;")
        close.clicked.connect(
            lambda _=False, nid=n["id"]: self.ctx.send({"type": "notification_dismiss", "id": nid})
        )
        lay.addWidget(icon, 0, Qt.AlignmentFlag.AlignTop)
        lay.addLayout(text, 1)
        lay.addWidget(close, 0, Qt.AlignmentFlag.AlignTop)
        return row

    def _refresh_keys(self) -> None:
        actions = self.ctx.state.actions
        key = tuple((a["id"], a["label"], bool(a.get("quick"))) for a in actions)
        if key == self._actions_key:
            return
        self._actions_key = key
        while self.keys_row.count():
            w = self.keys_row.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        quick = [a for a in actions if a.get("quick")][:6]
        for i, a in enumerate(quick):
            b = QPushButton(a["label"])
            b.setObjectName("key")
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.clicked.connect(lambda _=False, aid=a["id"]: self.ctx.press(aid))
            self.keys_row.addWidget(b, i // 3, i % 3)
        rest = [a for a in actions if not a.get("quick")]
        self.more_button.setVisible(bool(rest))
        menu = QMenu(self.more_button)
        for a in rest:
            menu.addAction(a["label"], lambda aid=a["id"]: self.ctx.press(aid))
        self.more_button.setMenu(menu)
