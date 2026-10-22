"""Estado del PC (procesador, memoria, tarjeta gráfica) y del teléfono, dicho en voz alta."""

from __future__ import annotations

import logging

import psutil

log = logging.getLogger(__name__)


def _gpu() -> tuple[int, float, float, int] | None:
    """(temperatura °C, GB usados, GB totales, % de uso) de la primera GPU NVIDIA."""
    try:
        import pynvml

        pynvml.nvmlInit()
        try:
            h = pynvml.nvmlDeviceGetHandleByIndex(0)
            mem = pynvml.nvmlDeviceGetMemoryInfo(h)
            temp = int(pynvml.nvmlDeviceGetTemperature(h, pynvml.NVML_TEMPERATURE_GPU))
            util = int(pynvml.nvmlDeviceGetUtilizationRates(h).gpu)
            return temp, float(mem.used) / 2**30, float(mem.total) / 2**30, util
        finally:
            pynvml.nvmlShutdown()
    except Exception as exc:  # sin GPU NVIDIA o sin controlador: se omite
        log.debug("Sin datos de la GPU: %s", exc)
        return None


def _gb(value: float) -> str:
    return f"{value:.1f}".replace(".", ",")


def pc_status(phone_battery: int | None = None) -> str:
    cpu = round(psutil.cpu_percent(interval=0.3))
    ram = psutil.virtual_memory()
    parts = [f"procesador al {cpu} %", f"memoria {_gb(ram.used / 2**30)} de {_gb(ram.total / 2**30)} GB"]
    gpu = _gpu()
    if gpu:
        temp, used, total, util = gpu
        parts.append(f"la gráfica a {temp} grados, al {util} % y con {_gb(used)} de {_gb(total)} GB ocupados")
    hot = gpu is not None and gpu[0] >= 80
    text = ("Ojo, el PC va caliente: " if hot else "El PC va bien: ") + ", ".join(parts) + "."
    if phone_battery is not None:
        text += f" Al teléfono le queda un {phone_battery} % de batería."
    return text
