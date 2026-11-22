"""Entregas de la universidad (Moodle): se leen del calendario que Moodle exporta y Vega avisa a tiempo.

No hace falta tu contraseña. En Moodle, Calendario → «Importar o exportar calendarios» → Exportar, eliges
«Todos los eventos» y un periodo (60 días va bien) y pulsas «Obtener URL del calendario». Ese enlace lleva una
clave que solo lee tu calendario: se guarda en `config/moodle.json` (fuera de git) y nunca se escribe en los
registros. Cada pocos minutos se baja, y por cada entrega pendiente se programa un recordatorio una hora
antes (configurable) con el sistema de recordatorios de siempre: suena en el teléfono y, si no está
conectado, llega por Telegram.

Limitación: el calendario no dice si ya entregaste la tarea. Para que no te avise de lo ya hecho, dile a Vega
«ya entregué <tarea>».
"""

from __future__ import annotations

import asyncio
import html
import json
import logging
import re
import sqlite3
from collections.abc import Awaitable, Callable, Iterable
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

import httpx

from server.core.text import normalize
from server.memory.personal import PersonalMemory, naive_local
from server.memory.store import ConversationStore

log = logging.getLogger(__name__)

REFRESH_SECONDS = 20 * 60
RETRY_SECONDS = 5 * 60
BROKEN_AFTER = timedelta(hours=6)  # sin poder leer Moodle tanto tiempo, Vega te avisa de que no te enteras
KEEP_DAYS = 30  # las entregas vencidas hace más de esto se olvidan
DEFAULT_ALERTS = (60,)  # minutos antes de la entrega en que se avisa

# Lo que sí es una entrega (vence, cierra…) y lo que no (se abre, empieza…).
_DEADLINE = re.compile(
    r"\b(vence|vencimiento|vencen|entrega|entregar|cierra|cierre|se cierra|plazo|limite"
    r"|closes|due|deadline|submit)\b"
)
_OPENS = re.compile(r"\b(abre|se abre|apertura|opens|opening|empieza|comienza|inicio)\b")
_SUFFIX = re.compile(r"\s+(?:is due|vence|vencimiento|cierra|se cierra|closes|due)\s*$", re.IGNORECASE)
# Moodle en español pone el motivo delante: «Vencimiento de Ensayo 1», «Se cierra Evaluación A».
_PREFIX = re.compile(r"^(?:vencimiento de|vencimiento|se cierra|cierre de|cierre|due:)\s+", re.IGNORECASE)
_TERM = re.compile(r"_\d{4}-\d+$")  # «MAT-101-01_2026-4» → «MAT-101-01»


@dataclass(frozen=True)
class Task:
    uid: str
    title: str
    course: str
    due: datetime  # hora local del PC, sin zona
    url: str = ""
    done: bool = False


# --- Leer el calendario (iCalendar) ---


def _unfold(text: str) -> list[str]:
    lines: list[str] = []
    for raw in text.replace("\r\n", "\n").replace("\r", "\n").split("\n"):
        if raw[:1] in (" ", "\t") and lines:
            lines[-1] += raw[1:]
        else:
            lines.append(raw)
    return lines


def _unescape(value: str) -> str:
    return re.sub(r"\\([nN,;\\])", lambda m: "\n" if m[1] in "nN" else m[1], value)


def _property(line: str) -> tuple[str, dict[str, str], str] | None:
    head, sep, value = line.partition(":")
    if not sep:
        return None
    name, *params = head.split(";")
    options = {k.upper(): v.strip('"') for k, _, v in (p.partition("=") for p in params)}
    return name.upper(), options, value


def _when(value: str, options: dict[str, str]) -> datetime | None:
    """Una fecha de iCalendar → hora local sin zona. Un día sin hora vence a las 23:59."""
    value = value.strip()
    try:
        if options.get("VALUE") == "DATE" or len(value) == 8:
            day = datetime.strptime(value[:8], "%Y%m%d")
            return day.replace(hour=23, minute=59)
        moment = datetime.strptime(value.rstrip("Z"), "%Y%m%dT%H%M%S")
    except ValueError:
        return None
    if value.endswith("Z"):
        return naive_local(moment.replace(tzinfo=UTC))
    if zone := options.get("TZID"):
        try:
            return naive_local(moment.replace(tzinfo=ZoneInfo(zone)))
        except (ZoneInfoNotFoundError, ValueError):
            return moment
    return moment  # hora «flotante»: la del propio PC


