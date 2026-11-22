"""Altavoces ESP32 «xiaozhi»: saludo, audio Opus en los dos sentidos y turnos de voz, con dobles de prueba."""

from __future__ import annotations

import json
from typing import Any

import numpy as np
import pytest
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession
from starlette.websockets import WebSocketDisconnect

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings, XiaozhiSettings
from server.memory.store import ConversationStore
from server.speech.opus import FRAME_SAMPLES, RATE, OpusDecoder, OpusEncoder
from server.tests.fakes import FakeChat, FakeSTT, FakeTTS
from server.ws.protocol import AudioEvent
from server.ws.xiaozhi import frame, unframe

TOKEN = "t" * 43
URL = "ws://127.0.0.1:8000/xiaozhi/v1/"


class SpeakingTTS(FakeTTS):
    """Una voz de mentira que dura un rato (la de FakeTTS dura menos de un milisegundo)."""

    def synthesize(self, text: str) -> bytes:
        super().synthesize(text)
        t = np.arange(int(self.sample_rate * 0.4)) / self.sample_rate
        return (np.sin(2 * np.pi * 220 * t) * 6000).astype(np.int16).tobytes()


def pcm_of(seconds: float, amplitude: int = 8000, freq: float = 300.0) -> bytes:
    t = np.arange(int(RATE * seconds)) / RATE
    return (np.sin(2 * np.pi * freq * t) * amplitude).astype(np.int16).tobytes()


def silence(seconds: float) -> bytes:
    return b"\x00\x00" * int(RATE * seconds)


def packets_of(pcm: bytes) -> list[bytes]:
    encoder = OpusEncoder()
    return encoder.encode(pcm) + encoder.flush()


def app_of(
    *,
    enabled: bool = True,
    stt: FakeSTT | None = None,
    tts: FakeTTS | None = None,
    chat: FakeChat | None = None,
) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    return create_app(
        Settings(
            assistant=AssistantSettings(name="Vega"),
            llm=LlmSettings(model="fake"),
            xiaozhi=XiaozhiSettings(enabled=enabled),
        ),
        token=TOKEN,
        llm_probe=probe,
        chat_model=chat or FakeChat(pieces=["Hola, ", "¿qué tal?"]),
        store=ConversationStore(":memory:"),
        stt=stt,
        tts=tts,
    )


def connect(app: Any, token: str = TOKEN, version: int = 1) -> Any:
    client = TestClient(app, base_url="http://127.0.0.1:8000")
    headers = {"Authorization": f"Bearer {token}", "Protocol-Version": str(version), "Device-Id": "aa:bb"}
    return client.websocket_connect(URL, headers=headers)


def hello(ws: WebSocketTestSession, version: int = 1) -> dict[str, Any]:
    ws.send_json(
        {
            "type": "hello",
            "version": version,
            "transport": "websocket",
            "features": {"mcp": True},
            "audio_params": {"format": "opus", "sample_rate": 16000, "channels": 1, "frame_duration": 60},
        }
    )
    reply: dict[str, Any] = ws.receive_json()
    return reply


def listen(ws: WebSocketTestSession, state: str, mode: str | None = None) -> None:
    ws.send_json({"type": "listen", "state": state, **({"mode": mode} if mode else {})})


def send_audio(ws: WebSocketTestSession, pcm: bytes, version: int = 1) -> None:
    for packet in packets_of(pcm):
        ws.send_bytes(frame(version, packet))


def turn(ws: WebSocketTestSession, version: int = 1) -> list[Any]:
    """Todo hasta el `tts stop`: mensajes JSON (dict) y paquetes de audio (bytes, ya sin marco)."""
    got: list[Any] = []
    while True:
        message = ws.receive()
        if (data := message.get("bytes")) is not None:
            got.append(unframe(version, data))
            continue
        item = json.loads(message["text"])
        got.append(item)
        if item.get("type") == "tts" and item.get("state") == "stop":
            return got


def kinds(items: list[Any]) -> list[str]:
    return [
        "audio" if isinstance(i, bytes) else (i["type"] + (":" + i["state"] if "state" in i else ""))
        for i in items
    ]


# --- Opus ---


def test_opus_round_trip_keeps_the_length_and_a_tone() -> None:
    pcm = pcm_of(1.0)
    packets = packets_of(pcm)
    decoder = OpusDecoder()
    back = np.frombuffer(b"".join(decoder.decode(p) for p in packets), dtype=np.int16)
    assert abs(back.size - RATE) < 0.1 * RATE  # el retraso propio de Opus, no más
    assert 4000 < int(np.abs(back).max()) < 12000


