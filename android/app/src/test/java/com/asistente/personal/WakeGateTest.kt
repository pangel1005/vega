package com.asistente.personal

import com.asistente.personal.local.WakeGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * El filtro de «Vega» en Kotlin tiene que calcular lo mismo que tools/wake/train.py: se comparan
 * sus salidas con las que escribió el entrenamiento para 4 frases (2 llamadas y 2 que no lo son).
 */
class WakeGateTest {
    private class Clip(val samples: FloatArray, val expectedLogit: Float)

    private val gate = WakeGate(File("src/main/assets/wake_gate.bin").readBytes())

    private fun fixture(): List<Clip> {
        val buf = ByteBuffer.wrap(File("src/test/resources/wake_gate_fixture.bin").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return List(buf.int) {
            val n = buf.int
            val samples = FloatArray(n) { buf.short / 32768f }
            Clip(samples, buf.float)
        }
    }

    @Test fun calculaLoMismoQueElEntrenamiento() {
        for ((i, clip) in fixture().withIndex()) {
            assertEquals("frase $i", clip.expectedLogit, gate.logit(clip.samples), 0.05f)
        }
    }

    @Test fun dejaPasarLasLlamadasYDescartaLasDemas() {
        val clips = fixture()
        assertTrue(gate.passes(clips[0].samples))
        assertTrue(gate.passes(clips[1].samples))
        assertFalse(gate.passes(clips[2].samples))
        assertFalse(gate.passes(clips[3].samples))
    }

    @Test fun apuntaLaProbabilidadDeLaUltimaFrase() {
        assertTrue(gate.lastScore.isNaN()) // aún no ha visto ninguna
        val clips = fixture()
        gate.passes(clips[0].samples)
        val llamada = gate.lastScore
        gate.passes(clips[2].samples)
        val otra = gate.lastScore
        assertTrue(llamada in 0f..1f && otra in 0f..1f)
        assertTrue("la llamada puntúa más que la frase ajena", llamada > otra)
    }

    @Test fun elSilencioNoPasa() {
        assertFalse(gate.passes(FloatArray(WakeGate.WINDOW)))
    }

    @Test fun unaFraseMasCortaQueLaVentanaSeRellenaConSilencio() {
        val clip = fixture()[0]
        val short = clip.samples.copyOf(WakeGate.WINDOW / 2)
        gate.logit(short) // no debe fallar
        gate.logit(FloatArray(0))
    }

    @Test fun cuestaPocoCalcularla() {
        val clip = fixture()[0]
        gate.logit(clip.samples) // calentar
        val t0 = System.nanoTime()
        repeat(5) { gate.logit(clip.samples) }
        val ms = (System.nanoTime() - t0) / 5e6
        println("filtro de Vega: %.1f ms por frase (en el PC)".format(ms))
        assertTrue("tarda demasiado: $ms ms", ms < 400)
    }

    @Test fun unArchivoQueNoEsDelFiltroSeRechaza() {
        val bad = ByteArray(64)
        val failed = runCatching { WakeGate(bad) }.isFailure
        assertTrue(failed)
    }
}
