"""Ventana principal: menú lateral, las páginas y el icono de la bandeja."""

from __future__ import annotations

from PySide6.QtCore import Qt
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
from desktop.widgets import Dot, style

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


def app_icon() -> QIcon:
    """Los ojos de Vega, dibujados: no hace falta ningún archivo de imagen."""
    pix = QPixmap(64, 64)
    pix.fill(Qt.GlobalColor.transparent)
    p = QPainter(pix)
    p.setRenderHint(QPainter.RenderHint.Antialiasing)
    p.setPen(Qt.PenStyle.NoPen)
    p.setBrush(QColor(theme.CARD))
    p.drawRoundedRect(2, 2, 60, 60, 16, 16)
    p.setBrush(QColor(theme.LIGHT))
    p.drawRoundedRect(14, 17, 13, 22, 6, 6)
    p.drawRoundedRect(37, 17, 13, 22, 6, 6)
    p.drawRoundedRect(25, 46, 14, 4, 2, 2)
    p.end()
    return QIcon(pix)


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
        side.setFixedWidth(210)
        sl = QVBoxLayout(side)
        sl.setContentsMargins(14, 18, 14, 14)
        sl.setSpacing(4)
        logo = QLabel("VEGA")
        logo.setFont(theme.dots(22))
        logo.setStyleSheet(f"color: {theme.LIGHT}; letter-spacing: 3px; padding: 4px 8px 14px 8px;")
        sl.addWidget(logo)
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
        foot = QHBoxLayout()
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
        self._refresh_link()
        self._make_tray()

    def show_page(self, n: int) -> None:
        self.pages.setCurrentIndex(n)
        for i, b in enumerate(self.nav):
            style(b, current=i == n)

    def _refresh_link(self) -> None:
        s = self.ctx.state
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
