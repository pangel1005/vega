import asyncio
from datetime import datetime, timedelta

from server.core.hub import Hub
from server.device.media import NowPlaying
from server.memory.personal import PersonalMemory, Reminder
from server.memory.store import ConversationStore
from server.skills.status import PcSample
from server.ws.protocol import MediaEvent, TelemetryEvent

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
        self.media: list[MediaEvent] = []

    async def announce(self, reminder: Reminder, late: bool) -> None:
        self.heard.append((reminder.text, late))

    async def send_telemetry(self, event: TelemetryEvent) -> None:
        self.telemetry.append(event)

    async def send_media(self, event: MediaEvent) -> None:
        self.media.append(event)


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


def _song(position: float = 10.0, playing: bool = True, title: str = "Human Nature") -> NowPlaying:
    return NowPlaying(
        "Spotify", title, "Madonna", "Bedtime Stories", playing, position, 293.9, "abc", True, True
    )


class FakeMedia:
    def __init__(self) -> None:
        self.now: NowPlaying | None = _song()
        self.commands: list[str] = []

    async def snapshot(self) -> NowPlaying | None:
        return self.now

    async def control(self, command: str) -> bool:
        self.commands.append(command)
        if command == "play_pause" and self.now:
            self.now = _song(self.now.position, playing=not self.now.playing)
        return True

    def art(self, art_id: str) -> bytes | None:
        return b"jpg" if art_id == "abc" else None


def test_media_is_sent_only_when_it_changes() -> None:
    media, client = FakeMedia(), Client()
    hub = Hub(PersonalMemory(ConversationStore(":memory:")), now=Clock(), media=media)  # type: ignore[arg-type]
    hub.add(client)
    asyncio.run(hub.send_media())
    assert client.media[0].title == "Human Nature" and client.media[0].art == "abc"
    media.now = _song(position=11.0)  # avanza normal: el teléfono ya lo cuenta solo
    asyncio.run(hub.send_media())
    assert len(client.media) == 1
    media.now = _song(position=120.0)  # salto (alguien movió la barra): se manda
    asyncio.run(hub.send_media())
    media.now = _song(title="Secret")  # otra canción
    asyncio.run(hub.send_media())
    media.now = None  # se cerró Spotify
    asyncio.run(hub.send_media())
    assert [(m.active, m.title, m.position) for m in client.media[1:]] == [
        (True, "Human Nature", 120.0),
        (True, "Secret", 10.0),
        (False, "", 0.0),
    ]


def test_new_client_gets_what_is_playing_and_controls_work() -> None:
    media, first, second = FakeMedia(), Client(), Client()
    hub = Hub(PersonalMemory(ConversationStore(":memory:")), now=Clock(), media=media)  # type: ignore[arg-type]
    hub.add(first)
    asyncio.run(hub.send_media())
    hub.add(second)  # el teléfono se reconecta
    asyncio.run(hub.send_media())
    assert second.media and second.media[-1].title == "Human Nature"
    asyncio.run(hub.media_control("play_pause"))
    assert media.commands == ["play_pause"]
    assert first.media[-1].playing is False  # la pausa llega sin esperar a la siguiente vuelta
    assert hub.media_art("abc") == b"jpg" and hub.media_art("otro") is None
