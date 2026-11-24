package com.asistente.personal.local

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/** Frecuencia de muestreo del «tilín» (la del reproductor la acepta tal cual). */
const val CHIME_RATE = 24_000

/**
 * El «tilín» de «te escucho»: dos notas suaves hacia arriba (mi y si, ~0,25 s en total), en PCM16 mono.
 *
 * Sustituye al «¿Sí?» hablado tras un «Vega» suelto: generar esa voz tardaba ~2 s y, como sueles seguir hablando
 * («Vega, … ¿qué hora es?»), la voz se te montaba encima y a veces se perdía la orden. El tilín suena al momento
 * y es demasiado corto (y bajo) para que el micrófono lo tome por una frase.
 */
fun chimePcm(rate: Int = CHIME_RATE): ByteArray {
    val notes = listOf(659.25 to 0.11, 987.77 to 0.15) // mi5 → si5
    val samples = ArrayList<Short>()
    for ((freq, seconds) in notes) {
        val n = (rate * seconds).toInt()
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val attack = min(1.0, i / (rate * 0.008)) // 8 ms de subida: sin chasquido
            val decay = exp(-t * 18.0)
            val tone = sin(2 * PI * freq * t) + 0.25 * sin(2 * PI * freq * 2 * t)
            samples.add((tone * attack * decay * 0.22 * Short.MAX_VALUE).toInt().toShort())
        }
    }
    val out = ByteArray(samples.size * 2)
    for ((i, s) in samples.withIndex()) {
        out[2 * i] = (s.toInt() and 0xFF).toByte()
        out[2 * i + 1] = (s.toInt() shr 8 and 0xFF).toByte()
    }
    return out
}
