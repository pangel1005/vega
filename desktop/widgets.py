"""Piezas de la app con el diseño de Vega 2: la cara dibujada, mosaicos, barras, iconos y botones."""

from __future__ import annotations

import math
import random
import time

from PySide6.QtCore import QObject, QPointF, QRectF, QSize, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QIcon, QPainter, QPainterPath, QPen, QPixmap
from PySide6.QtNetwork import QNetworkAccessManager, QNetworkReply, QNetworkRequest
from PySide6.QtWidgets import (
    QFrame,
    QHBoxLayout,
    QLabel,
    QPushButton,
    QSizePolicy,
    QVBoxLayout,
    QWidget,
)

from desktop import theme

# ----------------------------- La cara de Vega 2 -----------------------------
# Traducida de android/.../ui2/Face2.kt: dos ojos de cápsula, una boca y poco más. Cada ánimo es un conjunto
# de medidas hacia el que la cara se mueve con suavidad. Las medidas van en «unidades de cara» (1,0 de ancho).

_TARGETS: dict[str, dict[str, float]] = {
    "idle": {},
    "listen": {"ew": 0.19, "eh": 0.35, "ey": 0.25, "smile": 0.0, "open": 0.2, "mw": 0.05, "listen": 1.0},
    "think": {
        "ehL": 0.45,
        "ey": 0.26,
        "smile": -0.05,
        "mw": 0.08,
        "tiltL": -10.0,
        "tiltR": 6.0,
        "think": 1.0,
    },
    "talk": {"smile": 0.25, "mw": 0.16},
    "happy": {"arch": 1.0, "smile": 0.9, "mw": 0.2, "eh": 0.18},
    "sleep": {"eh": 0.025, "smile": 0.0, "mw": 0.05, "sleep": 1.0, "ey": 0.3},
    "error": {"cross": 1.0, "smile": -0.7, "mw": 0.16},
    "alert": {"ew": 0.19, "eh": 0.34, "open": 0.18, "mw": 0.05, "smile": 0.0},
    "offline": {"eh": 0.04, "smile": 0.0, "mw": 0.1},
}
_REST = {
    "ew": 0.17, "eh": 0.29, "gap": 0.23, "ey": 0.27, "tiltL": 0.0, "tiltR": 0.0, "ehL": 1.0, "ehR": 1.0,
    "smile": 0.35, "open": 0.0, "mw": 0.14, "arch": 0.0, "cross": 0.0,
    "listen": 0.0, "think": 0.0, "sleep": 0.0,
}  # fmt: skip


