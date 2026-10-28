"""El tiempo, con Open-Meteo (gratis, sin clave).

Es opcional y viene apagado: sale a internet, así que hay que activarlo a propósito en Ajustes con la
ciudad. Lo único que se envía son las coordenadas de esa ciudad (no tu dirección, tu nombre ni tu voz).

El servidor baja la previsión en segundo plano cada media hora y la guarda en `data/weather.json`;
las respuestas salen siempre de esa copia, al instante y sin red. Si internet falla, se sigue
contestando con lo último que se bajó, diciendo de cuándo es.
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import httpx

log = logging.getLogger(__name__)

GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
REFRESH_S = 1800.0
RETRY_S = 300.0
STALE_S = 3 * 3600.0  # a partir de aquí se avisa de que el dato es viejo

# Códigos del tiempo de la OMM (WMO) que usa Open-Meteo, en el español que se dice en voz alta.
WMO = {
    0: "despejado", 1: "casi despejado", 2: "parcialmente nublado", 3: "nublado",
    45: "con niebla", 48: "con niebla helada",
    51: "con llovizna débil", 53: "con llovizna", 55: "con llovizna intensa",
    56: "con llovizna helada", 57: "con llovizna helada intensa",
    61: "con lluvia débil", 63: "con lluvia", 65: "con lluvia fuerte",
    66: "con lluvia helada", 67: "con lluvia helada fuerte",
    71: "con nieve débil", 73: "con nieve", 75: "con nieve fuerte", 77: "con granizo fino",
    80: "con chubascos débiles", 81: "con chubascos", 82: "con chubascos fuertes",
    85: "con nevadas débiles", 86: "con nevadas fuertes",
    95: "con tormenta", 96: "con tormenta y granizo", 99: "con tormenta y granizo fuerte",
}  # fmt: skip


@dataclass(frozen=True)
class Day:
    date: str  # AAAA-MM-DD
    code: int
    tmax: float
    tmin: float
    rain: int  # % de probabilidad de lluvia


@dataclass(frozen=True)
class Forecast:
    fetched_at: float
    place: str
    temp: float
    feels: float
    code: int
    wind: float  # km/h
    days: list[Day]


@dataclass(frozen=True)
class Place:
    name: str
    region: str
    country: str
    latitude: float
    longitude: float

    @property
    def label(self) -> str:
        return ", ".join(p for p in (self.name, self.region, self.country) if p)


def parse_forecast(data: dict[str, Any], place: str, fetched_at: float) -> Forecast:
    """Convierte la respuesta de Open-Meteo. Lanza ValueError/KeyError/TypeError si no es lo esperado."""
    cur = data["current"]
    daily = data["daily"]
    days = [
        Day(
            str(daily["time"][i]),
            int(daily["weather_code"][i]),
            float(daily["temperature_2m_max"][i]),
            float(daily["temperature_2m_min"][i]),
            int(daily["precipitation_probability_max"][i] or 0),
        )
        for i in range(len(daily["time"]))
    ]
    if not days:
        raise ValueError("sin previsión por días")
    return Forecast(
        fetched_at, place, float(cur["temperature_2m"]), float(cur["apparent_temperature"]),
        int(cur["weather_code"]), float(cur["wind_speed_10m"]), days,
    )  # fmt: skip


def _to_json(f: Forecast) -> dict[str, Any]:
    return {
        "fetched_at": f.fetched_at, "place": f.place, "temp": f.temp, "feels": f.feels, "code": f.code,
        "wind": f.wind, "days": [vars(d) for d in f.days],
    }  # fmt: skip


def _from_json(d: dict[str, Any]) -> Forecast:
    return Forecast(
        float(d["fetched_at"]), str(d["place"]), float(d["temp"]), float(d["feels"]), int(d["code"]),
        float(d["wind"]), [Day(**x) for x in d["days"]],
    )  # fmt: skip


class WeatherService:
    """Guarda la última previsión y la renueva en segundo plano (`run`)."""

    def __init__(
        self,
        latitude: float,
        longitude: float,
        place: str,
        cache: Path | None = None,
        now: Callable[[], float] = time.time,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._lat, self._lon, self._place = latitude, longitude, place
        self._cache = cache
        self._now = now
        self._transport = transport
        self._forecast: Forecast | None = None
        self._load()

    def snapshot(self) -> Forecast | None:
        return self._forecast

    def _load(self) -> None:
        if self._cache is None or not self._cache.is_file():
            return
        try:
            loaded = _from_json(json.loads(self._cache.read_text(encoding="utf-8")))
        except (OSError, ValueError, KeyError, TypeError):
            return  # copia rota: se baja otra
        if loaded.place == self._place:  # si cambiaste de ciudad, la copia de la anterior no vale
            self._forecast = loaded

    async def refresh(self) -> bool:
        params: dict[str, str | int | float] = {
            "latitude": self._lat, "longitude": self._lon, "timezone": "auto", "forecast_days": 3,
            "current": "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
            "daily": "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
        }  # fmt: skip
        try:
            async with httpx.AsyncClient(timeout=15, transport=self._transport) as client:
                resp = await client.get(FORECAST_URL, params=params)
                resp.raise_for_status()
                forecast = parse_forecast(resp.json(), self._place, self._now())
        except (httpx.HTTPError, ValueError, KeyError, TypeError, IndexError) as exc:
            log.warning("No pude bajar el tiempo: %s", type(exc).__name__)
            return False
        self._forecast = forecast
        if self._cache is not None:
            try:
                self._cache.parent.mkdir(parents=True, exist_ok=True)
                self._cache.write_text(json.dumps(_to_json(forecast)), encoding="utf-8")
            except OSError as exc:
                log.debug("No pude guardar la copia del tiempo: %s", exc)
        return True

    async def run(self) -> None:
        """Renueva cada media hora (y cada 5 minutos mientras falle). Se cancela al apagar el servidor."""
        while True:
            ok = await self.refresh()
            await asyncio.sleep(REFRESH_S if ok else RETRY_S)


async def find_places(
    name: str, transport: httpx.AsyncBaseTransport | None = None, count: int = 5
) -> list[Place]:
    """Busca una ciudad por su nombre (para Ajustes). Lista vacía si no hay o falla la red."""
    name = name.strip()
    if not name:
        return []
    try:
        async with httpx.AsyncClient(timeout=10, transport=transport) as client:
            resp = await client.get(GEOCODE_URL, params={"name": name, "count": count, "language": "es"})
            resp.raise_for_status()
            found = resp.json().get("results") or []
        return [
            Place(
                str(r["name"]),
                str(r.get("admin1", "")),
                str(r.get("country", "")),
                float(r["latitude"]),
                float(r["longitude"]),
            )
            for r in found
        ]
    except (httpx.HTTPError, ValueError, KeyError, TypeError):
        return []


# ---------- Respuestas en español ----------


def _deg(x: float) -> str:
    n = round(x)
    return f"menos {abs(n)}" if n < 0 else str(n)


def _grados(x: float) -> str:
    return f"{_deg(x)} grado" + ("" if round(x) in (1, -1) else "s")


def _day_phrase(offset: int) -> str:
    return {0: "Hoy", 1: "Mañana", 2: "Pasado mañana"}[offset]


def _age(seconds: float) -> str:
    hours = int(seconds // 3600)
    return f"hace {hours} horas" if hours >= 2 else "hace un rato"


def answer(said: str, forecast: Forecast, now: float | None = None) -> str:
    """Contesta a lo que se preguntó con la previsión guardada. `said` va sin tildes ni mayúsculas."""
    now = time.time() if now is None else now
    offset = 2 if "pasado manana" in said else 1 if "manana" in said else 0
    day = forecast.days[min(offset, len(forecast.days) - 1)]
    stale = (
        f" (Es un dato {_age(now - forecast.fetched_at)}: no he podido actualizarlo.)"
        if now - forecast.fetched_at > STALE_S
        else ""
    )
    desc = WMO.get(day.code, "")
    if any(w in said for w in ("llover", "llueve", "lluvia", "paraguas", "mojarme", "mojar")):
        if day.rain >= 50:
            text = f"Sí, lleva paraguas: hay un {day.rain} % de probabilidad de lluvia {_when(offset)}."
        elif day.rain >= 20:
            text = f"Puede ser: hay un {day.rain} % de probabilidad de lluvia {_when(offset)}."
        else:
            text = f"No parece: solo hay un {day.rain} % de probabilidad de lluvia {_when(offset)}."
        return text + stale
    if offset == 0:
        lead = f"Ahora hay {_grados(forecast.temp)}"
        if abs(forecast.feels - forecast.temp) >= 3:
            lead += f" (sensación de {_deg(forecast.feels)})"
        lead += (
            f" y está {WMO.get(forecast.code, 'variable')}."
            if forecast.code <= 3
            else f", {WMO.get(forecast.code, 'variable')}."
        )
        rest = f" Hoy, máxima de {_deg(day.tmax)} y mínima de {_deg(day.tmin)}"
    else:
        lead = f"{_day_phrase(offset)}: {desc or 'tiempo variable'}."
        rest = f" Máxima de {_deg(day.tmax)} y mínima de {_deg(day.tmin)}"
    rest += f", con un {day.rain} % de probabilidad de lluvia." if day.rain >= 20 else "."
    return lead + rest + stale


def brief(forecast: Forecast, now: float) -> str:
    """Una frase para el resumen del día: cómo va a ser hoy."""
    day = forecast.days[0]
    desc = WMO.get(day.code, "tiempo variable")
    text = f"El tiempo de hoy: {desc}, máxima de {_deg(day.tmax)} y mínima de {_deg(day.tmin)}"
    text += f", con un {day.rain} % de probabilidad de lluvia." if day.rain >= 30 else "."
    if now - forecast.fetched_at > STALE_S:
        text += f" (Dato {_age(now - forecast.fetched_at)}.)"
    return text


def _when(offset: int) -> str:
    return {0: "hoy", 1: "mañana", 2: "pasado mañana"}[offset]
