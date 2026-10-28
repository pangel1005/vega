"""El tiempo: bajada en segundo plano, copia local, respuestas en español y la parte de Ajustes."""

import asyncio
import json
from datetime import datetime
from pathlib import Path
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient

from server.app import LlmStatus, create_app
from server.core.config import AssistantSettings, LlmSettings, Settings, WeatherSettings
from server.memory.personal import PersonalMemory
from server.memory.store import ConversationStore
from server.skills.router import SkillRouter
from server.skills.weather import (
    Day,
    Forecast,
    WeatherService,
    answer,
    find_places,
    parse_forecast,
)
from server.tests.fakes import FakeChat
from server.tests.test_wake import TOKEN, until

NOW = 1_800_000_000.0

API = {
    "current": {
        "temperature_2m": 18.4,
        "apparent_temperature": 17.0,
        "weather_code": 1,
        "wind_speed_10m": 9.0,
    },
    "daily": {
        "time": ["2026-10-28", "2026-10-29", "2026-10-30"],
        "weather_code": [1, 63, 95],
        "temperature_2m_max": [24.0, 19.2, 17.0],
        "temperature_2m_min": [12.0, 11.0, 9.5],
        "precipitation_probability_max": [10, 80, 35],
    },
}


def forecast(**over: Any) -> Forecast:
    base: dict[str, Any] = {
        "fetched_at": NOW, "place": "Madrid", "temp": 18.4, "feels": 17.0, "code": 1, "wind": 9.0,
        "days": [Day("2026-10-28", 1, 24.0, 12.0, 10), Day("2026-10-29", 63, 19.2, 11.0, 80), Day("2026-10-30", 95, 17.0, 9.5, 35)],  # noqa: E501
    }  # fmt: skip
    return Forecast(**{**base, **over})


def ok_transport(calls: list[httpx.Request] | None = None) -> httpx.MockTransport:
    def handler(request: httpx.Request) -> httpx.Response:
        if calls is not None:
            calls.append(request)
        return httpx.Response(200, json=API)

    return httpx.MockTransport(handler)


# ---------- Bajada y copia ----------


def test_parse_forecast() -> None:
    f = parse_forecast(API, "Madrid", NOW)
    assert f.temp == 18.4 and len(f.days) == 3 and f.days[1].rain == 80 and f.place == "Madrid"
    with pytest.raises(KeyError):
        parse_forecast({"current": API["current"]}, "x", NOW)


def test_refresh_guarda_la_prevision_y_una_copia_en_disco(tmp_path: Path) -> None:
    cache = tmp_path / "w.json"
    calls: list[httpx.Request] = []
    svc = WeatherService(40.4, -3.7, "Madrid", cache, lambda: NOW, ok_transport(calls))
    assert svc.snapshot() is None
    assert asyncio.run(svc.refresh())
    assert svc.snapshot() and svc.snapshot().temp == 18.4  # type: ignore[union-attr]
    q = dict(calls[0].url.params)
    assert q["latitude"] == "40.4" and q["longitude"] == "-3.7"  # lo único que sale: las coordenadas
    assert set(q) == {"latitude", "longitude", "timezone", "forecast_days", "current", "daily"}
    # Otro arranque, sin red: usa la copia.
    again = WeatherService(40.4, -3.7, "Madrid", cache, lambda: NOW)
    assert again.snapshot() and again.snapshot().days[1].code == 63  # type: ignore[union-attr]
    # Si cambiaste de ciudad, la copia de la otra no vale.
    assert WeatherService(41.4, 2.2, "Barcelona", cache, lambda: NOW).snapshot() is None


def test_si_falla_la_red_se_sigue_con_lo_ultimo(tmp_path: Path) -> None:
    state = {"fail": False}

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500) if state["fail"] else httpx.Response(200, json=API)

    svc = WeatherService(1, 1, "X", tmp_path / "w.json", lambda: NOW, httpx.MockTransport(handler))
    assert asyncio.run(svc.refresh())
    state["fail"] = True
    assert not asyncio.run(svc.refresh())
    assert svc.snapshot() and svc.snapshot().temp == 18.4  # type: ignore[union-attr]


def test_una_respuesta_rara_no_rompe_nada(tmp_path: Path) -> None:
    junk = httpx.MockTransport(lambda r: httpx.Response(200, json={"hola": 1}))
    svc = WeatherService(1, 1, "X", tmp_path / "w.json", lambda: NOW, junk)
    assert not asyncio.run(svc.refresh()) and svc.snapshot() is None
    (tmp_path / "w.json").write_text("{no es json", encoding="utf-8")
    assert WeatherService(1, 1, "X", tmp_path / "w.json").snapshot() is None  # copia rota: se ignora


def test_buscar_ciudad() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.params["name"] == "Sevilla"
        return httpx.Response(
            200,
            json={
                "results": [
                    {
                        "name": "Sevilla",
                        "admin1": "Andalucía",
                        "country": "España",
                        "latitude": 37.39,
                        "longitude": -5.99,
                    }
                ]
            },
        )

    found = asyncio.run(find_places("Sevilla", httpx.MockTransport(handler)))
    assert [(p.label, p.latitude) for p in found] == [("Sevilla, Andalucía, España", 37.39)]
    assert asyncio.run(find_places("   ")) == []  # sin nombre ni siquiera se pregunta
    down = httpx.MockTransport(lambda r: httpx.Response(503))
    assert asyncio.run(find_places("x", down)) == []


