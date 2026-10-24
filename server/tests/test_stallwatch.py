import asyncio
import time
from pathlib import Path

from server.core.stallwatch import StallWatch, dump_threads


def test_dump_shows_every_thread() -> None:
    text = dump_threads()
    assert "MainThread" in text and "test_dump_shows_every_thread" in text


def test_a_stalled_loop_leaves_a_report(tmp_path: Path) -> None:
    """Si el bucle se traba (aquí, un `time.sleep` dentro de una corrutina), queda el archivo
    con la línea exacta en la que se quedó."""
    watch = StallWatch(tmp_path, threshold=0.3, every=0.1)

    async def main() -> None:
        watch.start(asyncio.get_running_loop())
        await asyncio.sleep(0.3)  # sano: late con normalidad
        assert watch.dumps == []
        time.sleep(1.0)  # cuelgue: el bucle no atiende
        await asyncio.sleep(0.2)
        watch.stop()

    asyncio.run(main())
    assert len(watch.dumps) == 1
    report = watch.dumps[0].read_text(encoding="utf-8")
    assert "sin atender" in report and "time.sleep(1.0)" in report
