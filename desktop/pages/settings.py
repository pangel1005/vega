"""Ajustes: lo más usado de config/assistant.toml y el control del servidor."""

from __future__ import annotations

import threading
from typing import Any

from PySide6.QtCore import QObject, Signal
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QDoubleSpinBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QScrollArea,
    QSpinBox,
    QVBoxLayout,
    QWidget,
)

from desktop import config_edit, server_control, theme
from desktop.config_edit import ConfigError
from desktop.context import Context
from desktop.widgets import Card, Dot, pill

STT_MODELS = ("tiny", "base", "small", "medium")


class ServerJob(QObject):
    """Arrancar o parar el servidor tarda segundos: se hace fuera de la ventana."""

    done = Signal(str)

    def run(self, job: Any) -> None:
        threading.Thread(target=lambda: self.done.emit(job()), daemon=True).start()


class SettingsPage(QWidget):
    def __init__(self, ctx: Context, parent: QWidget | None = None) -> None:
        super().__init__(parent)
        self.ctx = ctx
        self.job = ServerJob(self)
        self.job.done.connect(self._job_done)
        outer = QVBoxLayout(self)
        outer.setContentsMargins(18, 18, 18, 18)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setFrameShape(QScrollArea.Shape.NoFrame)
        outer.addWidget(scroll)
        inner = QWidget()
        scroll.setWidget(inner)
        lay = QVBoxLayout(inner)
        lay.setSpacing(14)

        # --- Servidor ---
        server = Card("Servidor")
        row = QHBoxLayout()
        self.dot = Dot()
        self.server_state = QLabel()
        self.start_btn, self.restart_btn, self.stop_btn = (
            pill("Iniciar", accent=True),
            pill("Reiniciar"),
            pill("Parar", danger=True),
        )
        row.addWidget(self.dot)
        row.addWidget(self.server_state, 1)
        for b in (self.start_btn, self.restart_btn, self.stop_btn):
            row.addWidget(b)
        server.body.addLayout(row)
        self.job_text = QLabel()
        self.job_text.setObjectName("muted")
        server.body.addWidget(self.job_text)
        lay.addWidget(server)
        self.start_btn.clicked.connect(lambda: self._run("Arrancando…", server_control.start))
        self.restart_btn.clicked.connect(lambda: self._run("Reiniciando…", server_control.restart))
        self.stop_btn.clicked.connect(lambda: self._run("Parando…", server_control.stop))

        # --- Ajustes ---
        card = Card("Asistente")
        form = QFormLayout()
        form.setSpacing(10)
        self.name = QLineEdit()
        self.model = QLineEdit()
        self.gpu_layers = QSpinBox()
        self.gpu_layers.setRange(-1, 99)
        self.gpu_layers.setToolTip(
            "Capas del modelo en la tarjeta gráfica. 0 = nada en la gráfica; "
            "1 = la que se usa ahora; -1 = todas."
        )
        self.threads = QSpinBox()
        self.threads.setRange(1, 32)
        self.keep_alive = QLineEdit()
        self.stt_model = QComboBox()
        self.stt_model.addItems(STT_MODELS)
        self.stt_device = QComboBox()
        self.stt_device.addItems(("cpu", "cuda"))
        self.voice = QComboBox()
        self.voice.setEditable(True)
        self.speed = QDoubleSpinBox()
        self.speed.setRange(0.6, 1.6)
        self.speed.setSingleStep(0.05)
        self.notifications = QCheckBox("Mostrar en el teléfono las notificaciones del PC")
        self.lyrics = QCheckBox("Buscar letras de canciones en internet (lrclib.net)")
        self.usb = QCheckBox("Vigilar el cable USB y abrir la app al conectar el teléfono")
        form.addRow("Nombre", self.name)
        form.addRow("Modelo de lenguaje", self.model)
        form.addRow("Capas en la gráfica", self.gpu_layers)
        form.addRow("Hilos del procesador", self.threads)
        form.addRow("Memoria del modelo", self.keep_alive)
        form.addRow("Voz a texto", self.stt_model)
        form.addRow("…en", self.stt_device)
        form.addRow("Voz", self.voice)
        form.addRow("Velocidad de la voz", self.speed)
        card.body.addLayout(form)
        for c in (self.notifications, self.lyrics, self.usb):
            card.body.addWidget(c)
        lay.addWidget(card)

        row2 = QHBoxLayout()
        self.save_btn = pill("Guardar", accent=True)
        self.reload_btn = pill("Volver a leer")
        row2.addWidget(self.save_btn)
        row2.addWidget(self.reload_btn)
        row2.addStretch(1)
        lay.addLayout(row2)
        self.status = QLabel()
        self.status.setObjectName("muted")
        self.status.setWordWrap(True)
        lay.addWidget(self.status)
        lay.addStretch(1)
        self.save_btn.clicked.connect(self.save)
        self.reload_btn.clicked.connect(self.load)
        ctx.changed.connect(self._refresh_server)
        self.load()
        self._refresh_server()

    def load(self) -> None:
        r = config_edit.read_setting
        self.name.setText(str(r("assistant", "name") or ""))
        self.model.setText(str(r("llm", "model") or ""))
        self.gpu_layers.setValue(int(r("llm", "gpu_layers") if r("llm", "gpu_layers") is not None else 1))
        self.threads.setValue(int(r("llm", "threads") or 6))
        self.keep_alive.setText(str(r("llm", "keep_alive") or "12h"))
        self.stt_model.setCurrentText(str(r("speech", "stt_model") or "base"))
        self.stt_device.setCurrentText(str(r("speech", "stt_device") or "cpu"))
        self.speed.setValue(float(r("speech", "tts_speed") or 1.0))
        self.notifications.setChecked(bool(r("notifications", "enabled")))
        self.lyrics.setChecked(bool(r("media", "lyrics")))
        self.usb.setChecked(bool(r("device", "usb_watcher")))
        self._fill_voices(str(r("speech", "tts_voice") or ""))
        self.status.setText("")

    def _fill_voices(self, current: str) -> None:
        self.voice.clear()
        for v in self.ctx.state.voices:
            self.voice.addItem(f"{v['label']}  ({v['id']})", v["id"])
        i = self.voice.findData(current)
        if i >= 0:
            self.voice.setCurrentIndex(i)
        else:
            self.voice.setEditText(current)

    def save(self) -> None:
        voice = self.voice.currentData() or self.voice.currentText().strip()
        values: dict[tuple[str | None, str], Any] = {
            ("assistant", "name"): self.name.text().strip(),
            ("llm", "model"): self.model.text().strip(),
            ("llm", "gpu_layers"): self.gpu_layers.value(),
            ("llm", "threads"): self.threads.value(),
            ("llm", "keep_alive"): self.keep_alive.text().strip(),
            ("speech", "stt_model"): self.stt_model.currentText(),
            ("speech", "stt_device"): self.stt_device.currentText(),
            ("speech", "tts_voice"): voice,
            ("speech", "tts_speed"): round(self.speed.value(), 2),
            ("notifications", "enabled"): self.notifications.isChecked(),
            ("media", "lyrics"): self.lyrics.isChecked(),
            ("device", "usb_watcher"): self.usb.isChecked(),
        }
        try:
            config_edit.write_settings(values)
        except ConfigError as exc:
            self.status.setText(f"No se guardó: {exc}")
            return
        self.status.setText(
            "Guardado (con copia en assistant.toml.bak). Reinicia el servidor para aplicarlo."
        )

    # --- Servidor ---

    def _run(self, text: str, job: Any) -> None:
        self.job_text.setText(text)
        for b in (self.start_btn, self.restart_btn, self.stop_btn):
            b.setEnabled(False)
        self.job.run(job)

    def _job_done(self, text: str) -> None:
        self.job_text.setText(text)
        self._refresh_server()

    def _refresh_server(self) -> None:
        up = self.ctx.state.connected
        self.dot.set_color(theme.MINT if up else theme.RED)
        self.server_state.setText(f"En marcha · modelo {self.ctx.state.model}" if up else "Parado")
        self.start_btn.setEnabled(not up)
        self.restart_btn.setEnabled(up)
        self.stop_btn.setEnabled(up)
        if self.voice.count() == 0 and self.ctx.state.voices:
            self._fill_voices(str(config_edit.read_setting("speech", "tts_voice") or ""))
