package com.asistente.personal

import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.ui.BrainLoad
import com.asistente.personal.ui.LED_BAR_DOTS
import com.asistente.personal.ui.brainLoad
import com.asistente.personal.ui.ledBarLit
import com.asistente.personal.ui.ledBarRuns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainLoadTest {
    @Test fun `la descarga se lee de la pastilla con su porcentaje`() {
        assertEquals(BrainLoad(busy = true, fraction = 0.45f, failed = false), brainLoad("Bajando IA 45 %"))
        assertEquals(BrainLoad(true, 0f, false), brainLoad("Bajando IA 0 %"))
        assertEquals(BrainLoad(true, 1f, false), brainLoad("Bajando IA 100 %"))
    }

    @Test fun `cargar no tiene porcentaje y los fallos se distinguen`() {
        assertEquals(BrainLoad(busy = true, fraction = null, failed = false), brainLoad("Cargando IA\u2026"))
        assertTrue(brainLoad("IA: error").failed)
        assertTrue(brainLoad("IA: sin modelos").failed)
        assertFalse(brainLoad("IA: error").busy)
    }

    @Test fun `con la IA lista o en el PC no hay barra`() {
        assertEquals(BrainLoad.None, brainLoad("IA: tel\u00e9fono"))
        assertEquals(BrainLoad.None, brainLoad("IA: PC"))
        assertEquals(BrainLoad.None, brainLoad(""))
    }

    @Test fun `la barra enciende los puntos de la fraccion y nunca se pasa`() {
        assertEquals(0, ledBarLit(0f))
        assertEquals(LED_BAR_DOTS / 2, ledBarLit(0.5f))
        assertEquals(LED_BAR_DOTS, ledBarLit(1f))
        assertEquals(LED_BAR_DOTS, ledBarLit(7f))
        assertEquals(0, ledBarLit(-1f))
    }

    @Test fun `la luz que corre es una ventana de seis puntos que da la vuelta`() {
        for (step in 0 until 100) {
            val lit = (0 until LED_BAR_DOTS).count { ledBarRuns(it, step) }
            assertTrue("paso $step: $lit", lit in 0..6)
        }
        assertTrue(ledBarRuns(0, 0)) // empieza por la izquierda
        assertFalse(ledBarRuns(20, 0))
        assertTrue(ledBarRuns(20, 22)) // y avanza hacia la derecha
        assertEquals(6, (0 until LED_BAR_DOTS).count { ledBarRuns(it, 20) }) // en medio, la ventana entera
    }
}
