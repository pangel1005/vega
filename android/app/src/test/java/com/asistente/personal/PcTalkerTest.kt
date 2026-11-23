package com.asistente.personal

import com.asistente.personal.local.PcTalker
import com.asistente.personal.local.Talker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La charla mixta: primero el PC (si está libre) y, si no puede, el modelo pequeño del teléfono. */
class PcTalkerTest {
    private class Local(val answer: String?) : Talker, AutoCloseable {
        var asked = 0
        var closed = false
        override fun reply(question: String, onText: (String) -> Boolean): String? {
            asked++
            answer?.let { onText(it) }
            return answer
        }
        override fun close() { closed = true }
    }

    @Test fun theAnswerOfThePcIsUsedAndTheLocalModelIsNotAsked() {
        val local = Local("local")
        val said = mutableListOf<String>()
        val talker = PcTalker({ _, _ -> "Del PC." }, local)
        assertEquals("Del PC.", talker.reply("hola") { said.add(it); true })
        assertEquals(listOf("Del PC."), said)
        assertEquals(0, local.asked)
    }

    @Test fun ifThePcCannotThePhoneAnswers() {
        val local = Local("Del teléfono.")
        val said = mutableListOf<String>()
        val talker = PcTalker({ _, _ -> null }, local)
        assertEquals("Del teléfono.", talker.reply("hola") { said.add(it); true })
        assertEquals(1, local.asked)
        assertEquals(listOf("Del teléfono."), said)
    }

    @Test fun anErrorTalkingToThePcFallsBackToThePhone() {
        val local = Local("Del teléfono.")
        val talker = PcTalker({ _, _ -> throw IllegalStateException("sin cable") }, local)
        assertEquals("Del teléfono.", talker.reply("hola") { true })
    }

    @Test fun aBlankAnswerFromThePcCountsAsNoAnswer() {
        val local = Local("Del teléfono.")
        assertEquals("Del teléfono.", PcTalker({ _, _ -> "  " }, local).reply("hola") { true })
    }

    @Test fun withoutPcAndWithoutLocalModelThereIsNoAnswer() {
        assertNull(PcTalker({ _, _ -> null }, null).reply("hola") { true })
    }

    @Test fun thePcGetsTheLastExchangesWhoeverAnsweredThem() {
        val seen = mutableListOf<List<Pair<String, String>>>()
        var pcAnswers = true
        val talker = PcTalker({ _, history -> seen.add(history); if (pcAnswers) "Del PC." else null }, Local("Del teléfono."))
        talker.reply("uno") { true } // PC
        pcAnswers = false
        talker.reply("dos") { true } // teléfono
        pcAnswers = true
        talker.reply("tres") { true }
        talker.reply("cuatro") { true }
        assertEquals(emptyList<Pair<String, String>>(), seen[0])
        assertEquals(listOf("uno" to "Del PC."), seen[1])
        assertEquals(listOf("uno" to "Del PC.", "dos" to "Del teléfono."), seen[2])
        // solo se recuerdan los dos últimos intercambios
        assertEquals(listOf("dos" to "Del teléfono.", "tres" to "Del PC."), seen[3])
    }

    @Test fun closingClosesTheLocalModel() {
        val local = Local("x")
        PcTalker({ _, _ -> null }, local).close()
        assertTrue(local.closed)
    }
}
