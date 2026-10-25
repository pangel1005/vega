package com.asistente.personal.local

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

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

/** Lo que necesita `PhoneLlm` del modelo (en el teléfono, llama.cpp; en las pruebas, uno de mentira). */
interface LlmBackend {
    fun load(path: String, threads: Int, ctx: Int): Long
    fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: (String) -> Boolean): String
    fun free(handle: Long)
}

object NativeBackend : LlmBackend {
    override fun load(path: String, threads: Int, ctx: Int) = LlamaNative.load(path, threads, ctx)
    override fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: (String) -> Boolean) =
        LlamaNative.reply(handle, prompt, maxTokens) { onPiece(it) }
    override fun free(handle: Long) = LlamaNative.free(handle)
}

/**
 * Qwen 3.5 de 0.8B en el teléfono (llama.cpp).
 *
 * Ocupa ~530 MB y solo hace falta cuando charlas (casi todo lo demás son habilidades): se carga
 * al primer uso (~4 s) y se suelta tras `idleMs` sin usarlo. Así el teléfono tiene esa memoria
 * libre el resto del tiempo.
 *
 * Medido en el Redmi 15C: escribe ~9 palabras/s con 2 núcleos (con más, va más lento: los
 * 6 lentos frenan a los 2 rápidos). Sabe poco y se equivoca a veces: es para charlar.
 */
class PhoneLlm(
    private val model: File,
    private val threads: Int = 2,
    private val idleMs: Long = IDLE_MS,
    private val backend: LlmBackend = NativeBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : Talker, AutoCloseable {
    private val lock = Any()
    private var handle = 0L
    private var lastUse = 0L
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vega-llm-reloj").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    /** ¿Está cargado ahora? */
    val loaded: Boolean get() = synchronized(lock) { handle != 0L }

    override fun reply(question: String, onText: (String) -> Boolean): String? {
        synchronized(lock) {
            if (handle == 0L) {
                if (!model.isFile) return null
                val started = clock()
                handle = try {
                    backend.load(model.path, threads, CONTEXT)
                } catch (_: Throwable) {
                    0L
                }
                if (handle == 0L) return null
                log("modelo de charla cargado en ${clock() - started} ms")
            }
            pending?.cancel(false)
            val text = backend.reply(handle, chatPrompt(question), MAX_TOKENS, onText).trim()
            lastUse = clock()
            pending = timer.schedule({ unloadIfIdle() }, idleMs, TimeUnit.MILLISECONDS)
            return text.ifEmpty { null }
        }
    }

    /** Suelta el modelo si lleva `idleMs` sin usarse. */
    fun unloadIfIdle() {
        synchronized(lock) {
            if (handle != 0L && clock() - lastUse >= idleMs) {
                backend.free(handle)
                handle = 0L
                log("modelo de charla descargado (sin uso)")
            }
        }
    }

    override fun close() {
        pending?.cancel(false)
        timer.shutdownNow()
        synchronized(lock) {
            if (handle != 0L) backend.free(handle)
            handle = 0L
        }
    }

    companion object {
        private const val CONTEXT = 512 // instrucciones + pregunta + respuesta corta
        private const val MAX_TOKENS = 80
        const val IDLE_MS = 180_000L // 3 minutos
    }
}
