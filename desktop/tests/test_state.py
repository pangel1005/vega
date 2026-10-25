from desktop.state import DesktopState


def feed(state: DesktopState, *events: dict) -> DesktopState:
    for e in events:
        state.apply(e)
    return state


def test_conversation_is_built_from_events() -> None:
    s = feed(
        DesktopState(),
        {"type": "ready", "assistant": "Vega", "model": "qwen3.5:4b"},
        {
            "type": "history",
            "messages": [{"role": "user", "content": "hola"}, {"role": "assistant", "content": "¡Hola!"}],
        },
        {"type": "transcript", "text": "¿qué hora es?", "audio_seconds": 1.0, "elapsed_ms": 300},
        {"type": "token", "text": "Son "},
        {"type": "token", "text": "las 3."},
    )
    assert [(m.role, m.text, m.streaming) for m in s.messages] == [
        ("user", "hola", False),
        ("assistant", "¡Hola!", False),
        ("user", "¿qué hora es?", False),
        ("assistant", "Son las 3.", True),
    ]
    feed(s, {"type": "done", "text": "Son las 3."})
    assert s.messages[-1].streaming is False and s.messages[-1].text == "Son las 3."


def test_mood_follows_what_vega_is_doing() -> None:
    s = DesktopState()
    assert s.mood == "offline"
    feed(s, {"type": "ready", "assistant": "Vega", "model": "m"})
    assert s.mood == "idle"
    for wire, mood in (
        ("listening", "listen"),
        ("thinking", "think"),
        ("speaking", "talk"),
        ("error", "error"),
    ):
        feed(s, {"type": "state", "state": wire})
        assert s.mood == mood
    feed(s, {"type": "confirm", "nonce": "n", "id": "apagar", "message": "¿Seguro?", "expires_in": 10})
    assert s.mood == "alert"
    feed(
        s,
        {"type": "action_result", "id": "apagar", "ok": True, "message": "Hecho"},
        {"type": "state", "state": "idle"},
    )
    assert s.mood == "idle" and s.pending is None and s.notice == "Hecho"
    feed(s, {"type": "phone_state", "online": True, "sleeping": True})
    assert s.mood == "sleep"


def test_phone_state_and_disconnect() -> None:
    s = feed(
        DesktopState(),
        {"type": "ready", "assistant": "Vega", "model": "m"},
        {
            "type": "phone_state",
            "online": True,
            "brain": "phone",
            "battery": 80,
            "charging": True,
            "glass": True,
        },
        {"type": "telemetry", "cpu": 20, "ram_used": 10.0, "ram_total": 32.0, "upcoming": []},
        {"type": "media", "active": True, "title": "Canción", "artist": "Alguien"},
        {
            "type": "notifications",
            "items": [{"id": 1, "app": "WhatsApp", "title": "Ana", "body": "hola", "at": 1.0, "icon": ""}],
        },
    )
    assert s.phone.brain == "phone" and s.phone.battery == 80 and s.phone.glass and len(s.notifications) == 1
    assert s.media is not None and s.media["title"] == "Canción"
    feed(s, {"type": "media", "active": False})
    assert s.media is None
    s.disconnected()
    assert not s.connected and s.telemetry is None and not s.phone.online and s.mood == "offline"
    assert len(s.notifications) == 1  # lo que ya llegó no se borra al perder el enlace


def test_history_is_capped() -> None:
    s = DesktopState()
    for i in range(500):
        feed(s, {"type": "transcript", "text": f"frase {i}", "audio_seconds": 1, "elapsed_ms": 1})
    assert len(s.messages) == 200 and s.messages[-1].text == "frase 499"