def is_deadline(summary: str) -> bool:
    said = normalize(summary)
    return bool(_DEADLINE.search(said)) and not _OPENS.search(said)


def clean_title(summary: str) -> str:
    text = html.unescape(summary).strip()
    return _PREFIX.sub("", _SUFFIX.sub("", text)).strip() or text


def parse_ics(text: str, only_deadlines: bool = True) -> list[Task]:
    """Los eventos del calendario como entregas. Con `only_deadlines`, solo los que vencen o cierran."""
    tasks: dict[str, Task] = {}
    event: dict[str, tuple[dict[str, str], str]] | None = None
    for line in _unfold(text):
        if line.strip().upper() == "BEGIN:VEVENT":
            event = {}
        elif line.strip().upper() == "END:VEVENT":
            if event is not None and (task := _task(event, only_deadlines)) is not None:
                tasks[task.uid] = task
            event = None
        elif event is not None and (prop := _property(line)) is not None:
            name, options, value = prop
            event.setdefault(name, (options, value))
    return sorted(tasks.values(), key=lambda t: t.due)


def _task(event: dict[str, tuple[dict[str, str], str]], only_deadlines: bool) -> Task | None:
    if "UID" not in event or "DTSTART" not in event or "SUMMARY" not in event:
        return None
    summary = _unescape(event["SUMMARY"][1]).strip()
    if not summary or (only_deadlines and not is_deadline(summary)):
        return None
    due = _when(event["DTSTART"][1], event["DTSTART"][0])
    if due is None:
        return None
    categories = _unescape(event["CATEGORIES"][1]) if "CATEGORIES" in event else ""
    course = _TERM.sub("", html.unescape(categories.split(",")[0]).strip())
    return Task(
        uid=event["UID"][1].strip(),
        title=clean_title(summary),
        course=course,
        due=due,
        url=event["URL"][1].strip() if "URL" in event else "",
    )


# --- Cómo se dice ---


def _s(n: int) -> str:
    return "" if n == 1 else "s"


def in_words(seconds: float) -> str:
    """«en 1 hora», «en 45 minutos», «en 2 horas y 10 minutos»."""
    minutes = max(0, round(seconds / 60))
    if minutes < 1:
        return "ahora mismo"
    hours, rest = divmod(minutes, 60)
    parts = []
    if hours:
        parts.append(f"{hours} hora{_s(hours)}")
    if rest:
        parts.append(f"{rest} minuto{_s(rest)}")
    return "en " + " y ".join(parts)


def clock(due: datetime, ref: datetime) -> str:
    """«hoy a las 23:59», «mañana a las 9:00», «el 14/11 a las 9:00»."""
    days = (due.date() - ref.date()).days
    if days <= 0:
        day = "hoy"
    elif days == 1:
        day = "mañana"
    else:
        day = f"el {due:%d/%m}"
    return f"{day} a las {due:%H:%M}"


def alert_text(task: Task, ref: datetime) -> str:
    where = f" de {task.course}" if task.course else ""
    left = in_words((task.due - ref).total_seconds())
    return f"tu entrega «{task.title}»{where} vence {left}, {clock(task.due, ref)}"


def describe(tasks: list[Task], now: datetime, limit: int = 5) -> str:
    if not tasks:
        return "No tienes entregas pendientes en Moodle."
    n = len(tasks)
    first = [
        f"«{t.title}»{' de ' + t.course if t.course else ''}, {clock(t.due, now)}" for t in tasks[:limit]
    ]
    more = f" y {n - limit} más" if n > limit else ""
    return f"Tienes {n} entrega{_s(n)} pendiente{_s(n)}: " + "; ".join(first) + more + "."


