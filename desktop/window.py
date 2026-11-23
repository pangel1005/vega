"""Ventana principal: menú lateral, las páginas y el icono de la bandeja."""

from __future__ import annotations

from PySide6.QtCore import QRectF, QSize, Qt
from PySide6.QtGui import QAction, QCloseEvent, QColor, QIcon, QPainter, QPixmap
from PySide6.QtWidgets import (
    QApplication,
    QHBoxLayout,
    QLabel,
    QMainWindow,
    QMenu,
    QPushButton,
    QStackedWidget,
    QSystemTrayIcon,
    QVBoxLayout,
    QWidget,
)

from desktop import theme
from desktop.context import Context
from desktop.pages.buttons import ButtonsPage
from desktop.pages.car import CarPage
from desktop.pages.chat import ChatPage
from desktop.pages.logs import LogsPage
from desktop.pages.maintenance import MaintenancePage
from desktop.pages.panel import PanelPage
from desktop.pages.phone import PhonePage
from desktop.pages.settings import SettingsPage
from desktop.widgets import Dot, icon, paint_face, style

PAGES = (
    ("Panel", "panel"),
    ("Conversación", "chat"),
    ("Botones", "buttons"),
    ("Coche", "car"),
    ("Teléfono", "phone"),
    ("Mantenimiento", "maintenance"),
    ("Ajustes", "settings"),
    ("Registros", "logs"),
)


_REST_FACE = {
    "ew": 0.17, "eh": 0.29, "gap": 0.23, "ey": 0.27, "tiltL": 0.0, "tiltR": 0.0, "ehL": 1.0, "ehR": 1.0,
    "smile": 0.35, "open": 0.0, "mw": 0.14, "arch": 0.0, "cross": 0.0,
    "listen": 0.0, "think": 0.0, "sleep": 0.0,
}  # fmt: skip


def face_pixmap(size: int) -> QPixmap:
    """La cara de Vega 2 en reposo sobre su mosaico de sol (el icono de la app)."""
    pix = QPixmap(size * 2, size * 2)
    pix.setDevicePixelRatio(2)
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(theme.DAY.sun))
    p.drawRoundedRect(QRectF(0, 0, size, size), size * 0.28, size * 0.28)
    pad = size * 0.12
    paint_face(
        p, QRectF(pad, pad * 1.4, size - 2 * pad, size - 2 * pad), _REST_FACE, [0.0, 0.0], 0.0, 0.0,
        QColor(theme.DAY.ink), False,
    )  # fmt: skip
    p.end()
    return pix


def app_icon() -> QIcon:
    """La cara de Vega, dibujada: no hace falta ningún archivo de imagen."""
    return QIcon(face_pixmap(64))


