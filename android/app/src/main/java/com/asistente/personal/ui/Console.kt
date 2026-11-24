package com.asistente.personal.ui

import java.util.Locale

/** «04:59», «1:02:00»: lo que falta para un aviso. */
fun countdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss) else String.format(Locale.ROOT, "%02d:%02d", m, ss)
}
