"""Conversación: lo que se dice con Vega (en el teléfono o aquí) y una caja para escribirle."""

from __future__ import annotations

import html

from PySide6.QtWidgets import QHBoxLayout, QLineEdit, QTextBrowser, QVBoxLayout, QWidget

from desktop import theme
from desktop.context import Context
from desktop.widgets import pill

BUBBLE = {
    "user": (theme.CARD_HI, theme.INK, "Tú"),
    "assistant": ("#1A0F0B", theme.INK, "Vega"),
    "error": ("#1A0E0E", theme.RED, "Error"),
    "note": ("#171208", theme.AMBER, "Aviso"),
}


def render(messages: list, assistant: str = "Vega") -> str:
    """Los mensajes como HTML (burbujas oscuras, de a una por línea)."""
    rows = []
    for m in messages:
        bg, fg, who = BUBBLE.get(m.role, BUBBLE["note"])
        who = assistant if m.role == "assistant" else who
        cursor = " ▍" if m.streaming else ""
        rows.append(
            f'<table width="100%" cellspacing="0" cellpadding="9" style="margin-bottom:8px"><tr>'
            f'<td style="background:{bg}; color:{fg}">'
            f'<span style="color:{theme.INK3}; font-size:11px">{html.escape(who)}</span><br>'
            f"{html.escape(m.text)}{cursor}</td></tr></table>"
        )
    return "".join(rows)


class ChatPage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        lay = QVBoxLayout(self)
        lay.setContentsMargins(18, 18, 18, 18)
        self.view = QTextBrowser()
        self.view.setOpenLinks(False)
        lay.addWidget(self.view, 1)
        row = QHBoxLayout()
        self.input = QLineEdit()
        self.input.setPlaceholderText("Escríbele a Vega (contesta por escrito; la voz es del teléfono)")
        self.send = pill("Enviar", accent=True)
        row.addWidget(self.input, 1)
        row.addWidget(self.send)
        lay.addLayout(row)
        self.input.returnPressed.connect(self._submit)
        self.send.clicked.connect(self._submit)
        ctx.changed.connect(self.refresh)
        self.refresh()

    def _submit(self) -> None:
        self.ctx.say_to_vega(self.input.text())
        self.input.clear()

    def refresh(self) -> None:
        bar = self.view.verticalScrollBar()
        at_end = bar.value() >= bar.maximum() - 8
        s = self.ctx.state
        self.view.setHtml(
            render(s.messages, s.assistant) or f'<p style="color:{theme.INK3}">Aún no hay conversación.</p>'
        )
        self.send.setEnabled(s.connected)
        if at_end:  # solo sigue el final si ya estabas leyendo el final
            bar.setValue(bar.maximum())
