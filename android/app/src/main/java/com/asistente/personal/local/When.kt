package com.asistente.personal.local

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// Entender cuándo en español hablado: «en 20 minutos», «a las 6 y media», «mañana a las 8».
// Copia de server/skills/when.py. Whisper escribe a veces cifras y a veces palabras.

val NUMBERS = mapOf(
    "un" to 1, "una" to 1, "uno" to 1, "dos" to 2, "tres" to 3, "cuatro" to 4, "cinco" to 5, "seis" to 6,
    "siete" to 7, "ocho" to 8, "nueve" to 9, "diez" to 10, "once" to 11, "doce" to 12, "trece" to 13,
    "catorce" to 14, "quince" to 15, "dieciséis" to 16, "dieciseis" to 16, "veinte" to 20,
    "veinticinco" to 25, "treinta" to 30, "cuarenta" to 40, "cuarenta y cinco" to 45, "cincuenta" to 50,
    "sesenta" to 60, "noventa" to 90,
)
private val NUM = "(\\d+(?:[.,]\\d+)?|" +
    NUMBERS.keys.sortedByDescending { it.length }.joinToString("|") { it.replace(" ", "\\s+") } + ")"
private val UNITS = mapOf("segundo" to 1L, "minuto" to 60L, "hora" to 3600L, "dia" to 86400L, "día" to 86400L)
private val I = RegexOption.IGNORE_CASE

private val IN = Regex(
    "\\b(?:en|dentro\\s+de)\\s+(?:(?<n>$NUM)\\s+(?<u>segundos?|minutos?|horas?|d[ií]as?)" +
        "|(?<half>media\\s+hora)|(?<quarter>un\\s+cuarto\\s+de\\s+hora))\\b",
    I,
)
private val DURATION = Regex( // para temporizadores: «de 5 minutos», «5 minutos»
    "\\b(?:de\\s+)?(?<n>$NUM)\\s+(?<u>segundos?|minutos?|horas?)\\b|\\b(?<half>media\\s+hora)\\b",
    I,
)
private val AT = Regex(
    "\\b(?<tomorrow>mañana\\s+)?a\\s+las?\\s+(?<h>$NUM)(?::(?<m>\\d{2}))?" +
        "(?:\\s+y\\s+(?<extra>media|cuarto|\\d{1,2}))?" +
        "(?:\\s+(?:de\\s+la\\s+)?(?<part>mañana|tarde|noche|madrugada))?\\b",
    I,
)
private val TOMORROW = Regex("\\bmañana\\b", I)

/** Una hora y el trozo de texto que la describe (para quitarlo del recordatorio). */
data class When(val at: LocalDateTime, val span: IntRange)

private fun MatchResult.group(name: String): String? = groups[name]?.value

private fun number(text: String): Double {
    val t = text.lowercase().replace(",", ".")
    return if (t[0].isDigit()) t.toDouble() else NUMBERS.getValue(t.split(Regex("\\s+")).joinToString(" ")).toDouble()
}

private fun seconds(n: String?, unit: String?, half: String?, quarter: String? = null): Long {
    if (half != null) return 1800
    if (quarter != null) return 900
    val key = unit!!.lowercase().trimEnd('s')
    return (number(n!!) * UNITS.getValue(key)).toLong()
}

/** «en 20 minutos», «dentro de media hora»… */
fun parseIn(text: String, now: LocalDateTime): When? {
    val m = IN.find(text) ?: return null
    val secs = seconds(m.group("n"), m.group("u"), m.group("half"), m.group("quarter"))
    return When(now.plusSeconds(secs), m.range)
}

/** Duración de un temporizador: «de 5 minutos» → 300 s, con el trozo de texto. */
fun parseDuration(text: String): Pair<Long, IntRange>? {
    val m = DURATION.find(text) ?: return null
    return seconds(m.group("n"), m.group("u"), m.group("half")) to m.range
}

/** «a las 6», «a las 6 y media de la tarde», «mañana a las 8», «a las 18:30». */
fun parseAt(text: String, now: LocalDateTime): When? {
    val m = AT.find(text) ?: return null
    var hour = number(m.group("h")!!).toInt()
    var minute = m.group("m")?.toInt() ?: 0
    val extra = m.group("extra")?.lowercase().orEmpty()
    minute += when {
        extra == "media" -> 30
        extra == "cuarto" -> 15
        extra.isNotEmpty() && extra.all { it.isDigit() } -> extra.toInt()
        else -> 0
    }
    val part = m.group("part")?.lowercase().orEmpty()
    if ((part == "tarde" || part == "noche") && hour < 12) hour += 12
    if (part == "noche" && hour == 24) hour = 0
    if (hour > 23 || minute > 59) return null
    var at = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
    if (m.group("tomorrow") != null) {
        at = at.plusDays(1)
    } else if (!at.isAfter(now)) {
        // «a las 6» sin decir de la tarde: la próxima vez que sean las 6 (si son las 15, las 18).
        at = if (part.isEmpty() && hour < 12 && at.plusHours(12).isAfter(now)) at.plusHours(12) else at.plusDays(1)
    }
    return When(at, m.range)
}

/** Primero «en …», luego «a las …»; «mañana» a secas significa mañana a las 9:00. */
fun parseWhen(text: String, now: LocalDateTime): When? {
    parseIn(text, now)?.let { return it }
    parseAt(text, now)?.let { return it }
    val m = TOMORROW.find(text) ?: return null
    return When(now.plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0), m.range)
}

private val HH_MM = DateTimeFormatter.ofPattern("HH:mm")

/** «a las 18:30», «mañana a las 09:00» o «en 5 minutos» (para confirmar en voz alta). */
fun spokenTime(at: LocalDateTime, now: LocalDateTime): String {
    val delta = Duration.between(now, at).toMillis() / 1000.0
    if (delta < 3600) {
        if (delta < 60) return "en ${delta.toInt()} segundos"
        val mins = maxOf(1, Math.round(delta / 60).toInt())
        return "en $mins minuto${if (mins != 1) "s" else ""}"
    }
    val day = if (at.toLocalDate().isAfter(now.toLocalDate())) "mañana " else ""
    return "${day}a las ${at.format(HH_MM)}"
}
