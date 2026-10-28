package com.asistente.personal.ui.led

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.min

/** Color de un LED apagado: se ve la rejilla entera, como en un panel real. */
val LedOff = Color(0xFF181716)
/** LED apagado sobre cristal: un punto de luz casi invisible (el gris oscuro se veía como un agujero). */
val GlassLedOff = Color.White.copy(alpha = 0.07f)

/** El LED apagado del estilo actual. */
val ledOff: Color @androidx.compose.runtime.Composable get() =
    if (com.asistente.personal.ui.LocalAurora.current != null) GlassLedOff else LedOff

private const val LEVELS = 8

/**
 * Geometría de la matriz dentro del espacio disponible: puntos cuadrados y centrados.
 */
private class Grid(size: Size, cols: Int, rows: Int) {
    val pitch = min(size.width / cols, size.height / rows)
    val ox = (size.width - pitch * cols) / 2 + pitch / 2
    val oy = (size.height - pitch * rows) / 2 + pitch / 2
    fun at(x: Int, y: Int) = Offset(ox + x * pitch, oy + y * pitch)
}

/**
 * Dibuja una matriz de LED. Los puntos apagados se pintan una sola vez en una imagen;
 * en cada cuadro solo se pintan los encendidos, agrupados en 8 niveles de brillo (una
 * llamada por nivel), más un halo suave en los más brillantes.
 *
 * @param tick cambia cuando `values` cambió (leerlo aquí hace que solo se redibuje esta capa)
 */
@Composable
fun LedMatrix(
    values: FloatArray,
    cols: Int,
    rows: Int,
    color: Color,
    tick: () -> Int,
    modifier: Modifier = Modifier,
    dot: Float = 0.62f,
    /** Si no es null, los LED encendidos se pintan con este pincel (p. ej. el arcoíris) en vez de `color`. */
    brush: ((width: Float) -> Brush)? = null,
    off: Color = LedOff,
) {
    // Capa propia: cuando la cara cambia solo se vuelve a grabar esta capa, no toda la pantalla
    // (medido en el teléfono: redibujar todo costaba ~11 ms por cuadro).
    Box(
        modifier.graphicsLayer().drawWithCache {
            val g = Grid(size, cols, rows)
            val base = offBitmap(this, g, cols, rows, dot, off)
            val buckets = Array(LEVELS) { ArrayList<Offset>(cols * rows / 4) }
            val glow = ArrayList<Offset>(cols * rows / 4)
            onDrawBehind {
                drawImage(base)
                tick() // suscribe el dibujo a los cambios
                buckets.forEach { it.clear() }
                glow.clear()
                for (y in 0 until rows) for (x in 0 until cols) {
                    val a = values[y * cols + x]
                    if (a < 0.04f) continue
                    val p = g.at(x, y)
                    buckets[min(LEVELS - 1, (a * LEVELS).toInt())].add(p)
                    if (a > 0.8f) glow.add(p)
                }
                val paint = brush?.invoke(size.width)
                if (glow.isNotEmpty()) {
                    if (paint != null) {
                        drawPoints(glow, PointMode.Points, paint, strokeWidth = g.pitch * 0.9f, cap = StrokeCap.Round, alpha = 0.16f)
                    } else {
                        drawPoints(glow, PointMode.Points, color.copy(alpha = 0.16f), strokeWidth = g.pitch * 0.9f, cap = StrokeCap.Round)
                    }
                }
                for (i in 0 until LEVELS) {
                    if (buckets[i].isEmpty()) continue
                    val alpha = (i + 1f) / LEVELS
                    if (paint != null) {
                        drawPoints(buckets[i], PointMode.Points, paint, strokeWidth = g.pitch * dot, cap = StrokeCap.Round, alpha = alpha)
                    } else {
                        drawPoints(buckets[i], PointMode.Points, color.copy(alpha = alpha), strokeWidth = g.pitch * dot, cap = StrokeCap.Round)
                    }
                }
            }
        },
    )
}

