"""Piezas de la app: la cara de LED, tarjetas, barras de puntos, botones y notificaciones."""

from __future__ import annotations

import math
import random
import time

from PySide6.QtCore import QObject, QPointF, QSize, Qt, QTimer, QUrl, Signal
from PySide6.QtGui import QColor, QPainter, QPixmap
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

COLS, ROWS = 48, 20
EYE_W = 8
LEFT_X, RIGHT_X = 12, 28


# ----------------------------- La cara de LED -----------------------------


class Frame:
    """Una imagen de 48 × 20 puntos con brillo 0–1 (como la del teléfono)."""

    def __init__(self) -> None:
        self.v = [0.0] * (COLS * ROWS)

    def put(self, x: int, y: int, a: float = 1.0) -> None:
        if 0 <= x < COLS and 0 <= y < ROWS:
            self.v[y * COLS + x] = max(self.v[y * COLS + x], a)

    def rect(self, x: int, y: int, w: int, h: int, a: float = 1.0) -> None:
        for yy in range(h):
            for xx in range(w):
                self.put(x + xx, y + yy, a)

    def ring(self, x: int, y: int, w: int, h: int, a: float = 1.0) -> None:
        for xx in range(w):
            self.put(x + xx, y, a)
            self.put(x + xx, y + h - 1, a)
        for yy in range(h):
            self.put(x, y + yy, a)
            self.put(x + w - 1, y + yy, a)


def draw_eye(f: Frame, x: int, top: int, w: int, h: int, blink: float = 0.0, a: float = 1.0) -> None:
    """Un ojo redondeado; al parpadear se aplasta hacia el centro."""
    h_eff = max(1, round(h * (1 - blink)))
    y0 = top + (h - h_eff) // 2
    for yy in range(h_eff):
        edge = min(yy, h_eff - 1 - yy)
        inset = 2 if edge == 0 and h_eff >= 6 else 1 if edge <= 1 and h_eff >= 6 else 0
        f.rect(x + inset, y0 + yy, w - 2 * inset, 1, a)


def draw_smile(f: Frame, x0: int, y0: int, w: int, depth: int, a: float = 1.0) -> None:
    for i in range(w):
        d = round(depth * (1 - (2 * i / max(1, w - 1) - 1) ** 2))
        f.put(x0 + i, y0 + d, a)  # los extremos arriba y el centro abajo: sonrisa


