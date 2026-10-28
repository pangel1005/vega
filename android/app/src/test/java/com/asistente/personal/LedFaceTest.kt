package com.asistente.personal

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Notice
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.ui.led.Frame
import com.asistente.personal.ui.led.LedFace
import com.asistente.personal.ui.led.LedFaceAnimator
import com.asistente.personal.ui.led.LedFont
import com.asistente.personal.ui.led.Mood
import com.asistente.personal.ui.led.framePacingMs
import com.asistente.personal.ui.moodFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LedFaceTest {
    @Test fun `cada expresión enciende puntos y son distintas entre sí`() {
        val seen = mutableSetOf<List<Float>>()
        for (mood in Mood.entries) {
            val f = Frame()
            LedFace.draw(f, mood, t = 1f, level = 0.8f)
            assertTrue("$mood no enciende nada", f.v.count { it > 0.04f } > 4) // sin enlace los ojos van tenues a propósito
            seen += f.v.toList()
        }
        assertEquals("dos expresiones se ven iguales", Mood.entries.size, seen.size)
    }

    @Test fun `al parpadear se cierran los ojos y el guiño cierra solo uno`() {
        val open = Frame().also { LedFace.draw(it, Mood.IDLE, 0f) }
        val closed = Frame().also { LedFace.draw(it, Mood.IDLE, 0f, blinkL = 1f, blinkR = 1f) }
        val wink = Frame().also { LedFace.draw(it, Mood.IDLE, 0f, blinkR = 1f) }
        assertTrue(closed.lit() < wink.lit())
        assertTrue(wink.lit() < open.lit())
    }

    @Test fun `la mirada mueve los ojos`() {
        val center = Frame().also { LedFace.draw(it, Mood.IDLE, 0f) }
        val right = Frame().also { LedFace.draw(it, Mood.IDLE, 0f, lookX = 1f) }
        assertNotEquals(center.v.toList(), right.v.toList())
    }

    @Test fun `la boca sigue el volumen al hablar`() {
        val quiet = Frame().also { LedFace.draw(it, Mood.TALK, 1f, level = 0f) }
        val loud = Frame().also { LedFace.draw(it, Mood.TALK, 1f, level = 1f) }
        assertTrue(loud.lit() > quiet.lit())
    }

    @Test fun `las letras se pasan a mayúsculas sin tildes pero con eñe`() {
        assertEquals("TE RECUERDO: LLAMAR A MAMA!", LedFont.normalize("¡Te recuerdo: llamar a mamá!"))
        assertEquals("MAÑANA A LAS 8", LedFont.normalize("mañana a las 8"))
        assertEquals("HOLA", LedFont.normalize("  hola 😀 "))
        assertEquals(11, LedFont.width("TRE"))
    }

    @Test fun `un aviso cruza la cara y luego vuelve la cara`() {
        val anim = LedFaceAnimator(Random(1))
        val message = "Te recuerdo: estirar"
        anim.showText(message)
        assertTrue(anim.showingText)
        var t = 0f
        while (anim.showingText && t < 30f) {
            anim.step(0.05f, t, Mood.IDLE, 0f)
            t += 0.05f
        }
        assertFalse(anim.showingText)
        assertEquals(LedFont.crossSeconds(message), t, 0.2f)
    }

    @Test fun `en reposo no redibuja todo el rato`() {
        val anim = LedFaceAnimator(Random(3))
        var t = 0f
        repeat(60) { anim.step(1 / 60f, t, Mood.SLEEP, 0f); t += 1 / 60f } // se asienta
        var redraws = 0
        repeat(60) {
            if (anim.step(1 / 60f, t, Mood.OFFLINE, 0f)) redraws++
            t += 1 / 60f
        }
        assertTrue("un segundo sin enlace no debería redibujar en cada cuadro de forma ilimitada", redraws <= 60)
        // Con la cara quieta y ya asentada, un cuadro sin cambios no pide redibujar.
        val still = LedFaceAnimator(Random(5))
        repeat(120) { still.step(1 / 60f, 0f, Mood.ERROR, 0f) }
        assertFalse(still.step(1 / 60f, 0f, Mood.ERROR, 0f))
    }

    @Test fun `el ritmo de dibujo baja en reposo y es completo cuando Vega actua`() {
        for (mood in listOf(Mood.LISTEN, Mood.THINK, Mood.TALK, Mood.HAPPY, Mood.ALERT, Mood.ERROR)) {
            assertEquals("$mood se pinta a toda velocidad", 0L, framePacingMs(mood, rainbow = false, scrolling = false))
        }
        assertEquals(100L, framePacingMs(Mood.SLEEP, rainbow = false, scrolling = false))
        assertEquals(66L, framePacingMs(Mood.OFFLINE, rainbow = false, scrolling = false))
        assertEquals(33L, framePacingMs(Mood.IDLE, rainbow = false, scrolling = false))
        assertEquals(50L, framePacingMs(Mood.IDLE, rainbow = true, scrolling = false)) // el arcoíris da una vuelta cada 8 s
        assertEquals(0L, framePacingMs(Mood.IDLE, rainbow = true, scrolling = true)) // un aviso cruzando la cara se ve fluido
        assertEquals(0L, framePacingMs(Mood.SLEEP, rainbow = false, scrolling = true))
    }

    @Test fun `con el ritmo de reposo se dibuja mucho menos que a 60 cuadros por segundo`() {
        // 5 s a 60 Hz: antes, un paso (y un redibujado) en cada cuadro = 300.
        val anim = LedFaceAnimator(Random(7))
        val pause = framePacingMs(Mood.SLEEP, rainbow = false, scrolling = false) / 1000f
        var steps = 0
        var acc = 0f
        var t = 0f
        repeat(300) {
            acc += 1 / 60f
            if (acc >= pause) {
                anim.step(acc, t, Mood.SLEEP, 0f)
                steps++
                acc = 0f
            }
            t += 1 / 60f
        }
        assertTrue("dormida debe pintar ≤ 10 veces por segundo, pintó $steps en 5 s", steps <= 55)
    }

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
        // Un recordatorio no pone cara contenta: pasa por la cara como texto.
        assertEquals(Mood.IDLE, moodFor(idle, open, null, Notice(1, "Te recuerdo", ok = true, reminder = true), true))
    }

    @Test fun `la cuenta atras del temporizador ocupa el sitio de la boca`() {
        assertEquals("4:59", com.asistente.personal.ui.led.countdownText(299))
        assertEquals("1:05:00", com.asistente.personal.ui.led.countdownText(3900))
        val smile = com.asistente.personal.ui.led.Frame()
        val timer = com.asistente.personal.ui.led.Frame()
        com.asistente.personal.ui.led.LedFace.draw(smile, com.asistente.personal.ui.led.Mood.IDLE, 0f)
        com.asistente.personal.ui.led.LedFace.draw(timer, com.asistente.personal.ui.led.Mood.IDLE, 0f, countdown = "4:59")
        // Los ojos (filas 0–12) son iguales; debajo, la cuenta atrás enciende más puntos que la sonrisa.
        for (y in 0..12) for (x in 0 until 48) assertEquals(smile[x, y], timer[x, y], 0f)
        val lit = { f: com.asistente.personal.ui.led.Frame -> (13 until 20).sumOf { y -> (0 until 48).count { x -> f[x, y] > 0.5f } } }
        assertTrue(lit(timer) > lit(smile))
    }
}
