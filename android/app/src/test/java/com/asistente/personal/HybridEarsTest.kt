package com.asistente.personal

import com.asistente.personal.local.Ears
import com.asistente.personal.local.HybridEars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Con el PC conectado oye el PC (más rápido); si no contesta, oye el teléfono como siempre. */
class HybridEarsTest {
    private val samples = FloatArray(16_000)
    private var now = 0L
    private val lines = mutableListOf<String>()

    private class Phone(val said: String) : Ears {
        var calls = 0

        override fun transcribe(samples: FloatArray): String {
            calls++
            return said
        }
    }

    private fun ears(phone: Phone, pc: (FloatArray) -> String?) =
        HybridEars(phone, remote = { s -> pc(s).also { now += 500 } }, clock = { now }, log = lines::add)

    @Test
    fun theAnswerFromThePcIsUsedAndThePhoneDoesNotListen() {
        val phone = Phone("del teléfono")
        assertEquals("vega abre discord", ears(phone) { "vega abre discord" }.transcribe(samples))
        assertEquals(0, phone.calls) // no se gasta el procesador del teléfono
        assertEquals(listOf("oído: PC, 500 ms"), lines)
    }

    @Test
    fun withoutAnswerFromThePcThePhoneListensItself() {
        val phone = Phone("vega qué hora es")
        assertEquals("vega qué hora es", ears(phone) { null }.transcribe(samples))
        assertEquals(1, phone.calls)
        assertTrue(lines.single().startsWith("oído: teléfono"))
    }

    @Test
    fun anErrorTalkingToThePcIsNotAnErrorForTheUser() {
        val phone = Phone("vega hola")
        assertEquals("vega hola", ears(phone) { error("se cayó el enlace") }.transcribe(samples))
        assertEquals(1, phone.calls)
    }

    @Test
    fun anEmptyAnswerIsAnAnswer() {
        // El PC oyó silencio o ruido: no hay nada que decir, y el teléfono no debe repetir el trabajo.
        val phone = Phone("algo")
        assertEquals("", ears(phone) { "" }.transcribe(samples))
        assertEquals(0, phone.calls)
    }

    @Test
    fun theLogNeverCarriesWhatWasSaid() {
        ears(Phone("secreto")) { "contraseña" }.transcribe(samples)
        ears(Phone("secreto")) { null }.transcribe(samples)
        assertTrue(lines.none { "contraseña" in it || "secreto" in it })
    }
}
