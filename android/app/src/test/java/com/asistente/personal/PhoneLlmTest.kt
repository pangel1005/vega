package com.asistente.personal

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
        assertTrue(fake.prompts[0].contains("<|im_start|>user\n¿qué tal?<|im_end|>"))
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
        assertTrue(fake.prompts.none { "<|im_start|>system" in it }) // las instrucciones ya están en el estado guardado
        llm.close()
    }

    @Test fun `si no se puede guardar el estado se manda todo en cada pregunta`() {
        val fake = Fake().apply { primeWorks = false }
        val llm = PhoneLlm(model, backend = fake)
        llm.reply("hola") { true }
        assertTrue(fake.prompts[0].startsWith("<|im_start|>system\n"))
        assertTrue(fake.prompts[0].contains("<|im_start|>user\nhola<|im_end|>"))
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
}
