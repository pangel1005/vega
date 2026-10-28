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

    external fun load(path: String, threads: Int, threadsBatch: Int, ctx: Int): Long
    external fun prime(handle: Long, prefix: String): Boolean
    external fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: Piece): String
    external fun free(handle: Long)
}

/**
 * Instrucciones del modelo. Se leen una sola vez al cargarlo (su estado se guarda y cada pregunta
 * parte de él), así que pueden ser algo más largas; aun así, medido: un system rico con datos del
 * usuario empeora (el modelo pequeño los repite en 3 de cada 4 respuestas). Basta con la frase
 * final: con ella admite que no sabe en 8 de 15 preguntas trampa en vez de 4.
 */
const val TALK_SYSTEM = "Eres Vega, una asistente de voz que vive en un teléfono. Responde siempre en español, " +
    "con una o dos frases cortas y naturales, sin listas, sin emojis y sin inventar datos. " +
    "Si no sabes algo o no puedes saberlo (la hora, el tiempo, las noticias), di que no lo sabes."

/**
 * Pistas que se pegan a cada pregunta. Medido en el Redmi con LFM2.5 1.2B: sin ellas, las preguntas cortas
 * («¿y la de Italia?») se contestaban a veces en inglés o vacías; con la fuerte en la pregunta de ahora y la
 * suave en las anteriores, 5 de 5 casos que fallaban salieron bien. (Probado también con ejemplos de
 * respuesta en las instrucciones: el modelo se descontrolaba, así que no se usan.)
 */
const val HINT_NOW = " (Responde solo en español, nunca en inglés.)"
const val HINT_BEFORE = " (Responde en español.)"

/** Cuántos intercambios anteriores (pregunta y respuesta) recuerda el modelo para entender un «¿y cuándo nació?». */
const val TALK_MEMORY_TURNS = 2

/** Formato de mensajes ChatML (Qwen, LFM). La parte fija, que se lee una sola vez. */
fun chatPrefix(system: String = TALK_SYSTEM): String = "<|im_start|>system\n$system<|im_end|>\n"

/**
 * La parte que cambia en cada pregunta. Qwen 3.5 necesita además el bloque `<think>` vacío (sin
 * «pensar en voz alta»); LFM no lo usa.
 */
fun chatSuffix(question: String, emptyThink: Boolean = false, history: List<Pair<String, String>> = emptyList()): String =
    history.joinToString("") { (q, a) -> "<|im_start|>user\n$q$HINT_BEFORE<|im_end|>\n<|im_start|>assistant\n$a<|im_end|>\n" } +
        "<|im_start|>user\n$question$HINT_NOW<|im_end|>\n<|im_start|>assistant\n" + if (emptyThink) "<think>\n\n</think>\n\n" else ""

/** Lo que se recuerda de una respuesta: sus dos primeras frases, sin pasar de 240 letras (lo que se oyó, no todo lo escrito). */
fun rememberedAnswer(answer: String): String {
    val short = answer.replace('\n', ' ').trim().split(Regex("(?<=[.!?…])\\s+")).take(2).joinToString(" ")
    return if (short.length <= 240) short else short.take(240).trimEnd() + "…"
}

/** Lo que necesita `PhoneLlm` del modelo (en el teléfono, llama.cpp; en las pruebas, uno de mentira). */
interface LlmBackend {
    fun load(path: String, threads: Int, threadsBatch: Int, ctx: Int): Long

    /** Lee `prefix` una vez y guarda su estado; `reply` parte de él. Devuelve false si no pudo. */
    fun prime(handle: Long, prefix: String): Boolean
    fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: (String) -> Boolean): String
    fun free(handle: Long)
}

object NativeBackend : LlmBackend {
    override fun load(path: String, threads: Int, threadsBatch: Int, ctx: Int) = LlamaNative.load(path, threads, threadsBatch, ctx)
    override fun prime(handle: Long, prefix: String) = LlamaNative.prime(handle, prefix)
    override fun reply(handle: Long, prompt: String, maxTokens: Int, onPiece: (String) -> Boolean) =
        LlamaNative.reply(handle, prompt, maxTokens) { onPiece(it) }
    override fun free(handle: Long) = LlamaNative.free(handle)
}

/**
 * El modelo de charla del teléfono (llama.cpp): LFM2.5 1.2B Q4_0 (~700 MB).
 *
 * Solo hace falta cuando charlas (casi todo lo demás son habilidades): se carga al primer uso y se
 * suelta tras `idleMs` sin usarlo, así el teléfono tiene esa memoria libre el resto del tiempo.
 *
 * Medido en el Redmi 15C (informe del 25-10): con 4 hilos para leer y 3 para escribir va a ~8
 * palabras/s; guardando el estado de las instrucciones, el primer token sale a los ~0,7 s en vez
 * de a los 3–4 s. Sabe poco y se equivoca a veces: es para charlar.
 */
class PhoneLlm(
    private val model: File,
    private val threads: Int = 3,
    private val threadsBatch: Int = 4,
    private val idleMs: Long = IDLE_MS,
    private val backend: LlmBackend = NativeBackend,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : Talker, AutoCloseable {
    private val lock = Any()
    private var handle = 0L
    private var primed = false
    private var lastUse = 0L
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "vega-llm-reloj").apply { isDaemon = true } }
    private var pending: ScheduledFuture<*>? = null

    /** Los últimos intercambios (pregunta, respuesta), para que «¿y la de Italia?» se entienda. Se olvidan al soltar el modelo. */
    private val history = ArrayDeque<Pair<String, String>>()

    /** Los Qwen piden el bloque `<think>` vacío; los demás (LFM, Gemma) no. */
    private val emptyThink = model.name.contains("qwen", ignoreCase = true)

    /** ¿Está cargado ahora? */
    val loaded: Boolean get() = synchronized(lock) { handle != 0L }

    override fun reply(question: String, onText: (String) -> Boolean): String? {
        synchronized(lock) {
            if (handle == 0L) {
                if (!model.isFile) return null
                val started = clock()
                handle = try {
                    backend.load(model.path, threads, threadsBatch, CONTEXT)
                } catch (_: Throwable) {
                    0L
                }
                if (handle == 0L) return null
                log("modelo de charla cargado en ${clock() - started} ms")
                val primedAt = clock()
                primed = try {
                    backend.prime(handle, chatPrefix())
                } catch (_: Throwable) {
                    false
                }
                log(if (primed) "instrucciones leídas una vez en ${clock() - primedAt} ms" else "sin caché de instrucciones: se leen en cada pregunta")
            }
            pending?.cancel(false)
            val prompt = (if (primed) "" else chatPrefix()) + chatSuffix(question, emptyThink, history.toList())
            val text = backend.reply(handle, prompt, MAX_TOKENS, onText).trim()
            lastUse = clock()
            if (text.isNotEmpty()) {
                history.addLast(question to rememberedAnswer(text))
                while (history.size > TALK_MEMORY_TURNS) history.removeFirst()
            }
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
                primed = false
                history.clear()
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
            primed = false
            history.clear()
        }
    }

    companion object {
        private const val CONTEXT = 1024 // instrucciones (~70) + dos intercambios + pregunta + respuesta corta
        private const val MAX_TOKENS = 80
        const val IDLE_MS = 180_000L // 3 minutos
    }
}
