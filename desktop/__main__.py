"""App de escritorio de Vega (nativa, con Qt; sin navegador).

    uv run --group desktop python -m desktop          # abre la ventana
    uv run --group desktop python -m desktop --tray   # empieza escondida en la bandeja

Se conecta al servidor local (ws://127.0.0.1:8000/ws) como cliente «desktop»: ve en tiempo real lo
que pasa en el teléfono, puede controlarlo y edita los botones y los ajustes.
"""

from __future__ import annotations

import sys

from PySide6.QtWidgets import QApplication, QMessageBox

from desktop import server_control, theme
from desktop.client import VegaClient
from desktop.context import Context
from desktop.widgets import Images
from desktop.window import MainWindow

URL = "ws://127.0.0.1:8000/ws"


def main(argv: list[str]) -> int:
    app = QApplication(argv)
    app.setApplicationName("Vega")
    app.setQuitOnLastWindowClosed(False)  # la bandeja la mantiene viva
    theme.apply(app)  # día o noche según la hora (o lo elegido), como el teléfono
    try:
        token = server_control.token()
    except OSError:
        QMessageBox.critical(
            None, "Vega", "No encuentro config/token.txt. Arranca el servidor una vez (scripts/start.ps1)."
        )
        return 1
    client = VegaClient(URL, token)
    ctx = Context(client, Images(server_control.BASE))
    window = MainWindow(ctx)
    if "--tray" not in argv:
        window.show()
    client.start()
    code = app.exec()
    client.stop()
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
