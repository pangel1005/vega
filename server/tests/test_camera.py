"""La cámara del teléfono como cámara de Windows: encender, apagar y el aviso a la app."""

from __future__ import annotations

import os
import subprocess
from pathlib import Path
from typing import Any

import pytest
from fastapi.testclient import TestClient
from starlette.testclient import WebSocketTestSession

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.device.camera import CameraController, ensure_obs_config, obs_scene
from server.memory.store import ConversationStore
from server.tests.fakes import FakeChat
from server.tests.test_wake import until

TOKEN = "t" * 43


class FakeSystem:
    """Procesos de mentira: lo que arranca Popen «corre» hasta que taskkill lo cierra."""

    def __init__(self) -> None:
        self.running: set[str] = set()
        self.popen_calls: list[list[str]] = []
        self.run_calls: list[list[str]] = []
        self.tucked: list[str] = []

    def tuck(self, title: str) -> bool:
        self.tucked.append(title)
        return True

    def popen(self, args: list[str], **_: Any) -> None:
        self.popen_calls.append(args)
        self.running.add(Path(args[0]).name.lower())

    def run(self, cmd: list[str], **_: Any) -> subprocess.CompletedProcess[str]:
        self.run_calls.append(cmd)
        if cmd[0] == "taskkill":
            self.running.discard(cmd[-1].lower())
            return subprocess.CompletedProcess(cmd, 0, "", "")
        image = cmd[2].split("eq ")[1].lower()  # tasklist /FI "IMAGENAME eq x"
        return subprocess.CompletedProcess(
            cmd, 0, image if image in self.running else "INFO: no hay tareas", ""
        )


def controller(system: FakeSystem, tmp: Path, installed: bool = True, **kw: Any) -> CameraController:
    obs = tmp / "obs"
    obs.mkdir(exist_ok=True)
    if installed:
        (obs / "obs64.exe").write_text("x")
    return CameraController(
        "adb.exe",
        scrcpy=tmp / "scrcpy.exe" if installed else None,
        obs_dir=obs,
        popen=system.popen,
        run=system.run,
        sleep=lambda _: None,
        tuck=system.tuck,
        sentinel_dir=tmp / "sentinel",
        obs_home=kw.pop("obs_home", tmp / "obshome"),
        **kw,
    )


def test_turning_on_opens_scrcpy_with_the_camera_and_the_rotation_and_starts_obs(tmp_path: Path) -> None:
    system = FakeSystem()
    cam = controller(system, tmp_path, rotate=180, facing="front")
    assert not cam.is_on()
    cam.start()
    assert cam.is_on()
    scrcpy = system.popen_calls[0]
    assert "--video-source=camera" in scrcpy and "--camera-facing=front" in scrcpy
    assert "--capture-orientation=180" in scrcpy and "--no-audio" in scrcpy
    assert "--window-title=CamaraRedmi" in scrcpy  # OBS busca la ventana por este nombre
    assert "--startvirtualcam" in system.popen_calls[1]


def test_a_forced_close_of_obs_does_not_make_it_ask_for_safe_mode(tmp_path: Path) -> None:
    sentinel = tmp_path / "sentinel"
    sentinel.mkdir()
    (sentinel / "run_123").write_text("x")
    (sentinel / "otra_cosa").write_text("x")
    controller(FakeSystem(), tmp_path).start()
    assert not (sentinel / "run_123").exists() and (sentinel / "otra_cosa").exists()


def test_everything_stays_in_the_background(tmp_path: Path) -> None:
    """Sin ventanas a la vista: OBS en la bandeja, y la ventana de la cámara al fondo y sin borde."""
    system = FakeSystem()
    controller(system, tmp_path, size="1280x720").start()
    scrcpy, obs = system.popen_calls
    assert "--window-borderless" in scrcpy and "--window-width=1280" in scrcpy  # la imagen no se encoge
    assert "--minimize-to-tray" in obs and "--disable-updater" in obs
    assert system.tucked == ["CamaraRedmi"]