class FaceWidget(QWidget):
    """La cara de Vega sobre su mosaico: de día los ojos son tinta sobre color; de noche, luz."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._mood = "offline"
        self._ink = QColor(theme.INK)
        self._night = theme.P.night
        self._v = dict(_REST)
        self._t = 0.0
        self._look = [0.0, 0.0]
        self._goal = [0.0, 0.0]
        self._look_timer = 1.0
        self._blink = 0.0
        self._blink_t = -1.0
        self._next_blink = 2.0
        self._level = 0.0
        self._last = time.monotonic()
        self._rng = random.Random()
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self.setMinimumSize(220, 120)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._step)
        self._timer.start(33)

    def set_mood(self, mood: str, ink: str, night: bool) -> None:
        self._mood = mood if mood in _TARGETS else "idle"
        self._ink = QColor(ink)
        self._night = night
        self.update()

    def glance(self, x: float, y: float) -> None:
        self._goal = [max(-1.0, min(1.0, x)), max(-1.0, min(1.0, y))]
        self._look_timer = 1.3

    def _step(self) -> None:
        now = time.monotonic()
        dt = min(0.1, now - self._last)
        self._last = now
        self._t += dt
        target = dict(_REST)
        target.update(_TARGETS.get(self._mood, {}))
        if self._mood == "talk":
            # Sin el volumen real, la boca se mueve con un vaivén de voz.
            self._level += ((0.35 + 0.65 * abs(math.sin(self._t * 7))) - self._level) * min(1.0, dt * 10)
            target["open"] = max(
                0.0, min(1.0, self._level * 0.9 + 0.1 * (0.5 + 0.5 * math.sin(self._t * 12)))
            )
        k = min(1.0, dt * 9)
        for key, goal in target.items():
            self._v[key] += (goal - self._v[key]) * k
        self._look_timer -= dt
        if self._look_timer <= 0:
            self._look_timer = 0.8 + self._rng.random() * 2.6
            wander = self._mood in ("idle", "talk", "alert", "happy")
            self._goal = [
                self._rng.uniform(-1, 1) if wander and self._rng.random() < 0.7 else 0.0,
                self._rng.uniform(-0.5, 0.5) if wander and self._rng.random() < 0.5 else 0.0,
            ]
        if self._mood == "think":
            self._goal = [0.7, -0.7]
        kl = min(1.0, dt * 10)
        self._look = [self._look[i] + (self._goal[i] - self._look[i]) * kl for i in (0, 1)]
        self._next_blink -= dt
        if self._next_blink <= 0 and self._mood not in ("sleep", "offline", "error"):
            self._next_blink = 2.2 + self._rng.random() * 3.4
            self._blink_t = 0.0
        if self._blink_t >= 0:
            self._blink_t += dt
            if self._blink_t > 0.17:
                self._blink_t, self._blink = -1.0, 0.0
            else:
                self._blink = math.sin(math.pi * self._blink_t / 0.17)
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        paint_face(p, QRectF(self.rect()), self._v, self._look, self._blink, self._t, self._ink, self._night)
        p.end()


def _ink(c: QColor, alpha: float) -> QColor:
    out = QColor(c)
    out.setAlphaF(max(0.0, min(1.0, c.alphaF() * alpha)))
    return out


def paint_face(
    p: QPainter,
    area: QRectF,
    v: dict[str, float],
    look: list[float],
    blink: float,
    t: float,
    ink: QColor,
    night: bool,
) -> None:
    """Dibuja la cara en `area` (como `drawFace` del teléfono)."""
    s = min(area.width() * 1.08, area.height() / 0.58)
    ox = area.x() + (area.width() - s) / 2
    oy = area.y() + (area.height() - s * 0.58) / 2

    def px(x: float) -> float:
        return ox + x * s

    def py(y: float) -> float:
        return oy + y * s

    p.setPen(Qt.PenStyle.NoPen)
    if v["listen"] > 0.02:  # al escuchar, unos aros se abren desde la cara
        for k in range(3):
            ph = (t * 0.7 + k / 3) % 1
            pen = QPen(_ink(ink, (1 - ph) * 0.22 * v["listen"]), 0.012 * s)
            p.setPen(pen)
            p.setBrush(Qt.BrushStyle.NoBrush)
            r = (0.14 + ph * 0.3) * s
            p.drawEllipse(QPointF(px(0.5), py(0.29)), r, r)
        p.setPen(Qt.PenStyle.NoPen)

    for sg in (-1, 1):
        h_mul = v["ehL"] if sg < 0 else v["ehR"]
        tilt = v["tiltL"] if sg < 0 else v["tiltR"]
        cx = 0.5 + sg * v["gap"] / 2 + look[0] * 0.028
        cy = v["ey"] + look[1] * 0.02
        w = v["ew"]
        h = v["eh"] * h_mul * (1 - blink * 0.93)
        fill = (1 - v["arch"]) * (1 - v["cross"])
        if fill > 0.02:
            p.save()
            p.translate(px(cx), py(cy))
            p.rotate(tilt)
            r = min(w, h) * s / 2
            if night:  # de noche los ojos son luz: un halo suave alrededor
                p.setBrush(_ink(ink, 0.16 * fill))
                halo = QRectF((-w / 2 - 0.014) * s, (-h / 2 - 0.014) * s, (w + 0.028) * s, (h + 0.028) * s)
                p.drawRoundedRect(halo, r + 0.014 * s, r + 0.014 * s)
            p.setBrush(_ink(ink, fill))
            p.drawRoundedRect(QRectF(-w / 2 * s, -h / 2 * s, w * s, h * s), r, r)
            if h > 0.08:
                p.setBrush(QColor(255, 255, 255, round(0.85 * fill * 255)))
                p.drawEllipse(
                    QPointF((w * 0.16 + look[0] * 0.01) * s, -h * 0.27 * s), w * s * 0.15, w * s * 0.15
                )
            p.restore()
        if v["arch"] > 0.02:  # contenta: los ojos se hacen dos arcos
            pen = QPen(_ink(ink, v["arch"]), w * s * 0.42)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            p.setPen(pen)
            p.setBrush(Qt.BrushStyle.NoBrush)
            rect = QRectF(px(cx - w * 0.62), py(cy - w * 0.1), w * 1.24 * s, w * 1.1 * s)
            p.drawArc(rect, 20 * 16, 140 * 16)
            p.setPen(Qt.PenStyle.NoPen)
        if v["cross"] > 0.02:  # error: los ojos son dos aspas
            pen = QPen(_ink(ink, v["cross"]), w * s * 0.28)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            p.setPen(pen)
            half = w * 0.35
            p.drawLine(QPointF(px(cx - half), py(cy - half)), QPointF(px(cx + half), py(cy + half)))
            p.drawLine(QPointF(px(cx + half), py(cy - half)), QPointF(px(cx - half), py(cy + half)))
            p.setPen(Qt.PenStyle.NoPen)

    # Boca: una sonrisa que se abre con la voz.
    my = 0.52 + look[1] * 0.01
    mx = 0.5 + look[0] * 0.015
    open_a = max(0.0, min(1.0, v["open"] * 7))
    if open_a > 0.02:
        mh = 0.02 + v["open"] * 0.13
        mw = v["mw"] * (0.8 + 0.4 * v["open"])
        p.setBrush(_ink(ink, open_a))
        rr = min(mw, mh) * s / 2
        p.drawRoundedRect(QRectF(px(mx - mw / 2), py(my - mh / 2), mw * s, mh * s), rr, rr)
    if open_a < 0.98:
        path = QPainterPath(QPointF(px(mx - v["mw"] / 2), py(my)))
        path.quadTo(QPointF(px(mx), py(my + v["smile"] * 0.16)), QPointF(px(mx + v["mw"] / 2), py(my)))
        pen = QPen(_ink(ink, 1 - open_a), 0.026 * s)
        pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
        p.setPen(pen)
        p.setBrush(Qt.BrushStyle.NoBrush)
        p.drawPath(path)
        p.setPen(Qt.PenStyle.NoPen)

    if v["think"] > 0.02:  # pensando: tres puntos que rebotan
        for i in range(3):
            b = max(0.0, math.sin((t * 2.4 - i * 0.4) * math.pi / 1.6))
            p.setBrush(_ink(ink, v["think"] * (0.35 + 0.65 * b)))
            p.drawEllipse(QPointF(px(0.8 + i * 0.055), py(0.07 - 0.025 * b)), 0.014 * s, 0.014 * s)
    if v["sleep"] > 0.02:  # dormida: zetas que suben y se apagan
        for k in range(2):
            ph = (t * 0.35 + k * 0.5) % 1
            x, y = px(0.8 + k * 0.07), py(0.2 - ph * 0.18)
            z = (0.04 + k * 0.02) * s
            path = QPainterPath(QPointF(x, y))
            path.lineTo(x + z, y)
            path.lineTo(x, y + z)
            path.lineTo(x + z, y + z)
            pen = QPen(_ink(ink, (1 - ph) * v["sleep"]), 0.012 * s)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            p.setPen(pen)
            p.setBrush(Qt.BrushStyle.NoBrush)
            p.drawPath(path)
            p.setPen(Qt.PenStyle.NoPen)


# ----------------------------- Iconos dibujados -----------------------------


def draw_icon(p: QPainter, name: str, rect: QRectF, color: QColor) -> None:
    """Iconos de trazo redondo, como los de Icons2.kt del teléfono."""
    s = min(rect.width(), rect.height())
    cx, cy = rect.center().x(), rect.center().y()
    pen = QPen(color, max(1.6, s * 0.1))
    pen.setCapStyle(Qt.PenCapStyle.RoundCap)
    pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
    p.setPen(pen)
    p.setBrush(Qt.BrushStyle.NoBrush)
    u = s / 24

    def pt(x: float, y: float) -> QPointF:
        return QPointF(cx + (x - 12) * u, cy + (y - 12) * u)

    if name == "power":
        p.drawArc(QRectF(pt(5, 5), pt(19, 19)), 120 * 16, 300 * 16)
        p.drawLine(pt(12, 3.5), pt(12, 11))
    elif name in ("restart", "cancel"):
        p.drawArc(QRectF(pt(5, 5), pt(19, 19)), 60 * 16, 290 * 16)
        if name == "restart":
            p.drawLine(pt(17.5, 4.5), pt(17.5, 8.5))
            p.drawLine(pt(17.5, 8.5), pt(13.5, 8.5))
        else:
            p.drawLine(pt(9, 9), pt(15, 15))
    elif name == "lock":
        p.drawRoundedRect(QRectF(pt(6, 11), pt(18, 20)), 2.5 * u, 2.5 * u)
        p.drawArc(QRectF(pt(8.5, 4.5), pt(15.5, 13.5)), 0, 180 * 16)
    elif name in ("mute", "volume-up", "volume-down"):
        path = QPainterPath(pt(4, 10))
        for x, y in ((8, 10), (12.5, 6), (12.5, 18), (8, 14), (4, 14)):
            path.lineTo(pt(x, y))
        path.closeSubpath()
        p.drawPath(path)
        if name == "mute":
            p.drawLine(pt(16, 9.5), pt(20.5, 14.5))
            p.drawLine(pt(20.5, 9.5), pt(16, 14.5))
        else:
            p.drawLine(pt(16, 12), pt(21, 12))
            if name == "volume-up":
                p.drawLine(pt(18.5, 9.5), pt(18.5, 14.5))
    elif name in ("play", "pause", "play-pause"):
        if name != "pause":
            path = QPainterPath(pt(7, 5.5))
            path.lineTo(pt(7 if name == "play" else 5, 18.5))
            path.lineTo(pt(18 if name == "play" else 13, 12))
            path.closeSubpath()
            p.setBrush(color)
            p.drawPath(path)
            p.setBrush(Qt.BrushStyle.NoBrush)
        if name != "play":
            x0 = 9 if name == "pause" else 15.5
            p.drawLine(pt(x0, 6), pt(x0, 18))
            p.drawLine(
                pt(x0 + 5 if name == "pause" else 19.5, 6), pt(x0 + 5 if name == "pause" else 19.5, 18)
            )
    elif name in ("next", "prev"):
        sign = 1 if name == "next" else -1
        path = QPainterPath(pt(12 - 5 * sign, 6))
        path.lineTo(pt(12 - 5 * sign, 18))
        path.lineTo(pt(12 + 4 * sign, 12))
        path.closeSubpath()
        p.setBrush(color)
        p.drawPath(path)
        p.drawLine(pt(12 + 6 * sign, 6), pt(12 + 6 * sign, 18))
    elif name == "mic":
        p.drawRoundedRect(QRectF(pt(9, 3.5), pt(15, 14.5)), 3 * u, 3 * u)
        p.drawArc(QRectF(pt(6, 7), pt(18, 17)), 180 * 16, 180 * 16)
        p.drawLine(pt(12, 17), pt(12, 20.5))
    elif name == "moon":
        path = QPainterPath()
        path.addEllipse(QRectF(pt(4.5, 4.5), pt(19.5, 19.5)))
        cut = QPainterPath()
        cut.addEllipse(QRectF(pt(9.5, 2), pt(22.5, 15)))
        p.setBrush(color)
        p.setPen(Qt.PenStyle.NoPen)
        p.drawPath(path.subtracted(cut))
    elif name == "sun":
        p.setBrush(color)
        p.drawEllipse(pt(12, 12), 4 * u, 4 * u)
        for i in range(8):
            a = i * math.pi / 4
            p.drawLine(
                pt(12 + 7 * math.cos(a), 12 + 7 * math.sin(a)),
                pt(12 + 9.3 * math.cos(a), 12 + 9.3 * math.sin(a)),
            )
    elif name == "auto":
        p.drawArc(QRectF(pt(4.5, 4.5), pt(19.5, 19.5)), 0, 360 * 16)
        path = QPainterPath(pt(12, 4.5))
        path.arcTo(QRectF(pt(4.5, 4.5), pt(19.5, 19.5)), 90, 180)
        path.closeSubpath()
        p.setBrush(color)
        p.drawPath(path)
    elif name == "record":
        p.setBrush(color)
        p.drawEllipse(pt(12, 12), 6 * u, 6 * u)
    elif name == "screenshot":
        p.drawRoundedRect(QRectF(pt(3.5, 7), pt(20.5, 19)), 2.5 * u, 2.5 * u)
        p.drawEllipse(pt(12, 13), 3.3 * u, 3.3 * u)
        p.drawLine(pt(9, 7), pt(10.5, 4.5))
        p.drawLine(pt(10.5, 4.5), pt(13.5, 4.5))
        p.drawLine(pt(13.5, 4.5), pt(15, 7))
    elif name == "sleep":
        path = QPainterPath(pt(6, 7))
        for x, y in ((14, 7), (6, 17), (14, 17)):
            path.lineTo(pt(x, y))
        p.drawPath(path)
        p.drawLine(pt(16, 5), pt(20, 5))
        p.drawLine(pt(20, 5), pt(16, 10))
        p.drawLine(pt(16, 10), pt(20, 10))
    elif name == "more":
        p.setBrush(color)
        p.setPen(Qt.PenStyle.NoPen)
        for x in (6, 12, 18):
            p.drawEllipse(pt(x, 12), 1.9 * u, 1.9 * u)
    elif name == "close":
        p.drawLine(pt(7, 7), pt(17, 17))
        p.drawLine(pt(17, 7), pt(7, 17))
    else:  # «app» y lo demás: un mosaico pequeño
        p.drawRoundedRect(QRectF(pt(5, 5), pt(19, 19)), 4.5 * u, 4.5 * u)
        p.drawLine(pt(9, 12), pt(15, 12))


def icon(name: str, color: str, size: int = 40, circle: str | None = None) -> QIcon:
    """Un icono (con su círculo de color detrás, como las teclas del teléfono)."""
    pix = QPixmap(size * 2, size * 2)
    pix.setDevicePixelRatio(2)
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    if circle is not None:
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(circle))
        p.drawEllipse(QRectF(0, 0, size, size))
    pad = size * (0.27 if circle is not None else 0.1)
    draw_icon(p, name, QRectF(pad, pad, size - 2 * pad, size - 2 * pad), QColor(color))
    p.end()
    return QIcon(pix)


# ----------------------------- Mosaicos y botones -----------------------------


class Card(QFrame):
    """Mosaico claro (u oscuro de noche) de esquinas muy redondas y, opcionalmente, un título."""

    def __init__(self, title: str = "", parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setObjectName("card")
        self.body = QVBoxLayout(self)
        self.body.setContentsMargins(20, 16, 20, 18)
        self.body.setSpacing(9)
        self.head = QHBoxLayout()
        self.title = QLabel(title)
        self.title.setObjectName("title")
        self.head.addWidget(self.title)
        self.head.addStretch(1)
        if title:
            self.body.addLayout(self.head)

    def add_header_widget(self, widget: QWidget) -> None:
        self.head.addWidget(widget)
        if self.body.indexOf(self.head) < 0:
            self.body.insertLayout(0, self.head)


class ColorTile(QFrame):
    """Un mosaico de color propio (el de la cara): se pinta a mano para cambiar de color con suavidad."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._color = QColor(theme.SUN)
        self._goal = QColor(theme.SUN)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._fade)

    def set_color(self, hex_code: str, animate: bool = True) -> None:
        goal = QColor(hex_code)
        if goal == self._goal and (animate or goal == self._color):
            return
        self._goal = goal
        if animate:
            self._timer.start(16)
        else:  # al pasar de día a noche, de golpe (como el resto de la pantalla)
            self._timer.stop()
            self._color = QColor(goal)
            self.update()

    def _fade(self) -> None:
        c, g = self._color, self._goal
        k = 0.18
        self._color = QColor(
            round(c.red() + (g.red() - c.red()) * k),
            round(c.green() + (g.green() - c.green()) * k),
            round(c.blue() + (g.blue() - c.blue()) * k),
        )
        if (
            abs(self._color.red() - g.red())
            + abs(self._color.green() - g.green())
            + abs(self._color.blue() - g.blue())
            < 4
        ):
            self._color = QColor(g)
            self._timer.stop()
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(self._color)
        p.drawRoundedRect(QRectF(self.rect()), 26, 26)
        p.end()