# ---------- Qué se contesta ----------


def test_el_tiempo_de_ahora() -> None:
    text = answer("que tiempo hace", forecast(), NOW)
    assert text.startswith("Ahora hay 18 grados y está casi despejado.")
    assert "máxima de 24 y mínima de 12" in text and "lluvia" not in text  # solo 10 %


def test_manana_y_pasado_manana() -> None:
    assert "Mañana: con lluvia." in answer("que tiempo hara manana", forecast(), NOW)
    assert "80 % de probabilidad de lluvia" in answer("que tiempo hara manana", forecast(), NOW)
    assert "Pasado mañana: con tormenta." in answer("y pasado manana", forecast(), NOW)


def test_paraguas_segun_la_probabilidad() -> None:
    assert answer("llevo paraguas", forecast(), NOW).startswith("No parece")
    assert answer("va a llover manana", forecast(), NOW).startswith("Sí, lleva paraguas")
    assert answer("llovera pasado manana", forecast(), NOW).startswith("Puede ser")


def test_grados_negativos_y_uno_solo() -> None:
    cold = forecast(temp=-2.4, feels=-7.0)
    assert "Ahora hay menos 2 grados (sensación de menos 7)" in answer("que tiempo hace", cold, NOW)
    assert "Ahora hay 1 grado " in answer("que tiempo hace", forecast(temp=1.0, feels=1.0), NOW)


def test_un_dato_viejo_se_dice() -> None:
    old = forecast(fetched_at=NOW - 5 * 3600)
    assert "Es un dato hace 5 horas" in answer("que tiempo hace", old, NOW)
    assert "Es un dato" not in answer("que tiempo hace", forecast(), NOW)


# ---------- La habilidad ----------


def skills(weather: Any = None, ready: bool = False) -> SkillRouter:
    mem = PersonalMemory(ConversationStore(":memory:"))
    return SkillRouter(mem, now=lambda: datetime.fromtimestamp(NOW), weather=weather, weather_ready=ready)


def test_sin_activar_explica_como() -> None:
    reply = skills().handle("¿Qué tiempo hace?")
    assert reply and "Ajustes" in reply.text


def test_activado_pero_sin_dato_todavia() -> None:
    reply = skills(lambda: None, True).handle("¿qué tiempo hace hoy?")
    assert reply and "Todavía no he podido" in reply.text


def test_contesta_desde_la_copia_sin_red() -> None:
    reply = skills(lambda: forecast(), True).handle("Vega, ¿qué tiempo hará mañana?")
    assert reply and reply.text.startswith("Mañana:")


@pytest.mark.parametrize(
    "frase",
    [
        "¿cuánto tiempo le queda al temporizador?",  # el temporizador, no el clima
        "pon un temporizador de 5 minutos",
        "recuerda que no me gusta el calor",
        "no tengo tiempo",
    ],
)
def test_no_se_cuela_en_lo_que_no_es_el_tiempo(frase: str) -> None:
    reply = skills(lambda: forecast(), True).handle(frase)
    assert reply is None or "grados" not in reply.text


# ---------- Integración con el servidor ----------


def test_el_servidor_baja_el_tiempo_al_arrancar_y_lo_contesta(tmp_path: Path) -> None:
    async def probe() -> LlmStatus:
        return LlmStatus(True, "ok")

    settings = Settings(
        assistant=AssistantSettings(name="Vega"),
        llm=LlmSettings(model="fake"),
        weather=WeatherSettings(enabled=True, city="Madrid", latitude=40.4, longitude=-3.7),
    )
    app = create_app(
        settings, token=TOKEN, llm_probe=probe, chat_model=FakeChat(), store=ConversationStore(":memory:"),
        weather_transport=ok_transport(), weather_cache=tmp_path / "w.json",
    )  # fmt: skip
    ws: Any
    with (
        TestClient(app, base_url="http://127.0.0.1:8000") as client,
        client.websocket_connect("ws://127.0.0.1:8000/ws") as ws,
    ):
        ws.send_json({"type": "hello", "token": TOKEN})
        until(ws, "ready")
        said = ""
        for _ in range(40):  # la bajada ocurre en segundo plano justo al arrancar
            ws.send_json({"type": "chat", "text": "qué tiempo hace"})
            said = [e for e in until(ws, "done") if e["type"] == "done"][-1]["text"]
            if "grados" in said:
                break
        assert "18 grados" in said
    assert json.loads((tmp_path / "w.json").read_text(encoding="utf-8"))["place"] == "Madrid"


def test_los_ajustes_del_tiempo() -> None:
    assert not WeatherSettings().ready  # apagado de serie
    assert not WeatherSettings(enabled=True, city="X").ready  # sin coordenadas no cuenta
    assert WeatherSettings(enabled=True, latitude=1.0, longitude=2.0).ready
    with pytest.raises(ValueError):
        WeatherSettings(latitude=95.0)


def test_la_temperatura_de_la_grafica_sigue_siendo_del_pc() -> None:
    reply = skills(lambda: forecast(), True).handle("¿cómo está el PC? ¿qué temperatura tiene la gráfica?")
    assert reply and reply.text.startswith("El PC")
