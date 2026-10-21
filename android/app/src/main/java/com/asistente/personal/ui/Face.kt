package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import com.asistente.personal.net.AssistantState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val BARS = 11
private const val QUIET = 0.01f

/** Color de acento de cada estado (igual que la web). */
fun accentFor(state: AssistantState): Color = when (state) {
    AssistantState.THINKING -> Nerv.Amber
    AssistantState.LISTENING -> Nerv.Teal
    AssistantState.ACTING -> Nerv.Green
    AssistantState.ERROR -> Nerv.Red
    else -> Nerv.Orange
}

/**
 * Rostro del asistente, dibujado por capas para gastar lo mínimo:
 *
 * - Marco hexagonal y marcas: se dibujan una vez (solo cambian con el color).
 * - Anillos: giran con `graphicsLayer` (la GPU rota una capa ya dibujada).
 * - Ojos: se redibujan solo al cambiar su apertura (parpadeo, estado) o al parpadear en error.
 * - Boca y escáner: se redibujan solo mientras se mueven.
 *
 * En espera, lo único que trabaja en cada cuadro es la rotación de los anillos.
 *
 * @param pulse aumenta con cada trozo de respuesta o bloque de micrófono.
 * @param pulseAmount cuánto se abre la boca con cada pulso (0–1).
 */
@Composable
fun Face(state: AssistantState, pulse: Int, pulseAmount: Float, modifier: Modifier = Modifier) {
    val acc by animateColorAsState(accentFor(state), tween(400), label = "acento")
    val currentState by rememberUpdatedState(state)

    var ring by remember { mutableFloatStateOf(0f) }
    var ringRev by remember { mutableFloatStateOf(0f) }
    var outer by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var mouthTime by remember { mutableFloatStateOf(0f) }
    var scanPhase by remember { mutableFloatStateOf(-1f) }
    var eyeScale by remember { mutableFloatStateOf(1f) }
    var eyeDim by remember { mutableIntStateOf(0) } // 1 = ojos apagados (parpadeo de error)

    LaunchedEffect(pulse) {
        if (pulse > 0) level = min(1f, level + pulseAmount)
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1000f
                last = now
                val t = now / 1000f
                val s = currentState
                val speed = when (s) {
                    AssistantState.THINKING -> 6f
                    AssistantState.ERROR -> 0f
                    else -> 1f
                }
                ring = (ring + dt * 20f * speed) % 360f
                ringRev = (ringRev - dt * 14f * speed) % 360f
                outer = (outer + dt * 9f) % 360f

                // Boca: solo avanza el tiempo (y se redibuja) mientras se mueve.
                if (level > QUIET) {
                    level *= if (s == AssistantState.SPEAKING || s == AssistantState.LISTENING) 0.9f else 0.8f
                    mouthTime = t
                } else if (level != 0f) {
                    level = 0f
                }

                scanPhase = if (s == AssistantState.THINKING) (t % 1.2f) / 1.2f else -1f

                // Ojos: los valores solo cambian en el parpadeo; asignar el mismo valor no redibuja.
                val blinkPhase = (t % 5.5f) / 5.5f
                eyeScale = when (s) {
                    AssistantState.THINKING -> 0.45f
                    AssistantState.LISTENING -> 1.35f
                    else -> if (blinkPhase in 0.95f..0.99f) 0.1f else 1f
                }
                eyeDim = if (s == AssistantState.ERROR && (t * 5).toInt() % 2 == 0) 1 else 0
            }
        }
    }

    Box(modifier.aspectRatio(1f)) {
        // Marcas exteriores: dibujadas una vez, solo giran.
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = outer }.drawWithCache {
                onDrawBehind { drawTicks(acc) }
            },
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = ring }.drawWithCache {
                val effect = segments(size)
                val stroke = Stroke(width = size.minDimension * 0.01f, pathEffect = effect)
                onDrawBehind { drawCircle(acc.copy(alpha = 0.55f), radius = size.minDimension * 0.427f, style = stroke) }
            },
        )
        Box(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = ringRev }.drawWithCache {
                val stroke = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f)))
                onDrawBehind { drawCircle(acc.copy(alpha = 0.6f), radius = size.minDimension * 0.38f, style = stroke) }
            },
        )
        // Marco hexagonal: figuras calculadas una vez por tamaño.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val u = size.minDimension / 300f
                val c = Offset(size.width / 2, size.height / 2)
                val hex = hexPath(c, 110 * u)
                val inner = hexPath(c, 98 * u)
                val frame = Stroke(width = 2 * u)
                val thin = Stroke(width = 1 * u)
                onDrawBehind {
                    drawPath(hex, Color(0xFF0B0A09))
                    drawPath(hex, acc.copy(alpha = 0.06f))
                    drawPath(hex, acc, style = frame)
                    drawPath(inner, acc.copy(alpha = 0.35f), style = thin)
                }
            },
        )
        // Ojos (+ barrido de escáner al pensar).
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val u = size.minDimension / 300f
                val off = Offset(size.width / 2 - 150 * u, size.height / 2 - 150 * u)
                val eyes = listOf(92f, 164f).map { x0 ->
                    Path().apply {
                        moveTo(x0 * u, 122 * u); lineTo((x0 + 44) * u, 122 * u)
                        lineTo((x0 + 40) * u, 138 * u); lineTo((x0 + 4) * u, 138 * u); close()
                        translate(off)
                    }
                }
                val pivot = Offset(size.width / 2, off.y + 130 * u)
                onDrawBehind {
                    val color = if (eyeDim == 1) acc.copy(alpha = 0.15f) else acc
                    scale(1f, eyeScale, pivot = pivot) { eyes.forEach { drawPath(it, color) } }
                    val p = scanPhase
                    if (p >= 0f) {
                        drawRect(
                            acc.copy(alpha = (1 - p) * 0.9f),
                            topLeft = Offset(off.x + 88 * u, off.y + 116 * u + p * 26 * u),
                            size = Size(124 * u, 2 * u),
                        )
                    }
                }
            },
        )
        // Boca: barras tipo osciloscopio.
        Canvas(Modifier.fillMaxSize()) { drawMouth(acc, mouthTime, level) }
    }
}

