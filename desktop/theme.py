"""Colores, fuentes y estilo de la app: el mismo «Vega · noche» que el teléfono."""

from __future__ import annotations

from pathlib import Path

from PySide6.QtGui import QColor, QFont, QFontDatabase

ROOT = Path(__file__).resolve().parents[1]
FONTS = ROOT / "android" / "app" / "src" / "main" / "res" / "font"

BG = "#000000"
CARD = "#0E0E0F"
CARD_HI = "#17171A"
LINE = "#1F1F22"
INK = "#F5F2ED"
INK2 = "#9A968F"
INK3 = "#55524E"
LIGHT = "#FFF1DE"
CORAL = "#FF6A3D"
AMBER = "#FFB547"
MINT = "#6CF0A8"
RED = "#FF4D4D"
SKY = "#7FD8FF"
VIOLET = "#B79BFF"
PINK = "#FF8CC6"

FACE_COLORS = {"WARM": LIGHT, "CORAL": CORAL, "SKY": SKY, "MINT": MINT, "VIOLET": VIOLET, "PINK": PINK}

MOOD_COLORS = {
    "listen": CORAL,
    "think": AMBER,
    "talk": LIGHT,
    "alert": RED,
    "error": RED,
    "sleep": "#6E6760",
    "offline": "#4A4642",
}

# Así se llaman las familias dentro de los archivos .ttf del teléfono.
SANS = "Space Grotesk Light"
DOTS = "Doto Black"


def load_fonts() -> None:
    """Carga Space Grotesk y Doto (las del teléfono). Sin ellas, se usa la fuente del sistema."""
    for name in ("doto_black", "space_grotesk_regular", "space_grotesk_medium", "space_grotesk_bold"):
        path = FONTS / f"{name}.ttf"
        if path.exists():
            QFontDatabase.addApplicationFont(str(path))


def sans(size: int = 10, bold: bool = False) -> QFont:
    f = QFont(SANS, size)
    f.setBold(bold)
    return f


def dots(size: int = 20) -> QFont:
    f = QFont(DOTS, size)
    f.setBold(True)
    return f


def color(hex_code: str, alpha: float = 1.0) -> QColor:
    c = QColor(hex_code)
    c.setAlphaF(alpha)
    return c


STYLE = f"""
* {{ font-family: '{SANS}', 'Segoe UI'; color: {INK}; }}
QMainWindow, QWidget#root {{ background: {BG}; }}
QWidget {{ font-size: 13px; }}
QScrollArea, QScrollArea > QWidget > QWidget {{ background: transparent; border: none; }}
QFrame#card {{ background: {CARD}; border: 1px solid {LINE}; border-radius: 18px; }}
QLabel {{ background: transparent; }}
QLabel#title {{ font-size: 15px; font-weight: 700; }}
QLabel#muted {{ color: {INK2}; font-size: 12px; }}
QLabel#faint {{ color: {INK3}; font-size: 11px; }}
QPushButton {{ background: transparent; border: 1px solid {LINE}; border-radius: 13px; padding: 5px 13px; color: {INK2}; }}
QPushButton:hover {{ background: {CARD_HI}; color: {INK}; }}
QPushButton:pressed {{ background: {CARD_HI}; }}
QPushButton:disabled {{ color: {INK3}; border-color: {CARD_HI}; }}
QPushButton[accent="true"] {{ background: {CORAL}; border-color: {CORAL}; color: #140A05; font-weight: 700; }}
QPushButton[accent="true"]:hover {{ background: #FF815A; }}
QPushButton[on="true"] {{ border-color: {MINT}; color: {MINT}; }}
QPushButton[danger="true"] {{ border-color: {RED}; color: {RED}; }}
QPushButton#nav {{ border: none; border-radius: 12px; text-align: left; padding: 9px 14px; font-size: 14px; }}
QPushButton#nav[current="true"] {{ background: {CARD_HI}; color: {INK}; font-weight: 700; }}
QPushButton#key {{ background: {CARD}; border-radius: 16px; padding: 14px 8px; color: {INK}; font-weight: 500; }}
QPushButton#key:hover {{ background: {CARD_HI}; border-color: {CORAL}; }}
QLineEdit, QSpinBox, QDoubleSpinBox, QComboBox, QPlainTextEdit, QTextBrowser, QTableWidget, QListWidget {{
    background: {CARD}; border: 1px solid {LINE}; border-radius: 10px; padding: 6px 8px; selection-background-color: {CORAL};
}}
QTextBrowser, QPlainTextEdit {{ border-radius: 14px; }}
QTableWidget {{ gridline-color: {LINE}; alternate-background-color: #0A0A0B; }}
QHeaderView::section {{ background: {CARD_HI}; color: {INK2}; border: none; padding: 6px 8px; }}
QCheckBox {{ spacing: 8px; }}
QCheckBox::indicator {{ width: 16px; height: 16px; border-radius: 5px; border: 1px solid {INK3}; background: {CARD}; }}
QCheckBox::indicator:checked {{ background: {CORAL}; border-color: {CORAL}; }}
QScrollBar:vertical {{ background: transparent; width: 10px; }}
QScrollBar::handle:vertical {{ background: {LINE}; border-radius: 5px; min-height: 30px; }}
QScrollBar::add-line, QScrollBar::sub-line {{ height: 0; }}
QToolTip {{ background: {CARD_HI}; color: {INK}; border: 1px solid {LINE}; }}
QMenu {{ background: {CARD}; border: 1px solid {LINE}; padding: 6px; }}
QMenu::item {{ padding: 6px 18px; border-radius: 8px; }}
QMenu::item:selected {{ background: {CARD_HI}; }}
QDialog {{ background: {BG}; }}
"""
