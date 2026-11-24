"""Colores, letra y estilo de la app: el mismo «Vega 2» que el teléfono.

De día (de 7 a 20 h) papel y tinta; de noche, tinta y luz. Mosaicos grandes de esquinas muy redondas, cifras finas
y una cara dibujada con curvas sobre un mosaico de color. Los colores son los de `ui2/Theme2.kt` del teléfono.

`apply(app)` pone la paleta del momento (o la elegida en la barra lateral) y vuelve a aplicarla al cambiar: los
nombres de este módulo (INK, CORAL…) siempre apuntan a la paleta en uso, y los widgets dibujados a mano los leen
al pintarse.
"""

from __future__ import annotations

import os
import time
from dataclasses import dataclass
from pathlib import Path

from PySide6.QtCore import QObject, QSettings, Qt, QTimer, Signal
from PySide6.QtGui import QColor, QFont
from PySide6.QtWidgets import QApplication

ROOT = Path(__file__).resolve().parents[1]


@dataclass(frozen=True)
class Pal:
    night: bool
    bg: str
    tile: str
    tile2: str
    ink: str
    ink2: str
    ink3: str
    sun: str
    coral: str
    lilac: str
    mint: str
    sky: str
    red: str


DAY = Pal(
    night=False,
    bg="#EEE8DB", tile="#FBF8F1", tile2="#E0D8C7",
    ink="#211D17", ink2="#554C40", ink3="#645B4E",
    sun="#FFC23D", coral="#FF6A47", lilac="#A898FF", mint="#3FC79A", sky="#55B4FF", red="#E5484D",
)  # fmt: skip

NIGHT = Pal(
    night=True,
    bg="#0E0C13", tile="#1A1723", tile2="#2A2538",
    ink="#F3EEFA", ink2="#B3ACC8", ink3="#9890B2",
    sun="#FFC95C", coral="#FF7D5C", lilac="#B9ABFF", mint="#55E0AE", sky="#6CC2FF", red="#FF6166",
)  # fmt: skip

CREAM = "#FDF8EE"  # texto y ojos sobre un mosaico de color

# --- La paleta en uso (se actualiza con `use`) ---
P: Pal = DAY
BG = TILE = CARD = TILE2 = CARD_HI = LINE = INK = INK2 = INK3 = ""
LIGHT = SUN = AMBER = CORAL = LILAC = VIOLET = MINT = SKY = RED = PINK = ""
MOOD_COLORS: dict[str, str] = {}
STYLE = ""

SANS = "Segoe UI Variable Text"
DISPLAY = "Segoe UI Variable Display"


class _Signals(QObject):
    changed = Signal()  # cambió la paleta: los widgets dibujados a mano se repintan


signals = _Signals()

# Tema elegido: 0 = automático (por la hora), 1 = siempre de día, 2 = siempre de noche.
MODE_NAMES = ("Automático", "Día", "Noche")


def mode() -> int:
    try:
        return int(QSettings("Vega", "escritorio").value("tema", 0))
    except (TypeError, ValueError):
        return 0


def set_mode(value: int) -> None:
    QSettings("Vega", "escritorio").setValue("tema", value % 3)


def pal_for(hour: int, chosen: int = 0) -> Pal:
    if chosen == 1:
        return DAY
    if chosen == 2:
        return NIGHT
    return DAY if 7 <= hour < 20 else NIGHT


def mix(a: str, b: str, t: float) -> str:
    """Un color entre `a` (t=0) y `b` (t=1)."""
    ca, cb = QColor(a), QColor(b)
    return QColor(
        round(ca.red() + (cb.red() - ca.red()) * t),
        round(ca.green() + (cb.green() - ca.green()) * t),
        round(ca.blue() + (cb.blue() - ca.blue()) * t),
    ).name()


def luminance(hex_code: str) -> float:
    def ch(v: int) -> float:
        c = v / 255
        return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4

    c = QColor(hex_code)
    return 0.2126 * ch(c.red()) + 0.7152 * ch(c.green()) + 0.0722 * ch(c.blue())


def contrast(a: str, b: str) -> float:
    la, lb = luminance(a) + 0.05, luminance(b) + 0.05
    return max(la, lb) / min(la, lb)


