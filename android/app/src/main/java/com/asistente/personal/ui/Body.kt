package com.asistente.personal.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role

/** Lo que se enseña de la conversación al tocar la respuesta: las últimas frases, sin avisos internos. */
fun recentChat(messages: List<Message>, limit: Int = 6): List<Message> = messages.filter { it.role != Role.NOTE }.takeLast(limit)

const val CHAT_OPEN_MS = 10_000L

/** En qué punto está la IA del teléfono, sacado de lo que dice la pastilla («Bajando IA 45 %», «Cargando IA…», «IA: error»). */
data class BrainLoad(val busy: Boolean, val fraction: Float?, val failed: Boolean) {
    companion object {
        val None = BrainLoad(busy = false, fraction = null, failed = false)
    }
}

fun brainLoad(label: String): BrainLoad {
    val percent = Regex("""Bajando IA (\d+) ?%""").find(label)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        percent != null -> BrainLoad(busy = true, fraction = (percent / 100f).coerceIn(0f, 1f), failed = false)
        label.startsWith("Cargando IA") -> BrainLoad(busy = true, fraction = null, failed = false) // sin porcentaje: barra que corre
        label == "IA: error" || label == "IA: sin modelos" -> BrainLoad(busy = false, fraction = null, failed = true)
        else -> BrainLoad.None
    }
}

/** Marco rojo que late por todo el borde de la pantalla mientras hay que confirmar algo peligroso. */
@Composable
fun AlertFrame(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "alerta").animateFloat(
        0.25f, 0.95f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "latido",
    )
    Box(modifier.fillMaxSize().border(4.dp, Vega.Red.copy(alpha = alpha), RoundedCornerShape(18.dp)))
}

/** Cómo se ve una tecla según lo que suena en el PC: su nombre y si está «viva» (tira de luz encendida). */
data class KeyState(val label: String, val live: Boolean)

fun keyState(action: com.asistente.personal.net.DeckAction, media: com.asistente.personal.net.MediaInfo?): KeyState {
    val playing = media?.playing == true
    return when {
        // La tecla de reproducir: dice lo que hará al pulsarla y se enciende mientras suena algo.
        action.icon == "play" && media != null -> KeyState(if (playing) "Pausa" else "Play", playing)
        // La tecla de la app que suena (Spotify…): viva mientras suena.
        playing && action.label.length >= 4 && media != null && media.app.isNotBlank() &&
            (media.app.contains(action.label, ignoreCase = true) || action.label.contains(media.app, ignoreCase = true)) ->
            KeyState(action.label, true)
        else -> KeyState(action.label, false)
    }
}

/** Batería a punto de acabarse y sin cargador. */
fun lowBattery(battery: Int, charging: Boolean): Boolean = battery in 0..15 && !charging

/** El próximo aviso que no es un temporizador (una alarma o un recordatorio): qué es y cuándo, en hora real (ms desde 1970). */
data class NextAlert(val text: String, val atMs: Long)

/**
 * El aviso más próximo de los del PC (llegan con la lectura de telemetría: `dueIn` segundos desde `telemetryAt`)
 * y los del propio teléfono. Los temporizadores no cuentan: ya tienen su cuenta atrás.
 */
fun nextAlert(
    upcoming: List<com.asistente.personal.net.Upcoming>, telemetryAt: Long, nowMs: Long, wallMs: Long, local: NextAlert?,
): NextAlert? {
    val pc = upcoming.filter { it.kind != "timer" }.map { NextAlert(it.text, wallMs - (nowMs - telemetryAt) + it.dueIn * 1000L) }
    return (pc + listOfNotNull(local)).minByOrNull { it.atMs }
}

/** «Alarma 07:00», «Alarma mañana 07:00» o «18:30 · llamar a mamá». */
fun nextAlertLabel(a: NextAlert, wallMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val at = java.time.Instant.ofEpochMilli(a.atMs).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate()
    val time = "%02d:%02d".format(at.hour, at.minute)
    val day = when (java.time.temporal.ChronoUnit.DAYS.between(today, at.toLocalDate())) {
        0L -> ""
        1L -> "mañana "
        else -> "${at.dayOfMonth}/${at.monthValue} "
    }
    return if (a.text.contains("alarma", ignoreCase = true)) "Alarma $day$time" else "$day$time · ${a.text}"
}