def style(widget: QWidget, **props: bool | str) -> None:
    """Cambia propiedades de estilo (on, accent, danger, tone…) y vuelve a aplicarlo."""
    for k, v in props.items():
        widget.setProperty(k, v)
    widget.style().unpolish(widget)
    widget.style().polish(widget)


def tone(widget: QWidget, name: str) -> None:
    """Color de un texto según la paleta (red, mint, sun, sky, soft): cambia solo con el día y la noche."""
    style(widget, tone=name)


def pill(text: str, accent: bool = False, on: bool = False, danger: bool = False) -> QPushButton:
    b = QPushButton(text)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    style(b, accent=accent, on=on, danger=danger)
    return b


class DotBar(QWidget):
    """Una barra redondeada que se llena según una fracción (procesador, memoria, batería…)."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.fraction: float | None = None
        self.invert = False  # true: poco es malo (batería)
        self.charging = False
        self.setFixedHeight(10)
        self.setMinimumWidth(80)
        theme.signals.changed.connect(self.update)

    def set_value(self, fraction: float | None, invert: bool = False, charging: bool = False) -> None:
        self.fraction, self.invert, self.charging = fraction, invert, charging
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        f = self.fraction
        ff = 0.0 if f is None else max(0.0, min(1.0, f))
        if f is None:
            color = theme.INK3
        elif self.charging:
            color = theme.MINT
        elif (not self.invert and ff >= 0.9) or (self.invert and ff <= 0.15):
            color = theme.RED
        elif (not self.invert and ff >= 0.75) or (self.invert and ff <= 0.3):
            color = theme.SUN
        else:
            color = theme.SKY
        r = self.height() / 2
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(QColor(theme.TILE2))
        p.drawRoundedRect(QRectF(self.rect()), r, r)
        if ff > 0:
            p.setBrush(QColor(color))
            p.drawRoundedRect(QRectF(0, 0, max(self.height(), self.width() * ff), self.height()), r, r)
        p.end()


class Spark(QWidget):
    """Las últimas lecturas en dos líneas finas (procesador y gráfica), como el mosaico «PC» del teléfono."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.series: list[list[float]] = [[], []]
        self.setMinimumHeight(46)
        theme.signals.changed.connect(self.update)

    def push(self, *values: float | None) -> None:
        for i, v in enumerate(values[:2]):
            if v is not None:
                self.series[i] = (self.series[i] + [max(0.0, min(1.0, v))])[-40:]
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        h, w = self.height() - 4, self.width()
        pen = QPen(QColor(theme.TILE2), 1.2)
        p.setPen(pen)
        p.drawLine(QPointF(0, h + 2), QPointF(w, h + 2))
        for data, col in zip(self.series, (theme.SKY, theme.SUN), strict=True):
            if len(data) < 2:
                continue
            path = QPainterPath(QPointF(0, 2 + h * (1 - data[0])))
            step = w / (len(data) - 1)
            for i, v in enumerate(data[1:], 1):
                path.lineTo(QPointF(i * step, 2 + h * (1 - v)))
            pen = QPen(QColor(col), 2.2)
            pen.setCapStyle(Qt.PenCapStyle.RoundCap)
            pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
            p.setPen(pen)
            p.drawPath(path)
        p.end()


