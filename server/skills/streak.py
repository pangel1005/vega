"""La racha del gimnasio: una llama que se enciende cada día que entrenas y se va apagando si no vas.

Reglas (pensadas para el gimnasio, no para un juego que obliga a ir cada día):
- Cada día que vas, la llama se enciende (una vez por día).
- Puedes descansar hasta `MAX_REST` días seguidos sin perder la racha; la llama se va apagando mientras
  descansas (brasas el último día) y, si pasan más, se apaga y la racha vuelve a cero.
- La racha cuenta los días de entrenamiento de la cadena actual; también se guardan el total y la mejor racha.

Se guarda en la base de Vega (tabla `gym`, un día por fila, hora local del PC).
"""

from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

from server.memory.store import ConversationStore

MAX_REST = 2  # días seguidos de descanso que la racha aguanta
HISTORY_DAYS = 42  # lo que ve el calendario del teléfono (6 semanas)
EDIT_DAYS = 30  # hasta cuántos días atrás se puede corregir


@dataclass(frozen=True)
class Streak:
    count: int  # días de entrenamiento de la racha actual (0 si se apagó)
    best: int  # la mejor racha de siempre
    total: int  # días de gimnasio en total
    today: bool  # ¿ya fuiste hoy?
    rest: int  # días seguidos sin ir (0 si fuiste hoy)
    alive: bool  # ¿sigue viva la racha?
    week: int  # días de gimnasio en esta semana (lunes a domingo)
    days: list[str] = field(default_factory=list)  # los días con gimnasio de las últimas semanas (ISO)

    @property
    def heat(self) -> float:
        """Lo viva que está la llama: 1 recién encendida, baja con cada día de descanso, 0 apagada."""
        if not self.alive or self.count == 0:
            return 0.0
        if self.today:
            return 1.0
        return max(0.3, 0.85 - 0.25 * self.rest)  # ayer: 0,85 · un día de descanso: 0,6 · brasas: 0,35

    @property
    def rest_left(self) -> int:
        """Cuántos días más puedes descansar sin que se apague (si hoy no vas)."""
        if not self.alive or self.count == 0:
            return 0
        return max(0, MAX_REST - self.rest)


def chains(days: list[date]) -> list[list[date]]:
    """Los días agrupados en rachas: se corta si entre dos entrenos hay más de MAX_REST días."""
    out: list[list[date]] = []
    for day in sorted(set(days)):
        if out and (day - out[-1][-1]).days <= MAX_REST + 1:
            out[-1].append(day)
        else:
            out.append([day])
    return out


def compute(days: list[date], today: date) -> Streak:
    past = sorted(d for d in set(days) if d <= today)
    groups = chains(past)
    best = max((len(g) for g in groups), default=0)
    last = past[-1] if past else None
    since = (today - last).days if last else None
    alive = since is not None and since <= MAX_REST + 1
    monday = today - timedelta(days=today.weekday())
    window = today - timedelta(days=HISTORY_DAYS - 1)
    return Streak(
        count=len(groups[-1]) if alive and groups else 0,
        best=best,
        total=len(past),
        today=since == 0,
        rest=max(0, (since or 0) - 1) if since else 0,
        alive=alive,
        week=sum(1 for d in past if d >= monday),
        days=[d.isoformat() for d in past if d >= window],
    )


class GymLog:
    def __init__(self, store: ConversationStore, now: Callable[[], datetime] = datetime.now) -> None:
        self._db = store
        self._now = now

    def _days(self) -> list[date]:
        out = []
        for row in self._db.query("SELECT day FROM gym"):
            try:
                out.append(date.fromisoformat(row["day"]))
            except ValueError:
                continue
        return out

    def state(self) -> Streak:
        return compute(self._days(), self._now().date())

    def light(self, day: date | None = None) -> bool:
        """Enciende la llama de un día (hoy si no se dice). False si ya estaba o no se puede."""
        today = self._now().date()
        day = day or today
        if day > today or (today - day).days > EDIT_DAYS:
            return False
        before = self._db.query("SELECT 1 FROM gym WHERE day = ?", (day.isoformat(),))
        if before:
            return False
        self._db.execute(
            "INSERT INTO gym (day, at) VALUES (?, ?)",
            (day.isoformat(), self._now().isoformat(timespec="seconds")),
        )
        return True

    def put_out(self, day: date | None = None) -> bool:
        """Quita un día (por si se encendió sin querer). False si no estaba."""
        day = day or self._now().date()
        return self._db.execute("DELETE FROM gym WHERE day = ?", (day.isoformat(),)) > 0


