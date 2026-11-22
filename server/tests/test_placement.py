"""El reparto de la IA entre la gráfica y el procesador según haya un juego abierto o no."""

from __future__ import annotations

import asyncio
import threading
from collections.abc import Coroutine
from typing import Any

from server.core.placement import GpuFollower, PlacementState


class FakeChat:
    def __init__(self) -> None:
        self.busy = False
        self.vram = 620
        self.layers: list[int] = []
        self.preloads = 0
        self.reloads: list[bool] = []

    async def vram_mib(self) -> int | None:
        return self.vram

    async def preload(self) -> None:
        self.preloads += 1

    async def place(self, gpu_layers: int, *, reload: bool = True) -> None:
        self.layers.append(gpu_layers)
        self.reloads.append(reload)
        self.vram = 3600 if gpu_layers < 0 else 620


class FakeStt:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str, str]] = []

    def place(self, model: str, device: str, compute_type: str) -> None:
        self.calls.append((model, device, compute_type))

    def warmup(self) -> None:
        pass


class World:
    def __init__(self) -> None:
        self.gaming = False
        self.free: int | None = 4700
        self.now = 1000.0


def make(
    world: World, chat: FakeChat, stt: FakeStt | None = None, state: PlacementState | None = None
) -> GpuFollower:
    return GpuFollower(
        chat=chat,
        stt=stt,
        gaming=lambda: world.gaming,
        free_mib=lambda: world.free,
        light_layers=1,
        free_layers=-1,
        stt_free=("small", "cuda", "int8_float16"),
        stt_game=("base", "cpu", "int8"),
        need_mib=4000,
        stt_mib=1000,
        back_after=20.0,
        clock=lambda: world.now,
        state=state,
    )


def run[T](coro: Coroutine[Any, Any, T]) -> T:
    return asyncio.run(coro)


def test_starts_on_the_gpu_when_there_is_room_and_no_game() -> None:
    w, chat, stt = World(), FakeChat(), FakeStt()
    f = make(w, chat, stt)
    assert run(f.step()) == "free"
    assert chat.layers == [-1]
    assert stt.calls == [("small", "cuda", "int8_float16")]


def test_starts_light_if_a_game_is_already_open() -> None:
    w, chat, stt = World(), FakeChat(), FakeStt()
    w.gaming = True
    f = make(w, chat, stt)
    assert run(f.step()) == "game"
    assert chat.layers == [1]
    assert stt.calls == [("base", "cpu", "int8")]


def test_starts_light_if_it_does_not_fit() -> None:
    w, chat = World(), FakeChat()
    w.free = 2000
    f = make(w, chat)
    assert run(f.step()) == "game"
    assert chat.layers == [1]


def test_leaves_the_gpu_as_soon_as_a_game_opens() -> None:
    w, chat, stt = World(), FakeChat(), FakeStt()
    f = make(w, chat, stt)
    run(f.step())
    w.gaming = True
    w.free = 1300
    assert run(f.step()) == "game"
    assert chat.layers == [-1, 1]
    assert stt.calls[-1] == ("base", "cpu", "int8")


def test_does_not_move_in_the_middle_of_a_reply() -> None:
    w, chat = World(), FakeChat()
    f = make(w, chat)
    run(f.step())
    w.gaming = True
    chat.busy = True
    assert run(f.step()) == "free"  # espera
    chat.busy = False
    assert run(f.step()) == "game"


def test_leaves_if_another_program_keeps_the_video_memory_for_a_while() -> None:
    w, chat = World(), FakeChat()
    f = make(w, chat)
    run(f.step())
    w.free = 100
    assert run(f.step()) == "free"  # empieza la cuenta
    w.now += 12
    assert run(f.step()) == "game"


def test_a_short_dip_in_free_video_memory_does_not_move_the_model() -> None:
    """Un vídeo o una pestaña dan picos breves: medido aquí, el vídeo libre bajó a ~500 MiB y volvió."""
    w, chat = World(), FakeChat()
    f = make(w, chat)
    run(f.step())
    w.free = 100
    run(f.step())
    w.now += 5
    w.free = 2000  # se recupera antes de los 10 s
    assert run(f.step()) == "free"
    w.now += 8
    w.free = 100  # otro pico: la cuenta empieza de nuevo
    assert run(f.step()) == "free"
    assert chat.layers == [-1]


def test_after_leaving_for_lack_of_video_it_waits_before_coming_back() -> None:
    w, chat = World(), FakeChat()
    f = make(w, chat)
    run(f.step())
    w.free = 100
    run(f.step())
    w.now += 12
    run(f.step())  # se va
    w.free = 5200
    run(f.step())
    w.now += 60
    assert run(f.step()) == "game"  # aún no: ha pasado poco desde que se fue
    w.now += 180
    assert run(f.step()) == "free"
    assert chat.layers == [-1, 1, -1]


