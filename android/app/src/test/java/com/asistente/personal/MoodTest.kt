package com.asistente.personal

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Notice
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.ui.led.Mood
import com.asistente.personal.ui.led.countdownText
import com.asistente.personal.ui.moodFor
import com.asistente.personal.ui.reminderMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTest {
    @Test fun `la cara refleja lo que pasa de verdad`() {
        val idle = AssistantState.IDLE
        val open = LinkStatus.OPEN
        assertEquals(Mood.IDLE, moodFor(idle, open, null, null, listening = true))
        assertEquals(Mood.SLEEP, moodFor(idle, open, null, null, listening = false))
        assertEquals(Mood.OFFLINE, moodFor(idle, LinkStatus.RECONNECTING, null, null, true))
        assertEquals(Mood.ERROR, moodFor(idle, LinkStatus.DENIED, null, null, true))
        assertEquals(Mood.ALERT, moodFor(idle, open, PendingConfirm("apagar", "n", "¿Seguro?", 10), null, true))
        assertEquals(Mood.LISTEN, moodFor(AssistantState.LISTENING, open, null, null, true))
        assertEquals(Mood.THINK, moodFor(AssistantState.THINKING, open, null, null, true))
        assertEquals(Mood.TALK, moodFor(AssistantState.SPEAKING, open, null, null, true))
        assertEquals(Mood.HAPPY, moodFor(idle, open, null, Notice(1, "Hecho", ok = true), true))
        assertEquals(Mood.ERROR, moodFor(idle, open, null, Notice(1, "Falló", ok = false), true))
        // Un recordatorio no pone cara contenta: se lee como aviso.
        assertEquals(Mood.IDLE, moodFor(idle, open, null, Notice(1, "Te recuerdo", ok = true, reminder = true), true))
    }

    @Test fun `la cuenta atras del temporizador se escribe para leerla de un vistazo`() {
        assertEquals("4:59", countdownText(299))
        assertEquals("1:05:00", countdownText(3900))
        assertEquals("0:00", countdownText(-3))
    }

    @Test fun `un recordatorio largo se queda mas tiempo, nunca menos de 6 segundos`() {
        assertEquals(6_000L, reminderMs("Agua"))
        val long = reminderMs("Recuerda sacar la ropa de la lavadora antes de salir de casa")
        assertTrue(long > 6_000L)
        assertTrue(long < 30_000L)
    }
}
