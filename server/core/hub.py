"""Centro de avisos: lleva la cuenta de los clientes conectados, les entrega los
recordatorios cuando vencen y les manda la telemetría (estado del PC y próximos avisos)
para el panel del teléfono.

Un recordatorio solo se marca como entregado si había alguien conectado: si el
teléfono estaba desenchufado, se avisa en cuanto se vuelve a conectar.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from datetime import datetime
from typing import Protocol

from server.memory.personal import PersonalMemory, Reminder
from server.skills.status import PcSample, sample_pc
from server.ws.protocol import TelemetryEvent, Upcoming

log = logging.getLogger(__name__)

CHECK_SECONDS = 1.0
TELEMETRY_EVERY = 2  # vueltas de CHECK_SECONDS entre lecturas del PC
UPCOMING_MAX = 3


class Listener(Protocol):
    async def announce(self, reminder: Reminder, late: bool) -> None: ...

    async def send_telemetry(self, event: TelemetryEvent) -> None: ...


class Hub:
    def __init__(
        self,
        memory: PersonalMemory,
        now: Callable[[], datetime] = datetime.now,
        sample: Callable[[], PcSample] = sample_pc,
    ) -> None:
        self._memory = memory
        self._now = now
        self._sample = sample
        self._clients: set[Listener] = set()
        self._task: asyncio.Task[None] | None = None

    def add(self, client: Listener) -> None:
        self._clients.add(client)

    def remove(self, client: Listener) -> None:
        self._clients.discard(client)

    async def deliver_due(self) -> int:
        """Entrega los recordatorios vencidos. Devuelve cuántos se entregaron."""
        if not self._clients:
            return 0
        now = self._now()
        due = await asyncio.to_thread(self._memory.due, now)
        for reminder in due:
            late = (now - reminder.due_at).total_seconds() > 60  # p. ej. el teléfono estaba desenchufado
            for client in list(self._clients):
                try:
                    await client.announce(reminder, late)
                except Exception:  # un cliente que se cae no debe impedir avisar a los demás
                    log.exception("No pude avisar a un cliente")
            await asyncio.to_thread(self._memory.mark_delivered, reminder.id)
            log.info("Recordatorio entregado: %s", reminder.text)
        return len(due)

    def telemetry(self) -> TelemetryEvent:
        """Lee el PC y los próximos avisos (bloquea un poco: llamar en un hilo)."""
        pc = self._sample()
        now = self._now()
        upcoming = [
            Upcoming(kind=r.kind, text=r.text, due_in=max(0, round((r.due_at - now).total_seconds())))
            for r in self._memory.pending()[:UPCOMING_MAX]
        ]
        return TelemetryEvent(
            cpu=pc.cpu,
            ram_used=round(pc.ram_used, 1),
            ram_total=round(pc.ram_total, 1),
            gpu_temp=pc.gpu_temp,
            gpu_util=pc.gpu_util,
            vram_used=None if pc.vram_used is None else round(pc.vram_used, 1),
            vram_total=None if pc.vram_total is None else round(pc.vram_total, 1),
            upcoming=upcoming,
        )

    async def send_telemetry(self) -> None:
        if not self._clients:
            return
        event = await asyncio.to_thread(self.telemetry)
        for client in list(self._clients):
            try:
                await client.send_telemetry(event)
            except Exception:
                log.debug("No pude mandar la telemetría a un cliente", exc_info=True)

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._loop())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()

    async def _loop(self) -> None:
        tick = 0
        while True:
            try:
                await self.deliver_due()
            except Exception:
                log.exception("Fallo al revisar recordatorios")
            if tick % TELEMETRY_EVERY == 0:
                try:
                    await self.send_telemetry()
                except Exception:
                    log.exception("Fallo al leer la telemetría")
            tick += 1
            await asyncio.sleep(CHECK_SECONDS)
