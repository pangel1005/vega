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


def client_with(status: LlmStatus = OK, ui_dist: Path | None = None) -> TestClient:
    async def probe() -> LlmStatus:
        return status

    app = create_app(
        SETTINGS,
        token=TOKEN,
        llm_probe=probe,
        store=ConversationStore(":memory:"),
        ui_dist=ui_dist or Path("no-existe"),
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


def test_page_gets_token_injected(tmp_path: Path) -> None:
    (tmp_path / "assets").mkdir()
    (tmp_path / "assets" / "app.js").write_text("console.log(1)", encoding="utf-8")
    (tmp_path / "index.html").write_text(
        '<meta name="assistant-token" content="__ASSISTANT_TOKEN__">', encoding="utf-8"
    )
    client = client_with(ui_dist=tmp_path)

    resp = client.get("/")
    assert resp.status_code == 200
    assert f'content="{TOKEN}"' in resp.text
    assert resp.headers["cache-control"] == "no-store"
    assert client.get("/assets/app.js").text == "console.log(1)"


def test_missing_ui_explains_how_to_build() -> None:
    resp = client_with().get("/")
    assert resp.status_code == 503
    assert "npm run build" in resp.text


def test_no_auto_docs() -> None:
    assert client_with().get("/docs").status_code == 404