def readable(accent: str, min_ratio: float = 4.5) -> str:
    """Un acento usado como texto: se oscurece (o aclara de noche) hasta leerse sobre los mosaicos."""
    c = accent
    for _ in range(40):
        if all(contrast(c, g) >= min_ratio for g in (P.tile, P.tile2, P.bg)):
            return c
        c = mix(c, "#FFFFFF" if P.night else "#000000", 0.06)
    return c


def on_color(bg: str) -> str:
    """Tinta o crema: lo que mejor se lee sobre un mosaico de color."""
    return P.ink if not P.night and contrast(P.ink, bg) >= contrast(CREAM, bg) else CREAM


def face_accent(mood: str, base: str | None = None) -> str:
    """El color de la cara según su ánimo (en reposo, el sol o el del momento)."""
    return {
        "listen": P.coral,
        "think": P.lilac,
        "talk": base or P.sun,
        "alert": P.red,
        "error": P.red,
        "sleep": P.sky,
        "offline": P.ink3,
    }.get(mood, base or P.sun)


def use(pal: Pal) -> None:
    """Hace de `pal` la paleta en uso: los nombres del módulo y la hoja de estilo."""
    global P, BG, TILE, CARD, TILE2, CARD_HI, LINE, INK, INK2, INK3, LIGHT, SUN, AMBER, CORAL, LILAC, VIOLET
    global MINT, SKY, RED, PINK, MOOD_COLORS, STYLE
    P = pal
    BG, TILE, TILE2 = pal.bg, pal.tile, pal.tile2
    CARD, CARD_HI = TILE, TILE2
    LINE = mix(pal.tile2, pal.bg, 0.4)
    INK, INK2, INK3 = pal.ink, pal.ink2, pal.ink3
    SUN = AMBER = pal.sun
    CORAL, LILAC, MINT, SKY, RED = pal.coral, pal.lilac, pal.mint, pal.sky, pal.red
    VIOLET = LILAC
    PINK = "#FF8CC6"
    LIGHT = INK
    MOOD_COLORS = {
        m: face_accent(m) for m in ("idle", "listen", "think", "talk", "alert", "error", "sleep", "offline")
    }
    STYLE = _style(pal)


def _check_image(ink: str) -> str:
    """La marca ✓ de las casillas, dibujada en un PNG (la hoja de estilo de Qt solo acepta imágenes)."""
    from PySide6.QtCore import QPointF
    from PySide6.QtGui import QGuiApplication, QPainter, QPainterPath, QPen, QPixmap

    if QGuiApplication.instance() is None:
        return ""
    folder = ROOT / "data" / "desktop-ui"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / f"check-{ink.lstrip('#').lower()}.png"
    if not path.exists():
        pix = QPixmap(36, 36)
        pix.fill(QColor(0, 0, 0, 0))
        painter = QPainter(pix)
        painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        pen = QPen(QColor(ink), 4.5)
        pen.setCapStyle(Qt.PenCapStyle.RoundCap)
        pen.setJoinStyle(Qt.PenJoinStyle.RoundJoin)
        painter.setPen(pen)
        mark = QPainterPath(QPointF(9, 19))
        mark.lineTo(QPointF(15.5, 25))
        mark.lineTo(QPointF(27, 11))
        painter.drawPath(mark)
        painter.end()
        pix.save(str(path))
    return path.as_posix()


