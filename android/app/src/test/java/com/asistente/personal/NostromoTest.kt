package com.asistente.personal

import com.asistente.personal.local.EarsSnapshot
import com.asistente.personal.local.EarsStats
import com.asistente.personal.net.AssistantState
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.ui.logLines
import com.asistente.personal.ui.oidoLine
import com.asistente.personal.ui.statusLine
import com.asistente.personal.ui.terminalStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class NostromoTest {
    @Test fun `las lineas de estado se alinean con puntos hasta la misma columna`() {
        val a = statusLine("OÍDO", "ACTIVO")
        val b = statusLine("CEREBRO", "TELÉFONO")
        assertEquals("> OÍDO ...... ACTIVO", a)
        assertEquals("> CEREBRO ... TELÉFONO", b)
        assertEquals(a.indexOf("ACTIVO"), b.indexOf("TELÉFONO")) // el valor empieza en la misma columna
    }

    @Test fun `el estado se dice en el idioma de la terminal`() {
        assertEquals("ESCUCHANDO", terminalStatus(AssistantState.LISTENING, sleeping = false, micOn = true))
        assertEquals("PENSANDO", terminalStatus(AssistantState.THINKING, false, true))
        assertEquals("HABLANDO", terminalStatus(AssistantState.SPEAKING, false, true))
        assertEquals("A LA ESCUCHA", terminalStatus(AssistantState.IDLE, false, true))
        assertEquals("SILENCIO", terminalStatus(AssistantState.IDLE, false, false))
        assertEquals("REPOSO", terminalStatus(AssistantState.SPEAKING, sleeping = true, micOn = true)) // dormida manda
        assertEquals("ERROR", terminalStatus(AssistantState.ERROR, false, true))
    }

    @Test fun `la linea del oido cuenta lo oido solo con la IA en el telefono`() {
        val ears = EarsSnapshot(heard = 684, passed = 642, calls = 3)
        assertEquals("ACTIVO · 684 FRASES · 3 LLAMADAS", oidoLine(false, true, brainOnPhone = true, ears = ears))
        assertEquals("ACTIVO", oidoLine(false, true, brainOnPhone = false, ears = ears)) // con el PC, oye el PC
        assertEquals("MICRÓFONO APAGADO", oidoLine(false, false, true, ears))
        assertEquals("DORMIDO", oidoLine(true, true, true, ears))
    }

    @Test fun `el log muestra lo ultimo, con quien habla, y sin saltos de linea`() {
        val messages = listOf(
            Message(1, Role.USER, "hola"), Message(2, Role.ASSISTANT, "Hola.\nBuenas"), Message(3, Role.NOTE, "aviso interno"),
            Message(4, Role.USER, "¿qué hora es?"), Message(5, Role.ASSISTANT, "Son las dos."), Message(6, Role.ERROR, "sin enlace"),
        )
        val lines = logLines(messages, 4)
        assertEquals(
            listOf("> VEGA: Hola. Buenas", "> USUARIO: ¿qué hora es?", "> VEGA: Son las dos.", "> ERROR: sin enlace"),
            lines.map { it.first },
        )
        assertEquals(listOf(true, false, true, false), lines.map { it.second }) // true = lo dijo Vega
        assertTrue(logLines(emptyList(), 4).isEmpty())
    }

    @Test fun `el estilo pasa por Noche, Cristal y Nostromo`() {
        assertEquals(0, styleIndex(glass = false, terminal = false))
        assertEquals(1, styleIndex(glass = true, terminal = false))
        assertEquals(2, styleIndex(glass = false, terminal = true))
        assertEquals(2, styleIndex(glass = true, terminal = true)) // si hubiera los dos, manda la terminal
        assertEquals(listOf("Noche", "Cristal", "Nostromo"), (0..2).map(::styleName))
    }

    @Test fun `los contadores del oido son cifras y se ponen a cero cada dia`() {
        var day = LocalDate.of(2026, 10, 28)
        val stats = EarsStats { day }
        repeat(5) { stats.phrase() }
        repeat(3) { stats.passedFilter() }
        stats.call()
        assertEquals(EarsSnapshot(5, 3, 1), stats.snapshot())
        day = day.plusDays(1)
        assertEquals(EarsSnapshot(0, 0, 0), stats.snapshot()) // otro día: desde cero
        stats.phrase()
        assertEquals(EarsSnapshot(1, 0, 0), stats.snapshot())
    }
}
