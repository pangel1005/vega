package com.asistente.personal.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** «ahora», «hace 5 min», «hace 2 h» o la hora (si es de otro día, la fecha corta). */
fun timeAgo(atSeconds: Double, nowMs: Long): String {
    val secs = (nowMs / 1000.0 - atSeconds).toLong().coerceAtLeast(0)
    return when {
        secs < 60 -> "ahora"
        secs < 3600 -> "hace ${secs / 60} min"
        secs < 6 * 3600 -> "hace ${secs / 3600} h"
        else -> SimpleDateFormat(if (secs < 24 * 3600) "HH:mm" else "d MMM", Locale.forLanguageTag("es"))
            .format(Date((atSeconds * 1000).toLong()))
    }
}

/** Cuánto se queda abierta la barra al llegar un aviso nuevo (luego se pliega sola). */
const val NOTE_PEEK_MS = 7_000L
