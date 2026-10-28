package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

const val WAVE_COLS = 120
const val WAVE_STEP_MS = 40L

/** Cuánto tiempo en reposo (sin hablarle) antes de que la onda vuelva a sustituir a la cara. */
const val WAVE_RETURN_MS = 4_000L

/** Cómo se dibuja la onda de la música (se elige en «Más»). */
enum class WaveStyle(val label: String) {
    /** Barras finas verticales en espejo, que se afinan en los extremos. */
    BARS("Barras"),

    /** Curvas de neón que se cruzan, línea central con destello y fondo de columnas de puntos LED. */
    NEON("Neón"),

    /** Una silueta continua rellena con brillo. */
    SILHOUETTE("Silueta"),
    ;

    fun next(): WaveStyle = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(name: String?): WaveStyle = entries.firstOrNull { it.name == name } ?: BARS
    }
}

/**
 * La onda de la música: el volumen real de lo que suena en Spotify (lo mide el servidor) dibujado como una onda de
 * audio. Cada lectura entra por la derecha y todo se desliza a la izquierda.
 *
 * - Se resalta lo que sobresale de la media reciente (los golpes), no el volumen a secas: una canción plana se ve
 *   distinta de una con ritmo.
 * - Cada golpe (una subida brusca) levanta un pico que baja suave.
 * - Se normaliza con el pico reciente (que baja despacio): igual de viva con el volumen alto o bajo.
 *
 * La altura de todo sale de la lectura real. Lo único decorativo es la ondulación de las curvas del estilo «Neón»
 * (el medidor de Windows solo da el volumen, unas 20 veces por segundo, no la forma de la onda).
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

/** El valor de la envolvente en una posición fraccionaria (interpolado entre columnas); fuera de rango, el extremo. */
fun envAt(env: FloatArray, index: Float): Float {
    if (env.isEmpty()) return 0f
    val i = index.coerceIn(0f, env.size - 1f)
    val lo = floor(i).toInt()
    val hi = minOf(lo + 1, env.size - 1)
    val t = i - lo
    return env[lo] + (env[hi] - env[lo]) * t
}

/** La envolvente suavizada (media ponderada de tres puntos cercanos): sin picos agudos, para curvas fluidas. */
fun envSmooth(env: FloatArray, index: Float): Float = (envAt(env, index - 2f) + 2f * envAt(env, index) + envAt(env, index + 2f)) / 4f

/** ¿Lo que suena es de Spotify? La onda sigue solo a Spotify (el servidor mide solo su audio), no a otras apps. */
fun isSpotify(app: String): Boolean = app.contains("spotify", ignoreCase = true)

/** Cuánto de alta es la onda en cada punto del ancho (0 a 1): del grosor de una línea en los extremos a todo en el centro. */
fun waveTaper(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return TAPER_MIN + (1f - TAPER_MIN) * sin(PI.toFloat() * t).coerceAtLeast(0f).pow(TAPER_POWER) // sin(π) sale -1e-7: sin esto, NaN
}

private const val TAPER_MIN = 0.03f
private const val TAPER_POWER = 0.75f

private fun lighten(c: Color, amount: Float) = Color(
    c.red + (1f - c.red) * amount, c.green + (1f - c.green) * amount, c.blue + (1f - c.blue) * amount, c.alpha,
)

/** La cara de LED hecha onda: se mueve con lo que suena de verdad. */
@Composable
fun MusicWave(level: () -> Float, color: Color, style: WaveStyle = WaveStyle.BARS, modifier: Modifier = Modifier) {
    val state = remember { WaveState() }
    val path = remember { Path() }
    val curve = remember { Path() }
    var frame by remember { mutableLongStateOf(0L) }
    var scroll by remember { mutableFloatStateOf(0f) }
    var energy by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        // Una vuelta por cuadro de pantalla: la lectura entra cada WAVE_STEP_MS y entre lecturas todo se desliza
        // sin saltos.
        val stepNs = WAVE_STEP_MS * 1_000_000L
        var last = withFrameNanos { it }
        var pending = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = (now - last).coerceAtMost(stepNs * 3)
            pending += dt
            time += dt / 1e9f
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
        when (style) {
            WaveStyle.BARS -> drawBars(state.env, scroll, color, energy.coerceIn(0f, 1f), density, path)
            WaveStyle.NEON -> drawNeon(state.env, scroll, color, energy.coerceIn(0f, 1f), time, density, curve)
            WaveStyle.SILHOUETTE -> drawSilhouette(state.env, scroll, color, energy.coerceIn(0f, 1f), density, path)
        }
    }
}

