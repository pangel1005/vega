package com.asistente.personal

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.asistente.personal.ui.VINYL_SECONDS_PER_TURN
import com.asistente.personal.ui.Vinyl
import com.asistente.personal.ui.angleDelta
import com.asistente.personal.ui.scrubLabel
import com.asistente.personal.ui.scrubTarget
import com.asistente.personal.ui.spinStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class VinylTest {
    @get:Rule val compose = createComposeRule()

    // --- La parte pura ---

    @Test fun `la diferencia entre dos angulos es la mas corta y lleva signo`() {
        assertEquals(10f, angleDelta(0f, 10f), 1e-4f)
        assertEquals(-10f, angleDelta(10f, 0f), 1e-4f)
        assertEquals(20f, angleDelta(350f, 10f), 1e-4f) // da la vuelta por el 360
        assertEquals(-20f, angleDelta(10f, 350f), 1e-4f)
        assertEquals(-170f, angleDelta(90f, -80f), 1e-4f)
        assertEquals(0f, angleDelta(45f, 45f), 0f)
    }

    @Test fun `una vuelta entera del disco son 30 segundos hacia delante o atras`() {
        assertEquals(130.0, scrubTarget(100.0, 360f, 300.0), 1e-6)
        assertEquals(85.0, scrubTarget(100.0, -180f, 300.0), 1e-6)
        assertEquals(100.0 + VINYL_SECONDS_PER_TURN / 4, scrubTarget(100.0, 90f, 300.0), 1e-6)
        assertEquals(100.0, scrubTarget(100.0, 0f, 300.0), 0.0)
    }

    @Test fun `no se puede saltar antes del principio ni despues del final`() {
        assertEquals(0.0, scrubTarget(5.0, -3600f, 300.0), 0.0)
        assertEquals(300.0, scrubTarget(290.0, 3600f, 300.0), 0.0)
        assertEquals(0.0, scrubTarget(5.0, -3600f, 0.0), 0.0) // sin duración (directo): solo se limita el principio
        assertTrue(scrubTarget(5.0, 3600f, 0.0) > 100.0)
    }

    @Test fun `el plato tarda en pararse y en arrancar, como uno de verdad`() {
        var omega = 200f
        repeat(10) { omega = spinStep(omega, 0f, 0.1f) } // un segundo después de pausar
        assertTrue("sigue girando un poco: $omega", omega in 10f..80f)
        repeat(60) { omega = spinStep(omega, 0f, 0.1f) }
        assertTrue(omega < 0.5f) // al final para del todo
        omega = 0f
        omega = spinStep(omega, 200f, 0.1f)
        assertTrue(omega in 10f..60f) // arranca despacio
        assertEquals(200f, spinStep(1000f, 200f, 100f), 0.01f) // y una inercia grande vuelve a su velocidad
    }

    @Test fun `lo adelantado o retrasado se dice con signo y minutos`() {
        assertEquals("+0:12", scrubLabel(12.0))
        assertEquals("−0:05", scrubLabel(-5.0))
        assertEquals("+1:05", scrubLabel(65.4))
        assertEquals("+0:00", scrubLabel(0.0))
    }

    // --- El gesto, con el dedo de verdad ---

    private class Captured {
        var seek: Double? = null
        var toggles = 0
        var doubles = 0
        val scrubs = mutableListOf<Double?>()
    }

    private fun show(c: Captured, position: Double = 100.0, duration: Double = 300.0, cover: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Vinyl(
                art = null, accent = Color(0xFFFF6B9D), playing = true, position = position, duration = duration,
                onScrub = { c.scrubs += it }, onSeek = { c.seek = it }, onToggle = { c.toggles++ },
                modifier = Modifier.size(300.dp), cover = cover, onDoubleTap = { c.doubles++ },
            )
        }
        compose.mainClock.advanceTimeBy(100)
    }

    /** Mueve el dedo en círculo alrededor del centro del disco: `degrees` hacia la derecha (positivo) o la izquierda. */
    private fun turn(degrees: Float, steps: Int = 12) = compose.onRoot().performTouchInput {
        val s = min(width, height).toFloat()
        val center = Offset(s * 0.46f, s * 0.545f)
        val r = s * 0.30f
        fun at(deg: Float) = Offset(center.x + r * cos(deg * PI.toFloat() / 180f), center.y + r * sin(deg * PI.toFloat() / 180f))
        down(at(0f))
        for (i in 1..steps) moveTo(at(degrees * i / steps))
        up()
    }

    @Test fun `girar el disco hacia la derecha adelanta la cancion al soltar`() {
        val c = Captured()
        show(c)
        turn(120f)
        val target = c.seek
        assertNotNull(target)
        assertEquals(100.0 + 120.0 / 360.0 * VINYL_SECONDS_PER_TURN, target!!, 0.5) // 110 s
        assertEquals(0, c.toggles) // girar no es un toque
        assertNull(c.scrubs.last()) // y al soltar se acaba el «a dónde iría»
        assertTrue(c.scrubs.filterNotNull().isNotEmpty()) // mientras giraba se iba viendo
    }

    @Test fun `girar el disco hacia la izquierda retrasa la cancion`() {
        val c = Captured()
        show(c)
        turn(-180f)
        assertEquals(85.0, c.seek!!, 0.5)
    }

    @Test fun `un toque corto sobre el disco pausa o reanuda y no salta`() {
        val c = Captured()
        show(c)
        compose.onRoot().performTouchInput {
            val s = min(width, height).toFloat()
            click(Offset(s * 0.46f + s * 0.2f, s * 0.545f))
        }
        compose.mainClock.advanceTimeBy(400) // un toque suelto espera por si llega un segundo
        assertEquals(1, c.toggles)
        assertEquals(0, c.doubles)
        assertNull(c.seek)
    }

    @Test fun `dos toques seguidos cambian entre portada y vinilo y no pausan`() {
        val c = Captured()
        show(c)
        compose.onRoot().performTouchInput {
            val s = min(width, height).toFloat()
            doubleClick(Offset(s * 0.46f + s * 0.2f, s * 0.545f))
        }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(1, c.doubles)
        assertEquals(0, c.toggles) // el primer toque no pausó: esperó al segundo
        assertNull(c.seek)
    }

    @Test fun `en la portada se toca en cualquier parte, y el doble toque tambien cambia`() {
        val c = Captured()
        show(c, cover = true)
        compose.onRoot().performTouchInput { click(Offset(40f, 40f)) } // la esquina de la funda
        compose.mainClock.advanceTimeBy(400)
        assertEquals(1, c.toggles)
        compose.onRoot().performTouchInput { doubleClick(Offset(100f, 100f)) }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(1, c.doubles)
        assertEquals(1, c.toggles) // sigue siendo uno: el doble toque no pausa
    }

    @Test fun `en la portada girar no salta la cancion`() {
        val c = Captured()
        show(c, cover = true)
        turn(120f)
        assertNull(c.seek) // solo el vinilo entero se gira para adelantar o retroceder
    }

    @Test fun `tocar fuera del disco no hace nada`() {
        val c = Captured()
        show(c)
        compose.onRoot().performTouchInput { click(Offset(3f, 3f)) } // la esquina, lejos del disco
        assertEquals(0, c.toggles)
        assertNull(c.seek)
    }

    @Test fun `un giro muy pequeno sigue siendo un toque`() {
        val c = Captured()
        show(c)
        turn(4f, steps = 2) // menos que el margen de arrastre
        compose.mainClock.advanceTimeBy(400)
        assertNull(c.seek)
        assertEquals(1, c.toggles)
    }
}
