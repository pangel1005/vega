from server.device.notifications import PcNotification, newest, title_and_body


def test_first_line_is_the_title_and_the_rest_the_text() -> None:
    assert title_and_body(["Ana", "¿Vienes a cenar?", "Responde"], "WhatsApp") == (
        "Ana",
        "¿Vienes a cenar? · Responde",
    )


def test_a_single_line_uses_the_app_as_title() -> None:
    assert title_and_body(["Nuevo mensaje"], "Discord") == ("Discord", "Nuevo mensaje")
    assert title_and_body(["", "  "], "Discord") == ("Discord", "")


def test_long_text_is_cut() -> None:
    title, body = title_and_body(["T" * 300, "x " * 400], "App")
    assert len(title) == 120 and title.endswith("…")
    assert len(body) <= 400


def test_newest_first_and_limited() -> None:
    items = [PcNotification(i, "A", "t", "", float(i), "") for i in range(30)]
    got = newest(items)
    assert len(got) == 15 and got[0].id == 29


class _Listener:
    """Centro de notificaciones de mentira (como el de Windows)."""

    def __init__(self, ids: list[int]) -> None:
        self.ids = ids

    async def get_notifications_async(self, kinds: object) -> list[object]:
        return [type("N", (), {"id": i})() for i in self.ids]

    def remove_notification(self, notification_id: int) -> None:
        self.ids.remove(notification_id)

    def clear_notifications(self) -> None:  # en apps de escritorio no hace nada (comprobado)
        pass


def test_clear_all_removes_every_notification_one_by_one() -> None:
    import asyncio

    from server.device.notifications import NotificationReader

    reader = NotificationReader()
    listener = _Listener(list(range(26)))  # más de las 15 que ve el teléfono
    reader._listener = listener
    assert asyncio.run(reader.dismiss(None)) is True
    assert listener.ids == []
    listener.ids = [7, 8]
    asyncio.run(reader.dismiss(7))
    assert listener.ids == [8]
