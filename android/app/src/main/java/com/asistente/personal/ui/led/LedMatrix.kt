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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.min

/** Color de un LED apagado: se ve la rejilla entera, como en un panel real. */
val LedOff = Color(0xFF181716)

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
fun LedMatrix(values: FloatArray, cols: Int, rows: Int, color: Color, tick: () -> Int, modifier: Modifier = Modifier, dot: Float = 0.62f) {
    // Capa propia: cuando la cara cambia solo se vuelve a grabar esta capa, no toda la pantalla
    // (medido en el teléfono: redibujar todo costaba ~11 ms por cuadro).
    Box(
        modifier.graphicsLayer().drawWithCache {
            val g = Grid(size, cols, rows)
            val base = offBitmap(this, g, cols, rows, dot)
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
                if (glow.isNotEmpty()) {
                    drawPoints(glow, PointMode.Points, color.copy(alpha = 0.16f), strokeWidth = g.pitch * 0.9f, cap = StrokeCap.Round)
                }
                for (i in 0 until LEVELS) {
                    if (buckets[i].isEmpty()) continue
                    val alpha = (i + 1f) / LEVELS
                    drawPoints(buckets[i], PointMode.Points, color.copy(alpha = alpha), strokeWidth = g.pitch * dot, cap = StrokeCap.Round)
                }
            }
        },
    )
}

private fun offBitmap(scope: CacheDrawScope, g: Grid, cols: Int, rows: Int, dot: Float): ImageBitmap {
    val w = scope.size.width.toInt().coerceAtLeast(1)
    val h = scope.size.height.toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(scope, scope.layoutDirection, BitmapCanvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        val pts = ArrayList<Offset>(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) pts.add(g.at(x, y))
        drawPoints(pts, PointMode.Points, LedOff, strokeWidth = g.pitch * dot, cap = StrokeCap.Round)
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
) {
    val animator = remember { LedFaceAnimator() }
    val currentMood by rememberUpdatedState(mood)
    var tick by remember { mutableIntStateOf(0) }
    // Volumen: sube con cada trozo de voz (o de micrófono) y cae solo entre medias.
    val level = remember { floatArrayOf(0f) }
    val shade by animateColorAsState(color, tween(350), label = "color de la cara")

    LaunchedEffect(pulse) { if (pulse > 0) level[0] = maxOf(level[0], pulseAmount) }
    LaunchedEffect(noticeKey) { if (notice != null) animator.showText(notice) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
                last = now
                level[0] *= kotlin.math.exp(-dt * 5f)
                if (animator.step(dt, now / 1000f, currentMood, level[0])) tick++
            }
        }
    }
    LedMatrix(animator.shown, FACE_COLS, FACE_ROWS, shade, { tick }, modifier)
}