def _style(p: Pal) -> str:
    key_bottom = mix(p.tile2, p.ink, 0.12 if not p.night else 0.0) if not p.night else "#0A0910"
    on_bg = mix(p.mint, p.tile, 0.78)
    danger_bg = mix(p.red, p.tile, 0.84)
    accent_text = on_color(p.coral)
    red_text = readable(p.red)
    mint_text = readable(p.mint)
    sun_text = readable(p.sun)
    sky_text = readable(p.sky)
    check = _check_image(accent_text)
    check_rule = f"image: url({check});" if check else ""
    return f"""
* {{ font-family: '{SANS}', 'Segoe UI'; color: {p.ink}; }}
QMainWindow, QWidget#root {{ background: {p.bg}; }}
QWidget#side {{ background: {p.bg}; }}
QWidget {{ font-size: 13px; }}
QScrollArea, QScrollArea > QWidget > QWidget {{ background: transparent; border: none; }}
QFrame#card {{ background: {p.tile}; border: none; border-radius: 26px; }}
QFrame#inner {{ background: {p.tile2}; border: none; border-radius: 18px; }}
QLabel {{ background: transparent; }}
QLabel#title {{ font-size: 15px; font-weight: 700; }}
QLabel#eyebrow {{ color: {p.ink2}; font-size: 11px; font-weight: 600; letter-spacing: 1px; }}
QLabel#muted {{ color: {p.ink2}; font-size: 12px; }}
QLabel#faint {{ color: {p.ink3}; font-size: 11px; }}
QLabel#big {{ font-family: '{DISPLAY}', 'Segoe UI'; font-size: 64px; font-weight: 300; }}
QLabel#value {{ font-family: '{DISPLAY}', 'Segoe UI'; font-size: 22px; font-weight: 300; }}
QLabel[tone="red"] {{ color: {red_text}; }}
QLabel[tone="mint"] {{ color: {mint_text}; }}
QLabel[tone="sun"] {{ color: {sun_text}; }}
QLabel[tone="sky"] {{ color: {sky_text}; }}
QLabel[tone="soft"] {{ color: {p.ink2}; }}
QPushButton, QToolButton {{
    background: {p.tile2}; border: none; border-radius: 14px; padding: 7px 14px; color: {p.ink}; font-weight: 600;
}}
QPushButton:hover, QToolButton:hover {{ background: {mix(p.tile2, p.ink, 0.08)}; }}
QPushButton:pressed, QToolButton:pressed {{ background: {mix(p.tile2, p.ink, 0.16)}; }}
QPushButton:disabled, QToolButton:disabled {{ color: {p.ink3}; background: {mix(p.tile2, p.tile, 0.5)}; }}
QPushButton[accent="true"] {{ background: {p.coral}; color: {accent_text}; }}
QPushButton[accent="true"]:hover {{ background: {mix(p.coral, p.ink, 0.1)}; }}
QPushButton[on="true"] {{ background: {on_bg}; color: {mint_text}; }}
QPushButton[danger="true"] {{ background: {danger_bg}; color: {red_text}; }}
QPushButton::menu-indicator, QToolButton::menu-indicator {{ image: none; width: 0; }}
QPushButton#nav {{
    background: transparent; border-radius: 16px; text-align: left; padding: 10px 16px; font-size: 14px;
    font-weight: 500; color: {p.ink2};
}}
QPushButton#nav:hover {{ background: {p.tile}; color: {p.ink}; }}
QPushButton#nav[current="true"] {{ background: {p.ink}; color: {p.bg}; font-weight: 700; }}
QPushButton#round {{ border-radius: 20px; padding: 0; min-width: 40px; min-height: 40px; }}
QPushButton#round[accent="true"] {{ border-radius: 26px; min-width: 52px; min-height: 52px; }}
QToolButton#key {{
    background: {p.tile}; border-radius: 22px; border-bottom: 4px solid {key_bottom};
    padding: 10px 6px 8px 6px; font-weight: 600; font-size: 13px;
}}
QToolButton#key:hover {{ background: {mix(p.tile, p.tile2, 0.5)}; }}
QToolButton#key:pressed {{ border-bottom: 1px solid {key_bottom}; padding-top: 13px; }}
QPushButton#key {{ background: {p.tile}; border-radius: 22px; border-bottom: 4px solid {key_bottom}; padding: 14px 8px; }}
QLineEdit, QSpinBox, QDoubleSpinBox, QComboBox, QPlainTextEdit, QTextBrowser, QTableWidget, QListWidget {{
    background: {p.bg}; border: 1px solid {p.tile2}; border-radius: 12px; padding: 7px 10px;
    selection-background-color: {p.coral}; selection-color: {accent_text};
}}
QLineEdit:focus, QSpinBox:focus, QDoubleSpinBox:focus, QComboBox:focus, QPlainTextEdit:focus {{ border-color: {p.coral}; }}
QComboBox::drop-down {{ border: none; width: 24px; }}
QComboBox QAbstractItemView {{ background: {p.tile}; border: 1px solid {p.tile2}; selection-background-color: {p.tile2}; selection-color: {p.ink}; }}
QSpinBox::up-button, QSpinBox::down-button, QDoubleSpinBox::up-button, QDoubleSpinBox::down-button {{ width: 18px; border: none; }}
QTextBrowser, QPlainTextEdit {{ border-radius: 18px; }}
QTableWidget {{ gridline-color: {p.tile2}; alternate-background-color: {p.tile}; }}
QHeaderView::section {{ background: {p.tile2}; color: {p.ink2}; border: none; padding: 6px 8px; font-weight: 600; }}
QCheckBox {{ spacing: 9px; }}
QCheckBox::indicator {{ width: 18px; height: 18px; border-radius: 6px; border: 2px solid {p.ink3}; background: {p.tile}; }}
QCheckBox::indicator:checked {{ background: {p.coral}; border-color: {p.coral}; {check_rule} }}
QAbstractSpinBox {{ padding-left: 10px; }}
QStackedWidget, QStackedWidget > QWidget {{ background: {p.bg}; }}
QScrollBar:vertical {{ background: transparent; width: 10px; margin: 6px 2px; }}
QScrollBar::handle:vertical {{ background: {p.tile2}; border-radius: 3px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
QScrollBar::add-page, QScrollBar::sub-page {{ background: transparent; }}
QToolTip {{ background: {p.ink}; color: {p.bg}; border: none; padding: 5px 8px; border-radius: 8px; }}
QMenu {{ background: {p.tile}; border: 1px solid {p.tile2}; padding: 6px; border-radius: 14px; }}
QMenu::item {{ padding: 7px 18px; border-radius: 10px; }}
QMenu::item:selected {{ background: {p.tile2}; color: {p.ink}; }}
QDialog {{ background: {p.bg}; }}
"""