# --- Guardar y programar avisos ---


def _ints(text: str) -> list[int]:
    return [int(x) for x in text.split(",") if x.strip().lstrip("-").isdigit()]


def _join(values: Iterable[int]) -> str:
    return ",".join(str(v) for v in sorted(values))


def _row_task(row: sqlite3.Row) -> Task:
    return Task(
        uid=row["uid"],
        title=row["title"],
        course=row["course"],
        due=datetime.fromisoformat(row["due_at"]),
        url=row["url"],
        done=bool(row["done"]),
    )


class TaskBook:
    """Las entregas conocidas y los avisos programados para cada una (todo en la base de Vega)."""

    def __init__(
        self,
        store: ConversationStore,
        memory: PersonalMemory,
        now: Callable[[], datetime] = datetime.now,
        alerts: Iterable[int] = DEFAULT_ALERTS,
    ) -> None:
        self._db = store
        self._mem = memory
        self._now = now
        self._alerts = sorted({a for a in alerts if a > 0}, reverse=True) or list(DEFAULT_ALERTS)

    def sync(self, tasks: list[Task], authoritative: bool = False) -> int:
        """Guarda lo que devolvió Moodle y programa los avisos que falten. Devuelve cuántos programó.

        Con `authoritative` (el servicio web, que solo devuelve lo que sigue por hacer), lo que ya no
        aparece se da por entregado: se anulan sus avisos pendientes y deja de salir en el tablero."""
        now = self._now()
        stamp = now.isoformat(timespec="seconds")
        scheduled = 0
        for task in tasks:
            due = task.due.isoformat(timespec="seconds")
            rows = self._db.query("SELECT * FROM tasks WHERE uid = ?", (task.uid,))
            alerted: set[int] = set()
            ids: list[int] = []
            done = False
            if not rows:
                self._db.execute(
                    "INSERT INTO tasks (uid, title, course, due_at, url, seen_at) VALUES (?, ?, ?, ?, ?, ?)",
                    (task.uid, task.title, task.course, due, task.url, stamp),
                )
            else:
                row = rows[0]
                alerted, ids, done = set(_ints(row["alerted"])), _ints(row["reminder_ids"]), bool(row["done"])
                if row["due_at"] != due:  # el profesor movió la fecha: los avisos viejos ya no valen
                    for reminder_id in ids:
                        self._mem.cancel_reminder(reminder_id)
                    alerted, ids = set(), []
                self._db.execute(
                    "UPDATE tasks SET title = ?, course = ?, due_at = ?, url = ?, seen_at = ? WHERE uid = ?",
                    (task.title, task.course, due, task.url, stamp, task.uid),
                )
            if not done:
                added = self._schedule(task, alerted, ids, now)
                scheduled += added
                self._db.execute(
                    "UPDATE tasks SET alerted = ?, reminder_ids = ? WHERE uid = ?",
                    (_join(alerted), _join(ids), task.uid),
                )
        if authoritative:
            self._retire_missing({t.uid for t in tasks})
        self._purge(now)
        return scheduled

    def _retire_missing(self, present: set[str]) -> None:
        for row in self._db.query("SELECT uid, reminder_ids FROM tasks WHERE done = 0"):
            if row["uid"] in present:
                continue
            for reminder_id in _ints(row["reminder_ids"]):
                self._mem.cancel_reminder(reminder_id)
            self._db.execute("UPDATE tasks SET done = 1 WHERE uid = ?", (row["uid"],))

    def _schedule(self, task: Task, alerted: set[int], ids: list[int], now: datetime) -> int:
        added, late = 0, False
        for minutes in self._alerts:
            if minutes in alerted:
                continue
            alerted.add(minutes)
            if task.due <= now:
                continue
            at = task.due - timedelta(minutes=minutes)
            if at > now:
                ids.append(self._mem.add_reminder("reminder", alert_text(task, at), at))
                added += 1
            else:
                late = True
        if late:  # ya pasó el momento de avisar: un solo aviso, ahora
            ids.append(self._mem.add_reminder("reminder", alert_text(task, now), now))
            added += 1
        return added

    def _purge(self, now: datetime) -> None:
        old = (now - timedelta(days=KEEP_DAYS)).isoformat(timespec="seconds")
        self._db.execute("DELETE FROM tasks WHERE due_at < ?", (old,))

    def upcoming(self, now: datetime | None = None, within: timedelta | None = None) -> list[Task]:
        """Las entregas por hacer que Moodle devolvió la última vez, por orden de fecha."""
        now = now or self._now()
        rows = self._db.query(
            "SELECT * FROM tasks WHERE done = 0 AND due_at > ? AND seen_at = (SELECT MAX(seen_at) FROM tasks)"
            " ORDER BY due_at",
            (now.isoformat(timespec="seconds"),),
        )
        tasks = [_row_task(r) for r in rows]
        return [t for t in tasks if within is None or t.due - now <= within]

    def board(self, now: datetime | None = None, overdue_days: int = 7) -> list[Task]:
        """Lo del tablero del teléfono: lo pendiente y lo vencido hace poco que no marcaste como entregado."""
        now = now or self._now()
        since = (now - timedelta(days=overdue_days)).isoformat(timespec="seconds")
        rows = self._db.query(
            "SELECT * FROM tasks WHERE done = 0 AND due_at > ? AND seen_at = (SELECT MAX(seen_at) FROM tasks)"
            " ORDER BY due_at",
            (since,),
        )
        return [_row_task(r) for r in rows]

    def mark_done_uid(self, uid: str) -> bool:
        """Marca una entrega como hecha desde el tablero. False si no existe."""
        rows = self._db.query("SELECT * FROM tasks WHERE uid = ?", (uid,))
        if not rows:
            return False
        self.mark_done(_row_task(rows[0]))
        return True

    def find(self, about: str, now: datetime | None = None) -> list[Task]:
        """Las entregas por hacer cuyo título contiene todas las palabras de `about`."""
        words = [w for w in normalize(about).split() if len(w) > 2]
        if not words:
            return []
        return [t for t in self.upcoming(now) if all(w in normalize(t.title) for w in words)]

    def alert_ids(self) -> set[int]:
        """Los recordatorios que son avisos de entregas (el resumen del día ya dice las entregas aparte)."""
        rows = self._db.query("SELECT reminder_ids FROM tasks")
        return {i for row in rows for i in _ints(row["reminder_ids"])}

    def mark_done(self, task: Task) -> None:
        """Entregada: sus avisos pendientes se anulan."""
        rows = self._db.query("SELECT reminder_ids FROM tasks WHERE uid = ?", (task.uid,))
        for row in rows:
            for reminder_id in _ints(row["reminder_ids"]):
                self._mem.cancel_reminder(reminder_id)
        self._db.execute("UPDATE tasks SET done = 1 WHERE uid = ?", (task.uid,))

    def warn_access(self) -> None:
        """Moodle ya no acepta el acceso: nada se arregla esperando."""
        self._mem.add_reminder(
            "reminder",
            "Moodle ya no acepta mi acceso, así que no puedo avisarte de tus entregas. "
            "Vuelve a ejecutar la configuración de Moodle en el PC",
            self._now(),
        )

    def warn_broken(self, hours: int) -> None:
        """Moodle lleva horas sin leerse: mejor que lo sepas a que se te pasen entregas sin aviso."""
        self._mem.add_reminder(
            "reminder",
            f"no puedo leer Moodle desde hace {hours} horas, así que puedo no avisarte de entregas nuevas. "
            "Revisa el enlace del calendario",
            self._now(),
        )