/** Ancho en pantalla de una columna de la envolvente: la envolvente se desliza `scroll` columnas entre lecturas. */
private fun DrawScope.columnWidth() = size.width / (WAVE_COLS - 2)

/**
 * Estilo «Barras»: cientos de barras finas en forma de lente (puntas arriba y abajo), simétricas desde el centro. La
 * altura de cada una es la envolvente real en ese punto; las pares y las impares miran a momentos casi iguales
 * (un instante de diferencia) para que no parezca una escalera.
 */
private fun DrawScope.drawBars(env: FloatArray, scroll: Float, color: Color, energy: Float, density: Float, path: Path) {
    val w = size.width
    val h = size.height
    val cy = h / 2
    val half = h * 0.47f
    val pitch = 3.3f * density
    val count = (w / pitch).toInt()
    val barWidth = 1.9f * density
    val dx = columnWidth()
    val live = 0.78f + 0.22f * energy
    val bright = lighten(color, 0.6f)

    path.reset()
    for (b in 0 until count) {
        val x = (b + 0.5f) * pitch
        val index = x / dx + scroll
        val shift = if (b % 2 == 0) 0f else 0.45f
        val e = envAt(env, index + shift)
        val a = half * waveTaper(x / w) * (0.025f + 0.975f * e)
        path.moveTo(x, cy - a)
        path.quadraticTo(x + barWidth, cy, x, cy + a)
        path.quadraticTo(x - barWidth, cy, x, cy - a)
        path.close()
    }
    // Brillo difuso, el cuerpo (más claro en la mitad de abajo, como un reflejo) y la línea central.
    drawPath(path, color.copy(alpha = 0.10f * live), style = Stroke(9 * density, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.18f * live), style = Stroke(3.5f * density, join = StrokeJoin.Round))
    drawPath(
        path,
        Brush.verticalGradient(
            0f to color.copy(alpha = 0.92f * live),
            0.5f to bright.copy(alpha = live),
            0.5001f to lighten(color, 0.35f).copy(alpha = 0.95f * live),
            1f to bright.copy(alpha = 0.7f * live),
            startY = cy - half,
            endY = cy + half,
        ),
    )
    drawLine(bright.copy(alpha = 0.5f * live), Offset(0f, cy), Offset(w, cy), 1.2f * density, StrokeCap.Round)
}

private val NeonFreq = floatArrayOf(2.6f, 4.1f, 6.2f) // ondulaciones a lo ancho de cada curva
private val NeonSpeed = floatArrayOf(1.1f, -1.6f, 2.3f) // radianes por segundo
private val NeonGain = floatArrayOf(1f, 0.64f, 0.4f)
private val NeonLag = floatArrayOf(0f, 4f, 9f) // columnas de retraso: cada curva mira a un momento distinto
private val NeonWidth = floatArrayOf(2.6f, 1.8f, 1.1f)

/**
 * Estilo «Neón»: tres curvas brillantes que se cruzan, cuya altura es la envolvente real; una línea central con
 * destello que respira con la música; y detrás, columnas de puntos LED a la altura de la envolvente.
 */
