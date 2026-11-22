package com.asistente.personal.ui2

import com.asistente.personal.net.TaskItem
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/** Las cuatro columnas del tablero de Moodle, de lo más urgente a lo más lejano. */
internal enum class Lane(val label: String) { OVERDUE("VENCIDAS"), TODAY("HOY"), WEEK("ESTA SEMANA"), LATER("DESPUÉS") }

private val ES = Locale.forLanguageTag("es")

/** En qué columna cae una entrega: vencida, hoy (hasta medianoche), en los próximos 7 días o más allá. */
internal fun laneOf(dueSec: Long, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): Lane {
    if (dueSec <= nowSec) return Lane.OVERDUE
    val now = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowSec), zone)
    val endOfToday = now.toLocalDate().plusDays(1).atStartOfDay(zone).toEpochSecond()
    return when {
        dueSec < endOfToday -> Lane.TODAY
        dueSec < endOfToday + 6 * 86_400 -> Lane.WEEK
        else -> Lane.LATER
    }
}

/** Las entregas repartidas en columnas, cada una por orden de fecha (las vencidas, de la más reciente a la más antigua). */
internal fun board(items: List<TaskItem>, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): Map<Lane, List<TaskItem>> {
    val by = items.sortedBy { it.due }.groupBy { laneOf(it.due, nowSec, zone) }
    return Lane.entries.associateWith { lane -> by[lane].orEmpty().let { if (lane == Lane.OVERDUE) it.asReversed() else it } }
}

/** Cuánto falta, o cuánto hace que venció: «en 45 min», «en 3 h», «mañana 08:00», «vie 14 · 23:59», «hace 2 h», «ayer». */
internal fun dueLabel(dueSec: Long, nowSec: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val delta = dueSec - nowSec
    val due = ZonedDateTime.ofInstant(Instant.ofEpochSecond(dueSec), zone)
    val clock = "%02d:%02d".format(due.hour, due.minute)
    if (delta <= 0) {
        val ago = -delta
        return when {
            ago < 60 -> "ahora"
            ago < 3600 -> "hace ${ago / 60} min"
            ago < 24 * 3600 -> "hace ${ago / 3600} h"
            else -> "hace ${ago / 86_400} d"
        }
    }
    val today = ZonedDateTime.ofInstant(Instant.ofEpochSecond(nowSec), zone).toLocalDate()
    return when {
        delta < 3600 -> "en ${maxOf(1, delta / 60)} min"
        due.toLocalDate() == today -> "en ${delta / 3600} h"
        due.toLocalDate() == today.plusDays(1) -> "mañana $clock"
        else -> "${due.dayOfWeek.getDisplayName(TextStyle.SHORT, ES).replace(".", "")} ${due.dayOfMonth} · $clock"
    }
}

/** El tiempo grande de la tarjeta del mosaico: «45 min», «3 h», «2 d 4 h», «vencida». */
internal fun bigCountdown(dueSec: Long, nowSec: Long): String {
    val delta = dueSec - nowSec
    return when {
        delta <= 0 -> "vencida"
        delta < 3600 -> "${maxOf(1, delta / 60)} min"
        delta < 24 * 3600 -> "${delta / 3600} h ${delta % 3600 / 60} min"
        else -> "${delta / 86_400} d ${delta % 86_400 / 3600} h"
    }
}