# --- Bajar el calendario ---


class MoodleError(Exception):
    """Un fallo explicable (sin la dirección, que lleva la clave)."""

    fatal = False  # True: el acceso ya no vale y no se arregla esperando


async def fetch_calendar(url: str, transport: httpx.AsyncBaseTransport | None = None) -> str:
    try:
        async with httpx.AsyncClient(timeout=30, follow_redirects=True, transport=transport) as client:
            response = await client.get(url)
    except httpx.HTTPError as exc:
        raise MoodleError(f"No pude conectar con Moodle ({type(exc).__name__}).") from None
    if response.status_code != 200:
        raise MoodleError(f"Moodle contestó con el error {response.status_code}.")
    if "BEGIN:VCALENDAR" not in response.text[:2000].upper():
        raise MoodleError("Moodle no devolvió un calendario: puede que el enlace ya no valga.")
    return response.text


@dataclass(frozen=True)
class MoodleConfig:
    url: str | None  # el calendario exportado (iCal), con su clave
    site: str | None  # la dirección de Moodle (para el servicio web)
    token: str | None  # el token del servicio web


def _web(value: object) -> str | None:
    text = str(value or "").strip()
    return text if text.lower().startswith(("https://", "http://")) else None


def load_config(path: Path) -> MoodleConfig | None:
    """Lo guardado por `moodle_setup`, o None (y un aviso) si falta o no sirve para nada."""
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        url, site = _web(data.get("url")), _web(data.get("site"))
        token = str(data.get("token") or "").strip() or None
    except FileNotFoundError:
        log.warning("Moodle activado, pero falta %s: ejecuta server.tools.moodle_setup.", path.name)
        return None
    except (OSError, ValueError, AttributeError):
        log.warning("Moodle: %s no se puede leer.", path.name)
        return None
    if site is None and url is not None:
        parts = urlsplit(url)
        site = f"{parts.scheme}://{parts.netloc}"
    if token is not None and site is None:
        log.warning("Moodle: %s tiene un token pero no la dirección de Moodle («site»).", path.name)
        token = None
    if token is None and url is None:
        log.warning("Moodle: %s no tiene ni token ni enlace de calendario.", path.name)
        return None
    return MoodleConfig(url, site, token)


