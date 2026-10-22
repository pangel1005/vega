"""Centro de avisos: lleva la cuenta de los clientes conectados y les entrega los
recordatorios cuando vencen.

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

log = logging.getLogger(__name__)

CHECK_SECONDS = 1.0


class Listener(Protocol):
    async def announce(self, reminder: Reminder, late: bool) -> None: ...


class Hub:
    def __init__(self, memory: PersonalMemory, now: Callable[[], datetime] = datetime.now) -> None:
        self._memory = memory
        self._now = now
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

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._loop())

    async def stop(self) -> None:
        if self._task is not None:
            self._task.cancel()

    async def _loop(self) -> None:
        while True:
            try:
                await self.deliver_due()
            except Exception:
                log.exception("Fallo al revisar recordatorios")
            await asyncio.sleep(CHECK_SECONDS)
