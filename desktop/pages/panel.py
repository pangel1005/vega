"""Panel principal con el diseño de Vega 2: la misma pantalla que el teléfono, en grande.

La cara sobre su mosaico de color (de día) o con ojos de luz (de noche), la hora, el PC y su gráfica, el
teléfono,
lo que suena, los avisos del PC y las teclas con relieve.
"""

from __future__ import annotations

import time
from typing import Any

from PySide6.QtCore import QRectF, QSize, Qt, QTimer
from PySide6.QtGui import QIcon, QPainter, QPainterPath, QPixmap
from PySide6.QtWidgets import (
    QFrame,
    QGridLayout,
    QHBoxLayout,
    QLabel,
    QMenu,
    QPushButton,
    QScrollArea,
    QSizePolicy,
    QToolButton,
    QVBoxLayout,
    QWidget,
)

from desktop import theme
from desktop.context import Context
from desktop.widgets import Card, ColorTile, Dot, DotBar, FaceWidget, Spark, ago, badge, icon, pill, style

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
DAYS = ("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
MONTHS = ("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")
KEY_COLORS = {"red": "red", "orange": "coral", "amber": "sun", "green": "mint", "teal": "sky"}
MAX_KEYS = 7


def _gb(x: float | None) -> str:
    return "—" if x is None else f"{x:.1f} GB".replace(".", ",")


def _rounded(pix: QPixmap, size: int, radius: float) -> QPixmap:
    """La imagen recortada en cuadrado con esquinas redondas (carátulas e iconos)."""
    out = QPixmap(size * 2, size * 2)
    out.setDevicePixelRatio(2)
    out.fill(Qt.GlobalColor.transparent)
    p = QPainter(out)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setRenderHint(QPainter.RenderHint.SmoothPixmapTransform)
    path = QPainterPath()
    path.addRoundedRect(QRectF(0, 0, size, size), radius, radius)
    p.setClipPath(path)
    scaled = pix.scaled(
        size * 2,
        size * 2,
        Qt.AspectRatioMode.KeepAspectRatioByExpanding,
        Qt.TransformationMode.SmoothTransformation,
    )
    scaled.setDevicePixelRatio(2)
    p.drawPixmap(
        QRectF(0, 0, size, size),
        scaled,
        QRectF((scaled.width() - size * 2) / 2, (scaled.height() - size * 2) / 2, size * 2, size * 2),
    )
    p.end()
    return out


class PanelPage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        self._notes_key: tuple[Any, ...] | None = None
        self._actions_key: tuple[Any, ...] | None = None
        self._last_tel: Any = None
        self._pal: theme.Pal | None = None
        grid = QGridLayout(self)
        grid.setContentsMargins(18, 18, 18, 18)
        grid.setSpacing(14)
        for col, stretch in enumerate((8, 7, 8, 9)):
            grid.setColumnStretch(col, stretch)
        grid.setColumnMinimumWidth(3, 290)
        grid.addWidget(self._face_tile(), 0, 0, 2, 1)
        grid.addWidget(self._clock_tile(), 0, 1)
        grid.addWidget(self._pc_card(), 0, 2)
        grid.addWidget(self._phone_card(), 1, 1)
        grid.addWidget(self._media_card(), 1, 2)
        grid.addWidget(self._notes_card(), 0, 3, 3, 1)
        grid.addLayout(self._keys_strip(), 2, 0, 1, 3)
        grid.setRowStretch(0, 1)
        grid.setRowStretch(1, 1)
        ctx.changed.connect(self.refresh)
        theme.signals.changed.connect(self._repaint_all)
        ctx.images.loaded.connect(lambda *_: self._on_image())
        self._clock = QTimer(self)
        self._clock.timeout.connect(self._refresh_clock)
        self._clock.start(5_000)
        self.refresh()

    # ----------------------------- Construcción -----------------------------

    def _face_tile(self) -> ColorTile:
        tile = ColorTile()
        body = QVBoxLayout(tile)
        body.setContentsMargins(22, 18, 22, 20)
        body.setSpacing(6)
        top = QHBoxLayout()
        self.dot = Dot()
        self.mood_label = QLabel()
        self.mood_label.setObjectName("title")
        self.name_label = QLabel()
        self.name_label.setObjectName("eyebrow")
        top.addWidget(self.dot)
        top.addSpacing(4)
        top.addWidget(self.mood_label)
        top.addStretch(1)
        top.addWidget(self.name_label)
        body.addLayout(top)
        self.face = FaceWidget()
        body.addWidget(self.face, 1)
        self.confirm_bar = QWidget()
        cb = QHBoxLayout(self.confirm_bar)
        cb.setContentsMargins(0, 0, 0, 0)
        self.confirm_text = QLabel()
        self.confirm_text.setWordWrap(True)
        self.confirm_yes = pill("Confirmar", danger=True)
        self.confirm_no = pill("Cancelar")
        cb.addWidget(self.confirm_text, 1)
        cb.addWidget(self.confirm_yes)
        cb.addWidget(self.confirm_no)
        self.confirm_yes.clicked.connect(
            lambda: self.ctx.state.pending and self.ctx.confirm(self.ctx.state.pending["nonce"])
        )
        self.confirm_no.clicked.connect(self._cancel_pending)
        body.addWidget(self.confirm_bar)
        self.asked = QLabel()
        self.asked.setWordWrap(True)
        self.answer = QLabel()
        self.answer.setWordWrap(True)
        self.answer.setMinimumHeight(48)
        self.answer.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Minimum)
        body.addWidget(self.asked)
        body.addWidget(self.answer)
        self.face_tile = tile
        return tile

    def _clock_tile(self) -> Card:
        card = Card()
        self.time_label = QLabel()
        self.time_label.setObjectName("big")
        self.date_label = QLabel()
        self.date_label.setStyleSheet("font-size: 17px;")
        status = QHBoxLayout()
        self.link_dot = Dot()
        self.link_label = QLabel()
        status.addWidget(self.link_dot)
        status.addWidget(self.link_label, 1)
        self.upcoming = QLabel()
        self.upcoming.setWordWrap(True)
        style(self.upcoming, tone="sun")
        card.body.addWidget(self.time_label)
        card.body.addWidget(self.date_label)
        card.body.addStretch(1)
        card.body.addWidget(self.upcoming)
        card.body.addLayout(status)
        return card

    def _pc_card(self) -> Card:
        card = Card()
        head = QHBoxLayout()
        self.pc_dot = Dot()
        self.pc_title = QLabel("PC")
        self.pc_title.setObjectName("title")
        self.cpu_big = QLabel("—")
        self.cpu_big.setObjectName("value")
        head.addWidget(self.pc_dot)
        head.addWidget(self.pc_title)
        head.addStretch(1)
        head.addWidget(self.cpu_big)
        card.body.addLayout(head)
        self.spark = Spark()
        card.body.addWidget(self.spark, 1)
        self.rows: dict[str, tuple[QLabel, DotBar]] = {}
        rows = QGridLayout()
        rows.setHorizontalSpacing(10)
        rows.setVerticalSpacing(6)
        for i, (key, label) in enumerate(
            (("cpu", "Procesador"), ("ram", "Memoria"), ("gpu", "Gráfica"), ("vram", "Vídeo"))
        ):
            name = QLabel(label)
            name.setObjectName("muted")
            value = QLabel("—")
            value.setAlignment(Qt.AlignmentFlag.AlignRight | Qt.AlignmentFlag.AlignVCenter)
            value.setMinimumWidth(62)
            bar = DotBar()
            rows.addWidget(name, i, 0)
            rows.addWidget(bar, i, 1)
            rows.addWidget(value, i, 2)
            self.rows[key] = (value, bar)
        rows.setColumnStretch(1, 1)
        card.body.addLayout(rows)
        return card

    def _phone_card(self) -> Card:
        card = Card()
        head = QHBoxLayout()
        self.phone_dot = Dot()
        title = QLabel("Teléfono")
        title.setObjectName("title")
        self.battery_label = QLabel("—")
        self.battery_label.setObjectName("value")
        head.addWidget(self.phone_dot)
        head.addWidget(title)
        head.addStretch(1)
        head.addWidget(self.battery_label)
        card.body.addLayout(head)
        self.battery = DotBar()
        card.body.addWidget(self.battery)
        self.phone_info = QLabel()
        self.phone_info.setObjectName("muted")
        self.phone_info.setWordWrap(True)
        self.phone_info.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Minimum)
        card.body.addWidget(self.phone_info)
        card.body.addStretch(1)
        grid = QGridLayout()
        grid.setSpacing(8)
        self.sleep_btn = pill("Dormir")
        self.brain_btn = pill("IA: PC")
        self.mic_btn = pill("Micro")
        self.glass_btn = pill("Cristal")
        for i, b in enumerate((self.sleep_btn, self.brain_btn, self.mic_btn, self.glass_btn)):
            grid.addWidget(b, i // 2, i % 2)
        card.body.addLayout(grid)
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

    def _media_card(self) -> Card:
        card = Card()
        self.media_head = QLabel("SONANDO")
        self.media_head.setObjectName("eyebrow")
        card.body.addWidget(self.media_head)
        row = QHBoxLayout()
        row.setSpacing(14)
        self.art = QLabel()
        self.art.setFixedSize(84, 84)
        text = QVBoxLayout()
        text.setSpacing(2)
        self.song = QLabel("Nada suena")
        self.song.setStyleSheet("font-weight: 700; font-size: 17px;")
        self.song.setWordWrap(True)
        self.song.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Minimum)
        self.artist = QLabel()
        self.artist.setObjectName("muted")
        self.artist.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Minimum)
        text.addStretch(1)
        text.addWidget(self.song)
        text.addWidget(self.artist)
        text.addStretch(1)
        row.addWidget(self.art)
        row.addLayout(text, 1)
        card.body.addLayout(row)
        card.body.addStretch(1)
        controls = QHBoxLayout()
        controls.setSpacing(12)
        self.prev_btn, self.play_btn, self.next_btn = QPushButton(), QPushButton(), QPushButton()
        for b, cmd, tip in (
            (self.prev_btn, "previous", "Anterior"),
            (self.play_btn, "play_pause", "Pausa / play"),
            (self.next_btn, "next", "Siguiente"),
        ):
            b.setObjectName("round")
            b.setToolTip(tip)
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.clicked.connect(lambda _=False, c=cmd: self.ctx.send({"type": "media_control", "command": c}))
        style(self.play_btn, accent=True)
        controls.addStretch(1)
        for b in (self.prev_btn, self.play_btn, self.next_btn):
            controls.addWidget(b)
        controls.addStretch(1)
        card.body.addLayout(controls)
        return card

    def _notes_card(self) -> Card:
        card = Card("Avisos")
        self.notes_count = QLabel()
        self.notes_count.setObjectName("muted")
        self.clear_notes = pill("Borrar todo")
        self.clear_notes.setToolTip("Quita todos los avisos del PC (también del teléfono)")
        self.clear_notes.clicked.connect(lambda: self.ctx.send({"type": "notification_dismiss"}))
        card.add_header_widget(self.notes_count)
        card.add_header_widget(self.clear_notes)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)  # el texto se ajusta
        self.notes_holder = QWidget()
        self.notes_layout = QVBoxLayout(self.notes_holder)
        self.notes_layout.setContentsMargins(0, 0, 0, 0)
        self.notes_layout.setSpacing(8)
        self.notes_layout.addStretch(1)
        scroll.setWidget(self.notes_holder)
        card.body.addWidget(scroll, 1)
        return card

    def _keys_strip(self) -> QHBoxLayout:
        strip = QHBoxLayout()
        strip.setSpacing(10)
        self.keys_row = QHBoxLayout()  # solo las teclas de los botones (las pruebas las cuentan)
        self.keys_row.setSpacing(10)
        strip.addLayout(self.keys_row, 1)
        self.more_button = QToolButton()
        self.more_button.setObjectName("key")
        self.more_button.setText("Más")
        self.more_button.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
        self.more_button.setPopupMode(QToolButton.ToolButtonPopupMode.InstantPopup)
        self.more_button.setIconSize(QSize(34, 34))
        self.more_button.setFixedWidth(96)
        self.more_button.setCursor(Qt.CursorShape.PointingHandCursor)
        strip.addWidget(self.more_button)
        return strip

    # ----------------------------- Actualización -----------------------------

    def _repaint_all(self) -> None:
        self._actions_key = None
        self._notes_key = None
        self.refresh()

    def _on_image(self) -> None:
        self._actions_key = None
        self._refresh_keys()
        self._refresh_media()
        self._refresh_notes(force=True)

    def refresh(self) -> None:
        self._refresh_face()
        self._refresh_clock()
        self._refresh_conversation()
        self._refresh_confirm()
        self._refresh_phone()
        self._refresh_pc()
        self._refresh_media()
        self._refresh_notes()
        self._refresh_keys()

    def _refresh_face(self) -> None:
        s = self.ctx.state
        mood = s.mood
        p = theme.P
        accent = theme.face_accent(mood)
        bg = p.tile if p.night else accent
        text = p.ink if p.night else theme.on_color(bg)
        eyes = accent if p.night else text
        self.face_tile.set_color(bg, animate=p is self._pal)
        self._pal = p
        self.face.set_mood(mood, eyes, p.night)
        self.mood_label.setText(MOOD_LABEL[mood].format(name=s.assistant))
        self.name_label.setText(s.assistant.upper())
        self.dot.set_color(text if not p.night else accent)
        soft = theme.mix(text, bg, 0.28)
        self.mood_label.setStyleSheet(f"color: {text};")
        self.name_label.setStyleSheet(f"color: {soft}; letter-spacing: 2px;")
        self.asked.setStyleSheet(f"color: {soft}; font-size: 13px;")
        self.answer.setStyleSheet(f"color: {text}; font-size: 18px; font-weight: 500;")
        self.confirm_text.setStyleSheet(f"color: {text}; font-weight: 700;")

    def _refresh_clock(self) -> None:
        now = time.localtime()
        self.time_label.setText(time.strftime("%H:%M", now))
        self.date_label.setText(f"{DAYS[now.tm_wday]} {now.tm_mday} {MONTHS[now.tm_mon - 1]}")
        s = self.ctx.state
        self.link_dot.set_color(theme.MINT if s.connected else theme.RED)
        self.link_label.setText("En línea" if s.connected else "Servidor parado")

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
            theme.MINT if online and not ph.sleeping else theme.SUN if online else theme.INK3
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
        self.battery_label.setText(f"{ph.battery} %" if online and ph.battery >= 0 else "—")
        self.battery.set_value(
            ph.battery / 100 if online and ph.battery >= 0 else None, invert=True, charging=ph.charging
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
            self.cpu_big.setText("—")
            self.pc_title.setText("PC")
            self.pc_dot.set_color(theme.INK3)
            return
        used, total = t.get("ram_used"), t.get("ram_total")
        gpu_temp, vram_used, vram_total = t.get("gpu_temp"), t.get("vram_used"), t.get("vram_total")
        cpu = t["cpu"]
        self.rows["cpu"][0].setText(f"{cpu} %")
        self.rows["cpu"][1].set_value(cpu / 100)
        self.rows["ram"][0].setText(_gb(used))
        self.rows["ram"][1].set_value(used / total if used is not None and total else None)
        self.rows["gpu"][0].setText("—" if gpu_temp is None else f"{gpu_temp} °C")
        self.rows["gpu"][1].set_value(None if gpu_temp is None else min(1.0, gpu_temp / 90))
        self.rows["vram"][0].setText(_gb(vram_used))
        self.rows["vram"][1].set_value(
            vram_used / vram_total if vram_used is not None and vram_total else None
        )
        self.cpu_big.setText(f"{cpu} %")
        hot = (gpu_temp or 0) >= 80 or cpu >= 90 or (used is not None and total and used / total >= 0.92)
        self.pc_title.setText("PC caliente" if hot else "PC bien")
        self.pc_dot.set_color(theme.RED if hot else theme.MINT)
        if t is not self._last_tel:  # una lectura nueva: un punto más en la gráfica
            self._last_tel = t
            gpu = t.get("gpu_util")
            self.spark.push(cpu / 100, None if gpu is None else gpu / 100)
        up = t.get("upcoming") or []
        if up:
            first = up[0]
            mins, secs = divmod(int(first["due_in"]), 60)
            self.upcoming.setText(
                f"⏰ {first['text']} · {mins}:{secs:02d}" + (f"  +{len(up) - 1}" if len(up) > 1 else "")
            )
        else:
            self.upcoming.setText("")

    def _refresh_media(self) -> None:
        m = self.ctx.state.media
        for b in (self.prev_btn, self.play_btn, self.next_btn):
            b.setEnabled(m is not None)
        playing = bool(m and m.get("playing"))
        self.prev_btn.setIcon(icon("prev", theme.INK, 40))
        self.next_btn.setIcon(icon("next", theme.INK, 40))
        self.play_btn.setIcon(icon("pause" if playing else "play", theme.on_color(theme.CORAL), 52))
        self.prev_btn.setIconSize(QSize(22, 22))
        self.next_btn.setIconSize(QSize(22, 22))
        self.play_btn.setIconSize(QSize(26, 26))
        art_pix: QPixmap | None = None
        if m is None:
            self.media_head.setText("SONANDO")
            self.song.setText("Nada suena")
            self.artist.setText("")
        else:
            state = "SONANDO" if playing else "EN PAUSA"
            self.media_head.setText(f"{state} · {m.get('app', '').upper()}" if m.get("app") else state)
            self.song.setText(m.get("title", ""))
            self.artist.setText(m.get("artist", ""))
            if m.get("art"):
                art_pix = self.ctx.images.get(f"/app/media/art/{m['art']}.jpg")
        self.media_head.setStyleSheet(f"color: {theme.readable(theme.CORAL)};")
        if art_pix is not None:
            self.art.setPixmap(_rounded(art_pix, 84, 20))
        else:  # sin carátula: una nota sobre un mosaico de color
            pix = QPixmap(168, 168)
            pix.setDevicePixelRatio(2)
            pix.fill(Qt.GlobalColor.transparent)
            p = QPainter(pix)
            p.setRenderHint(QPainter.RenderHint.Antialiasing)
            p.setPen(Qt.PenStyle.NoPen)
            p.setBrush(theme.color(theme.CORAL if m is not None else theme.TILE2))
            p.drawRoundedRect(QRectF(0, 0, 84, 84), 20, 20)
            ink = theme.on_color(theme.CORAL) if m is not None else theme.INK3
            p.setPen(theme.color(ink))
            f = theme.sans(30, bold=True)
            p.setFont(f)
            p.drawText(QRectF(0, 0, 84, 84), Qt.AlignmentFlag.AlignCenter, "♪")
            p.end()
            self.art.setPixmap(pix)

    def _refresh_notes(self, force: bool = False) -> None:
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
            return
        self._notes_key = key
        while self.notes_layout.count() > 1:
            w = self.notes_layout.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        self.clear_notes.setVisible(bool(items))
        self.notes_count.setText(str(len(items)) if items else "")
        if not items:
            empty = QLabel("Sin avisos. Lo que te llegue al PC sale aquí y en el teléfono.")
            empty.setObjectName("faint")
            empty.setWordWrap(True)
            self.notes_layout.insertWidget(0, empty)
        for i, n in enumerate(items):
            self.notes_layout.insertWidget(i, self._note_row(n))

    def _note_row(self, n: dict[str, Any]) -> QWidget:
        row = QFrame()
        row.setObjectName("inner")
        lay = QHBoxLayout(row)
        lay.setContentsMargins(10, 10, 4, 10)
        lay.setSpacing(10)
        icon_label: QLabel
        pix = self.ctx.images.get(f"/app/notifications/icon/{n['icon']}.png") if n.get("icon") else None
        if pix is not None:
            icon_label = QLabel()
            icon_label.setPixmap(_rounded(pix, 32, 16))
            icon_label.setFixedSize(32, 32)
        else:
            icon_label = badge(n["app"], 32)
        text = QVBoxLayout()
        text.setSpacing(1)
        head = QLabel(n["app"].upper())
        head.setObjectName("eyebrow")
        when = QLabel(ago(n["at"]))
        when.setObjectName("faint")
        title = QLabel(n["title"])
        title.setStyleSheet("font-weight: 700; font-size: 14px;")
        body = QLabel(n.get("body", ""))
        body.setWordWrap(True)
        body.setObjectName("muted")
        text.addWidget(head)
        for w in (head, title, body):
            # Sin ancho propio: el texto se parte según el espacio que haya (si no, empuja la fila).
            w.setSizePolicy(QSizePolicy.Policy.Ignored, QSizePolicy.Policy.Minimum)
            w.setMinimumWidth(0)
        text.addWidget(title)
        text.addWidget(body)
        text.addWidget(when)  # hace cuánto llegó, debajo: así el título y la app caben enteros
        close = QPushButton()
        close.setIcon(icon("close", theme.INK2, 28))
        close.setIconSize(QSize(16, 16))
        close.setFixedSize(28, 28)
        close.setToolTip("Quitar")
        close.setStyleSheet("background: transparent; border-radius: 14px; padding: 0;")
        close.setCursor(Qt.CursorShape.PointingHandCursor)
        close.clicked.connect(
            lambda _=False, nid=n["id"]: self.ctx.send({"type": "notification_dismiss", "id": nid})
        )
        lay.addWidget(icon_label, 0, Qt.AlignmentFlag.AlignTop)
        lay.addLayout(text, 1)
        lay.addWidget(close, 0, Qt.AlignmentFlag.AlignTop)
        return row

    def _key_icon(self, a: dict[str, Any]) -> QIcon:
        tint = getattr(theme, KEY_COLORS.get(a.get("color", ""), "coral").upper(), theme.CORAL)
        if a.get("icon") == "app":
            pix = self.ctx.images.get(f"/app/icons/{a['id']}.png")
            if pix is not None:
                return QIcon(_rounded(pix, 34, 17))
        return icon(a.get("icon", "app"), tint, 34, circle=theme.mix(tint, theme.TILE, 0.78))

    def _refresh_keys(self) -> None:
        actions = self.ctx.state.actions
        key = tuple(
            (a["id"], a["label"], bool(a.get("quick")), a.get("icon"), a.get("color")) for a in actions
        )
        if key == self._actions_key:
            return
        self._actions_key = key
        while self.keys_row.count():
            w = self.keys_row.takeAt(0).widget()
            if w is not None:
                w.setParent(None)
                w.deleteLater()
        quick = [a for a in actions if a.get("quick")][:MAX_KEYS]
        for a in quick:
            b = QToolButton()
            b.setObjectName("key")
            b.setText(a["label"])
            b.setIcon(self._key_icon(a))
            b.setIconSize(QSize(34, 34))
            b.setToolButtonStyle(Qt.ToolButtonStyle.ToolButtonTextUnderIcon)
            b.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Fixed)
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.clicked.connect(lambda _=False, aid=a["id"]: self.ctx.press(aid))
            self.keys_row.addWidget(b)
        rest = [a for a in actions if a not in quick]
        self.more_button.setIcon(icon("more", theme.INK2, 34, circle=theme.TILE2))
        self.more_button.setVisible(bool(rest))
        menu = QMenu(self.more_button)
        for a in rest:
            menu.addAction(a["label"], lambda aid=a["id"]: self.ctx.press(aid))
        self.more_button.setMenu(menu)