def write_config(path: Path, **changes: str) -> None:
    """Guarda (sumando a lo que ya hubiera) los datos de acceso. Fuera de git."""
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        data = data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        data = {}
    data.update(changes)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def ical_source(
    url: str, only_deadlines: bool = True, transport: httpx.AsyncBaseTransport | None = None
) -> Callable[[], Awaitable[list[Task]]]:
    """De dónde salen las entregas cuando solo hay el calendario exportado."""

    async def source() -> list[Task]:
        return parse_ics(await fetch_calendar(url, transport), only_deadlines)

    return source


class MoodleSync:
    """Mantiene al día las entregas: las pide a su fuente cada rato y programa los avisos."""

    def __init__(
        self,
        book: TaskBook,
        source: Callable[[], Awaitable[list[Task]]],
        authoritative: bool = False,
        refresh_s: float = REFRESH_SECONDS,
        now: Callable[[], datetime] = datetime.now,
    ) -> None:
        self._book = book
        self._source = source
        self._authoritative = authoritative
        self._refresh = refresh_s
        self._now = now
        self._last_ok = now()
        self._warned = False

    async def refresh(self) -> bool:
        try:
            tasks = await self._source()
            added = await asyncio.to_thread(self._book.sync, tasks, self._authoritative)
        except MoodleError as exc:
            log.warning("Moodle: %s", exc)
            await self._check_broken(exc.fatal)
            return False
        self._last_ok, self._warned = self._now(), False
        log.info("Moodle: %d entregas leídas, %d avisos nuevos", len(tasks), added)
        return True

    async def _check_broken(self, fatal: bool) -> None:
        silent = self._now() - self._last_ok
        if self._warned:
            return
        if fatal:
            self._warned = True
            await asyncio.to_thread(self._book.warn_access)
        elif silent >= BROKEN_AFTER:
            self._warned = True
            await asyncio.to_thread(self._book.warn_broken, int(silent.total_seconds() // 3600))

    async def run(self) -> None:
        """Se cancela al apagar el servidor."""
        while True:
            ok = await self.refresh()
            await asyncio.sleep(self._refresh if ok else RETRY_SECONDS)