# --- Por voz ---

_LIGHT = re.compile(
    r"\b(fui|he ido|vengo|vuelvo|regrese|volvi|acabo de ir|acabo de volver|ya fui) (al|del)"
    r" (gimnasio|gym|gimnacio)\b"
    r"|\b(ya )?(entrene|he entrenado|hice ejercicio|hice gimnasio|termine de entrenar)\b"
    r"|\b(enciende|prende|marca) (la )?(llama|racha)\b"
)
_ASK = re.compile(
    r"\b(mi|la) racha\b|\bcuantos dias (llevo|seguidos)\b|\bcomo va (mi|la) (racha|llama)\b"
    r"|\bcuantas veces (fui|he ido) al (gimnasio|gym)\b"
)
_UNDO = re.compile(
    r"\b(apaga|quita|borra|deshaz) (la )?(llama|racha)( de hoy)?\b|\bno fui al (gimnasio|gym)\b"
)
_YESTERDAY = re.compile(r"\bayer\b")


def _days(n: int) -> str:
    return f"{n} día" + ("" if n == 1 else "s")


def describe(s: Streak) -> str:
    if s.total == 0:
        return "Aún no has encendido la llama. Cuando vuelvas del gimnasio, dime «fui al gimnasio»."
    if not s.alive or s.count == 0:
        best = _days(s.best)
        return f"La llama está apagada. Tu mejor racha fue de {best}. Vuelve hoy y la enciendes otra vez."
    parts = [f"Llevas una racha de {_days(s.count)} de gimnasio"]
    if s.today:
        parts.append("y hoy ya está encendida")
    elif s.rest_left == 0:
        parts.append("y hoy tienes que ir o se apaga")
    else:
        parts.append(f"y puedes descansar {_days(s.rest_left)} más sin perderla")
    tail = f" Esta semana vas {s.week} {'vez' if s.week == 1 else 'veces'}; tu mejor racha: {_days(s.best)}."
    return " ".join(parts) + "." + tail


def morning(s: Streak) -> str | None:
    """La llama en el resumen de «buenos días» (None si aún no se encendió nunca)."""
    if s.total == 0:
        return None
    if not s.alive or s.count == 0:
        return "La llama del gimnasio está apagada: hoy puedes volver a encenderla."
    if s.today:
        return f"La llama ya está encendida hoy: {_days(s.count)} de racha."
    if s.rest_left == 0:
        return f"Hoy toca gimnasio: si no vas, se apaga tu llama de {_days(s.count)}."
    return f"Tu llama del gimnasio lleva {_days(s.count)}."


def night(s: Streak) -> str | None:
    """La llama en «buenas noches»: cómo queda el día y qué pide mañana."""
    if s.total == 0:
        return None
    if not s.alive or s.count == 0:
        return "La llama del gimnasio está apagada: mañana puedes encenderla otra vez."
    if s.today:
        return f"Hoy encendiste la llama: {_days(s.count)} de racha."
    if s.rest_left == 0:
        return f"Hoy no fuiste al gimnasio y era el último día: tu llama de {_days(s.count)} se apaga."
    if s.rest_left == 1:
        return f"Mañana toca gimnasio o se apaga tu llama de {_days(s.count)}."
    return f"Hoy descansaste del gimnasio; tu llama de {_days(s.count)} sigue viva."


def handle(log: GymLog, said: str) -> str | None:
    """«fui al gimnasio», «ayer fui al gimnasio», «¿cómo va mi racha?», «no fui al gimnasio» (deshace hoy)."""
    if _UNDO.search(said):
        day = log._now().date() - timedelta(days=1 if _YESTERDAY.search(said) else 0)
        if not log.put_out(day):
            return "Ese día no estaba encendido."
        return "Hecho, apagué ese día. " + describe(log.state())
    if _LIGHT.search(said):
        yesterday = bool(_YESTERDAY.search(said))
        day = log._now().date() - timedelta(days=1 if yesterday else 0)
        if not log.light(day):
            return ("Ayer ya estaba encendida. " if yesterday else "Hoy ya estaba encendida. ") + describe(
                log.state()
            )
        s = log.state()
        first = "¡Llama encendida! " if s.count > 1 else "¡Encendiste la llama! "
        return first + describe(s)
    if _ASK.search(said):
        return describe(log.state())
    return None
