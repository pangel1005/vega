import asyncio
import json
from pathlib import Path

import httpx

from server.device.lyrics import Line, LyricsFinder, from_api, parse_lrc

LRC = "[00:31.84] Some people think\n[00:35.18] Suffice to say\n[00:40.00]\n[01:02.5] Harvey"


def test_parse_lrc_keeps_order_and_pauses() -> None:
    lines = parse_lrc(LRC)
    assert lines[0] == Line(31.84, "Some people think")
    assert lines[2] == Line(40.0, "")  # pausa instrumental
    assert lines[3] == Line(62.5, "Harvey")


def test_prefers_synced_then_plain() -> None:
    assert from_api({"syncedLyrics": LRC, "plainLyrics": "x"}).synced is True
    plain = from_api({"syncedLyrics": None, "plainLyrics": "uno\ndos"})
    assert not plain.synced and [line.text for line in plain.lines] == ["uno", "dos"]
    assert from_api({"instrumental": True}).instrumental is True
    assert not from_api({}).found


def test_finds_once_and_caches(tmp_path: Path) -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(str(request.url))
        assert "track_name=Harvey" in str(request.url)
        assert request.headers["User-Agent"].startswith("Vega")
        return httpx.Response(200, json={"syncedLyrics": LRC})

    finder = LyricsFinder(tmp_path, transport=httpx.MockTransport(handler))
    first = asyncio.run(finder.find("Her's", "Harvey", "Invitation to Her's", 211))
    second = asyncio.run(finder.find("Her's", "Harvey"))
    assert first.synced and first == second
    assert len(calls) == 1  # la segunda vez sale del disco


def test_falls_back_to_search_and_remembers_not_found(tmp_path: Path) -> None:
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request.url.path)
        if request.url.path.endswith("/get"):
            return httpx.Response(404, json={})
        return httpx.Response(200, json=[])

    finder = LyricsFinder(tmp_path, transport=httpx.MockTransport(handler))
    assert not asyncio.run(finder.find("Nadie", "Inexistente")).found
    assert not asyncio.run(finder.find("Nadie", "Inexistente")).found
    assert calls == ["/api/get", "/api/search"]  # «no hay letra» también se recuerda
    assert json.loads(next(tmp_path.glob("*.json")).read_text(encoding="utf-8"))["lines"] == []


def test_network_error_is_not_cached_and_disabled_does_nothing(tmp_path: Path) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("sin internet", request=request)

    finder = LyricsFinder(tmp_path, transport=httpx.MockTransport(handler))
    assert not asyncio.run(finder.find("A", "B")).found
    assert list(tmp_path.glob("*.json")) == []  # se reintentará cuando haya internet
    off = LyricsFinder(tmp_path, enabled=False, transport=httpx.MockTransport(handler))
    assert not asyncio.run(off.find("A", "B")).found
