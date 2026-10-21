import sqlite3
from pathlib import Path

import pytest

from server.llm.base import ChatMessage
from server.memory.store import MIGRATIONS, ConversationStore


def test_migrates_once_and_reopens(tmp_path: Path) -> None:
    db = tmp_path / "h.sqlite"
    store = ConversationStore(db)
    assert store.schema_version == len(MIGRATIONS)
    conv = store.latest_conversation()
    store.add_messages(conv, [ChatMessage("user", "hola")])
    store.close()

    reopened = ConversationStore(db)
    assert reopened.schema_version == len(MIGRATIONS)
    assert [m.content for m in reopened.recent(reopened.latest_conversation(), 10)] == ["hola"]


def test_recent_returns_last_n_in_order() -> None:
    store = ConversationStore(":memory:")
    conv = store.latest_conversation()
    store.add_messages(conv, [ChatMessage("user", str(i)) for i in range(5)])
    assert [m.content for m in store.recent(conv, 3)] == ["2", "3", "4"]


def test_conversations_are_separate() -> None:
    store = ConversationStore(":memory:")
    first = store.latest_conversation()
    store.add_messages(first, [ChatMessage("user", "viejo")])
    second = store.new_conversation()
    assert store.latest_conversation() == second
    assert store.recent(second, 10) == []


def test_add_messages_is_all_or_nothing() -> None:
    store = ConversationStore(":memory:")
    conv = store.latest_conversation()
    bad = ChatMessage("system", "no permitido")  # el esquema solo acepta user/assistant
    with pytest.raises(sqlite3.IntegrityError):
        store.add_messages(conv, [ChatMessage("user", "pregunta"), bad])
    assert store.recent(conv, 10) == []


def test_search_ignores_accents_and_case() -> None:
    store = ConversationStore(":memory:")
    conv = store.latest_conversation()
    store.add_messages(
        conv,
        [ChatMessage("user", "Mañana tengo reunión con Lucía"), ChatMessage("assistant", "Anotado.")],
    )
    assert [m.content for m in store.search("REUNION lucia")] == ["Mañana tengo reunión con Lucía"]
    assert store.search("inexistente") == []


@pytest.mark.parametrize("weird", ['"', "AND OR NOT", "reunión*", "col:valor", "   "])
def test_search_never_breaks_on_user_text(weird: str) -> None:
    store = ConversationStore(":memory:")
    store.add_messages(store.latest_conversation(), [ChatMessage("user", "reunión")])
    store.search(weird)  # no debe lanzar excepción
