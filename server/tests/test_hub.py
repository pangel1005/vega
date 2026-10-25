import asyncio
from datetime import datetime, timedelta

from server.core.hub import Hub
from server.device.media import NowPlaying
from server.device.notifications import PcNotification
from server.memory.personal import PersonalMemory, Reminder
from server.memory.store import ConversationStore
from server.skills.status import PcSample
from server.ws.protocol import MediaEvent, NotificationsEvent, TelemetryEvent

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
        self.notifications: list[NotificationsEvent] = []
        self.events: list[object] = []
        self.actions_sent = 0

    async def announce(self, reminder: Reminder, late: bool) -> None:
        self.heard.append((reminder.text, late))

    async def send_telemetry(self, event: TelemetryEvent) -> None:
        self.telemetry.append(event)

    async def send_media(self, event: MediaEvent) -> None:
        self.media.append(event)

    async def send_notifications(self, event: NotificationsEvent) -> None:
        self.notifications.append(event)

    async def send_event(self, event: object) -> None:
        self.events.append(event)

    async def send_actions(self) -> None:
        self.actions_sent += 1


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


def _note(i: int, title: str = "Ana") -> PcNotification:
    return PcNotification(id=i, app="WhatsApp", title=title, body="¿Vienes?", at=1_000.0 + i, icon="ic")


class FakeNotifications:
    def __init__(self) -> None:
        self.items: list[PcNotification] | None = [_note(1)]
        self.dismissed: list[int | None] = []

    async def read(self) -> list[PcNotification] | None:
        return self.items

    async def dismiss(self, notification_id: int | None) -> bool:
        self.dismissed.append(notification_id)
        self.items = (
            [] if notification_id is None else [n for n in self.items or [] if n.id != notification_id]
        )
        return True

    def icon(self, icon_id: str) -> bytes | None:
        return b"png" if icon_id == "ic" else None


def test_pc_notifications_are_sent_when_they_change() -> None:
    notes, client = FakeNotifications(), Client()
    hub = Hub(PersonalMemory(ConversationStore(":memory:")), now=Clock(), notifications=notes)  # type: ignore[arg-type]
    hub.add(client)
    asyncio.run(hub.send_notifications())
    asyncio.run(hub.send_notifications())  # sin cambios: no se repite
    assert len(client.notifications) == 1
    assert client.notifications[0].items[0].title == "Ana"
    notes.items = [_note(2, "Luis"), _note(1)]  # llega otra
    asyncio.run(hub.send_notifications())
    assert [n.title for n in client.notifications[-1].items] == ["Luis", "Ana"]
    notes.items = None  # Windows no responde: se deja lo último que se mandó
    asyncio.run(hub.send_notifications())
    assert len(client.notifications) == 2
    assert hub.notification_icon("ic") == b"png"


def test_dismissing_from_the_phone_removes_it_on_the_pc() -> None:
    notes, client = FakeNotifications(), Client()
    notes.items = [_note(2), _note(1)]
    hub = Hub(PersonalMemory(ConversationStore(":memory:")), now=Clock(), notifications=notes)  # type: ignore[arg-type]
    hub.add(client)
    asyncio.run(hub.dismiss_notification(2))
    assert notes.dismissed == [2]
    assert [n.id for n in client.notifications[-1].items] == [1]
    asyncio.run(hub.dismiss_notification(None))
    assert client.notifications[-1].items == []


def test_desktop_sees_the_phone_and_controls_it() -> None:
    from server.ws.protocol import PhoneControlEvent, PhoneState, PhoneStateEvent

    hub = Hub(PersonalMemory(ConversationStore(":memory:")), now=Clock())
    phone, desktop = Client(), Client()
    hub.add(phone, "phone")
    hub.add(desktop, "desktop")
    asyncio.run(hub.send_phone_state(desktop))  # el teléfono aún no dijo nada, pero está conectado
    assert desktop.events == [PhoneStateEvent(online=True)]
    asyncio.run(hub.set_phone_state(PhoneState(brain="phone", sleeping=True, battery=80)))
    last = desktop.events[-1]
    assert isinstance(last, PhoneStateEvent) and last.brain == "phone" and last.sleeping and last.online
    assert phone.events == []  # el teléfono no recibe su propio estado
    asyncio.run(hub.control_phone("sleep"))
    assert phone.events == [PhoneControlEvent(command="sleep")]
    assert len(desktop.events) == 2  # y el escritorio no recibe las órdenes
    asyncio.run(hub.mirror(PhoneControlEvent(command="wake")))  # lo que pasa en el teléfono se refleja
    assert desktop.events[-1] == PhoneControlEvent(command="wake") and phone.events == [
        PhoneControlEvent(command="sleep")
    ]


def test_desktop_is_told_when_the_phone_leaves() -> None:
    from server.ws.protocol import PhoneStateEvent

    async def run() -> None:
        hub = Hub(PersonalMemory(ConversationStore(":memory:")), now=Clock())
        phone, desktop = Client(), Client()
        hub.add(phone, "phone")
        hub.add(desktop, "desktop")
        task = hub.remove(phone)
        assert task is not None
        await task
        assert desktop.events[-1] == PhoneStateEvent(online=False)
        assert hub.remove(desktop) is None  # irse el escritorio no avisa a nadie

    asyncio.run(run())
