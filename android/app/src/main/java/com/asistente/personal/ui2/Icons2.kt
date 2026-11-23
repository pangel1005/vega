package com.asistente.personal.ui2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

/** Un icono de trazo redondeado, en una rejilla de 24 × 24. */
@Composable
internal fun Icon2(name: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { icon2(name, color) }
}

@Composable
internal fun IconBox(name: String, color: Color, size: Dp) = Box(Modifier.size(size)) { Icon2(name, color, size) }

internal fun DrawScope.icon2(name: String, c: Color) {
    val u = size.width / 24f
    val sw = 2.1f * u
    val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun o(x: Float, y: Float) = Offset(x * u, y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, o(x1, y1), o(x2, y2), sw, StrokeCap.Round)
    fun tri(vararg p: Float, fill: Boolean = true) {
        val path = Path().apply {
            moveTo(p[0] * u, p[1] * u)
            for (i in 2 until p.size step 2) lineTo(p[i] * u, p[i + 1] * u)
            close()
        }
        drawPath(path, c, style = if (fill) androidx.compose.ui.graphics.drawscope.Fill else st)
    }
    fun speaker() = tri(3f, 9.5f, 7.5f, 9.5f, 12.5f, 5f, 12.5f, 19f, 7.5f, 14.5f, 3f, 14.5f)
    fun ring(cx: Float, cy: Float, r: Float) = drawCircle(c, r * u, o(cx, cy), style = st)
    when (name) {
        "power" -> {
            drawArc(c, -55f, 290f, false, o(5f, 5.5f), Size(14f * u, 14f * u), style = st)
            line(12f, 3f, 12f, 11f)
        }
        "restart" -> {
            drawArc(c, -40f, 290f, false, o(5f, 5f), Size(14f * u, 14f * u), style = st)
            tri(14f, 2.5f, 19.5f, 5f, 14f, 8f)
        }
        "sleep" -> {
            val full = Path().apply { addOval(Rect(o(4f, 4f), Size(16f * u, 16f * u))) }
            val bite = Path().apply { addOval(Rect(o(9f, 1f), Size(15f * u, 15f * u))) }
            drawPath(Path.combine(PathOperation.Difference, full, bite), c)
        }
        "lock" -> {
            drawRoundRect(c, o(5f, 10.5f), Size(14f * u, 10f * u), CornerRadius(3f * u), style = st)
            drawArc(c, 180f, 180f, false, o(8f, 3.5f), Size(8f * u, 9f * u), style = st)
        }
        "sliders" -> {
            line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f)
            drawCircle(c, 2.6f * u, o(9f, 7f)); drawCircle(c, 2.6f * u, o(15f, 17f))
        }
        "cancel" -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        "check" -> { line(5f, 12.5f, 10f, 17.5f); line(10f, 17.5f, 19f, 7f) }
        "camera" -> {
            drawRoundRect(c, o(3f, 6.5f), Size(12.5f * u, 11f * u), CornerRadius(3f * u), style = st)
            tri(16.5f, 10.2f, 21.2f, 7.2f, 21.2f, 16.8f, 16.5f, 13.8f)
        }
        "record" -> { ring(12f, 12f, 8f); drawCircle(c, 4f * u, o(12f, 12f)) }
        "screenshot" -> {
            drawRoundRect(c, o(3f, 6f), Size(18f * u, 13f * u), CornerRadius(3f * u), style = st)
            ring(12f, 12.5f, 3.4f)
            line(8.5f, 6f, 9.5f, 3.8f); line(9.5f, 3.8f, 14.5f, 3.8f); line(14.5f, 3.8f, 15.5f, 6f)
        }
        "mute" -> { speaker(); line(16f, 9.5f, 21f, 14.5f); line(21f, 9.5f, 16f, 14.5f) }
        "volume-up" -> {
            speaker()
            drawArc(c, -40f, 80f, false, o(8f, 8f), Size(10f * u, 8f * u), style = st)
            drawArc(c, -45f, 90f, false, o(5f, 4f), Size(16f * u, 16f * u), style = st)
        }
        "volume-down" -> { speaker(); drawArc(c, -40f, 80f, false, o(8f, 8f), Size(10f * u, 8f * u), style = st) }
        "play" -> {
            tri(4f, 5f, 4f, 19f, 14f, 12f)
            drawRoundRect(c, o(16f, 5f), Size(2.6f * u, 14f * u), CornerRadius(1.3f * u))
            drawRoundRect(c, o(20f, 5f), Size(2.6f * u, 14f * u), CornerRadius(1.3f * u))
        }
        "play-solid" -> tri(7f, 4.5f, 7f, 19.5f, 19f, 12f)
        "pause" -> {
            drawRoundRect(c, o(6.5f, 5f), Size(3.6f * u, 14f * u), CornerRadius(1.6f * u))
            drawRoundRect(c, o(13.9f, 5f), Size(3.6f * u, 14f * u), CornerRadius(1.6f * u))
        }
        "next" -> { tri(5f, 5f, 5f, 19f, 15f, 12f); drawRoundRect(c, o(16.5f, 5f), Size(3f * u, 14f * u), CornerRadius(1.5f * u)) }
        "prev" -> { tri(19f, 5f, 19f, 19f, 9f, 12f); drawRoundRect(c, o(4.5f, 5f), Size(3f * u, 14f * u), CornerRadius(1.5f * u)) }
        "mic" -> {
            drawRoundRect(c, o(8.5f, 2.5f), Size(7f * u, 12f * u), CornerRadius(3.5f * u), style = st)
            drawArc(c, 0f, 180f, false, o(5f, 8f), Size(14f * u, 11f * u), style = st)
            line(12f, 19f, 12f, 22f)
        }
        "more" -> for (x in listOf(5f, 12f, 19f)) drawCircle(c, 1.9f * u, o(x, 12f))
        "note" -> {
            line(9f, 17f, 9f, 5f); line(9f, 5f, 19f, 3.5f); line(19f, 3.5f, 19f, 15f)
            drawCircle(c, 2.8f * u, o(6.5f, 17.5f)); drawCircle(c, 2.8f * u, o(16.5f, 15.5f))
        }
        "pad" -> {
            drawRoundRect(c, o(2f, 7f), Size(20f * u, 11f * u), CornerRadius(5.5f * u), style = st)
            line(7f, 10.5f, 7f, 14.5f); line(5f, 12.5f, 9f, 12.5f)
            drawCircle(c, 1.3f * u, o(15.5f, 11.5f)); drawCircle(c, 1.3f * u, o(18.5f, 13.5f))
        }
        else -> { // una app cualquiera: un cuadrado de esquinas muy redondas con un punto
            drawRoundRect(c, o(4f, 4f), Size(16f * u, 16f * u), CornerRadius(5f * u), style = st)
            drawCircle(c, 2.2f * u, o(12f, 12f))
        }
    }
}
