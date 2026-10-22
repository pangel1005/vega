import asyncio
from datetime import datetime, timedelta

from server.core.hub import Hub
from server.memory.personal import PersonalMemory, Reminder
from server.memory.store import ConversationStore
from server.skills.status import PcSample
from server.ws.protocol import TelemetryEvent

NOW = datetime(2026, 10, 22, 15, 0, 0)


class Clock:
    def __init__(self) -> None:
        self.t = NOW

    def __call__(self) -> datetime:
        return self.t


class Client:
    def __init__(self) -> None:
        self.heard: list[tuple[str, bool]] = []
        self.telemetry: list[TelemetryEvent] = []

    async def announce(self, reminder: Reminder, late: bool) -> None:
        self.heard.append((reminder.text, late))

    async def send_telemetry(self, event: TelemetryEvent) -> None:
        self.telemetry.append(event)


def test_reminder_is_delivered_when_due() -> None:
    mem, clock, client = PersonalMemory(ConversationStore(":memory:")), Clock(), Client()
    hub = Hub(mem, now=clock)
    hub.add(client)
    mem.add_reminder("reminder", "estirar", NOW + timedelta(minutes=5))

    assert asyncio.run(hub.deliver_due()) == 0
    clock.t = NOW + timedelta(minutes=5)
    assert asyncio.run(hub.deliver_due()) == 1
    assert client.heard == [("estirar", False)]
    assert asyncio.run(hub.deliver_due()) == 0  # solo una vez


def test_disconnected_phone_gets_it_later_marked_as_late() -> None:
    mem, clock, client = PersonalMemory(ConversationStore(":memory:")), Clock(), Client()
    hub = Hub(mem, now=clock)
    mem.add_reminder("reminder", "llamar a mamá", NOW + timedelta(minutes=1))
    clock.t = NOW + timedelta(minutes=30)
    assert asyncio.run(hub.deliver_due()) == 0  # nadie conectado: sigue pendiente
    hub.add(client)  # se conecta el teléfono
    assert asyncio.run(hub.deliver_due()) == 1
    assert client.heard == [("llamar a mamá", True)]


def test_all_connected_clients_hear_it() -> None:
    mem, clock = PersonalMemory(ConversationStore(":memory:")), Clock()
    a, b = Client(), Client()
    hub = Hub(mem, now=clock)
    hub.add(a)
    hub.add(b)
    mem.add_reminder("timer", "temporizador de 5 minutos", NOW)
    asyncio.run(hub.deliver_due())
    assert a.heard == b.heard == [("temporizador de 5 minutos", False)]


def test_telemetry_has_pc_and_upcoming_reminders() -> None:
    mem, clock, client = PersonalMemory(ConversationStore(":memory:")), Clock(), Client()
    pc = PcSample(
        cpu=12, ram_used=10.04, ram_total=31.9, gpu_temp=45, gpu_util=7, vram_used=5.12, vram_total=8.0
    )
    hub = Hub(mem, now=clock, sample=lambda: pc)
    mem.add_reminder("timer", "temporizador de 5 minutos", NOW + timedelta(minutes=5))
    mem.add_reminder("reminder", "llamar a mamá", NOW + timedelta(minutes=1))

    asyncio.run(hub.send_telemetry())
    assert client.telemetry == []  # nadie conectado: ni se lee el PC
    hub.add(client)
    asyncio.run(hub.send_telemetry())
    t = client.telemetry[0]
    assert (t.cpu, t.ram_used, t.gpu_temp, t.vram_used) == (12, 10.0, 45, 5.1)
    assert [(u.text, u.due_in) for u in t.upcoming] == [
        ("llamar a mamá", 60),
        ("temporizador de 5 minutos", 300),
    ]


def test_telemetry_without_gpu() -> None:
    hub = Hub(
        PersonalMemory(ConversationStore(":memory:")), now=Clock(), sample=lambda: PcSample(3, 1.0, 2.0)
    )
    t = hub.telemetry()
    assert t.gpu_temp is None and t.vram_total is None and t.upcoming == []
