package com.asistente.personal.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Iconos dibujados con trazos (sin imágenes): ligeros y en el color del botón. */
fun DrawScope.drawDeckIcon(name: String, c: Color) {
    val w = size.width
    val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val mid = Offset(w / 2, w / 2)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), s.width, StrokeCap.Round)
    fun poly(vararg p: Float, fill: Boolean = true) {
        val path = Path().apply {
            moveTo(p[0] * w, p[1] * w)
            for (i in 2 until p.size step 2) lineTo(p[i] * w, p[i + 1] * w)
            close()
        }
        if (fill) drawPath(path, c) else drawPath(path, c, style = s)
    }
    fun speaker() = poly(0.1f, 0.38f, 0.28f, 0.38f, 0.5f, 0.18f, 0.5f, 0.82f, 0.28f, 0.62f, 0.1f, 0.62f)

    when (name) {
        "power" -> {
            drawArc(c, -60f, 300f, false, Offset(w * 0.15f, w * 0.18f), Size(w * 0.7f, w * 0.7f), style = s)
            line(0.5f, 0.08f, 0.5f, 0.48f)
        }
        "restart" -> {
            drawArc(c, -30f, 290f, false, Offset(w * 0.15f, w * 0.15f), Size(w * 0.7f, w * 0.7f), style = s)
            poly(0.62f, 0.05f, 0.92f, 0.22f, 0.62f, 0.38f)
        }
        "sleep" -> {
            // Una luna creciente: un círculo al que se le quita otro desplazado (no se pinta de negro encima: en LED el negro también cuenta).
            val full = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.14f, w * 0.14f), Size(w * 0.72f, w * 0.72f))) }
            val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * 0.36f, w * 0.0f), Size(w * 0.66f, w * 0.66f))) }
            drawPath(Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, full, bite), c)
        }
        "lock" -> {
            drawArc(c, 180f, 180f, false, Offset(w * 0.28f, w * 0.1f), Size(w * 0.44f, w * 0.5f), style = s)
            drawRect(c, Offset(w * 0.18f, w * 0.42f), Size(w * 0.64f, w * 0.48f))
        }
        "cancel" -> { line(0.2f, 0.2f, 0.8f, 0.8f); line(0.8f, 0.2f, 0.2f, 0.8f) }
        "record" -> { drawCircle(c, w * 0.42f, mid, style = s); drawCircle(c, w * 0.24f, mid) }
        "screenshot" -> {
            drawRect(c, Offset(w * 0.1f, w * 0.22f), Size(w * 0.8f, w * 0.6f), style = s)
            drawCircle(c, w * 0.14f, Offset(w * 0.5f, w * 0.52f))
        }
        "mute" -> { speaker(); line(0.64f, 0.36f, 0.92f, 0.64f); line(0.92f, 0.36f, 0.64f, 0.64f) }
        "volume-up" -> { speaker(); line(0.62f, 0.5f, 0.94f, 0.5f); line(0.78f, 0.34f, 0.78f, 0.66f) }
        "volume-down" -> { speaker(); line(0.62f, 0.5f, 0.94f, 0.5f) }
        "play" -> { poly(0.12f, 0.15f, 0.5f, 0.5f, 0.12f, 0.85f); drawRect(c, Offset(w * 0.6f, w * 0.18f), Size(w * 0.1f, w * 0.64f)); drawRect(c, Offset(w * 0.8f, w * 0.18f), Size(w * 0.1f, w * 0.64f)) }
        "next" -> { poly(0.1f, 0.15f, 0.48f, 0.5f, 0.1f, 0.85f); poly(0.45f, 0.15f, 0.83f, 0.5f, 0.45f, 0.85f); drawRect(c, Offset(w * 0.82f, w * 0.15f), Size(w * 0.1f, w * 0.7f)) }
        "prev" -> { poly(0.9f, 0.15f, 0.52f, 0.5f, 0.9f, 0.85f); poly(0.55f, 0.15f, 0.17f, 0.5f, 0.55f, 0.85f); drawRect(c, Offset(w * 0.08f, w * 0.15f), Size(w * 0.1f, w * 0.7f)) }
        "mic" -> {
            drawRoundRect(c, Offset(w * 0.36f, w * 0.06f), Size(w * 0.28f, w * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f))
            drawArc(c, 0f, 180f, false, Offset(w * 0.2f, w * 0.26f), Size(w * 0.6f, w * 0.48f), style = s)
            line(0.5f, 0.74f, 0.5f, 0.92f)
        }
        "more" -> for (dx in listOf(0.2f, 0.5f, 0.8f)) drawCircle(c, w * 0.09f, Offset(dx * w, 0.5f * w))
        else -> { // app: hexágono NERV
            val p = Path()
            for (i in 0 until 6) {
                val a = (PI / 3 * i - PI / 2).toFloat()
                val x = mid.x + w * 0.42f * cos(a)
                val y = mid.y + w * 0.42f * sin(a)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, c, style = s)
            drawCircle(c, w * 0.12f, mid)
        }
    }
}
