"""Historial de conversaciones en SQLite (con búsqueda de texto FTS5).

Se usa el `sqlite3` de la biblioteca estándar, sin ORM: menos dependencias para
que el servidor pueda correr también en equipos modestos. El esquema se versiona
con `PRAGMA user_version`; cada migración se aplica una sola vez, en orden.
"""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path

from server.llm.base import ChatMessage, Role

MIGRATIONS: list[str] = [
    # v1: conversaciones, mensajes y un índice de texto completo sobre los mensajes.
    """
    CREATE TABLE conversations (
        id          INTEGER PRIMARY KEY,
        started_at  TEXT NOT NULL
    );
    CREATE TABLE messages (
        id               INTEGER PRIMARY KEY,
        conversation_id  INTEGER NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
        role             TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
        content          TEXT NOT NULL,
        created_at       TEXT NOT NULL
    );
    CREATE INDEX messages_by_conversation ON messages(conversation_id, id);
    CREATE VIRTUAL TABLE messages_fts USING fts5(
        content, content='messages', content_rowid='id', tokenize='unicode61 remove_diacritics 2'
    );
    CREATE TRIGGER messages_ai AFTER INSERT ON messages BEGIN
        INSERT INTO messages_fts(rowid, content) VALUES (new.id, new.content);
    END;
    CREATE TRIGGER messages_ad AFTER DELETE ON messages BEGIN
        INSERT INTO messages_fts(messages_fts, rowid, content) VALUES ('delete', old.id, old.content);
    END;
    """,
]


@dataclass(frozen=True)
class StoredMessage:
    id: int
    conversation_id: int
    role: Role
    content: str
    created_at: str

    def as_chat(self) -> ChatMessage:
        return ChatMessage(self.role, self.content)


def _now() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


class ConversationStore:
    """Acceso al historial. Seguro para usar desde varios hilos (un candado por almacén)."""

    def __init__(self, path: Path | str) -> None:
        if isinstance(path, Path):
            path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._db = sqlite3.connect(path, check_same_thread=False, isolation_level=None)
        self._db.row_factory = sqlite3.Row
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.execute("PRAGMA journal_mode = WAL")
        self._migrate()

    def _migrate(self) -> None:
        with self._lock:
            version: int = self._db.execute("PRAGMA user_version").fetchone()[0]
            for number, script in enumerate(MIGRATIONS[version:], start=version + 1):
                self._db.executescript(f"BEGIN;\n{script}\nPRAGMA user_version = {number};\nCOMMIT;")

    @property
    def schema_version(self) -> int:
        with self._lock:
            return int(self._db.execute("PRAGMA user_version").fetchone()[0])

    def close(self) -> None:
        with self._lock:
            self._db.close()

    def new_conversation(self) -> int:
        with self._lock:
            cur = self._db.execute("INSERT INTO conversations (started_at) VALUES (?)", (_now(),))
            return int(cur.lastrowid or 0)

    def latest_conversation(self) -> int:
        """La conversación más reciente; si no hay ninguna, crea la primera."""
        with self._lock:
            row = self._db.execute("SELECT id FROM conversations ORDER BY id DESC LIMIT 1").fetchone()
        return int(row["id"]) if row else self.new_conversation()

    def add_messages(self, conversation_id: int, messages: list[ChatMessage]) -> None:
        """Guarda varios mensajes juntos: o se guardan todos, o ninguno."""
        now = _now()
        with self._lock:
            self._db.execute("BEGIN")
            try:
                self._db.executemany(
                    "INSERT INTO messages (conversation_id, role, content, created_at) VALUES (?, ?, ?, ?)",
                    [(conversation_id, m.role, m.content, now) for m in messages],
                )
            except Exception:
                self._db.execute("ROLLBACK")
                raise
            self._db.execute("COMMIT")

    def recent(self, conversation_id: int, limit: int) -> list[StoredMessage]:
        """Los últimos `limit` mensajes de la conversación, del más antiguo al más nuevo."""
        with self._lock:
            rows = self._db.execute(
                "SELECT * FROM (SELECT * FROM messages WHERE conversation_id = ? ORDER BY id DESC LIMIT ?)"
                " ORDER BY id",
                (conversation_id, limit),
            ).fetchall()
        return [_row(r) for r in rows]

    def search(self, text: str, limit: int = 10) -> list[StoredMessage]:
        """Búsqueda de texto en todo el historial (sin distinguir tildes ni mayúsculas)."""
        words = [w for w in text.replace('"', " ").split() if w]
        if not words:
            return []
        # Cada palabra entre comillas: así el texto del usuario nunca se interpreta como sintaxis FTS5.
        query = " ".join(f'"{w}"' for w in words)
        with self._lock:
            rows = self._db.execute(
                "SELECT m.* FROM messages_fts f JOIN messages m ON m.id = f.rowid"
                " WHERE messages_fts MATCH ? ORDER BY rank LIMIT ?",
                (query, limit),
            ).fetchall()
        return [_row(r) for r in rows]


def _row(r: sqlite3.Row) -> StoredMessage:
    return StoredMessage(r["id"], r["conversation_id"], r["role"], r["content"], r["created_at"])
