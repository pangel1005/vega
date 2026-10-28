package com.asistente.personal

import com.asistente.personal.local.HINT_BEFORE
import com.asistente.personal.local.HINT_NOW
import com.asistente.personal.local.LlmBackend
import com.asistente.personal.local.PhoneLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneLlmTest {
    private class Fake : LlmBackend {
        var loads = 0
        var frees = 0
        var primes = 0
        var primeWorks = true
        val prefixes = mutableListOf<String>()
        val prompts = mutableListOf<String>()
        override fun load(path: String, threads: Int, threadsBatch: Int, ctx: Int): Long { loads++; return 7L }
        override fun prime(handle: Long, prefix: String): Boolean { primes++; prefixes += prefix; return primeWorks }
        override fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: (String) -> Boolean): String {
            prompts += prompt
            onPiece("Hola ")
            return "Hola mundo."
        }
        override fun free(handle: Long) { frees++ }
    }

    private val model = Files.createTempFile("modelo", ".gguf").toFile().apply { writeText("x") }

    @Test fun `se carga al primer uso y no antes`() {
        val fake = Fake()
        val llm = PhoneLlm(model, backend = fake)
        assertFalse(llm.loaded)
        assertEquals(0, fake.loads)
        assertEquals("Hola mundo.", llm.reply("¿qué tal?") { true })
        assertTrue(llm.loaded)
        llm.reply("otra") { true }
        assertEquals(1, fake.loads) // la segunda vez ya está cargado
        assertTrue(fake.prompts[0].contains("<|im_start|>user\n¿qué tal?$HINT_NOW<|im_end|>"))
        assertTrue(fake.prompts[0].endsWith("<|im_start|>assistant\n")) // LFM: sin bloque <think>
        llm.close()
        assertEquals(1, fake.frees)
    }

    @Test fun `se suelta cuando lleva un rato sin usarse`() {
        val fake = Fake()
        var now = 1_000L
        val llm = PhoneLlm(model, idleMs = 60_000, backend = fake, clock = { now })
        llm.reply("hola") { true }
        now += 30_000
        llm.unloadIfIdle()
        assertTrue(llm.loaded) // aún no
        now += 31_000
        llm.unloadIfIdle()
        assertFalse(llm.loaded)
        assertEquals(1, fake.frees)
        llm.reply("hola otra vez") { true } // vuelve a cargar solo
        assertEquals(2, fake.loads)
        llm.close()
    }

    @Test fun `sin el archivo del modelo no responde`() {
        val llm = PhoneLlm(File("no-existe.gguf"), backend = Fake())
        assertNull(llm.reply("hola") { true })
        llm.close()
    }

    @Test fun `las instrucciones se leen una sola vez y las preguntas solo llevan lo suyo`() {
        val fake = Fake()
        val llm = PhoneLlm(model, backend = fake)
        llm.reply("primera") { true }
        llm.reply("segunda") { true }
        assertEquals(1, fake.primes)
        assertTrue(fake.prefixes[0].startsWith("<|im_start|>system\n") && fake.prefixes[0].contains("Si no sabes algo"))
        assertFalse("<|im_start|>assistant" in fake.prefixes[0]) // sin ejemplos de respuesta: descontrolaban al modelo (medido)
        assertTrue(fake.prompts.none { "<|im_start|>system" in it }) // las instrucciones ya están en el estado guardado
        llm.close()
    }

    @Test fun `si no se puede guardar el estado se manda todo en cada pregunta`() {
        val fake = Fake().apply { primeWorks = false }
        val llm = PhoneLlm(model, backend = fake)
        llm.reply("hola") { true }
        assertTrue(fake.prompts[0].startsWith("<|im_start|>system\n"))
        assertTrue(fake.prompts[0].contains("<|im_start|>user\nhola$HINT_NOW<|im_end|>"))
        llm.close()
    }

    @Test fun `al soltarlo y recargarlo se vuelven a leer las instrucciones`() {
        val fake = Fake()
        var now = 1_000L
        val llm = PhoneLlm(model, idleMs = 1_000, backend = fake, clock = { now })
        llm.reply("a") { true }
        now += 2_000
        llm.unloadIfIdle()
        llm.reply("b") { true }
        assertEquals(2, fake.primes)
        llm.close()
    }

    @Test fun `un Qwen pide el bloque think vacio`() {
        val qwen = File(Files.createTempDirectory("m").toFile(), "qwen35-08b-q4.gguf").apply { writeText("x") }
        val fake = Fake()
        val llm = PhoneLlm(qwen, backend = fake)
        llm.reply("hola") { true }
        assertTrue(fake.prompts[0].endsWith("<think>\n\n</think>\n\n"))
        llm.close()
    }

    @Test fun `recuerda los dos ultimos intercambios para entender un seguimiento`() {
        val fake = Fake()
        val llm = PhoneLlm(model, backend = fake)
        llm.reply("¿quién escribió Don Quijote?") { true }
        assertFalse("Hola mundo." in fake.prompts[0]) // la primera pregunta no arrastra nada
        llm.reply("¿y cuándo nació?") { true }
        // La segunda lleva la primera pregunta y lo que contestó, antes de su propia pregunta.
        val second = fake.prompts[1]
        assertTrue(second.indexOf("¿quién escribió Don Quijote?") < second.indexOf("Hola mundo."))
        assertTrue(second.indexOf("Hola mundo.") < second.indexOf("¿y cuándo nació?"))
        assertTrue(second.endsWith("<|im_start|>user\n¿y cuándo nació?$HINT_NOW<|im_end|>\n<|im_start|>assistant\n"))
        assertTrue("¿quién escribió Don Quijote?$HINT_BEFORE" in second) // lo anterior lleva la pista suave
        llm.reply("tercera") { true }
        llm.reply("cuarta") { true }
        val fourth = fake.prompts[3]
        assertFalse("¿quién escribió Don Quijote?" in fourth) // solo se recuerdan dos
        assertTrue("¿y cuándo nació?" in fourth && "tercera" in fourth)
        llm.close()
    }

    @Test fun `al soltar el modelo se olvida la conversacion`() {
        val fake = Fake()
        var now = 1_000L
        val llm = PhoneLlm(model, idleMs = 1_000, backend = fake, clock = { now })
        llm.reply("secreto de la primera") { true }
        now += 2_000
        llm.unloadIfIdle()
        llm.reply("otra") { true }
        assertFalse("secreto de la primera" in fake.prompts[1])
        llm.close()
    }

    @Test fun `de una respuesta larga solo se recuerdan las dos primeras frases`() {
        assertEquals("Una. Dos.", com.asistente.personal.local.rememberedAnswer("Una. Dos. Tres. Cuatro."))
        assertEquals("Hola mundo.", com.asistente.personal.local.rememberedAnswer("  Hola\nmundo.  "))
        val long = "x".repeat(400)
        assertTrue(com.asistente.personal.local.rememberedAnswer(long).length <= 241)
    }
}
