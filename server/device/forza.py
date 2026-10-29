"""Forza Horizon por su «Data Out», la telemetría que el propio juego manda por UDP a esta máquina.

Se activa en el juego (Ajustes → Juego / HUD → «Data Out»): IP `127.0.0.1` y el puerto de
`[games] forza_port` (5300 por defecto). Cada paquete trae velocidad, marcha, vueltas del motor, potencia,
clase del coche y posición. Solo se escucha en 127.0.0.1 y solo mientras hay un Forza abierto; nada se guarda.

El formato es el de Forza Horizon 4 y 5 (324 bytes). Si el juego manda otro tamaño, se dice cuál para poder
ajustarlo: no se adivina.
"""

from __future__ import annotations

import socket
import struct
import threading
import time
from collections.abc import Callable
from typing import Any

from server.device.match import Match

DASH_SIZE = 324  # Forza Horizon 4 y 5: «Sled» (232 bytes) + 12 de relleno + «Dash» (80)
STALE_SECONDS = 3.0  # sin paquetes: en pausa o en el menú
CLASSES = ("D", "C", "B", "A", "S1", "S2", "X")
WATTS_PER_HP = 735.4987


def parse_dash(packet: bytes) -> dict[str, Any] | None:
    """Los campos que se enseñan de un paquete de Forza Horizon 4/5, o None si no tiene el tamaño conocido."""
    if len(packet) != DASH_SIZE:
        return None
    (race_on,) = struct.unpack_from("<i", packet, 0)
    rpm_max, rpm_idle, rpm = struct.unpack_from("<3f", packet, 8)
    car_class, index = struct.unpack_from("<2i", packet, 216)
    speed, power = struct.unpack_from("<2f", packet, 256)
    (lap,) = struct.unpack_from("<H", packet, 312)
    position, _accel, _brake, _clutch, _handbrake, gear = struct.unpack_from("<6B", packet, 314)
    return {
        "race_on": race_on == 1, "rpm_max": rpm_max, "rpm_idle": rpm_idle, "rpm": rpm, "class": car_class,
        "index": index, "speed": speed, "power": power, "lap": lap, "position": position, "gear": gear,
    }  # fmt: skip


def match_from(dash: dict[str, Any]) -> Match:
    kmh = max(0.0, float(dash["speed"])) * 3.6
    gear = "R" if dash["gear"] == 0 else str(dash["gear"])
    klass = CLASSES[dash["class"]] if 0 <= dash["class"] < len(CLASSES) else "?"
    stats = [
        ("Velocidad", f"{kmh:.0f} km/h"),
        ("Marcha", gear),
        ("RPM", f"{float(dash['rpm']):,.0f}".replace(",", " ")),
        ("Potencia", f"{max(0.0, float(dash['power'])) / WATTS_PER_HP:.0f} CV"),
        ("Clase", f"{klass} {dash['index']}"),
    ]
    if dash["position"] > 0:
        stats.append(("Posición", str(dash["position"])))
        stats.append(("Vuelta", str(dash["lap"] + 1)))
    title = "En carrera" if dash["position"] > 0 else "Conduciendo"
    return Match(title=title if dash["race_on"] else "En pausa", stats=tuple(stats))


class ForzaTelemetry:
    """Escucha en segundo plano los paquetes UDP de «Data Out» y guarda el último."""

    def __init__(self, port: int = 5300, *, now: Callable[[], float] = time.monotonic) -> None:
        self._port = port
        self._now = now
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._dash: dict[str, Any] | None = None
        self._heard_at = -1e9
        self._odd_size = 0

    def start(self) -> None:
        if self._thread is None or not self._thread.is_alive():
            self._stop.clear()
            self._thread = threading.Thread(target=self._run, daemon=True, name="forza-telemetry")
            self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._dash, self._odd_size = None, 0

    def feed(self, packet: bytes) -> None:
        dash = parse_dash(packet)
        if dash is None:
            self._odd_size = len(packet)
            return
        self._dash, self._heard_at, self._odd_size = dash, self._now(), 0

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                sock.bind(("127.0.0.1", self._port))
                sock.settimeout(1.0)
            except OSError:
                self._stop.wait(2.0)  # el puerto está ocupado
                continue
            try:
                while not self._stop.is_set():
                    try:
                        self.feed(sock.recv(2048))
                    except TimeoutError:
                        continue
                    except OSError:
                        break
            finally:
                sock.close()

    def _live(self) -> Match | None:
        if self._dash is not None and self._now() - self._heard_at < STALE_SECONDS:
            return match_from(self._dash)
        return None

    def _hint(self) -> Match:
        if self._odd_size:
            return Match(
                hint=f"Forza manda paquetes de {self._odd_size} bytes y espero de {DASH_SIZE} (Horizon 4/5): "
                "este Forza usa otro formato y hay que ajustarlo."
            )
        return Match(
            hint="Para ver la telemetría, activa «Data Out» en los ajustes de Forza: IP 127.0.0.1 y puerto "
            f"{self._port}."
        )

    def read(self) -> Match | None:
        self.start()
        return self._live() or self._hint()
