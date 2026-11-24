package com.asistente.personal.ui.led

/** Estado de ánimo que se ve en la cara. Lo decide la pantalla a partir del estado real. */
enum class Mood { IDLE, LISTEN, THINK, TALK, HAPPY, SLEEP, ERROR, ALERT, OFFLINE }

/** Segundos que faltan → «4:59» (o «1:05:00» si pasa de una hora). */
fun countdownText(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Una mirada hacia algo de la pantalla (x, y entre −1 y 1). */
data class Glance(val seq: Int, val x: Float, val y: Float)
