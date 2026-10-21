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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
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

/** Color de acento de cada estado (igual que la web). */
fun accentFor(state: AssistantState): Color = when (state) {
    AssistantState.THINKING -> Nerv.Amber
    AssistantState.LISTENING -> Nerv.Teal
    AssistantState.ACTING -> Nerv.Green
    AssistantState.ERROR -> Nerv.Red
    else -> Nerv.Orange
}

/**
 * Rostro del asistente dibujado de forma nativa.
 *
 * Rendimiento: un único bucle de cuadros actualiza unos pocos números (ángulos,
 * nivel de la boca, parpadeo). Los anillos giran con `graphicsLayer` (solo cambia
 * la rotación de una capa ya dibujada) y la boca se redibuja leyendo esos números
 * dentro de la fase de dibujo: nada provoca recomposición en cada cuadro.
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
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(pulse) {
        if (pulse > 0) level = min(1f, level + pulseAmount)
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 0f else (now - last) / 1000f
                last = now
                time = now / 1000f
                val s = currentState
                val speed = when (s) {
                    AssistantState.THINKING -> 6f
                    AssistantState.ERROR -> 0f
                    else -> 1f
                }
                ring = (ring + dt * 20f * speed) % 360f
                ringRev = (ringRev - dt * 14f * speed) % 360f
                outer = (outer + dt * 9f) % 360f
                level *= if (s == AssistantState.SPEAKING || s == AssistantState.LISTENING) 0.9f else 0.8f
            }
        }
    }

    Box(modifier.aspectRatio(1f)) {
        // Anillo exterior de marcas (gira muy despacio).
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = outer }) { drawTicks(acc) }
        // Anillos de segmentos.
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = ring }) {
            drawCircle(
                acc.copy(alpha = 0.55f), radius = size.minDimension * 0.427f,
                style = Stroke(width = size.minDimension * 0.01f, pathEffect = segments(size)),
            )
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = ringRev }) {
            drawCircle(
                acc.copy(alpha = 0.6f), radius = size.minDimension * 0.38f,
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f))),
            )
        }
        // Hexágono, ojos y boca.
        Canvas(Modifier.fillMaxSize()) { drawCore(currentState, acc, time, level) }
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
    for (i in 0 until 48) {
        val a = (i / 48f) * 2 * PI.toFloat()
        drawLine(
            acc.copy(alpha = 0.55f),
            Offset(c.x + r1 * cos(a), c.y + r1 * sin(a)),
            Offset(c.x + r2 * cos(a), c.y + r2 * sin(a)),
            strokeWidth = size.minDimension * 0.008f,
        )
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

private fun DrawScope.drawCore(state: AssistantState, acc: Color, time: Float, level: Float) {
    val u = size.minDimension / 300f // unidades del diseño original de 300×300 (face.css)
    val c = center

    // Marco hexagonal
    val hex = hexPath(c, 110 * u)
    drawPath(hex, Color(0xFF0B0A09))
    drawPath(hex, acc.copy(alpha = 0.06f))
    drawPath(hex, acc, style = Stroke(width = 2 * u))
    drawPath(hexPath(c, 98 * u), acc.copy(alpha = 0.35f), style = Stroke(width = 1 * u))

    // Ojos: parpadeo cada 5,5 s; entrecerrados al pensar, abiertos al escuchar.
    val blinkPhase = (time % 5.5f) / 5.5f
    val blink = if (blinkPhase in 0.95f..0.99f) 0.1f else 1f
    val eyeScale = when (state) {
        AssistantState.THINKING -> 0.45f
        AssistantState.LISTENING -> 1.35f
        else -> blink
    }
    val errorBlink = state == AssistantState.ERROR && ((time * 5).toInt() % 2 == 0)
    val eyeColor = if (errorBlink) acc.copy(alpha = 0.15f) else acc
    scale(1f, eyeScale, pivot = Offset(c.x, 130 * u + (c.y - 150 * u))) {
        for (x0 in floatArrayOf(92f, 164f)) {
            val p = Path().apply {
                moveTo((x0) * u, 122 * u); lineTo((x0 + 44) * u, 122 * u)
                lineTo((x0 + 40) * u, 138 * u); lineTo((x0 + 4) * u, 138 * u); close()
            }
            p.translate(Offset(c.x - 150 * u, c.y - 150 * u))
            drawPath(p, eyeColor)
        }
    }

    // Barrido de escáner sobre los ojos al pensar.
    if (state == AssistantState.THINKING) {
        val t = (time % 1.2f) / 1.2f
        drawRect(
            acc.copy(alpha = (1 - t) * 0.9f),
            topLeft = Offset(c.x - 62 * u, c.y - 34 * u + t * 26 * u),
            size = Size(124 * u, 2 * u),
        )
    }

    // Boca: barras tipo osciloscopio.
    val mid = (BARS - 1) / 2f
    for (i in 0 until BARS) {
        val profile = 1 - abs(i - mid) / BARS
        val wobble = 0.55f + 0.45f * sin(time * 17 + i * 1.7f) * sin(time * 5.3f + i)
        val s = 0.08f + level * profile * abs(wobble)
        val h = 44 * u * s
        val x = c.x + (102 + i * 9 - 150) * u
        drawRect(acc, topLeft = Offset(x, c.y + 42 * u - h / 2), size = Size(5 * u, h))
    }
}
