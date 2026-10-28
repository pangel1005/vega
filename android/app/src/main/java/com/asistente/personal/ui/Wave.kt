package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val WAVE_COLS = 120
const val WAVE_STEP_MS = 40L

/** Cuánto tiempo en reposo (sin hablarle) antes de que la onda vuelva a sustituir a la cara. */
const val WAVE_RETURN_MS = 4_000L

/**
 * La onda de la música: el volumen real de lo que suena en el PC (lo mide el servidor) dibujado como la silueta
 * de una onda de audio. Cada lectura entra por la derecha y la silueta se desliza a la izquierda.
 *
 * - Se resalta lo que sobresale de la media reciente (los golpes), no el volumen a secas: una canción plana se ve
 *   distinta de una con ritmo.
 * - Cada golpe (una subida brusca) levanta un pico que baja suave, como las puntas de una onda de audio.
 * - Se normaliza con el pico reciente (que baja despacio): igual de viva con el volumen alto o bajo.
 *
 * Todo sale de la lectura real del volumen; no hay adornos aleatorios.
 */
class WaveState {
    val env = FloatArray(WAVE_COLS)
    var peak = MIN_PEAK
        private set
    private var average = 0f
    private var previous = 0f

    fun push(level: Float) {
        val v = level.coerceIn(0f, 1f)
        peak = max(v, peak * PEAK_DECAY).coerceAtLeast(MIN_PEAK)
        average += (v - average) * AVERAGE_RATE
        val floor = average * FLOOR
        val relative = ((v - floor) / (peak - floor)).coerceIn(0f, 1f) // lo que sobresale de la media
        val hit = ((v - previous) / peak).coerceAtLeast(0f) // la subida brusca de este instante
        previous = v
        val target = (sqrt(relative) * BODY + hit * HIT).coerceIn(0f, 1f)
        val last = env[WAVE_COLS - 1]
        System.arraycopy(env, 1, env, 0, WAVE_COLS - 1)
        env[WAVE_COLS - 1] = max(target, last * RELEASE) // sube de golpe, baja suave
    }

    companion object {
        const val MIN_PEAK = 0.12f // por debajo de esto es silencio: no se amplifica el ruido
        const val PEAK_DECAY = 0.992f
        const val AVERAGE_RATE = 0.06f
        const val FLOOR = 0.55f
        const val BODY = 0.85f
        const val HIT = 1.1f
        const val RELEASE = 0.72f
    }
}

/** ¿Lo que suena es de Spotify? La onda sigue solo a Spotify (el servidor mide solo su audio), no a otras apps. */
fun isSpotify(app: String): Boolean = app.contains("spotify", ignoreCase = true)

/** Cuánto de alta es la onda en cada punto del ancho (0 a 1): del grosor de una línea en los extremos a todo en el centro. */
fun waveTaper(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return TAPER_MIN + (1f - TAPER_MIN) * sin(PI.toFloat() * t).coerceAtLeast(0f).pow(TAPER_POWER) // sin(π) sale -1e-7: sin esto, NaN
}

private const val TAPER_MIN = 0.03f
private const val TAPER_POWER = 0.75f

/** La cara de LED hecha onda: se mueve con lo que suena de verdad. */
@Composable
fun MusicWave(level: () -> Float, color: Color, modifier: Modifier = Modifier) {
    val state = remember { WaveState() }
    val outline = remember { Path() }
    var frame by remember { mutableLongStateOf(0L) }
    var scroll by remember { mutableFloatStateOf(0f) }
    var energy by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // Una vuelta por cuadro de pantalla: la lectura entra cada WAVE_STEP_MS y entre lecturas la silueta
        // se desliza sin saltos.
        val stepNs = WAVE_STEP_MS * 1_000_000L
        var last = androidx.compose.runtime.withFrameNanos { it }
        var pending = 0L
        while (true) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            pending += (now - last).coerceAtMost(stepNs * 3)
            last = now
            while (pending >= stepNs) {
                val v = level()
                state.push(v)
                energy += (v / state.peak - energy) * 0.35f
                pending -= stepNs
            }
            scroll = pending / stepNs.toFloat()
            frame = now
        }
    }
    val density = LocalDensity.current.density
    Canvas(modifier) {
        frame // se repinta en cada cuadro
        val w = size.width
        val h = size.height
        val cy = h / 2
        val n = WAVE_COLS
        val dx = w / (n - 2)
        val half = h * 0.47f
        val live = 0.72f + 0.28f * energy.coerceIn(0f, 1f)

        fun x(i: Int) = (i - scroll) * dx
        fun amp(i: Int): Float = half * waveTaper(x(i) / w) * (0.045f + 0.955f * state.env[i])

        outline.reset()
        outline.moveTo(x(0), cy - amp(0))
        for (i in 1 until n) {
            val mx = (x(i - 1) + x(i)) / 2
            val my = (cy - amp(i - 1) + cy - amp(i)) / 2
            outline.quadraticTo(x(i - 1), cy - amp(i - 1), mx, my)
        }
        outline.lineTo(x(n - 1), cy - amp(n - 1))
        outline.lineTo(x(n - 1), cy + amp(n - 1))
        for (i in n - 1 downTo 1) {
            val mx = (x(i) + x(i - 1)) / 2
            val my = (cy + amp(i) + cy + amp(i - 1)) / 2
            outline.quadraticTo(x(i), cy + amp(i), mx, my)
        }
        outline.lineTo(x(0), cy + amp(0))
        outline.close()

        val bright = Color(
            color.red + (1f - color.red) * 0.45f, color.green + (1f - color.green) * 0.45f,
            color.blue + (1f - color.blue) * 0.45f, 1f,
        )
        // Brillo difuso alrededor (dos pasadas anchas y tenues), el cuerpo y un filo claro.
        drawPath(outline, color.copy(alpha = 0.10f * live), style = Stroke(16 * density, join = StrokeJoin.Round))
        drawPath(outline, color.copy(alpha = 0.22f * live), style = Stroke(6 * density, join = StrokeJoin.Round))
        drawPath(outline, Brush.verticalGradient(listOf(color.copy(alpha = 0.8f * live), bright.copy(alpha = live), color.copy(alpha = 0.8f * live)), cy - half, cy + half))
        drawPath(outline, bright.copy(alpha = 0.9f * live), style = Stroke(1.2f * density, join = StrokeJoin.Round))
        // La línea central, de lado a lado.
        drawLine(bright.copy(alpha = 0.55f), Offset(0f, cy), Offset(w, cy), 1.4f * density, StrokeCap.Round)
    }
}
