from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings

SETTINGS = Settings(
    assistant=AssistantSettings(name="Prueba"),
    llm=LlmSettings(model="qwen3:8b"),
)


def client_with(status: LlmStatus) -> TestClient:
    async def probe() -> LlmStatus:
        return status

    return TestClient(create_app(SETTINGS, token="x" * 32, llm_probe=probe))


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
