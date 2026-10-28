package com.asistente.personal

import com.asistente.personal.MUSIC_SIGNAL_MS
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.ui.WAVE_COLS
import com.asistente.personal.ui.WaveState
import com.asistente.personal.ui.WaveStyle
import com.asistente.personal.ui.envAt
import com.asistente.personal.ui.isSpotify
import com.asistente.personal.ui.waveTaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveTest {
    private fun steady(level: Float, times: Int = 80) = WaveState().apply { repeat(times) { push(level) } }

    @Test fun `cada lectura entra por la derecha y la silueta se desliza a la izquierda`() {
        val w = WaveState()
        w.push(1f)
        val first = w.env[WAVE_COLS - 1]
        assertTrue(first > 0.5f)
        w.push(0f)
        assertEquals(first, w.env[WAVE_COLS - 2], 1e-6f) // lo que entró pasó una posición a la izquierda
        assertTrue(w.env[WAVE_COLS - 1] < first) // y lo nuevo, ya en silencio, baja suave
    }

    @Test fun `un golpe levanta un pico por encima del volumen sostenido`() {
        val w = steady(0.3f)
        val sustained = w.env[WAVE_COLS - 1]
        w.push(0.9f) // sube de golpe
        assertTrue(w.env[WAVE_COLS - 1] > sustained)
        assertEquals(1f, w.env[WAVE_COLS - 1], 1e-6f) // y llega arriba del todo
    }

    @Test fun `una cancion con ritmo se ve distinta de una plana`() {
        val flat = steady(0.5f, 300)
        val beat = WaveState().apply { repeat(300) { push(if (it % 6 == 0) 0.9f else 0.2f) } }
        val recent = WAVE_COLS / 2 // lo ya asentado, sin el arranque
        val flatSpread = flat.env.takeLast(recent).let { it.max() - it.min() }
        val beatSpread = beat.env.takeLast(recent).let { it.max() - it.min() }
        assertTrue("plana $flatSpread, con ritmo $beatSpread", beatSpread > flatSpread + 0.2f)
    }

    @Test fun `sube de golpe y baja suave`() {
        val w = steady(1f)
        val top = w.env[WAVE_COLS - 1]
        w.push(0f)
        val after = w.env[WAVE_COLS - 1]
        assertEquals(top * WaveState.RELEASE, after, 1e-5f)
        w.push(0f)
        assertTrue(w.env[WAVE_COLS - 1] < after)
    }

    @Test fun `el silencio no se amplifica y al callar se apaga`() {
        val w = WaveState()
        repeat(50) { w.push(0.02f) } // casi nada: ruido de fondo
        assertTrue(w.env.max() < 0.6f) // no se estira hasta llenar la onda
        repeat(WAVE_COLS + 10) { w.push(0f) }
        assertTrue(w.env.max() < 0.02f) // las columnas se renuevan y baja hasta casi nada
    }

    @Test fun `con volumen bajo o alto se ve igual de viva gracias al pico reciente`() {
        val quiet = steady(0.3f)
        val loud = steady(0.9f)
        assertEquals(quiet.env.last(), loud.env.last(), 1e-6f) // los dos llenan igual
        assertTrue(quiet.env.last() > 0.8f)
    }

    @Test fun `el pico recuerda un golpe fuerte y se olvida poco a poco`() {
        val w = WaveState()
        w.push(1f)
        val high = w.peak
        repeat(300) { w.push(0.3f) }
        assertTrue(w.peak < high)
        assertTrue(w.peak >= WaveState.MIN_PEAK)
    }

    @Test fun `los extremos se afinan como una linea y el centro llega a todo`() {
        assertEquals(1f, waveTaper(0.5f), 1e-5f)
        assertTrue(waveTaper(0f) < 0.05f)
        assertTrue(waveTaper(1f) < 0.05f)
        assertEquals(waveTaper(0.2f), waveTaper(0.8f), 1e-3f) // simétrica
        assertTrue(waveTaper(0.25f) > waveTaper(0.1f)) // crece hacia el centro
        assertEquals(waveTaper(0f), waveTaper(-3f), 0f) // fuera de rango se recorta
    }

    @Test fun `el volumen del PC llega como evento y se recorta a 0 y 1`() {
        assertEquals(ServerEvent.MusicLevel(0.42f), Protocol.parse("""{"type":"music_level","level":0.42}"""))
        assertEquals(ServerEvent.MusicLevel(1f), Protocol.parse("""{"type":"music_level","level":7}"""))
        assertEquals(ServerEvent.MusicLevel(0f), Protocol.parse("""{"type":"music_level"}"""))
    }

    @Test fun `la onda es solo de Spotify`() {
        assertTrue(isSpotify("Spotify"))
        assertTrue(isSpotify("spotify.exe"))
        assertTrue(isSpotify("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"))
        assertTrue(!isSpotify("Opera"))
        assertTrue(!isSpotify("chrome.exe"))
        assertTrue(!isSpotify(""))
    }

    @Test fun `el estilo de onda pasa por espectro, barras, neon y silueta y se recuerda por nombre`() {
        assertEquals(listOf("Espectro", "Barras", "Neón", "Silueta"), WaveStyle.entries.map { it.label })
        assertEquals(WaveStyle.BARS, WaveStyle.SPECTRUM.next())
        assertEquals(WaveStyle.NEON, WaveStyle.BARS.next())
        assertEquals(WaveStyle.SILHOUETTE, WaveStyle.NEON.next())
        assertEquals(WaveStyle.SPECTRUM, WaveStyle.SILHOUETTE.next()) // da la vuelta
        assertEquals(WaveStyle.NEON, WaveStyle.from("NEON"))
        assertEquals(WaveStyle.SPECTRUM, WaveStyle.from(null)) // sin ajuste guardado: el espectro, como una radio de coche
        assertEquals(WaveStyle.SPECTRUM, WaveStyle.from("algo raro"))
    }

    @Test fun `la envolvente se interpola entre columnas y no se sale del rango`() {
        val env = floatArrayOf(0f, 1f, 0.5f)
        assertEquals(0.5f, envAt(env, 0.5f), 1e-6f)
        assertEquals(1f, envAt(env, 1f), 1e-6f)
        assertEquals(0.75f, envAt(env, 1.5f), 1e-6f)
        assertEquals(0f, envAt(env, -5f), 1e-6f) // antes del principio: el primer valor
        assertEquals(0.5f, envAt(env, 99f), 1e-6f) // pasado el final: el último
        assertEquals(0f, envAt(FloatArray(0), 3f), 0f)
    }

    @Test fun `la señal se da por perdida tras un rato sin lecturas, mas que el hueco normal entre dos`() {
        assertTrue(MUSIC_SIGNAL_MS >= 1_000) // el servidor manda ~20 por segundo: un segundo sin nada es de verdad un corte
        assertTrue(MUSIC_SIGNAL_MS <= 3_000) // y no se tarda una eternidad en volver la cara
    }
}

