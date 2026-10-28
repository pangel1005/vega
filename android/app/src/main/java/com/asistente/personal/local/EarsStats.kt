package com.asistente.personal.local

import java.time.LocalDate

/** Lo que el oído oyó hoy (contadores, sin nada de lo dicho). */
data class EarsSnapshot(val heard: Int = 0, val passed: Int = 0, val calls: Int = 0)

/**
 * Contadores diarios del oído: frases que llegaron, las que pasaron el filtro de «Vega» y las que de
 * verdad la llamaron. Sirven para ver de un vistazo cómo oye (y, con el tiempo, para recalibrar el
 * filtro). Se ponen a cero al cambiar el día. Solo números: nunca lo que se dijo.
 */
class EarsStats(private val today: () -> LocalDate = LocalDate::now) {
    private var day = today()
    private var heard = 0
    private var passed = 0
    private var calls = 0

    @Synchronized private fun fresh() {
        val now = today()
        if (now != day) {
            day = now
            heard = 0
            passed = 0
            calls = 0
        }
    }

    @Synchronized fun phrase() { fresh(); heard++ }
    @Synchronized fun passedFilter() { fresh(); passed++ }
    @Synchronized fun call() { fresh(); calls++ }

    @Synchronized fun snapshot(): EarsSnapshot { fresh(); return EarsSnapshot(heard, passed, calls) }
}