def build_face(mood: str, t: float, dx: int, dy: int, blink_l: float, blink_r: float, level: float) -> Frame:
    f = Frame()
    if mood == "idle":
        a = 0.86 + 0.14 * math.sin(t * 1.3)
        draw_eye(f, LEFT_X + dx, 3 + dy, EYE_W, 10, blink_l, a)
        draw_eye(f, RIGHT_X + dx, 3 + dy, EYE_W, 10, blink_r, a)
        draw_smile(f, 20 + dx // 2, 15, 8, 1, a * 0.9)
    elif mood == "listen":
        draw_eye(f, LEFT_X - 1 + dx // 2, 2, EYE_W + 1, 12, blink_l * 0.5)
        draw_eye(f, RIGHT_X - 1 + dx // 2, 2, EYE_W + 1, 12, blink_r * 0.5)
        f.ring(22, 15, 4, 3)
        for side in (-1, 1):  # ondas que se mueven con la voz
            for i in range(3):
                h = 1 + round(level * (3 - i) * 1.6 * (0.6 + 0.4 * math.sin(t * 11 + i)))
                x = 6 - i * 3 if side < 0 else 41 + i * 3
                for k in range(h):
                    f.put(x, 8 + 2 - h // 2 + k, 0.9 - i * 0.2)
    elif mood == "think":
        draw_eye(f, LEFT_X + 2, 5 - 1, EYE_W, 6)
        draw_eye(f, RIGHT_X + 2, 3 - 1, EYE_W, 9, blink_r)
        step = int(t * 3) % 3
        for i in range(3):
            f.rect(20 + i * 3 + 1, 16, 2, 2, 1.0 if i == step else 0.25)
    elif mood == "talk":
        bounce = 1 if level > 0.5 else 0
        draw_eye(f, LEFT_X + dx // 2, 3 - bounce, EYE_W, 10, blink_l)
        draw_eye(f, RIGHT_X + dx // 2, 3 - bounce, EYE_W, 10, blink_r)
        for i in range(10):
            n = abs(math.sin(t * 13 + i * 1.7) * math.sin(t * 4.1 + i * 0.6))
            h = 1 + round(level * 3.4 * (0.35 + 0.65 * n))
            f.rect(19 + i, 18 - h, 1, h)
    elif mood == "alert":
        for x0 in (LEFT_X - 1, RIGHT_X - 1):
            f.ring(x0, 2, EYE_W + 2, 12)
            f.rect(x0 + 4 + max(-2, min(2, dx)), 6 + max(-2, min(2, dy)), 3, 3)
        f.ring(22, 15, 4, 4)
    elif mood == "error":
        for x0 in (LEFT_X, RIGHT_X):
            for i in range(7):
                f.put(x0 + i, 4 + i)
                f.put(x0 + 6 - i, 4 + i)
        for i in range(10):
            f.put(19 + i, 16 + i % 2)
    elif mood == "sleep":
        for x0 in (LEFT_X, RIGHT_X):
            for i in range(EYE_W):
                f.put(x0 + i, 9 if i in (0, EYE_W - 1) else 10, 0.8)
        f.rect(22, 16, 4, 1, 0.45)
        for k in range(2):  # zetas que suben y se apagan
            p = (t * 0.35 + k * 0.5) % 1
            y = round(8 - p * 7)
            for xx, yy in ((0, 0), (1, 0), (2, 0), (1, 1), (0, 2), (1, 2), (2, 2)):
                f.put(39 + k * 3 + xx, y + yy, 1 - p)
    else:  # offline: ojos entornados y un punto que va y viene
        for x0 in (LEFT_X, RIGHT_X):
            f.rect(x0, 8, EYE_W, 2, 0.35)
        x = int(t * 14 % (COLS + 8)) - 4
        for i in range(4):
            f.put(x - i, ROWS - 2, 0.7 - i * 0.15)
    return f


class LedFaceWidget(QWidget):
    """La cara de Vega: 48 × 20 puntos que miran, parpadean y cambian con su ánimo."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._mood = "offline"
        self._color = QColor(theme.LIGHT)
        self._rainbow = False
        self._shown = [0.0] * (COLS * ROWS)
        self._t = 0.0
        self._look = (0.0, 0.0)
        self._goal = (0.0, 0.0)
        self._look_at = 1.0
        self._blink = [0.0, 0.0]
        self._next_blink = 2.5
        self._level = 0.0
        self._last = time.monotonic()
        self._rng = random.Random()
        self.setSizePolicy(QSizePolicy.Policy.Expanding, QSizePolicy.Policy.Expanding)
        self.setMinimumSize(320, 140)
        self._timer = QTimer(self)
        self._timer.timeout.connect(self._step)
        self._timer.start(33)

    def set_mood(self, mood: str, face: str = "") -> None:
        self._mood = mood
        self._rainbow = face == "RAINBOW" and mood in ("idle", "talk")
        self._color = QColor(theme.MOOD_COLORS.get(mood) or theme.FACE_COLORS.get(face, theme.LIGHT))

    def glance(self, x: float, y: float) -> None:
        """Mira hacia algo un momento (−1…1 en cada eje)."""
        self._goal = (max(-1.0, min(1.0, x)), max(-1.0, min(1.0, y)))
        self._look_at = self._t + 1.3

    def _step(self) -> None:
        now = time.monotonic()
        dt = min(0.1, now - self._last)
        self._last = now
        self._t += dt
        if self._t >= self._look_at:
            self._look_at = self._t + 0.8 + self._rng.random() * 2.6
            wander = self._mood in ("idle", "talk", "alert")
            self._goal = (
                self._rng.uniform(-1, 1) if wander and self._rng.random() < 0.7 else 0.0,
                self._rng.uniform(-0.5, 0.5) if wander and self._rng.random() < 0.5 else 0.0,
            )
        k = min(1.0, dt * 14)
        self._look = (
            self._look[0] + (self._goal[0] - self._look[0]) * k,
            self._look[1] + (self._goal[1] - self._look[1]) * k,
        )
        self._next_blink -= dt
        if self._next_blink <= 0 and self._mood not in ("sleep", "offline"):
            self._next_blink = 2.2 + self._rng.random() * 3.8
            wink = self._mood == "idle" and self._rng.random() < 0.12
            self._blink = [0.0 if wink else 1.0, 1.0]
        self._blink = [max(0.0, b - dt * 6) for b in self._blink]
        # Sin voz real que medir, el volumen se simula mientras escucha o habla.
        target = (0.35 + 0.65 * abs(math.sin(self._t * 7))) if self._mood in ("listen", "talk") else 0.0
        self._level += (target - self._level) * min(1.0, dt * 10)
        frame = build_face(
            self._mood,
            self._t,
            round(self._look[0] * 3),
            round(self._look[1] * 2),
            self._blink[0],
            self._blink[1],
            self._level,
        )
        fall = 7.0
        for i, goal in enumerate(frame.v):
            now_v = self._shown[i]
            if now_v == goal:
                continue
            kk = min(1.0, dt * 25) if goal > now_v else min(1.0, dt * fall)
            nxt = now_v + (goal - now_v) * kk
            self._shown[i] = goal if abs(nxt - goal) < 0.01 else nxt
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        pitch = min(self.width() / COLS, self.height() / ROWS)
        ox = (self.width() - pitch * COLS) / 2 + pitch / 2
        oy = (self.height() - pitch * ROWS) / 2 + pitch / 2
        r = pitch * 0.31
        off = QColor(255, 255, 255, 14)
        p.setPen(Qt.PenStyle.NoPen)
        for y in range(ROWS):
            for x in range(COLS):
                v = self._shown[y * COLS + x]
                center = QPointF(ox + x * pitch, oy + y * pitch)
                if v < 0.03:
                    p.setBrush(off)
                    p.drawEllipse(center, r * 0.8, r * 0.8)
                    continue
                c = (
                    QColor.fromHsvF((x / COLS + self._t * 0.08) % 1.0, 0.6, 1.0)
                    if self._rainbow
                    else QColor(self._color)
                )
                c.setAlphaF(min(1.0, 0.18 + 0.82 * v))
                p.setBrush(c)
                p.drawEllipse(center, r * (0.8 + 0.25 * v), r * (0.8 + 0.25 * v))
        p.end()


# ----------------------------- Tarjetas y botones -----------------------------


class Card(QFrame):
    """Tarjeta de fondo oscuro con esquinas redondeadas y, opcionalmente, un título."""

    def __init__(self, title: str = "", parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.setObjectName("card")
        self.body = QVBoxLayout(self)
        self.body.setContentsMargins(16, 13, 16, 14)
        self.body.setSpacing(8)
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


def style(widget: QWidget, **props: bool) -> None:
    """Cambia propiedades de estilo (on, accent, danger…) y vuelve a aplicarlo."""
    for k, v in props.items():
        widget.setProperty(k, v)
    widget.style().unpolish(widget)
    widget.style().polish(widget)


def pill(text: str, accent: bool = False, on: bool = False, danger: bool = False) -> QPushButton:
    b = QPushButton(text)
    b.setCursor(Qt.CursorShape.PointingHandCursor)
    style(b, accent=accent, on=on, danger=danger)
    return b


class DotBar(QWidget):
    """Diez puntos que se encienden según una fracción (procesador, memoria, batería…)."""

    def __init__(self, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.fraction: float | None = None
        self.invert = False  # true: poco es malo (batería)
        self.charging = False
        self.setFixedHeight(14)
        self.setMinimumWidth(110)

    def set_value(self, fraction: float | None, invert: bool = False, charging: bool = False) -> None:
        self.fraction, self.invert, self.charging = fraction, invert, charging
        self.update()

    def paintEvent(self, _event: object) -> None:
        p = QPainter(self)
        p.setRenderHint(QPainter.RenderHint.Antialiasing)
        n = 10
        step = self.width() / n
        r = min(step, self.height()) * 0.42
        f = self.fraction
        lit = 0 if f is None else int(max(0.0, min(1.0, f)) * n + 0.5)
        ff = f or 0.0
        if f is None:
            color = QColor(theme.INK3)
        elif self.charging:
            color = QColor(theme.MINT)
        elif (not self.invert and ff >= 0.9) or (self.invert and ff <= 0.15):
            color = QColor(theme.RED)
        elif (not self.invert and ff >= 0.75) or (self.invert and ff <= 0.3):
            color = QColor(theme.AMBER)
        else:
            color = QColor(theme.LIGHT)
        p.setPen(Qt.PenStyle.NoPen)
        for i in range(n):
            p.setBrush(color if i < lit else QColor(255, 255, 255, 18))
            p.drawEllipse(QPointF(step * (i + 0.5), self.height() / 2), r, r)
        p.end()


class Dot(QWidget):
    """Punto de estado (conectado, dormida…)."""

    def __init__(self, hex_color: str = theme.INK3, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self._color = QColor(hex_color)
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
    palette = [theme.CORAL, theme.AMBER, theme.MINT, theme.SKY, theme.VIOLET, theme.PINK]
    c = QColor(palette[sum(map(ord, app)) % len(palette)])
    label = QLabel(app[:1].upper())
    label.setFixedSize(QSize(size, size))
    label.setAlignment(Qt.AlignmentFlag.AlignCenter)
    label.setStyleSheet(
        f"background: rgba({c.red()},{c.green()},{c.blue()},45); color: {c.name()}; "
        f"border-radius: {size // 4}px; font-weight: 700;"
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
