from pathlib import Path

from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings
from server.memory.store import ConversationStore

SETTINGS = Settings(
    assistant=AssistantSettings(name="Prueba"),
    llm=LlmSettings(model="qwen3:8b"),
)
TOKEN = "x" * 32
OK = LlmStatus(True, "ok")


def client_with(status: LlmStatus = OK) -> TestClient:
    async def probe() -> LlmStatus:
        return status

    app = create_app(
        SETTINGS,
        token=TOKEN,
        llm_probe=probe,
        store=ConversationStore(":memory:"),
    )
    return TestClient(app, base_url="http://127.0.0.1:8000")


def test_health_ok_when_ollama_answers() -> None:
    body = client_with(LlmStatus(True, "Ollama 0.40.7")).get("/health").json()
    assert body["status"] == "ok"
    assert body["assistant"] == "Prueba"
    assert body["llm"] == {"model": "qwen3:8b", "reachable": True, "detail": "Ollama 0.40.7"}


def test_health_degraded_when_ollama_is_down() -> None:
    resp = client_with(LlmStatus(False, "Ollama no responde")).get("/health")
    assert resp.status_code == 200
    assert resp.json()["status"] == "degraded"
    assert resp.json()["llm"]["reachable"] is False


def test_rejects_foreign_host_header() -> None:
    """Defensa contra DNS rebinding: un dominio ajeno que apunte a 127.0.0.1 no recibe nada."""
    resp = client_with().get("/health", headers={"host": "malicioso.example:8000"})
    assert resp.status_code == 400


def test_home_page_says_vega_is_running() -> None:
    resp = client_with().get("/")
    assert resp.status_code == 200 and "Vega está en marcha" in resp.text


def test_app_update_endpoints(tmp_path: Path) -> None:
    from fastapi import FastAPI

    from server.web import register_app_updates

    app = FastAPI()
    register_app_updates(app, tmp_path)
    client = TestClient(app)
    assert client.get("/app/version").json() == {"version_code": 0, "version_name": ""}
    assert client.get("/app/latest.apk").status_code == 404

    (tmp_path / "output-metadata.json").write_text(
        '{"elements": [{"versionCode": 42, "versionName": "0.1.0"}]}', encoding="utf-8"
    )
    (tmp_path / "app-debug.apk").write_bytes(b"PK")
    assert client.get("/app/version").json() == {"version_code": 42, "version_name": "0.1.0"}
    resp = client.get("/app/latest.apk")
    assert resp.content == b"PK" and resp.headers["content-type"] == "application/vnd.android.package-archive"


def test_phone_models_are_listed_and_served(tmp_path: Path) -> None:
    from fastapi import FastAPI

    from server.web import register_phone_models

    app = FastAPI()
    register_phone_models(app, tmp_path / "telefono")
    client = TestClient(app)
    assert client.get("/app/models").status_code == 404  # aún no hay modelos en el PC

    (tmp_path / "telefono" / "whisper-base").mkdir(parents=True)
    (tmp_path / "telefono" / "whisper-base" / "tokens.txt").write_bytes(b"abc")
    (tmp_path / "secreto.txt").write_text("token", encoding="utf-8")
    assert client.get("/app/models").json() == {"files": [{"path": "whisper-base/tokens.txt", "size": 3}]}
    assert client.get("/app/models/whisper-base/tokens.txt").content == b"abc"
    assert client.get("/app/models/../secreto.txt").status_code == 404
    assert client.get("/app/models/%2E%2E/secreto.txt").status_code == 404


def test_no_auto_docs() -> None:
    assert client_with().get("/docs").status_code == 404


def test_clean_shutdown_needs_the_token(monkeypatch: object) -> None:
    import signal
    import time

    import server.app as app_module

    raised: list[int] = []
    monkeypatch.setattr(app_module.signal, "raise_signal", raised.append)  # type: ignore[attr-defined]
    client = client_with()
    assert client.post("/admin/apagar").status_code == 403
    assert client.post("/admin/apagar", headers={"x-token": "otro"}).status_code == 403
    assert client.post("/admin/apagar", headers={"x-token": TOKEN}).status_code == 202
    time.sleep(0.4)
    assert raised == [signal.SIGINT]
