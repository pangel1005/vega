package com.asistente.personal.local

import java.text.Normalizer

// Utilidades de texto en español y palabra de activación. Copian server/core/text.py y
// server/speech/listener.py: el teléfono debe entender lo mismo que el PC.

private val MARKS = Regex("\\p{M}+")
private val NOT_PLAIN = Regex("[^a-z0-9 ]+")

/** Minúsculas, sin tildes ni signos: «¡Vega, Apágalo!» → «vega apagalo». */
fun normalize(text: String): String {
    val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFKD).replace(MARKS, "")
    return plain.replace(NOT_PLAIN, " ").split(' ').filter { it.isNotEmpty() }.joinToString(" ")
}

private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
        val cur = IntArray(b.length + 1)
        cur[0] = i
        for (j in 1..b.length) {
            cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] != b[j - 1]) 1 else 0)
        }
        prev = cur
    }
    return prev[b.length]
}

/** Cómo puede escribir Whisper el nombre: «vega», «bega», «vegas»… */
fun nameVariants(name: String): Set<String> {
    val base = normalize(name)
    val swapped = base.replace('v', '#').replace('b', 'v').replace('#', 'b')
    return setOf(base, swapped, base + "s", swapped + "s")
}

/** Whisper a veces oye «Mega», «Bega» o «Veca»: se acepta una letra de diferencia
 * (solo con nombres de 4 letras o más, para no confundir palabras cortas). */
fun soundsLikeName(word: String, name: String): Boolean {
    val base = normalize(name)
    if (word in nameVariants(name)) return true
    return base.length >= 4 && kotlin.math.abs(word.length - base.length) <= 1 && editDistance(word, base) <= 1
}

data class Wake(val woke: Boolean, val command: String)

private val FILLERS = setOf("oye", "hey", "ey", "eh", "hola", "ok", "okay", "a", "e")
private val WHITESPACE = Regex("\\s+")

/** ¿La frase empieza llamando al asistente? Devuelve también lo que viene después, con sus
 * tildes y signos originales («Oye, Vega: ¿qué hora es?» → «¿qué hora es?»). */
fun parseWake(text: String, name: String): Wake {
    val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
    var seen = 0 // palabras reales vistas antes del nombre
    for ((i, token) in tokens.withIndex()) {
        val word = normalize(token)
        if (word.isEmpty()) continue // signos sueltos
        // «Vega,» o «Bega.»: una sola palabra; «vega como estas» no (lo trata como varias).
        if (!word.contains(' ') && soundsLikeName(word, name)) {
            val tail = tokens.drop(i + 1).joinToString(" ").trimStart(' ', ',', '.', ':', ';', '-', '—').trim()
            return Wake(true, tail)
        }
        // Se permiten hasta dos muletillas antes del nombre («oye, Vega…», «hola Vega…»).
        seen++
        if (seen > 2 || word !in FILLERS) break
    }
    return Wake(false, "")
}
