package com.asistente.personal.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** «23,6»: decimales con coma, como se leen en español. */
fun gb(v: Double): String = String.format(Locale.forLanguageTag("es"), "%.1f", v)

/** «04:59», «1:02:00»: lo que falta para un aviso. */
fun countdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss) else String.format(Locale.ROOT, "%02d:%02d", m, ss)
}

/**
 * Marco de peligro: franjas amarillas y negras arriba y abajo de la pantalla (cuando hay
 * que confirmar algo serio, como apagar el PC).
 */
fun Modifier.hazardFrame(color: Color, thickness: Dp = 8.dp): Modifier = drawBehind {
    val t = thickness.toPx()
    val step = t * 1.6f
    fun stripes(x: Float, y: Float, w: Float, h: Float) {
        val path = Path()
        var o = -h
        while (o < w + h) {
            path.moveTo(x + o, y + h)
            path.lineTo(x + o + h, y)
            path.lineTo(x + o + h + step / 2, y)
            path.lineTo(x + o + step / 2, y + h)
            path.close()
            o += step
        }
        drawRect(Color(0xFF0A0A0A), Offset(x, y), Size(w, h))
        clipRect(x, y, x + w, y + h) { drawPath(path, color) }
    }
    stripes(0f, 0f, size.width, t)
    stripes(0f, size.height - t, size.width, t)
}
