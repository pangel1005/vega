package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import com.asistente.personal.ui.led.ledOff
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val WAVE_COLS = 64
const val WAVE_HALF = 10 // puntos hacia arriba y hacia abajo de la línea central (21 filas en total)
const val WAVE_STEP_MS = 45L

/** Cuánto tiempo en reposo (sin hablarle) antes de que la onda vuelva a sustituir a la cara. */
const val WAVE_RETURN_MS = 4_000L

/**
 * La onda de la música: el volumen real de lo que suena en el PC (lo mide el servidor), dibujado como las barras
 * simétricas de una onda de audio. Cada lectura entra por la derecha y la onda se desplaza a la izquierda.
 *
 * El volumen se normaliza con el pico reciente (que baja despacio): así se ve igual de viva con el volumen del PC
 * alto o bajo.
 */
class WaveState {
    val bars = FloatArray(WAVE_COLS)
    var peak = MIN_PEAK
        private set

    fun push(level: Float) {
        val v = level.coerceIn(0f, 1f)
        peak = max(v, peak * PEAK_DECAY).coerceAtLeast(MIN_PEAK)
        val shaped = sqrt((v / peak).coerceIn(0f, 1f)) // la raíz da más vida a lo suave
        val previous = bars[WAVE_COLS - 1]
        System.arraycopy(bars, 1, bars, 0, WAVE_COLS - 1)
        bars[WAVE_COLS - 1] = max(shaped, previous * RELEASE) // sube de golpe, baja suave
    }

    companion object {
        const val MIN_PEAK = 0.12f // por debajo de esto es silencio, no se amplifica el ruido
        const val PEAK_DECAY = 0.992f
        const val RELEASE = 0.6f
    }
}

/** Cuántos puntos se encienden por encima (y por debajo) de la línea central. */
fun waveLit(bar: Float): Int = (bar.coerceIn(0f, 1f) * WAVE_HALF).roundToInt()

/** La cara de LED hecha onda: se mueve con lo que suena de verdad. */
@Composable
fun MusicWave(level: () -> Float, color: Color, modifier: Modifier = Modifier) {
    val state = remember { WaveState() }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(WAVE_STEP_MS)
            state.push(level())
            tick++
        }
    }
    val off = ledOff
    Canvas(modifier) {
        tick // se repinta con cada lectura
        val rows = WAVE_HALF * 2 + 1
        val pitch = minOf(size.width / WAVE_COLS, size.height / rows)
        val r = pitch * 0.4f
        val left = (size.width - pitch * WAVE_COLS) / 2
        val top = (size.height - pitch * rows) / 2
        for (c in 0 until WAVE_COLS) {
            val lit = waveLit(state.bars[c])
            for (row in -WAVE_HALF..WAVE_HALF) {
                val on = row == 0 || abs(row) <= lit
                val edge = 1f - 0.45f * abs(row) / WAVE_HALF
                val dot = when {
                    !on -> off
                    row == 0 && lit == 0 -> color.copy(alpha = 0.5f) // en silencio, solo la línea central
                    else -> color.copy(alpha = edge)
                }
                drawCircle(dot, r, Offset(left + pitch * (c + 0.5f), top + pitch * (row + WAVE_HALF + 0.5f)))
            }
        }
    }
}
