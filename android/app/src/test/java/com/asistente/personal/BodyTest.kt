package com.asistente.personal

import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.ui.SCOPE_BARS
import com.asistente.personal.ui.SCOPE_ROWS
import com.asistente.personal.ui.earsText
import com.asistente.personal.ui.recentChat
import com.asistente.personal.ui.scopeLit
import com.asistente.personal.ui.scopePush
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BodyTest {
    @Test fun `el osciloscopio desplaza la onda a la izquierda y mete lo nuevo por la derecha`() {
        val bars = FloatArray(SCOPE_BARS) { it.toFloat() / SCOPE_BARS }
        scopePush(bars, 0.9f)
        assertEquals(1f / SCOPE_BARS, bars[0], 0f) // lo que estaba en la posición 1 pasó a la 0
        assertEquals(0.9f, bars.last(), 0f)
        scopePush(bars, 3f) // un volumen fuera de rango se recorta
        assertEquals(1f, bars.last(), 0f)
        scopePush(bars, -1f)
        assertEquals(0f, bars.last(), 0f)
    }

    @Test fun `el volumen enciende de cero a todos los puntos`() {
        assertEquals(0, scopeLit(0f))
        assertEquals(SCOPE_ROWS, scopeLit(1f))
        assertEquals(SCOPE_ROWS, scopeLit(5f))
        assertEquals(0, scopeLit(-1f))
        assertEquals(2, scopeLit(0.6f))
    }

    @Test fun `el oido de hoy se dice en singular y plural`() {
        assertEquals("684 frases \u00b7 3 llamadas", earsText(684, 3))
        assertEquals("1 frase \u00b7 1 llamada", earsText(1, 1))
        assertEquals("0 frases \u00b7 0 llamadas", earsText(0, 0))
    }

    @Test fun `la conversacion reciente deja fuera los avisos internos y se queda con lo ultimo`() {
        val all = (1..10).map { Message(it, if (it % 2 == 0) Role.ASSISTANT else Role.USER, "m$it") } + Message(11, Role.NOTE, "interno")
        val shown = recentChat(all, 4)
        assertEquals(listOf("m7", "m8", "m9", "m10"), shown.map { it.text })
        assertEquals(emptyList<Message>(), recentChat(emptyList()))
        assertArrayEquals(arrayOf(Role.USER, Role.ASSISTANT), recentChat(all.take(2)).map { it.role }.toTypedArray())
    }
}
