"""Memoria personal: lo que el asistente sabe de ti, tus notas y tus recordatorios.

Comparte la base SQLite del historial. Las horas de los recordatorios se guardan en
hora local del PC (sin zona), que es la que entiende el usuario («a las 6»).
"""

from __future__ import annotations

import re
import sqlite3
from dataclasses import dataclass
from datetime import datetime
from typing import Literal

from server.core.text import normalize
from server.memory.store import ConversationStore

Kind = Literal["reminder", "timer"]
MAX_FACTS_IN_PROMPT = 30
# Palabras con las que el usuario habla de sí mismo: entonces sí conviene darle todo lo que se sabe de él.
SELF_WORDS = frozenset(
    {"mi", "mis", "mio", "mia", "mios", "mias", "yo", "soy", "conmigo", "sabes", "recuerdas", "conoces"}
)


def _content_words(text: str) -> set[str]:
    return {w[:5] for w in normalize(text).split() if len(w) >= 4}


def _relevant(facts: list[str], question: str) -> list[str]:
    words = normalize(question).split()
    if SELF_WORDS.intersection(words):
        return facts
    asked = _content_words(question)
    return [f for f in facts if asked & _content_words(f)]


@dataclass(frozen=True)
class Reminder:
    id: int
    kind: Kind
    text: str
    due_at: datetime


def _now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


class PersonalMemory:
    def __init__(self, store: ConversationStore) -> None:
        self._db = store

    # --- Datos del usuario ---

    def remember(self, fact: str) -> None:
        self._db.execute("INSERT INTO facts (text, created_at) VALUES (?, ?)", (fact.strip(), _now_iso()))

    def facts(self) -> list[str]:
        rows = self._db.query("SELECT text FROM facts ORDER BY id DESC LIMIT ?", (MAX_FACTS_IN_PROMPT,))
        return [r["text"] for r in reversed(rows)]

    def forget(self, about: str) -> list[str]:
        """Borra los datos que contienen todas las palabras de `about`. Devuelve lo borrado."""
        words = [w for w in normalize(about).split() if len(w) > 2]
        if not words:
            return []
        rows = self._db.query("SELECT id, text FROM facts")
        gone = [r for r in rows if all(w in normalize(r["text"]) for w in words)]
        for r in gone:
            self._db.execute("DELETE FROM facts WHERE id = ?", (r["id"],))
        return [r["text"] for r in gone]

    def prompt_context(self, question: str | None = None) -> str:
        """Lo que sabe del usuario, para añadirlo a las instrucciones del modelo. Va en
        segunda persona («te gusta…»): en primera, el modelo lo repetía como si fuera suyo.

        Con `question`, solo los datos que vienen al caso: si se daban todos en cada pregunta, el modelo los
        colaba donde no tocaban (un perro en un firewall). Vienen al caso si el usuario habla de sí mismo
        («mi nombre», «qué sabes de mí») o la pregunta comparte una palabra con el dato."""
        facts = self.facts()
        if question is not None:
            facts = _relevant(facts, question)
        if not facts:
            return ""
        return (
            "Lo que sabes del usuario (háblale de tú; úsalo solo si la pregunta trata de ello): "
            + "; ".join(to_you(f) for f in facts)
            + "."
        )

    # --- Notas ---

    def add_note(self, text: str) -> None:
        self._db.execute("INSERT INTO notes (text, created_at) VALUES (?, ?)", (text.strip(), _now_iso()))

    def notes(self, limit: int = 10) -> list[str]:
        rows = self._db.query("SELECT text FROM notes ORDER BY id DESC LIMIT ?", (limit,))
        return [r["text"] for r in reversed(rows)]

    def clear_notes(self) -> int:
        return self._db.execute("DELETE FROM notes")

    # --- Recordatorios y temporizadores ---

    def add_reminder(self, kind: Kind, text: str, due_at: datetime) -> int:
        return self._db.execute(
            "INSERT INTO reminders (kind, text, due_at, created_at) VALUES (?, ?, ?, ?)",
            (kind, text.strip(), due_at.isoformat(timespec="seconds"), _now_iso()),
        )

    def pending(self) -> list[Reminder]:
        rows = self._db.query(
            "SELECT * FROM reminders WHERE delivered_at IS NULL AND cancelled = 0 ORDER BY due_at"
        )
        return [rem for r in rows if (rem := _reminder(r)) is not None]

    def due(self, now: datetime) -> list[Reminder]:
        return [r for r in self.pending() if r.due_at <= now]

    def mark_delivered(self, reminder_id: int) -> None:
        self._db.execute("UPDATE reminders SET delivered_at = ? WHERE id = ?", (_now_iso(), reminder_id))

    def cancel_reminder(self, reminder_id: int) -> None:
        """Anula un recordatorio concreto si aún no se entregó."""
        self._db.execute(
            "UPDATE reminders SET cancelled = 1 WHERE id = ? AND delivered_at IS NULL", (reminder_id,)
        )

    def cancel(self, kind: Kind | None = None) -> int:
        if kind is None:
            return self._db.execute(
                "UPDATE reminders SET cancelled = 1 WHERE delivered_at IS NULL AND cancelled = 0"
            )
        return self._db.execute(
            "UPDATE reminders SET cancelled = 1 WHERE delivered_at IS NULL AND cancelled = 0 AND kind = ?",
            (kind,),
        )


# Primera persona del usuario → segunda («me gusta» → «te gusta»). Solo las formas más
# comunes: lo que no esté aquí se deja tal cual.
_TO_YOU = {
    "me": "te", "mi": "tu", "mis": "tus", "yo": "tú", "conmigo": "contigo", "mío": "tuyo", "mía": "tuya",
    "soy": "eres", "estoy": "estás", "tengo": "tienes", "vivo": "vives", "trabajo": "trabajas",
    "prefiero": "prefieres", "quiero": "quieres", "necesito": "necesitas", "odio": "odias",
    "juego": "juegas", "uso": "usas", "estudio": "estudias", "voy": "vas", "hago": "haces",
}  # fmt: skip


def to_you(text: str) -> str:
    """«me gusta el café y mi perro se llama Toby» → «te gusta el café y tu perro se llama Toby»."""

    def swap(match: re.Match[str]) -> str:
        word = match.group(0)
        new = _TO_YOU.get(word.lower(), word)
        return new.capitalize() if word[0].isupper() else new

    return re.sub(r"\b\w+\b", swap, text)


def naive_local(moment: datetime) -> datetime:
    """Una hora con zona («…Z», «+02:00») pasa a la hora local sin zona, que es como se guarda todo."""
    return moment.astimezone().replace(tzinfo=None) if moment.tzinfo else moment


def _reminder(r: sqlite3.Row) -> Reminder | None:
    try:
        return Reminder(r["id"], r["kind"], r["text"], naive_local(datetime.fromisoformat(r["due_at"])))
    except ValueError:
        return None  # una fila ilegible no debe impedir que suenen las demás