def test_switching_to_the_back_camera_restarts_scrcpy_but_not_obs(tmp_path: Path) -> None:
    system = FakeSystem()
    cam = controller(system, tmp_path)
    cam.start("front")
    cam.start("back")
    scrcpy_launches = [c for c in system.popen_calls if c[0].endswith("scrcpy.exe")]
    assert [c for c in scrcpy_launches[-1] if c.startswith("--camera-facing")] == ["--camera-facing=back"]
    assert len([c for c in system.popen_calls if c[0].endswith("obs64.exe")]) == 1
    assert cam.facing == "back"


def test_turning_off_closes_scrcpy_and_leaves_obs_in_the_tray_so_discord_keeps_the_camera(
    tmp_path: Path,
) -> None:
    system = FakeSystem()
    cam = controller(system, tmp_path)
    cam.start()
    cam.stop()
    assert not cam.is_on() and "obs64.exe" in system.running
    cam.start()  # al encenderla otra vez no se abre un OBS nuevo
    assert len([c for c in system.popen_calls if c[0].endswith("obs64.exe")]) == 1


def test_with_keep_obs_off_everything_is_closed(tmp_path: Path) -> None:
    system = FakeSystem()
    cam = controller(system, tmp_path, keep_obs=False)
    cam.start()
    cam.stop()
    assert not cam.is_on() and "obs64.exe" not in system.running


def test_without_scrcpy_or_obs_it_is_not_available_and_refuses_to_start(tmp_path: Path) -> None:
    cam = controller(FakeSystem(), tmp_path, installed=False)
    assert not cam.available
    with pytest.raises(RuntimeError, match="scrcpy"):
        cam.start()


def session(cam: CameraController) -> Any:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    app = create_app(
        Settings(assistant=AssistantSettings(name="Vega"), llm=LlmSettings(model="fake")),
        token=TOKEN,
        llm_probe=probe,
        chat_model=FakeChat(),
        store=ConversationStore(":memory:"),
        camera=cam,
    )
    return TestClient(app, base_url="http://127.0.0.1:8000").websocket_connect("ws://127.0.0.1:8000/ws")


def start(ws: WebSocketTestSession) -> dict[str, Any]:
    ws.send_json({"type": "hello", "token": TOKEN, "role": "phone"})
    return until(ws, "camera_state")[-1]


def test_the_app_is_told_the_camera_state_when_it_connects(tmp_path: Path) -> None:
    system = FakeSystem()
    with session(controller(system, tmp_path)) as ws:
        assert start(ws) == {"type": "camera_state", "available": True, "on": False, "facing": "front"}


def test_the_app_turns_the_camera_on_and_off_and_chooses_the_side(tmp_path: Path) -> None:
    system = FakeSystem()
    with session(controller(system, tmp_path)) as ws:
        start(ws)
        ws.send_json({"type": "camera", "on": True, "facing": "back"})
        assert until(ws, "camera_state")[-1] == {
            "type": "camera_state",
            "available": True,
            "on": True,
            "facing": "back",
        }
        ws.send_json({"type": "camera", "on": False})
        assert until(ws, "camera_state")[-1]["on"] is False


def test_if_the_programs_are_missing_the_app_gets_an_error_and_the_state(tmp_path: Path) -> None:
    with session(controller(FakeSystem(), tmp_path, installed=False)) as ws:
        assert start(ws)["available"] is False
        ws.send_json({"type": "camera", "on": True})
        events = until(ws, "camera_state")
        assert any(e["type"] == "error" and "scrcpy" in e["message"] for e in events)


# --- La escena de OBS: se instala sola y se repara ---