def test_encoder_makes_packets_of_sixty_milliseconds() -> None:
    encoder = OpusEncoder()
    packets = encoder.encode(pcm_of(1.0)) + encoder.flush()
    assert 16 <= len(packets) <= 18  # 1 s / 60 ms, más el relleno final
    assert all(0 < len(p) < 400 for p in packets)


def test_encoder_resamples_other_rates_and_keeps_the_remainder() -> None:
    encoder = OpusEncoder()
    pcm24 = (np.sin(2 * np.pi * 300 * np.arange(24000) / 24000) * 8000).astype(np.int16).tobytes()
    first = encoder.encode(pcm24[:10000], 24000)
    second = encoder.encode(pcm24[10000:], 24000) + encoder.flush()
    assert 16 <= len(first + second) <= 18


def test_a_corrupt_packet_gives_silence_not_an_error() -> None:
    decoder = OpusDecoder()
    assert decoder.decode(b"") == b""
    decoder.decode(b"\x00\x01garbage")  # no debe lanzar
    assert decoder.decode(packets_of(pcm_of(0.2))[0]) != b""  # y sigue funcionando después


def test_framing_round_trips_in_the_three_versions() -> None:
    packet = b"\x01\x02\x03" * 20
    for version in (1, 2, 3):
        assert unframe(version, frame(version, packet, 120)) == packet
    assert len(frame(2, packet)) == len(packet) + 16
    assert len(frame(3, packet)) == len(packet) + 4
    assert unframe(2, b"\x00\x02") == b"" and unframe(3, b"\x00") == b""  # cortos: se ignoran
    assert unframe(3, frame(3, packet)[:-1]) == b""  # tamaño que no cuadra: se ignora


# --- Saludo y acceso ---


def test_hello_is_answered_with_the_audio_parameters() -> None:
    with connect(app_of()) as ws:
        reply = hello(ws)
    assert reply["type"] == "hello" and reply["transport"] == "websocket"
    assert reply["session_id"]
    assert reply["audio_params"] == {
        "format": "opus",
        "sample_rate": 16000,
        "channels": 1,
        "frame_duration": 60,
    }


def test_a_wrong_token_is_refused() -> None:
    with pytest.raises(WebSocketDisconnect) as closed, connect(app_of(), token="x" * 43) as ws:
        ws.receive_json()
    assert closed.value.code == 4401


def test_the_first_message_must_be_a_hello() -> None:
    with pytest.raises(WebSocketDisconnect), connect(app_of()) as ws:
        ws.send_json({"type": "listen", "state": "start"})
        ws.receive_json()


def test_it_is_off_unless_enabled() -> None:
    with pytest.raises(WebSocketDisconnect), connect(app_of(enabled=False)) as ws:
        ws.receive_json()


# --- Turnos de voz ---


def test_a_manual_turn_hears_answers_and_speaks() -> None:
    stt, tts = FakeSTT("¿qué tal estás?"), SpeakingTTS()
    with connect(app_of(stt=stt, tts=tts)) as ws:
        hello(ws)
        listen(ws, "start", "manual")
        send_audio(ws, pcm_of(1.0))
        listen(ws, "stop")
        got = turn(ws)
    names = kinds(got)
    assert names[0] == "stt" and got[0]["text"] == "¿qué tal estás?"
    assert names[1] == "tts:start"
    assert "tts:sentence_start" in names and "audio" in names
    assert names[-1] == "tts:stop"
    assert names.index("tts:sentence_start") < names.index("audio")
    assert any(i.get("text") == "Hola, ¿qué tal?" for i in got if isinstance(i, dict) and "text" in i)
    assert tts.said == ["Hola, ¿qué tal?"]
    heard = len(stt.received[0]) / 2 / RATE
    assert 0.8 < heard < 1.2  # la placa mandó 1 s y eso es lo que oyó Whisper


def test_the_spoken_reply_is_valid_opus() -> None:
    with connect(app_of(stt=FakeSTT("hola"), tts=SpeakingTTS())) as ws:
        hello(ws)
        listen(ws, "start", "manual")
        send_audio(ws, pcm_of(0.6))
        listen(ws, "stop")
        got = turn(ws)
    audio = [i for i in got if isinstance(i, bytes)]
    assert audio
    decoder = OpusDecoder()
    assert all(decoder.decode(p) for p in audio)


