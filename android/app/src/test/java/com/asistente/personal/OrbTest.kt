package com.asistente.personal

import androidx.compose.ui.graphics.Color
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.Telemetry
import com.asistente.personal.ui.OrbMotion
import com.asistente.personal.ui.Vega
import com.asistente.personal.ui.moteRadius
import com.asistente.personal.ui.orbColor
import com.asistente.personal.ui.orbFrameMs
import com.asistente.personal.ui.orbLegend
import com.asistente.personal.ui.orbMotion
import com.asistente.personal.ui.orbRings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbTest {
    private val pc = Telemetry(cpu = 21, ramUsed = 23.6, ramTotal = 31.9, gpuTemp = 62)

    @Test fun `el color de la esfera dice que hace Vega`() {
        val idle = Color(0xFFFFB547)
        assertEquals(Vega.Mint, orbColor(AssistantState.LISTENING, sleeping = false, pending = false, idle = idle))
        assertEquals(Vega.Amber, orbColor(AssistantState.THINKING, false, false, idle))
        assertEquals(Vega.Amber, orbColor(AssistantState.ACTING, false, false, idle))
        assertEquals(Vega.Red, orbColor(AssistantState.ERROR, false, false, idle))
        assertEquals(idle, orbColor(AssistantState.IDLE, false, false, idle)) // en reposo, el color elegido (o el de la canción)
        assertEquals(Vega.Red, orbColor(AssistantState.SPEAKING, false, pending = true, idle = idle)) // confirmar manda
        assertEquals(Color(0xFF3A4660), orbColor(AssistantState.LISTENING, sleeping = true, pending = false, idle = idle)) // dormida manda
    }

    @Test fun `los anillos se llenan con el PC y la bateria, y los que faltan quedan vacios`() {
        val rings = orbRings(pc, battery = 91)
        assertEquals(0.21f, rings[0]!!, 1e-4f)
        assertEquals(23.6f / 31.9f, rings[1]!!, 1e-4f)
        assertEquals(62f / 90f, rings[2]!!, 1e-4f)
        assertEquals(0.91f, rings[3]!!, 1e-4f)
        val off = orbRings(null, battery = -1) // sin enlace ni lectura de batería
        assertTrue(off.all { it == null })
        assertEquals(1f, orbRings(pc.copy(gpuTemp = 120), 50)[2]!!, 0f) // la gráfica no pasa de lleno
        assertNull(orbRings(pc.copy(ramTotal = 0.0), 50)[1]) // sin memoria total no se divide por cero
        assertNull(orbRings(pc.copy(gpuTemp = null), 50)[2]) // sin NVIDIA no hay anillo de gráfica
    }

    @Test fun `la leyenda dice las mismas cuatro cifras que los anillos`() {
        assertEquals(listOf("CPU 21%", "RAM 24/32 GB", "GPU 62\u00b0", "M\u00f3vil 91%"), orbLegend(pc, 91))
        assertEquals(listOf("CPU \u2014", "RAM \u2014", "GPU \u2014", "M\u00f3vil \u2014"), orbLegend(null, -1))
    }

    @Test fun `las motas fluyen hacia dentro al escuchar, hacia fuera al hablar y se quedan quietas en reposo`() {
        assertEquals(OrbMotion(0f, 1f, 0f), orbMotion(AssistantState.LISTENING, false))
        assertEquals(OrbMotion(1f, 0f, 0f), orbMotion(AssistantState.THINKING, false))
        assertEquals(OrbMotion(0f, 0f, 1f), orbMotion(AssistantState.SPEAKING, false))
        assertEquals(OrbMotion(0f, 0f, 0f), orbMotion(AssistantState.IDLE, false))
        assertEquals(OrbMotion(0f, 0f, 0f), orbMotion(AssistantState.SPEAKING, sleeping = true))
    }

    @Test fun `el radio de una mota sigue su ciclo`() {
        assertEquals(2.2f, moteRadius(2.2f, flow = 0.5f, inward = 0f, outward = 0f), 1e-5f) // reposo: el suyo
        assertEquals(2.2f, moteRadius(2.2f, 0f, inward = 1f, outward = 0f), 1e-5f) // al escuchar, nace lejos...
        assertEquals(1.15f, moteRadius(2.2f, 1f, inward = 1f, outward = 0f), 1e-5f) // ...y muere en la esfera
        assertEquals(1.15f, moteRadius(2.2f, 0f, inward = 0f, outward = 1f), 1e-5f) // al hablar, nace en la esfera...
        assertEquals(2.2f * 1.25f, moteRadius(2.2f, 1f, inward = 0f, outward = 1f), 1e-5f) // ...y se aleja
    }

    @Test fun `la esfera se repinta despacio en reposo y a toda velocidad cuando actua`() {
        assertEquals(16L, orbFrameMs(AssistantState.SPEAKING, sleeping = false))
        assertEquals(40L, orbFrameMs(AssistantState.IDLE, false))
        assertEquals(120L, orbFrameMs(AssistantState.SPEAKING, sleeping = true))
    }

    @Test fun `el estilo pasa por Noche, Cristal, Nostromo y Orbe`() {
        assertEquals(3, styleIndex(glass = false, terminal = false, orb = true))
        assertEquals(3, styleIndex(glass = true, terminal = true, orb = true)) // si hubiera varios, manda el orbe
        assertEquals(2, styleIndex(glass = false, terminal = true)) // sin el tercer dato, como antes
        assertEquals(listOf("Noche", "Cristal", "Nostromo", "Orbe"), (0 until STYLE_COUNT).map(::styleName))
    }
}
