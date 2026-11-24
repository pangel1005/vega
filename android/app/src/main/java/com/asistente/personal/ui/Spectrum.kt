package com.asistente.personal.ui

import kotlin.math.max

const val SPECTRUM_BANDS = 24

/**
 * Las barras de la música alrededor de la portada (AlbumDisc): una por banda de frecuencias (de graves a agudos) con el valor real que manda
 * el servidor (el análisis del audio de Spotify). Cada columna sube de golpe y baja suave, y su pico se queda
 * un instante arriba y luego cae como con gravedad, como en los ecualizadores de las radios de coche.
 *
 * La ganancia sigue al nivel reciente (con un tope para no amplificar ruido). Y, como en un analizador de verdad, no
 * se dibuja solo el nivel: se mezcla con lo que **sobresale del nivel habitual de esa banda** (los golpes). En la
 * música real las bandas casi no se mueven en bruto (medido: ~0,69 de media con ±0,09), y sin esto saldría una pared.
 */
class SpectrumState(val bands: Int = SPECTRUM_BANDS) {
    val level = FloatArray(bands)
    val peak = FloatArray(bands)
    private val hold = IntArray(bands)
    private val fall = FloatArray(bands)
    private val usual = FloatArray(bands) // el nivel habitual de cada banda (media lenta)
    private var recentTop = MIN_TOP
    private var started = false
    var gain = 1f
        private set

    /** Una lectura nueva (la columna `i` toma `raw[i]`; si llegan otras tantas bandas, se reparten). Vacío = silencio. */
    fun push(raw: FloatArray) {
        val top = raw.maxOrNull() ?: 0f
        // La ganancia sube despacio (si no, un golpe fuerte se anularía a sí mismo) y baja despacio.
        recentTop = (if (top > recentTop) recentTop + (top - recentTop) * GAIN_ATTACK else recentTop * GAIN_DECAY).coerceAtLeast(MIN_TOP)
        gain = (TARGET_TOP / recentTop).coerceIn(0.9f, 2.2f)
        for (i in 0 until bands) {
            val v = if (raw.isEmpty()) 0f else (raw[if (raw.size == bands) i else i * raw.size / bands] * gain).coerceIn(0f, 1f)
            if (!started) usual[i] = v // el primer instante no es un «golpe»: se parte de lo que suena
            val presence = (v / PRESENCE).coerceIn(0f, 1f) // en silencio no hay contraste que resaltar
            val shown = (ABS_MIX * v + REL_MIX * presence * (BASE + (v - usual[i]) * CONTRAST)).coerceIn(0f, 1f)
            usual[i] += (v - usual[i]) * USUAL_RATE
            level[i] = if (shown >= level[i]) shown else max(shown, level[i] * FALL) // sube de golpe, baja suave
            if (level[i] >= peak[i]) {
                peak[i] = level[i]
                hold[i] = HOLD_STEPS
                fall[i] = 0f
            } else if (hold[i] > 0) {
                hold[i]--
            } else {
                fall[i] += GRAVITY
                peak[i] = max(level[i], peak[i] - fall[i])
            }
        }
        if (raw.isNotEmpty()) started = true
    }

    companion object {
        const val ABS_MIX = 0.55f // cuánto pesa el nivel de la banda...
        const val REL_MIX = 0.45f // ...y cuánto lo que sobresale de su nivel habitual
        const val BASE = 0.30f
        const val CONTRAST = 3.2f
        const val USUAL_RATE = 0.03f
        const val PRESENCE = 0.25f
        const val FALL = 0.78f
        const val HOLD_STEPS = 14 // ~0,55 s con una lectura cada 40 ms
        const val GRAVITY = 0.006f
        const val TARGET_TOP = 0.92f
        const val GAIN_DECAY = 0.995f
        const val GAIN_ATTACK = 0.06f
        const val MIN_TOP = 0.18f // por debajo de esto es silencio: no se amplifica el ruido
    }
}