private fun segments(size: Size): PathEffect {
    val u = size.minDimension / 300f
    return PathEffect.dashPathEffect(floatArrayOf(46 * u, 18 * u, 8 * u, 18 * u))
}

private fun DrawScope.drawTicks(acc: Color) {
    val c = center
    val r1 = size.minDimension * 0.47f
    val r2 = size.minDimension * 0.49f
    val color = acc.copy(alpha = 0.55f)
    val w = size.minDimension * 0.008f
    for (i in 0 until 48) {
        val a = (i / 48f) * 2 * PI.toFloat()
        drawLine(color, Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)), Offset(c.x + r2 * cos(a), c.y + r2 * sin(a)), w)
    }
}

private fun hexPath(c: Offset, r: Float): Path = Path().apply {
    for (i in 0 until 6) {
        val a = (PI / 3 * i - PI / 2).toFloat()
        val p = Offset(c.x + r * cos(a), c.y + r * sin(a))
        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
    }
    close()
}

private fun DrawScope.drawMouth(acc: Color, time: Float, level: Float) {
    val u = size.minDimension / 300f
    val c = center
    val mid = (BARS - 1) / 2f
    for (i in 0 until BARS) {
        val profile = 1 - abs(i - mid) / BARS
        val wobble = 0.55f + 0.45f * sin(time * 17 + i * 1.7f) * sin(time * 5.3f + i)
        val h = 44 * u * (0.08f + level * profile * abs(wobble))
        drawRect(acc, topLeft = Offset(c.x + (102 + i * 9 - 150) * u, c.y + 42 * u - h / 2), size = Size(5 * u, h))
    }
}