private fun offBitmap(scope: CacheDrawScope, g: Grid, cols: Int, rows: Int, dot: Float, off: Color): ImageBitmap {
    val w = scope.size.width.toInt().coerceAtLeast(1)
    val h = scope.size.height.toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(scope, scope.layoutDirection, BitmapCanvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        val pts = ArrayList<Offset>(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) pts.add(g.at(x, y))
        drawPoints(pts, PointMode.Points, off, strokeWidth = g.pitch * dot, cap = StrokeCap.Round)
    }
    return bitmap
}

/**
 * La cara de Vega en LED. Late con el reloj de la pantalla y solo pide redibujar
 * cuando algún punto cambia (en reposo, casi nunca: parpadeos y miradas).
 *
 * @param notice texto que pasa por la cara (un aviso); `noticeKey` cambia con cada aviso nuevo
 */
@Composable
fun LedFaceView(
    mood: Mood,
    pulse: Int,
    pulseAmount: Float,
    color: Color,
    notice: String?,
    noticeKey: Int,
    modifier: Modifier = Modifier,
    /** Arcoíris que se desplaza despacio por la cara (en vez de un solo color). */
    rainbow: Boolean = false,
    /** Cuenta atrás del temporizador en la boca («4:59»); null si no hay. */
    countdown: String? = null,
    /** Hacia dónde mirar un momento; cada mirada nueva lleva otro `seq`. */
    glance: Glance? = null,
) {
    val animator = remember { LedFaceAnimator() }
    animator.countdown = countdown
    LaunchedEffect(glance?.seq) { glance?.let { animator.glance(it.x, it.y) } }
    val currentRainbow by rememberUpdatedState(rainbow)
    val time = remember { floatArrayOf(0f) }
    val currentMood by rememberUpdatedState(mood)
    var tick by remember { mutableIntStateOf(0) }
    // Volumen: sube con cada trozo de voz (o de micrófono) y cae solo entre medias.
    val level = remember { floatArrayOf(0f) }
    val shade by animateColorAsState(color, tween(350), label = "color de la cara")

    LaunchedEffect(pulse) { if (pulse > 0) level[0] = maxOf(level[0], pulseAmount) }
    LaunchedEffect(noticeKey) { if (notice != null) animator.showText(notice) }
    val currentNotice by rememberUpdatedState(notice)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            // En reposo no hace falta un cuadro cada 16 ms: se espera y se pinta solo cuando toca.
            val pause = framePacingMs(currentMood, currentRainbow, currentNotice != null)
            if (pause > 0) delay(pause)
            withFrameMillis { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
                last = now
                level[0] *= kotlin.math.exp(-dt * 5f)
                time[0] = now / 1000f
                // En arcoíris los colores se mueven: hay que redibujar cada cuadro aunque la cara esté quieta.
                if (animator.step(dt, time[0], currentMood, level[0]) || currentRainbow) tick++
            }
        }
    }
    LedMatrix(
        animator.shown, FACE_COLS, FACE_ROWS, shade, { tick }, modifier,
        brush = if (rainbow) { width -> rainbowBrush(width, time[0]) } else null,
        off = ledOff,
    )
}

/** Una mirada hacia algo de la pantalla (x, y entre −1 y 1). */
data class Glance(val seq: Int, val x: Float, val y: Float)

private val RAINBOW = listOf(
    Color(0xFFFF4D4D), Color(0xFFFFA43D), Color(0xFFFFE15C), Color(0xFF6CF0A8),
    Color(0xFF59C8FF), Color(0xFF9B7BFF), Color(0xFFFF6FD8), Color(0xFFFF4D4D),
)

/** Arcoíris horizontal que da una vuelta completa cada 8 segundos. */
fun rainbowBrush(width: Float, seconds: Float): Brush {
    val shift = (seconds / 8f % 1f) * width
    return Brush.linearGradient(RAINBOW, start = Offset(-shift, 0f), end = Offset(width - shift, 0f), tileMode = TileMode.Repeated)
}
