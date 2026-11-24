package com.asistente.personal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import com.asistente.personal.net.LyricLine
import com.asistente.personal.net.MediaInfo
import kotlinx.coroutines.delay

/** «3:17»: minutos y segundos de una canción. */
fun clockTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Posición actual: el PC la manda al cambiar algo y el teléfono la avanza solo mientras
 * suena (un tic por segundo, o más a menudo con la letra a la vista).
 */
@Composable
fun livePosition(media: MediaInfo, receivedAt: Long, now: () -> Long, tickMs: Long = 1000): Double {
    var pos by remember(media, receivedAt) { mutableStateOf(media.position) }
    LaunchedEffect(media, receivedAt, tickMs) {
        while (media.playing) {
            val elapsed = (now() - receivedAt) / 1000.0
            pos = (media.position + elapsed).let { if (media.duration > 0) it.coerceAtMost(media.duration) else it }
            delay(tickMs)
        }
    }
    return pos
}

/** Línea de la letra que suena ahora (la última que ya empezó), o -1 antes de la primera. */
fun currentLine(lines: List<LyricLine>, position: Double): Int {
    var current = -1
    for (i in lines.indices) {
        if (lines[i].t <= position + 0.25) current = i else break // un pelín antes: se lee a tiempo
    }
    return current
}

/**
 * Color principal de una carátula (promedio, subido de brillo para que se lea sobre negro).
 * Se calcula una vez por carátula.
 */
fun accentOf(art: ImageBitmap?): Color {
    if (art == null) return Vega.Coral
    val small = android.graphics.Bitmap.createScaledBitmap(art.asAndroidBitmap(), 8, 8, true)
    var r = 0f
    var g = 0f
    var b = 0f
    for (x in 0 until 8) for (y in 0 until 8) {
        val p = small.getPixel(x, y)
        r += android.graphics.Color.red(p)
        g += android.graphics.Color.green(p)
        b += android.graphics.Color.blue(p)
    }
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((r / 64).toInt(), (g / 64).toInt(), (b / 64).toInt(), hsv)
    // Muy apagado o gris → coral de Vega; si no, el tono de la carátula, vivo y claro.
    if (hsv[1] < 0.15f) return Vega.Coral
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = 0.95f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
