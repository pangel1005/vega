package com.asistente.personal

import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.Protocol
import com.asistente.personal.ui.WAVE_COLS
import com.asistente.personal.ui.WAVE_HALF
import com.asistente.personal.ui.WaveState
import com.asistente.personal.ui.waveLit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveTest {
    @Test fun `cada lectura entra por la derecha y la onda se desplaza a la izquierda`() {
        val w = WaveState()
        w.push(1f)
        assertEquals(1f, w.bars[WAVE_COLS - 1], 1e-6f)
        w.push(0f)
        assertEquals(1f, w.bars[WAVE_COLS - 2], 1e-6f) // el pico pasó una posición a la izquierda
        assertTrue(w.bars[WAVE_COLS - 1] < 1f) // y entra lo nuevo: baja suave, no de golpe
    }

    @Test fun `sube de golpe y baja suave`() {
        val w = WaveState()
        repeat(5) { w.push(1f) }
        w.push(0f)
        val after = w.bars[WAVE_COLS - 1]
        assertEquals(WaveState.RELEASE, after, 1e-6f) // 1 * 0,6
        w.push(0f)
        assertTrue(w.bars[WAVE_COLS - 1] < after)
    }

    @Test fun `el silencio no se amplifica`() {
        val w = WaveState()
        repeat(50) { w.push(0.02f) } // casi nada: ruido de fondo
        assertTrue(w.bars.max() < 0.5f) // no se estira hasta llenar la onda
        repeat(WAVE_COLS + 10) { w.push(0f) } // las 64 columnas se renuevan
        assertEquals(0, waveLit(w.bars.max())) // al callar baja suave hasta no encender ningún punto
    }

    @Test fun `con volumen bajo o alto se ve igual de viva gracias al pico reciente`() {
        val quiet = WaveState().apply { repeat(60) { push(0.3f) } }
        val loud = WaveState().apply { repeat(60) { push(0.9f) } }
        assertEquals(quiet.bars.last(), loud.bars.last(), 1e-6f) // los dos llenan igual: se normaliza con su propio pico
        assertTrue(quiet.bars.last() > 0.9f)
    }

    @Test fun `el pico recuerda un golpe fuerte y se olvida poco a poco`() {
        val w = WaveState()
        w.push(1f)
        val high = w.peak
        repeat(300) { w.push(0.3f) }
        assertTrue(w.peak < high) // se fue olvidando
        assertTrue(w.peak >= WaveState.MIN_PEAK)
    }

    @Test fun `los puntos encendidos van de cero a la mitad de la altura`() {
        assertEquals(0, waveLit(0f))
        assertEquals(WAVE_HALF, waveLit(1f))
        assertEquals(WAVE_HALF, waveLit(3f))
        assertEquals(0, waveLit(-1f))
        assertEquals(5, waveLit(0.5f))
    }

    @Test fun `el volumen del PC llega como evento y se recorta a 0 y 1`() {
        assertEquals(ServerEvent.MusicLevel(0.42f), Protocol.parse("""{"type":"music_level","level":0.42}"""))
        assertEquals(ServerEvent.MusicLevel(1f), Protocol.parse("""{"type":"music_level","level":7}"""))
        assertEquals(ServerEvent.MusicLevel(0f), Protocol.parse("""{"type":"music_level"}"""))
    }
}