class MainWindow(QMainWindow):
    def __init__(self, ctx: Context) -> None:
        super().__init__()
        self.ctx = ctx
        self.setWindowTitle("Vega")
        self.setWindowIcon(app_icon())
        self.resize(1240, 760)
        self.setMinimumSize(1000, 620)
        root = QWidget()
        root.setObjectName("root")
        self.setCentralWidget(root)
        lay = QHBoxLayout(root)
        lay.setContentsMargins(0, 0, 0, 0)
        lay.setSpacing(0)

        side = QWidget()
        side.setObjectName("side")
        side.setFixedWidth(214)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(16, 20, 12, 16)
        sl.setSpacing(4)
        brand = QHBoxLayout()
        brand.setContentsMargins(6, 0, 0, 16)
        brand.setSpacing(10)
        mark = QLabel()
        mark.setPixmap(face_pixmap(36))
        mark.setFixedSize(36, 36)
        self.brand_name = QLabel(ctx.state.assistant)
        self.brand_name.setStyleSheet("font-size: 20px; font-weight: 700;")
        brand.addWidget(mark)
        brand.addWidget(self.brand_name, 1)
        sl.addLayout(brand)
        self.pages = QStackedWidget()
        self.nav: list[QPushButton] = []
        widgets: dict[str, QWidget] = {
            "panel": PanelPage(ctx),
            "chat": ChatPage(ctx),
            "buttons": ButtonsPage(ctx),
            "car": CarPage(ctx),
            "phone": PhonePage(),
            "maintenance": MaintenancePage(ctx),
            "settings": SettingsPage(ctx),
            "logs": LogsPage(),
        }
        for i, (label, key) in enumerate(PAGES):
            b = QPushButton(label)
            b.setObjectName("nav")
            b.setCursor(Qt.CursorShape.PointingHandCursor)
            b.clicked.connect(lambda _=False, n=i: self.show_page(n))
            self.nav.append(b)
            sl.addWidget(b)
            self.pages.addWidget(widgets[key])
        sl.addStretch(1)
        self.theme_btn = QPushButton()
        self.theme_btn.setObjectName("nav")
        self.theme_btn.setCursor(Qt.CursorShape.PointingHandCursor)
        self.theme_btn.setIconSize(QSize(18, 18))
        self.theme_btn.clicked.connect(self._cycle_theme)
        sl.addWidget(self.theme_btn)
        sl.addSpacing(6)
        foot = QHBoxLayout()
        foot.setContentsMargins(8, 0, 0, 0)
        self.link_dot = Dot()
        self.link_text = QLabel()
        self.link_text.setObjectName("faint")
        self.link_text.setWordWrap(True)
        foot.addWidget(self.link_dot)
        foot.addWidget(self.link_text, 1)
        sl.addLayout(foot)
        lay.addWidget(side)
        lay.addWidget(self.pages, 1)
        self.show_page(0)
        ctx.changed.connect(self._refresh_link)
        theme.signals.changed.connect(self._refresh_theme_button)
        theme.signals.changed.connect(self._refresh_link)
        self._refresh_link()
        self._refresh_theme_button()
        self._make_tray()

    def show_page(self, n: int) -> None:
        self.pages.setCurrentIndex(n)
        for i, b in enumerate(self.nav):
            style(b, current=i == n)

    def _cycle_theme(self) -> None:
        theme.cycle_mode()
        self._refresh_theme_button()

    def _refresh_theme_button(self) -> None:
        m = theme.mode()
        name = ("auto", "sun", "moon")[m]
        self.theme_btn.setText(f"  Tema: {theme.MODE_NAMES[m].lower()}")
        self.theme_btn.setIcon(icon(name, theme.INK2, 18))
        self.theme_btn.setToolTip("Automático: de día de 7 a 20 h, de noche el resto")

    def _refresh_link(self) -> None:
        s = self.ctx.state
        self.brand_name.setText(s.assistant)
        self.link_dot.set_color(theme.MINT if s.connected else theme.RED)
        phone = "teléfono conectado" if s.phone.online else "sin teléfono"
        self.link_text.setText(f"Servidor en marcha · {phone}" if s.connected else "Servidor parado")
        if hasattr(self, "tray"):
            self.tray.setToolTip(f"Vega · {self.link_text.text()}")
            self.sleep_action.setText("Despertar el teléfono" if s.phone.sleeping else "Dormir el teléfono")
            self.sleep_action.setEnabled(s.connected and s.phone.online)

    # --- Bandeja del sistema: Vega se queda viva aunque cierres la ventana ---

    def _make_tray(self) -> None:
        self.tray = QSystemTrayIcon(app_icon(), self)
        menu = QMenu()
        show = QAction("Abrir Vega", self)
        show.triggered.connect(self.bring_to_front)
        self.sleep_action = QAction("Dormir el teléfono", self)
        self.sleep_action.triggered.connect(
            lambda: self.ctx.phone_control("wake" if self.ctx.state.phone.sleeping else "sleep")
        )
        quit_action = QAction("Salir de la app (el servidor sigue)", self)
        quit_action.triggered.connect(QApplication.instance().quit)  # type: ignore[union-attr]
        for a in (show, self.sleep_action):
            menu.addAction(a)
        menu.addSeparator()
        menu.addAction(quit_action)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(
            lambda reason: (
                self.bring_to_front() if reason == QSystemTrayIcon.ActivationReason.Trigger else None
            )
        )
        self.tray.show()

    def bring_to_front(self) -> None:
        self.showNormal()
        self.raise_()
        self.activateWindow()

    def closeEvent(self, event: QCloseEvent) -> None:
        # Cerrar la ventana la manda a la bandeja; se sale desde el menú del icono.
        if self.tray.isVisible():
            self.hide()
            event.ignore()
        else:
            event.accept()
