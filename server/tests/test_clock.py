from datetime import datetime

from server.core.clock import spanish_datetime, time_context
from server.tests.fakes import FakeChat


def test_spanish_datetime() -> None:
    assert spanish_datetime(datetime(2026, 10, 21, 17, 43)) == "miércoles 21 de octubre de 2026, 17:43"
    assert spanish_datetime(datetime(2026, 1, 4, 9, 5)) == "domingo 4 de enero de 2026, 09:05"


def test_time_context_mentions_date() -> None:
    assert "21 de octubre de 2026" in time_context(datetime(2026, 10, 21, 8, 0))


def test_model_receives_current_date() -> None:
    import asyncio

    from server.core.orchestrator import Orchestrator
    from server.memory.store import ConversationStore

    model = FakeChat(pieces=["Hoy es miércoles."])

    async def emit(_: object) -> None:
        pass

    orch = Orchestrator(
        model, "Eres Vega.", ConversationStore(":memory:"), emit, now_context=lambda: "Fecha: HOY-PRUEBA."
    )
    asyncio.run(orch.handle_text("¿Qué día es?"))
    system = model.calls[0][0]
    assert system.role == "system" and system.content == "Eres Vega.\nFecha: HOY-PRUEBA."
