package com.asistente.personal.local

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Charla libre: contesta a una pregunta y va entregando el texto según lo escribe. */
fun interface Talker {
    /**
     * Devuelve la respuesta completa (o null si no puede). `onText` recibe cada trozo según
     * sale; si devuelve false, se deja de escribir.
     */
    fun reply(question: String, onText: (String) -> Boolean): String?
}

/** Llamadas a libvegallm.so (android/native/vega_llm.cpp). */
object LlamaNative {
    fun interface Piece {
        fun onPiece(text: String): Boolean
    }

    init {
        System.loadLibrary("vegallm")
    }

    external fun load(path: String, threads: Int, ctx: Int): Long
    external fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: Piece): String
    external fun free(handle: Long)
}

/** Instrucciones del modelo: cortas a propósito (Qwen 3.5 las relee en cada pregunta, ~1 s). */
const val TALK_SYSTEM = "Eres Vega, una asistente de voz que vive en un teléfono. Responde siempre en español, " +
    "con una o dos frases cortas y naturales, sin listas, sin emojis y sin inventar datos."

/** Formato de mensajes de Qwen (ChatML), sin «pensar en voz alta» (el bloque <think> vacío). */
fun chatPrompt(question: String, system: String = TALK_SYSTEM): String =
    "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$question<|im_end|>\n" +
        "<|im_start|>assistant\n<think>\n\n</think>\n\n"

/**
 * Qwen 3.5 de 0.8B en el teléfono (llama.cpp). Se carga en segundo plano al arrancar la IA
 * del teléfono (~4 s); si llega una pregunta antes, espera a que termine de cargar.
 *
 * Medido en el Redmi 15C: escribe ~9 palabras/s con 2 núcleos (con más, va más lento: los
 * 6 lentos frenan a los 2 rápidos). Sabe poco y se equivoca a veces: es para charlar.
 */
class PhoneLlm(model: File, threads: Int = 2) : Talker, AutoCloseable {
    @Volatile private var handle = 0L
    private val ready = CountDownLatch(1)

    init {
        thread(name = "vega-llm-carga") {
            try {
                if (model.isFile) handle = LlamaNative.load(model.path, threads, CONTEXT)
            } catch (_: Throwable) {
                handle = 0L
            } finally {
                ready.countDown()
            }
        }
    }

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) LlamaNative.free(h)
    }

    override fun reply(question: String, onText: (String) -> Boolean): String? {
        if (!ready.await(20, TimeUnit.SECONDS) || handle == 0L) return null
        return LlamaNative.reply(handle, chatPrompt(question), MAX_TOKENS) { onText(it) }
            .trim().ifEmpty { null }
    }

    companion object {
        private const val CONTEXT = 512 // instrucciones + pregunta + respuesta corta
        private const val MAX_TOKENS = 80
    }
}