private fun DrawScope.drawNeon(
    env: FloatArray, scroll: Float, color: Color, energy: Float, time: Float, density: Float, curve: Path,
) {
    val w = size.width
    val h = size.height
    val cy = h / 2
    val half = h * 0.46f
    val dx = columnWidth()
    val live = 0.8f + 0.2f * energy
    val bright = lighten(color, 0.55f)
    val white = lighten(color, 0.85f)

    // Fondo: columnas de puntos LED (solo los encendidos), a la altura de la envolvente.
    val colPitch = 10f * density
    val rowPitch = 7.5f * density
    val rows = (half / rowPitch).toInt()
    val dots = ArrayList<Offset>(512)
    var c = 0
    while (c * colPitch < w) {
        val x = (c + 0.5f) * colPitch
        val e = envAt(env, x / dx + scroll - 2f) * waveTaper(x / w)
        val lit = (e * rows * 0.9f + 0.4f).toInt().coerceAtMost(rows)
        for (r in 1..lit) {
            dots.add(Offset(x, cy - r * rowPitch))
            dots.add(Offset(x, cy + r * rowPitch))
        }
        c++
    }
    drawPoints(dots, PointMode.Points, color.copy(alpha = 0.42f * live), 3.8f * density, StrokeCap.Round)

    // Curvas de neón.
    val step = 6f * density
    for (k in 0 until 3) {
        curve.reset()
        var x = 0f
        var px = 0f
        var py = 0f
        var first = true
        while (x <= w + step) {
            val e = envSmooth(env, x / dx + scroll - NeonLag[k])
            val amp = half * NeonGain[k] * waveTaper(x / w) * (0.03f + 0.97f * e)
            val y = cy - amp * sin(2f * PI.toFloat() * NeonFreq[k] * x / w + NeonSpeed[k] * time + k * 1.7f)
            if (first) {
                curve.moveTo(x, y)
            } else {
                curve.quadraticTo(px, py, (px + x) / 2, (py + y) / 2) // curva suave por los puntos medios
            }
            first = false
            px = x
            py = y
            x += step
        }
        val tint = when (k) {
            0 -> bright
            1 -> color
            else -> white
        }
        val round = { width: Float -> Stroke(width * density, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        drawPath(curve, tint.copy(alpha = 0.10f * live), style = round(NeonWidth[k] * 4.5f))
        drawPath(curve, tint.copy(alpha = 0.28f * live), style = round(NeonWidth[k] * 2.2f))
        drawPath(curve, tint.copy(alpha = live), style = round(NeonWidth[k]))
    }

    // Línea central con destello: se afina hacia los lados y el centro brilla más cuanto más suena.
    val fade = Brush.horizontalGradient(
        0f to Color.Transparent, 0.18f to bright.copy(alpha = 0.7f), 0.5f to white, 0.82f to bright.copy(alpha = 0.7f), 1f to Color.Transparent,
    )
    drawLine(fade, Offset(0f, cy), Offset(w, cy), 7f * density, StrokeCap.Round, alpha = 0.22f * live)
    drawLine(fade, Offset(0f, cy), Offset(w, cy), 2.2f * density, StrokeCap.Round, alpha = live)
    val flareR = w * (0.10f + 0.16f * energy)
    scale(1f, 0.22f, Offset(w / 2, cy)) {
        drawCircle(
            Brush.radialGradient(
                listOf(white.copy(alpha = 0.55f * live), bright.copy(alpha = 0.22f * live), Color.Transparent), Offset(w / 2, cy), flareR,
            ),
            flareR, Offset(w / 2, cy),
        )
    }
}

/** Estilo «Silueta»: una forma continua rellena con brillo, de contorno suave. */
private fun DrawScope.drawSilhouette(env: FloatArray, scroll: Float, color: Color, energy: Float, density: Float, path: Path) {
    val w = size.width
    val h = size.height
    val cy = h / 2
    val n = WAVE_COLS
    val dx = columnWidth()
    val half = h * 0.47f
    val live = 0.72f + 0.28f * energy

    fun x(i: Int) = (i - scroll) * dx
    fun amp(i: Int): Float = half * waveTaper(x(i) / w) * (0.045f + 0.955f * env[i])

    path.reset()
    path.moveTo(x(0), cy - amp(0))
    for (i in 1 until n) {
        val mx = (x(i - 1) + x(i)) / 2
        val my = (cy - amp(i - 1) + cy - amp(i)) / 2
        path.quadraticTo(x(i - 1), cy - amp(i - 1), mx, my)
    }
    path.lineTo(x(n - 1), cy - amp(n - 1))
    path.lineTo(x(n - 1), cy + amp(n - 1))
    for (i in n - 1 downTo 1) {
        val mx = (x(i) + x(i - 1)) / 2
        val my = (cy + amp(i) + cy + amp(i - 1)) / 2
        path.quadraticTo(x(i), cy + amp(i), mx, my)
    }
    path.lineTo(x(0), cy + amp(0))
    path.close()

    val bright = lighten(color, 0.45f)
    drawPath(path, color.copy(alpha = 0.10f * live), style = Stroke(16 * density, join = StrokeJoin.Round))
    drawPath(path, color.copy(alpha = 0.22f * live), style = Stroke(6 * density, join = StrokeJoin.Round))
    drawPath(
        path,
        Brush.verticalGradient(listOf(color.copy(alpha = 0.8f * live), bright.copy(alpha = live), color.copy(alpha = 0.8f * live)), cy - half, cy + half),
    )
    drawPath(path, bright.copy(alpha = 0.9f * live), style = Stroke(1.2f * density, join = StrokeJoin.Round))
    drawLine(bright.copy(alpha = 0.55f), Offset(0f, cy), Offset(w, cy), 1.4f * density, StrokeCap.Round)
}