def test_the_obs_scene_is_installed_where_obs_looks_for_it(tmp_path: Path) -> None:
    home = tmp_path / "obs-studio"
    assert ensure_obs_config(home) is True
    scene = (home / "basic" / "scenes" / "Vega.json").read_text(encoding="utf-8")
    assert '"name": "Vega"' in scene and "CamaraRedmi" in scene
    assert (home / "basic" / "profiles" / "Vega" / "basic.ini").is_file()
    ini = (home / "user.ini").read_text(encoding="utf-8")
    assert "[Basic]" in ini and "SceneCollection=Vega" in ini and "Profile=Vega" in ini
    assert ensure_obs_config(home) is False  # la segunda vez no toca nada


def test_an_empty_or_foreign_collection_is_replaced(tmp_path: Path) -> None:
    home = tmp_path / "obs-studio"
    scene = home / "basic" / "scenes" / "Vega.json"
    scene.parent.mkdir(parents=True)
    scene.write_text('{"name": "Vega", "sources": [{"name": "Escena", "id": "scene"}]}', encoding="utf-8")
    assert ensure_obs_config(home) is True
    assert "CamaraRedmi" in scene.read_text(encoding="utf-8")


def test_the_rest_of_the_obs_settings_are_kept(tmp_path: Path) -> None:
    home = tmp_path / "obs-studio"
    home.mkdir()
    lines = [
        "[General]", "FirstRun=true", "", "[Basic]", "Profile=Sin título", "ConfigOnNewProfile=true", "",
        "[Appearance]", "FontScale=10",
    ]  # fmt: skip
    (home / "user.ini").write_text("\n".join(lines) + "\n", encoding="utf-8")
    ensure_obs_config(home)
    ini = (home / "user.ini").read_text(encoding="utf-8")
    assert "Profile=Vega" in ini and "Sin título" not in ini
    assert "FirstRun=true" in ini and "ConfigOnNewProfile=true" in ini and "FontScale=10" in ini
    assert ini.count("[Basic]") == 1


def test_the_scene_obs_is_showing_comes_from_its_newest_log(tmp_path: Path) -> None:
    home = tmp_path / "obs-studio"
    assert obs_scene(home) is None
    logs = home / "logs"
    logs.mkdir(parents=True)
    old = logs / "2026-11-11 08-28-24.txt"
    old.write_text("Switched to scene 'Camara'\n", encoding="utf-8")
    new = logs / "2026-11-21 11-02-20.txt"
    new.write_text("Loaded scenes:\nSwitched to scene 'Escena'\n", encoding="utf-8")
    os.utime(old, (1, 1))
    assert obs_scene(home) == "Escena"


def test_obs_open_with_the_wrong_scene_is_restarted_with_the_right_one(tmp_path: Path) -> None:
    system = FakeSystem()
    system.running.add("obs64.exe")  # lo abrieron a mano, sin la escena
    home = tmp_path / "obshome"
    (home / "logs").mkdir(parents=True)
    (home / "logs" / "a.txt").write_text("Switched to scene 'Escena'\n", encoding="utf-8")
    controller(system, tmp_path, obs_home=home).start()
    assert ["taskkill", "/IM", "obs64.exe"] in system.run_calls
    obs = [c for c in system.popen_calls if Path(c[0]).name == "obs64.exe"]
    assert len(obs) == 1 and "--collection" in obs[0] and "Vega" in obs[0]
    assert (home / "basic" / "scenes" / "Vega.json").is_file()


def test_obs_already_showing_the_camera_is_left_alone(tmp_path: Path) -> None:
    system = FakeSystem()
    system.running.add("obs64.exe")
    home = tmp_path / "obshome"
    (home / "logs").mkdir(parents=True)
    (home / "logs" / "a.txt").write_text("Switched to scene 'Camara'\n", encoding="utf-8")
    controller(system, tmp_path, obs_home=home).start()
    assert not any(c[0] == "taskkill" and c[-1] == "obs64.exe" for c in system.run_calls)
    assert not [c for c in system.popen_calls if Path(c[0]).name == "obs64.exe"]
