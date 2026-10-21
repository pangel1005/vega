package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

private const val R = 30f
private const val PULSES = 7

private fun hexAt(path: Path, x: Float, y: Float, r: Float) {
    for (i in 0 until 6) {
        val a = (PI / 3 * i + PI / 6).toFloat()
        val px = x + r * cos(a)
        val py = y + r * sin(a)
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
}

/**
 * Fondo de hexágonos (como el portafolio NERV). La rejilla se construye una sola vez
 * por tamaño (`drawWithCache`) y vive en su propia capa; encima, unas pocas celdas
 * laten a ~30 FPS, que es de sobra para un fondo y ahorra batería.
 */
@Composable
fun HexBackground(accent: Color, fast: Boolean, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    val cells = remember { mutableListOf<Offset>() }
    val pulses = remember { Array(PULSES) { floatArrayOf(Random.nextFloat(), 0f) } } // [fase, celda]

    LaunchedEffect(fast) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1000f
                last = now
                val speed = if (fast) 0.9f else 0.3f
                for (p in pulses) {
                    p[0] += dt * speed
                    if (p[0] >= 1f && cells.isNotEmpty()) {
                        p[0] = 0f
                        p[1] = Random.nextInt(cells.size).toFloat()
                    }
                }
                t = now / 1000f
            }
            delay(33) // ~30 FPS
        }
    }

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                val w = R * sqrt(3f)
                val h = R * 1.5f
                val path = Path()
                cells.clear()
                var row = -1
                while (row * h < size.height + R) {
                    var col = -1
                    while (col * w < size.width + w) {
                        val x = col * w + if (row % 2 != 0) w / 2 else 0f
                        val y = row * h
                        cells += Offset(x, y)
                        hexAt(path, x, y, R - 1)
                        col++
                    }
                    row++
                }
                // Se pinta una vez en una imagen; cada cuadro solo la copia (muy barato para la GPU).
                val grid = renderToBitmap(this) { drawPath(path, accent.copy(alpha = 0.07f), style = Stroke(1f)) }
                onDrawBehind { drawImage(grid) }
            },
        )
        Canvas(Modifier.fillMaxSize()) {
            // Leer `t` aquí hace que cada tic redibuje solo esta capa, sin recomponer nada.
            if (t < 0f) return@Canvas
            for (p in pulses) {
                val cell = cells.getOrNull(p[1].toInt()) ?: continue
                val path = Path().also { hexAt(it, cell.x, cell.y, R - 3) }
                drawPath(path, accent.copy(alpha = sin(p[0] * PI.toFloat()) * 0.16f))
            }
        }
    }
}

/**
 * Capa de líneas de escáner y viñeta, para poner encima de todo. Vive en su propia
 * imagen y solo se pinta de nuevo si cambia el tamaño de la pantalla.
 */
fun Modifier.scanlines(): Modifier = this.graphicsLayer().drawWithCache {
    val vignette = Brush.radialGradient(
        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f),
        center = Offset(size.width / 2, size.height / 2), radius = size.maxDimension * 0.6f,
    )
    val overlay = renderToBitmap(this) {
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), 1f)
            y += 3f
        }
        drawRect(vignette)
    }
    onDrawBehind { drawImage(overlay) }
}

/** Dibuja una sola vez en una imagen del tamaño de la capa. */
private fun renderToBitmap(scope: CacheDrawScope, block: DrawScope.() -> Unit): ImageBitmap {
    val w = scope.size.width.toInt().coerceAtLeast(1)
    val h = scope.size.height.toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(scope, scope.layoutDirection, BitmapCanvas(bitmap), Size(w.toFloat(), h.toFloat()), block)
    return bitmap
}