def test_comes_back_only_after_the_game_has_been_closed_for_a_while() -> None:
    w, chat, stt = World(), FakeChat(), FakeStt()
    w.gaming = True
    f = make(w, chat, stt)
    run(f.step())
    w.gaming = False
    w.free = 5200
    run(f.step())  # empieza la cuenta
    assert f.mode == "game"
    w.now += 10
    assert run(f.step()) == "game"
    w.now += 15
    assert run(f.step()) == "free"
    assert chat.layers == [1, -1]


def test_a_game_reopening_resets_the_wait() -> None:
    w, chat = World(), FakeChat()
    w.gaming = True
    f = make(w, chat)
    run(f.step())
    w.gaming = False
    run(f.step())
    w.now += 15
    w.gaming = True
    run(f.step())
    w.gaming = False
    w.now += 10
    assert run(f.step()) == "game"  # la cuenta empezó de nuevo


def test_coming_back_counts_the_speech_model_when_it_was_on_the_cpu() -> None:
    w, chat, stt = World(), FakeChat(), FakeStt()
    w.gaming = True
    f = make(w, chat, stt)
    run(f.step())
    w.gaming = False
    w.free = 3500  # 3500 + 620 = 4120: cabe el modelo (4000) pero no con la voz a texto (+1000)
    run(f.step())
    w.now += 30
    assert run(f.step()) == "game"
    w.free = 4500
    assert run(f.step()) == "free"


def test_without_an_nvidia_card_nothing_changes() -> None:
    w, chat = World(), FakeChat()
    w.free = None
    f = make(w, chat)
    assert run(f.step()) is None
    assert chat.layers == []


def test_hybrid_with_the_phone_brain_keeps_the_model_ready_for_free_chat() -> None:
    w, chat = World(), FakeChat()
    state = PlacementState(hybrid=True)
    state.phone_brain = True
    f = make(w, chat, state=state)
    run(f.step())
    assert state.mode == "free" and state.ready and state.can_chat
    assert chat.preloads == 1  # nadie más lo cargaría: la IA está en el teléfono


def test_hybrid_does_not_load_the_model_if_the_pc_is_the_brain() -> None:
    w, chat = World(), FakeChat()
    state = PlacementState(hybrid=True)
    run(make(w, chat, state=state).step())
    assert chat.preloads == 0 and not state.can_chat


def test_a_game_takes_the_model_out_completely_with_the_phone_brain() -> None:
    w, chat = World(), FakeChat()
    state = PlacementState(hybrid=True)
    state.phone_brain = True
    f = make(w, chat, state=state)
    run(f.step())
    w.gaming = True
    run(f.step())
    assert state.mode == "game" and not state.can_chat
    assert chat.reloads[-1] is False  # se libera la gráfica y no se vuelve a cargar nada


def test_without_hybrid_the_phone_chat_is_never_ready() -> None:
    w, chat = World(), FakeChat()
    state = PlacementState(hybrid=False)
    state.phone_brain = True
    run(make(w, chat, state=state).step())
    assert not state.can_chat


def test_at_startup_the_speech_model_is_already_in_the_gpu_and_does_not_count_twice() -> None:
    w, chat, stt = World(), FakeChat(), FakeStt()
    w.free = 4601  # con la voz a texto ya cargada: cabe (hacen falta 4000), aunque 4601 < 5000
    f = make(w, chat, stt)
    assert run(f.step()) == "free"


class SlowStt(FakeStt):
    """Una voz a texto cuyo `place` tarda (el candado lo tiene otro hilo cargando el modelo)."""

    def __init__(self, release: threading.Event) -> None:
        super().__init__()
        self._release = release

    def place(self, model: str, device: str, compute_type: str) -> None:
        self._release.wait(5)
        super().place(model, device, compute_type)


def test_a_slow_speech_model_never_freezes_the_event_loop() -> None:
    """Si `place` esperase el candado en el hilo principal, el servidor (y el teléfono) se colgaría."""
    release = threading.Event()
    w, chat, stt = World(), FakeChat(), SlowStt(release)
    f = make(w, chat, stt)
    ticks = 0

    async def scenario() -> None:
        nonlocal ticks
        step = asyncio.create_task(f.step())
        for _ in range(20):  # mientras la voz a texto espera, el bucle sigue atendiendo
            await asyncio.sleep(0.01)
            ticks += 1
        assert not step.done()
        release.set()
        assert await step == "free"

    run(scenario())
    assert ticks == 20
    assert stt.calls == [("small", "cuda", "int8_float16")]
