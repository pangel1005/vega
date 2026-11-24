package com.asistente.personal

import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.ui.SPECTRUM_BANDS
import com.asistente.personal.ui.SpectrumState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpectrumTest {
    private fun frame(vararg v: Float) = FloatArray(SPECTRUM_BANDS) { i -> v.getOrElse(i) { 0f } }
    private fun loud(level: Float = 1f) = FloatArray(SPECTRUM_BANDS) { level }

    @Test fun `una columna sube de golpe y baja suave`() {
        val s = SpectrumState()
        s.push(frame(0.9f))
        val top = s.level[0]
        assertTrue(top > 0.6f)
        s.push(frame(0f)) // el sonido se acaba
        assertEquals(top * SpectrumState.FALL, s.level[0], 1e-4f) // baja un paso, no de golpe
        repeat(60) { s.push(frame(0f)) }
        assertTrue(s.level[0] < 0.01f) // y acaba apagada
    }

    @Test fun `el pico se queda arriba un rato y luego cae cada vez mas deprisa`() {
        val s = SpectrumState()
        s.push(frame(0.9f))
        val top = s.peak[0]
        repeat(SpectrumState.HOLD_STEPS) { s.push(frame(0f)) }
        assertEquals(top, s.peak[0], 1e-6f) // aguantado
        s.push(frame(0f))
        val firstFall = top - s.peak[0]
        assertTrue(firstFall > 0f) // empieza a caer
        val before = s.peak[0]
        s.push(frame(0f))
        val secondFall = before - s.peak[0]
        assertTrue("la caída acelera: $firstFall luego $secondFall", secondFall > firstFall) // gravedad
        repeat(200) { s.push(frame(0f)) }
        assertTrue(s.peak[0] <= s.level[0] + 1e-6f) // y nunca por debajo de la columna
    }

    @Test fun `un golpe nuevo mas alto que el pico lo sube y reinicia la espera`() {
        val s = SpectrumState()
        s.push(frame(0.4f))
        repeat(30) { s.push(frame(0f)) } // el pico ya ha caído
        val low = s.peak[0]
        s.push(frame(0.95f))
        assertTrue(s.peak[0] > low)
        assertEquals(s.level[0], s.peak[0], 1e-6f)
    }

    @Test fun `con volumen bajo se amplifica, con un tope, y los golpes llegan arriba`() {
        val quiet = SpectrumState().apply { repeat(60) { push(frame(0.3f)) } }
        val loud = SpectrumState().apply { repeat(60) { push(frame(0.9f)) } }
        assertTrue(quiet.gain > loud.gain) // lo bajo se amplifica más...
        assertTrue(quiet.gain <= 2.2f) // ...pero con un tope, para no convertir el ruido en música
        val steady = loud.level[0]
        loud.push(frame(1f, 1f, 1f)) // un golpe sobre lo sostenido
        assertTrue("golpe ${loud.level[0]} sobre $steady", loud.level[0] > steady + 0.1f)
    }

    @Test fun `un sonido sostenido se queda a media altura y un golpe sube por encima y un hueco baja por debajo`() {
        val s = SpectrumState()
        repeat(200) { s.push(loud(0.7f)) } // la música «de pared»: casi igual todo el rato
        val steady = s.level[0]
        assertTrue("sostenido: $steady", steady in 0.35f..0.75f) // no llena toda la columna
        s.push(loud(1f))
        assertTrue("golpe ${s.level[0]} sobre $steady", s.level[0] > steady + 0.1f) // el golpe se nota
        repeat(60) { s.push(loud(0.7f)) }
        val back = s.level[0]
        repeat(3) { s.push(loud(0.15f)) }
        assertTrue("hueco ${s.level[0]} bajo $back", s.level[0] < back - 0.1f) // y un hueco baja de lo habitual
    }

    @Test fun `el silencio no se amplifica`() {
        val s = SpectrumState()
        repeat(200) { s.push(loud(0.02f)) } // casi nada: ruido de fondo
        assertTrue(s.level.max() < 0.1f)
    }

    @Test fun `sin datos todo baja a cero y los valores nunca se salen de 0 a 1`() {
        val s = SpectrumState()
        s.push(loud(1f))
        s.push(FloatArray(0))
        assertTrue(s.level.all { it in 0f..1f } && s.peak.all { it in 0f..1f })
        repeat(80) { s.push(FloatArray(0)) }
        assertTrue(s.level.max() < 0.01f)
        s.push(floatArrayOf(5f, -3f)) // valores raros
        assertTrue(s.level.all { it in 0f..1f })
    }

    @Test fun `si llegan mas o menos bandas de las que se dibujan se reparten`() {
        val s = SpectrumState(bands = 4)
        s.push(floatArrayOf(1f, 0f)) // dos bandas para cuatro columnas: las dos primeras con la primera, las otras con la segunda
        assertTrue(s.level[0] > 0.5f && s.level[1] > 0.5f)
        assertEquals(0f, s.level[2], 1e-6f)
        assertEquals(0f, s.level[3], 1e-6f)
    }

    @Test fun `el volumen del PC llega como evento y se recorta a 0 y 1`() {
        assertEquals(ServerEvent.MusicLevel(0.42f), Protocol.parse("""{"type":"music_level","level":0.42}"""))
        assertEquals(ServerEvent.MusicLevel(1f), Protocol.parse("""{"type":"music_level","level":7}"""))
        assertEquals(ServerEvent.MusicLevel(0f), Protocol.parse("""{"type":"music_level"}"""))
    }

    @Test fun `el espectro llega del servidor como lista de bandas y se recorta`() {
        assertEquals(ServerEvent.MusicSpectrum(listOf(0.1f, 0.9f, 0f, 1f)), Protocol.parse("""{"type":"music_spectrum","bands":[0.1,0.9,-2,7]}"""))
        assertEquals(ServerEvent.MusicSpectrum(emptyList()), Protocol.parse("""{"type":"music_spectrum"}"""))
        val many = (1..200).joinToString(",") { "0.5" }
        val parsed = Protocol.parse("""{"type":"music_spectrum","bands":[$many]}""") as ServerEvent.MusicSpectrum
        assertTrue(parsed.bands.size <= 64) // un servidor raro no puede mandar miles
    }
}
