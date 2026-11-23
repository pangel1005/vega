"""El instalador: ajustes según la tarjeta gráfica, el modelo de Ollama y las voces (sin red de verdad)."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Any

import httpx

from server.core.config import EXAMPLE_CONFIG, load_settings
from server.tools import install


def test_set_values_changes_only_the_key_in_its_section() -> None:
    text = '[llm]\n# comentario\ngpu_layers = 1   # nota\n\n[speech]\ngpu_layers = 9\nstt_device = "cuda"\n'
    out = install.set_values(text, {("llm", "gpu_layers"): "0", ("speech", "stt_device"): '"cpu"'})
    assert out == '[llm]\n# comentario\ngpu_layers = 0\n\n[speech]\ngpu_layers = 9\nstt_device = "cpu"\n'


def config_from_example(tmp_path: Path, vram: int | None) -> tuple[str, Any]:
    path = tmp_path / "assistant.toml"
    message = install.prepare_config(path, vram=lambda: vram)
    return message, load_settings(path)


def test_without_nvidia_everything_runs_on_the_processor(tmp_path: Path) -> None:
    message, s = config_from_example(tmp_path, None)
    assert "sin tarjeta NVIDIA" in message
    assert (s.speech.stt_device, s.speech.stt_model, s.speech.stt_compute) == ("cpu", "base", "int8")
    assert s.llm.gpu_layers == 0 and s.llm.follow_game is False


def test_a_small_gpu_keeps_more_of_the_model_on_the_processor(tmp_path: Path) -> None:
    _, s = config_from_example(tmp_path, 4096)
    assert s.llm.gpu_layers_free == 8 and s.llm.gpu_free_mib == 2500
    assert s.speech.stt_device == "cuda"


def test_a_big_gpu_keeps_the_defaults(tmp_path: Path) -> None:
    _, s = config_from_example(tmp_path, 8188)
    default = load_settings(EXAMPLE_CONFIG)
    assert s.llm == default.llm and s.speech == default.speech


def test_existing_settings_are_never_touched(tmp_path: Path) -> None:
    path = tmp_path / "assistant.toml"
    path.write_text('[assistant]\nname = "Nova"\n', encoding="utf-8")
    message = install.prepare_config(path, vram=lambda: None)
    assert "no los toco" in message and path.read_text(encoding="utf-8") == '[assistant]\nname = "Nova"\n'


def test_nvidia_vram_is_read_from_nvidia_smi(monkeypatch: Any) -> None:
    monkeypatch.setattr("shutil.which", lambda _: "nvidia-smi")

    def run(cmd: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
        return subprocess.CompletedProcess(cmd, 0, "8188\n", "")

    assert install.nvidia_vram_mib(run) == 8188
    monkeypatch.setattr("shutil.which", lambda _: None)
    assert install.nvidia_vram_mib(run) is None


def ollama(models: list[str], pulled: list[str]) -> httpx.Client:
    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/tags":
            return httpx.Response(200, json={"models": [{"name": m} for m in models]})
        if request.url.path == "/api/pull":
            pulled.append(json.loads(request.content)["model"])
            lines = [{"status": "pulling", "total": 100, "completed": 50}, {"status": "success"}]
            return httpx.Response(200, content="\n".join(json.dumps(x) for x in lines).encode())
        return httpx.Response(404)

    return httpx.Client(transport=httpx.MockTransport(handler))


def test_the_model_is_pulled_only_if_missing() -> None:
    pulled: list[str] = []
    assert "ya está" in install.pull_model("http://o", "qwen3.5:4b", ollama(["qwen3.5:4b"], pulled))
    assert pulled == []
    assert "listo" in install.pull_model("http://o", "qwen3.5:4b", ollama([], pulled))
    assert pulled == ["qwen3.5:4b"]


def test_ollama_down_is_explained() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no", request=request)

    client = httpx.Client(transport=httpx.MockTransport(handler))
    assert "no responde" in install.pull_model("http://o", "m", client)


def test_voices_are_downloaded_once(tmp_path: Path) -> None:
    asked: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        asked.append(request.url.path.rsplit("/", 1)[-1])
        return httpx.Response(200, content=b"datos")

    client = httpx.Client(transport=httpx.MockTransport(handler))
    assert install.download_voices(tmp_path, client) == "Voces listas."
    assert sorted(asked) == ["kokoro-v1.0.onnx", "voices-v1.0.bin"]
    assert (tmp_path / "kokoro-v1.0.onnx").read_bytes() == b"datos"
    assert not list(tmp_path.glob("*.part"))
    assert install.download_voices(tmp_path, client) == "Las voces ya están."
    assert len(asked) == 2
