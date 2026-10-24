package com.asistente.personal.local

// Texto escrito → texto para leer en voz alta (copia de server/speech/spoken.py).
// El lector fonético de Piper (espeak) lee «18:00» como «dieciocho, cero cero».

private val HOURS = listOf("una", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez", "once", "doce")
val MONTHS = listOf(
    "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre",
    "octubre", "noviembre", "diciembre",
)

private fun hourWord(h: Int): String = HOURS[(if (h % 12 == 0) 12 else h % 12) - 1]

private fun partOfDay(h: Int): String = when {
    h < 6 -> "de la madrugada"
    h < 12 -> "de la mañana"
    h < 13 -> "del mediodía"
    h < 20 -> "de la tarde"
    else -> "de la noche"
}

/** 18:30 → «seis y media de la tarde»; 9:45 → «diez menos cuarto de la mañana». */
fun sayTime(hour: Int, minute: Int): String {
    var h = hour
    val words = when (minute) {
        0 -> hourWord(h)
        15 -> "${hourWord(h)} y cuarto"
        30 -> "${hourWord(h)} y media"
        45 -> { h += 1; "${hourWord(h)} menos cuarto" }
        else -> "${hourWord(h)} y $minute"
    }
    return "$words ${partOfDay(h % 24)}"
}

private val TIME = Regex("(?:\\b(?<art>las|la)\\s+)?\\b(?<h>\\d{1,2}):(?<m>\\d{2})\\b(?:\\s*h\\b)?", RegexOption.IGNORE_CASE)
private val DATE = Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b")
private val REPLACE = listOf(
    Regex("\\s*°\\s*C\\b") to " grados",
    Regex("\\s*°") to " grados",
    Regex("(?<=\\d)\\s*GB\\b") to " gigas",
    Regex("(?<=\\d)\\s*MB\\b") to " megas",
    Regex("\\bGPU\\b") to "gráfica",
    Regex("\\bCPU\\b") to "procesador",
    Regex("\\bRAM\\b") to "memoria",
)

fun speakable(text: String): String {
    var out = TIME.replace(text) { m ->
        val h = m.groups["h"]!!.value.toInt()
        val min = m.groups["m"]!!.value.toInt()
        if (h > 23 || min > 59) return@replace m.value
        val article = if (m.groups["art"] != null) (if (h % 12 == 1) "la " else "las ") else ""
        article + sayTime(h, min)
    }
    out = DATE.replace(out) { m ->
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        if (d in 1..31 && mo in 1..12) "$d de ${MONTHS[mo - 1]} de ${m.groupValues[3]}" else m.value
    }
    for ((pattern, words) in REPLACE) out = pattern.replace(out, words)
    return out
}