class Dot(QWidget):
    """Punto de estado (conectado, dormida…)."""

    def __init__(self, hex_color: str = "", parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._color = QColor(hex_color or theme.INK3)
        self.setFixedSize(10, 10)

    def set_color(self, hex_color: str) -> None:
        self._color = QColor(hex_color)
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        p.setPen(Qt.PenStyle.NoPen)
        p.setBrush(self._color)
        p.drawEllipse(self.rect().adjusted(1, 1, -1, -1))
        p.end()


# ----------------------------- Imágenes del servidor -----------------------------


class Images(QObject):
    """Baja y guarda en memoria las carátulas y los iconos que sirve el servidor."""

    loaded = Signal(str, QPixmap)

    def __init__(self, base: str, parent: QObject | None = None) -> None:
        super().__init__(parent)
        self._base = base.rstrip("/")
        self._net = QNetworkAccessManager(self)
        self._cache: dict[str, QPixmap] = {}
        self._asked: set[str] = set()

    def get(self, path: str) -> QPixmap | None:
        """La imagen si ya está; si no, la pide y avisa con `loaded` cuando llegue."""
        if path in self._cache:
            return self._cache[path]
        if path not in self._asked:
            self._asked.add(path)
            reply = self._net.get(QNetworkRequest(QUrl(self._base + path)))
            reply.finished.connect(lambda r=reply, pth=path: self._done(r, pth))
        return None

    def _done(self, reply: QNetworkReply, path: str) -> None:
        data = reply.readAll()
        reply.deleteLater()
        pix = QPixmap()
        if reply.error() == QNetworkReply.NetworkError.NoError and pix.loadFromData(bytes(data)):
            self._cache[path] = pix
            self.loaded.emit(path, pix)
        else:
            self._asked.discard(path)


def badge(app: str, size: int = 30) -> QLabel:
    """La inicial de la app en un cuadro de color (cuando aún no hay icono)."""
    palette = [theme.CORAL, theme.SUN, theme.MINT, theme.SKY, theme.LILAC, theme.PINK]
    c = QColor(palette[sum(map(ord, app)) % len(palette)])
    label = QLabel(app[:1].upper())
    label.setFixedSize(QSize(size, size))
    label.setAlignment(Qt.AlignmentFlag.AlignCenter)
    label.setStyleSheet(
        f"background: {c.name()}; color: {theme.on_color(c.name())}; "
        f"border-radius: {size // 2}px; font-weight: 700; border: none;"
    )
    return label


def ago(seconds_since_1970: float, now: float | None = None) -> str:
    secs = max(0, int((now if now is not None else time.time()) - seconds_since_1970))
    if secs < 60:
        return "ahora"
    if secs < 3600:
        return f"hace {secs // 60} min"
    if secs < 6 * 3600:
        return f"hace {secs // 3600} h"
    return time.strftime("%H:%M" if secs < 86400 else "%d %b", time.localtime(seconds_since_1970))
