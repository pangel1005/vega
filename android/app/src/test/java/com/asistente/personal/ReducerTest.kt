package com.asistente.personal

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.PendingConfirm
import org.junit.Assert.assertNull
import com.asistente.personal.net.HistoryItem
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.state.Action
import com.asistente.personal.state.Message
import com.asistente.personal.state.NOT_HEARD
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReducerTest {
    private fun run(vararg actions: Action, start: UiState = UiState()) = actions.fold(start, ::reduce)
    private fun server(e: ServerEvent) = Action.Server(e)

    @Test fun `arma la respuesta trozo a trozo y la cierra con done`() {
        val s = run(
            Action.Sent("hola"),
            server(ServerEvent.State(AssistantState.THINKING)),
            server(ServerEvent.Token("Ho")),
            server(ServerEvent.Token("la")),
        )
        assertEquals(listOf(Message(1, Role.USER, "hola"), Message(2, Role.ASSISTANT, "Hola", streaming = true)), s.messages)
        assertEquals(2, s.tokenPulse)
        assertEquals(Message(2, Role.ASSISTANT, "Hola."), reduce(s, server(ServerEvent.Done("Hola."))).messages.last())
    }

    @Test fun `carga el historial sin mensajes de sistema`() {
        val s = run(
            Action.Sent("viejo"),
            server(ServerEvent.History(listOf(HistoryItem("system", "x"), HistoryItem("user", "a"), HistoryItem("assistant", "b")))),
        )
        assertEquals(listOf("a", "b"), s.messages.map { it.text })
    }

    @Test fun `la voz muestra lo dicho y su tiempo`() {
        val s = run(server(ServerEvent.Transcript("hola", 1.2, 180)))
        assertEquals(Message(1, Role.USER, "hola", meta = "voz · 0,18 s"), s.messages.single())
    }

    @Test fun `avisa si no se oyó nada`() {
        val s = run(server(ServerEvent.Transcript("", 1.0, 90)))
        assertEquals(Message(1, Role.NOTE, NOT_HEARD), s.messages.single())
    }

    @Test fun `el panel guarda la confirmación pendiente y la limpia con el resultado`() {
        val s = run(
            server(ServerEvent.Actions(listOf(DeckAction("apagar", "Apagar PC", "停止", "power", "red", true)))),
            server(ServerEvent.Confirm("n1", "apagar", "¿Seguro?", 10)),
        )
        assertEquals(PendingConfirm("apagar", "n1", "¿Seguro?", 10), s.pending)
        val done = reduce(s, server(ServerEvent.ActionResult("apagar", true, "Se apagará en 15 s.")))
        assertNull(done.pending)
        assertEquals("Se apagará en 15 s.", done.notice?.text)
        assertNull(reduce(done, Action.NoticeExpired(done.notice!!.seq)).notice)
    }

    @Test fun `la confirmación caduca solo si es la misma`() {
        val s = run(server(ServerEvent.Confirm("n1", "apagar", "¿Seguro?", 10)))
        assertEquals(s, reduce(s, Action.ConfirmExpired("otra")))
        assertNull(reduce(s, Action.ConfirmExpired("n1")).pending)
    }

    @Test fun `cierra la respuesta a medias si se corta la conexión`() {
        val s = run(server(ServerEvent.Token("Medio")), Action.Link(LinkStatus.RECONNECTING, 500))
        assertFalse(s.messages.last().streaming)
        assertEquals(LinkStatus.RECONNECTING, s.link)
        assertEquals(500L, s.retryInMs)
    }
}