use(DAY)


def apply(app: QApplication | None = None) -> None:
    """Pone la paleta que toca ahora (por la hora o la elegida) y la vuelve a mirar cada minuto."""
    app = app or QApplication.instance()  # type: ignore[assignment]
    load_fonts()

    def refresh(force: bool = False) -> None:
        pal = pal_for(time.localtime().tm_hour, mode())
        if pal is P and not force:
            return
        use(pal)
        if app is not None:
            app.setStyleSheet(STYLE)
        signals.changed.emit()

    refresh(force=True)
    if app is not None and not hasattr(app, "_vega_theme_timer"):
        timer = QTimer(app)
        timer.timeout.connect(refresh)
        timer.start(60_000)
        app._vega_theme_timer = timer  # type: ignore[attr-defined]
        app._vega_theme_refresh = refresh  # type: ignore[attr-defined]


def cycle_mode() -> int:
    """Automático → día → noche → automático. Devuelve el nuevo modo."""
    new = (mode() + 1) % 3
    set_mode(new)
    app = QApplication.instance()
    refresh = getattr(app, "_vega_theme_refresh", None)
    if refresh is not None:
        refresh(force=True)
    return new


def load_fonts() -> None:
    """La letra es la del sistema (Segoe UI Variable), como en el teléfono (la del sistema de Android).

    Se registra también desde C:\Windows\Fonts: sin pantalla (pruebas y capturas) Qt no ve las fuentes del
    sistema y escribiría cuadritos."""
    from PySide6.QtGui import QFontDatabase

    fonts = Path(os.environ.get("WINDIR", r"C:\Windows")) / "Fonts"
    for name in (
        "SegUIVar.ttf",
        "segoeui.ttf",
        "segoeuib.ttf",
        "segoeuil.ttf",
        "seguisb.ttf",
        "seguisym.ttf",
    ):
        path = fonts / name
        if path.is_file():
            QFontDatabase.addApplicationFont(str(path))


def sans(size: int = 10, bold: bool = False, light: bool = False) -> QFont:
    f = QFont(SANS, size)
    if bold:
        f.setWeight(QFont.Weight.Bold)
    elif light:
        f.setWeight(QFont.Weight.Light)
    return f


def display(size: int = 40) -> QFont:
    f = QFont(DISPLAY, size)
    f.setWeight(QFont.Weight.Light)
    return f


def dots(size: int = 20) -> QFont:
    """(Del diseño viejo: ya no hay letra de puntos; se deja por compatibilidad.)"""
    return sans(size, bold=True)


def color(hex_code: str, alpha: float = 1.0) -> QColor:
    c = QColor(hex_code)
    c.setAlphaF(alpha)
    return c