def test_an_auto_turn_cuts_the_phrase_by_itself() -> None:
    stt = FakeSTT("¿qué hora es?")
    with connect(app_of(stt=stt, tts=SpeakingTTS())) as ws:
        hello(ws)
        listen(ws, "start", "auto")
        send_audio(ws, silence(0.8) + pcm_of(1.2) + silence(1.0))  # sin «stop»: el servidor oye dónde acaba
        got = turn(ws)
    assert got[0] == {"type": "stt", "text": "¿qué hora es?"}
    assert len(stt.received) == 1
    assert 0.9 < len(stt.received[0]) / 2 / RATE < 2.5


def test_pure_silence_in_auto_mode_is_not_transcribed() -> None:
    stt = FakeSTT("nada")
    with connect(app_of(stt=stt, tts=SpeakingTTS())) as ws:
        hello(ws)
        listen(ws, "start", "auto")
        send_audio(ws, silence(2.0))
        ws.send_json({"type": "abort"})  # para tener algo con lo que sincronizar
        assert turn(ws)[-1] == {"type": "tts", "state": "stop"}
    assert stt.received == []


@pytest.mark.parametrize("version", [2, 3])
def test_other_protocol_versions_work_both_ways(version: int) -> None:
    with connect(app_of(stt=FakeSTT("hola"), tts=SpeakingTTS()), version=version) as ws:
        hello(ws, version)
        listen(ws, "start", "manual")
        send_audio(ws, pcm_of(0.6), version)
        listen(ws, "stop")
        got = turn(ws, version)
    audio = [i for i in got if isinstance(i, bytes)]
    assert audio and all(OpusDecoder().decode(p) for p in audio)


def test_without_a_voice_the_reply_still_arrives_as_text() -> None:
    with connect(app_of(stt=FakeSTT("hola"), tts=None)) as ws:
        hello(ws)
        listen(ws, "start", "manual")
        send_audio(ws, pcm_of(0.5))
        listen(ws, "stop")
        got = turn(ws)
    assert kinds(got) == ["stt", "tts:start", "tts:sentence_start", "tts:stop"]
    assert got[2]["text"] == "Hola, ¿qué tal?"


def test_without_speech_recognition_the_board_gets_an_alert() -> None:
    with connect(app_of(stt=None, tts=None)) as ws:
        hello(ws)
        listen(ws, "start", "manual")
        send_audio(ws, pcm_of(0.5))
        listen(ws, "stop")
        alert = ws.receive_json()
    assert alert["type"] == "alert" and alert["emotion"] == "sad"


def test_an_abort_is_answered_with_a_stop() -> None:
    with connect(app_of(stt=FakeSTT(), tts=SpeakingTTS())) as ws:
        hello(ws)
        ws.send_json({"type": "abort", "reason": "wake_word_detected"})
        assert ws.receive_json() == {"type": "tts", "state": "stop"}


def test_noise_and_unknown_messages_do_not_break_the_session() -> None:
    stt = FakeSTT("hola")
    with connect(app_of(stt=stt, tts=SpeakingTTS())) as ws:
        hello(ws)
        ws.send_bytes(b"\xff\x00\x12")  # audio sin haber dicho «listen»: se ignora
        ws.send_text("esto no es json")
        ws.send_json({"type": "mcp", "payload": {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}})
        ws.send_json({"type": "algo-raro"})
        listen(ws, "start", "manual")
        ws.send_bytes(b"\xff\x00\x12")  # un paquete roto en mitad de la frase
        send_audio(ws, pcm_of(0.5))
        listen(ws, "stop")
        got = turn(ws)
    assert got[0]["type"] == "stt"


def test_two_questions_in_a_row_each_get_their_answer() -> None:
    stt = FakeSTT(texts=["primera", "segunda"])
    chat = FakeChat(pieces=["Vale."])
    with connect(app_of(stt=stt, tts=SpeakingTTS(), chat=chat)) as ws:
        hello(ws)
        for expected in ("primera", "segunda"):
            listen(ws, "start", "manual")
            send_audio(ws, pcm_of(0.5))
            listen(ws, "stop")
            assert turn(ws)[0]["text"] == expected


def test_the_header_of_the_audio_event_is_what_the_tts_reports() -> None:
    assert AudioEvent(seq=1, rate=24000, text="x").rate == 24000
    assert FRAME_SAMPLES == 960
