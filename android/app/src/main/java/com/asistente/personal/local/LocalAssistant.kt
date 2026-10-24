package com.asistente.personal.local

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.ServerEvent
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Voz → texto (en el teléfono: Whisper base con sherpa-onnx). */
fun interface Ears {
    fun transcribe(samples: FloatArray): String
}

/** Texto → voz (en el teléfono: Piper con sherpa-onnx). Entrega PCM16 y su frecuencia. */
fun interface Mouth {
    fun say(text: String, onAudio: (ByteArray, Int) -> Unit)
}

/** Tras oír su voz, cuánto tiempo se le puede seguir hablando sin decir «Vega» (como en el PC). */
const val FOLLOW_UP_MS = 8_000L

/** Frases de más de 3,5 s: primero se mira si empiezan por «Vega» (2,2 s, con el margen previo de 0,3 s). */
const val PEEK_IF_LONGER = 16_000 * 7 / 2
const val PEEK_SAMPLES = 16_000 * 11 / 5

/**
 * Vega sin el PC: corta el micrófono en frases, las transcribe, mira si la llaman
 * («Vega, …»), responde con sus habilidades y habla. Lo que no empieza por el nombre se
 * descarta sin guardarlo, igual que en el PC.
 *
 * Avisa a la pantalla con los mismos eventos que manda el PC (`ServerEvent`): así la cara,
 * los mensajes y los avisos funcionan igual en los dos modos.
 *
 * El trabajo pesado (Whisper y Piper) va en un hilo propio, de una frase en una: si llega
 * otra mientras piensa, se descarta (casi siempre es ruido o su propio eco).
 */
class LocalAssistant(
    private val name: String,
    private val skills: Skills,
    private val memory: LocalMemory,
    private val ears: Ears,
    private val mouth: Mouth,
    private val events: (ServerEvent) -> Unit,
    private val audio: (ByteArray, Int) -> Unit,
    private val clock: () -> Long,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    /**
     * Con el PC conectado: le pasa la orden ya entendida para que use sus habilidades y botones
     * (sin modelo ni gráfica). Devuelve lo que hay que decir, o null si no es cosa del PC o no
     * hay PC. Así la memoria, las notas y los recordatorios viven en un solo sitio.
     */
    private val pc: (String) -> String? = { null },
    /**
     * Con el PC conectado, le pide que diga el texto con su voz (Kokoro, más natural). Devuelve
     * true si ya empezó a sonar; false si hay que hablar con la voz del teléfono (Piper).
     */
    private val pcVoice: (String) -> Boolean = { false },
    private val runner: (Runnable) -> Unit = Executors.newSingleThreadExecutor { Thread(it, "vega-local") }::execute,
) {
    private val segmenter = Segmenter()
    private val busy = AtomicBoolean(false)
    @Volatile private var listenUntil = 0L
    @Volatile private var attentive = false
    /** La cara está en «escuchando» (tras tocarla o tras contestar) y hay que devolverla a reposo. */
    @Volatile private var listening = false

    /** Audio del micrófono (desde su hilo). */
    fun hear(pcm: ByteArray, length: Int = pcm.size) {
        for (phrase in segmenter.feed(pcm, length)) {
            if (!busy.compareAndSet(false, true)) continue
            runner(Runnable {
                try {
                    process(phrase)
                } finally {
                    busy.set(false)
                }
            })
        }
    }

    /** Tocar la cara: la próxima frase es una orden aunque no digas «Vega». */
    fun attention() {
        attentive = true
        openFollowUp()
    }

    /** Terminó de sonar su voz: se le puede seguir hablando un rato sin decir «Vega». */
    fun playbackDone() = openFollowUp()

    private fun openFollowUp() {
        listenUntil = clock() + FOLLOW_UP_MS
        listening = true
        events(ServerEvent.State(AssistantState.LISTENING))
    }

    /** Se llama cada segundo: cierra la escucha sin nombre y avisa de los recordatorios vencidos. */
    fun tick() {
        if (listening && !busy.get() && clock() >= listenUntil) {
            listening = false
            attentive = false
            events(ServerEvent.State(AssistantState.IDLE))
        }
        val due = memory.due(now())
        if (due.isEmpty() || !busy.compareAndSet(false, true)) return
        runner(Runnable {
            try {
                for (r in due) {
                    memory.remove(r.id)
                    val late = java.time.Duration.between(r.dueAt, now()).seconds > 60
                    val spoken = when {
                        r.kind == "timer" -> "¡Tiempo! Terminó el ${r.text}."
                        late -> "Mientras no estaba encendida tenía que recordarte: ${r.text}."
                        else -> "Te recuerdo: ${r.text}."
                    }
                    events(ServerEvent.Reminder(r.kind, spoken))
                    speak(spoken)
                }
            } finally {
                busy.set(false)
            }
        })
    }

    /** Dice un texto que viene de fuera (p. ej. un recordatorio del PC), si no está ocupada. */
    fun say(text: String) {
        if (!busy.compareAndSet(false, true)) return
        runner(Runnable {
            try {
                speak(text)
            } finally {
                busy.set(false)
            }
        })
    }

    private fun process(phrase: ByteArray) {
        val samples = pcm16ToFloat(phrase)
        val started = clock()
        val following = attentive || clock() < listenUntil
        val text = try {
            // Whisper tarda según lo larga que sea la frase (Redmi: 1,2 s una de 1 s; 5,5 s una de
            // 8 s). Las largas casi nunca son para Vega (charla, la tele): se mira solo el
            // principio, que es donde va el nombre, y se transcribe entera solo si la llaman.
            if (!following && samples.size > PEEK_IF_LONGER) {
                val start = ears.transcribe(samples.copyOfRange(0, PEEK_SAMPLES))
                if (!parseWake(start, name).woke) return
            }
            ears.transcribe(samples).trim()
        } catch (e: Exception) {
            events(ServerEvent.Error("No pude entender el audio: ${e.message}"))
            return
        }
        val elapsed = (clock() - started).toInt()
        val wake = parseWake(text, name)
        if (!wake.woke && !following) return // no era para Vega: no se guarda
        attentive = false
        listening = false
        val command = if (wake.woke) wake.command else text
        if (command.isEmpty() && !wake.woke) return
        val seconds = samples.size / 16_000.0
        events(ServerEvent.Transcript(text, seconds, elapsed))
        if (command.isEmpty()) {
            // Solo «Vega»: contesta y espera la orden sin pedir otra vez el nombre.
            reply("¿Sí?")
            return
        }
        events(ServerEvent.State(AssistantState.THINKING))
        val fromPc = try {
            pc(command)
        } catch (_: Exception) {
            null
        }
        reply(fromPc ?: skills.handle(command) ?: Skills.UNKNOWN)
    }

    private fun reply(text: String) {
        events(ServerEvent.Done(text))
        speak(text)
    }

    private fun speak(text: String) {
        events(ServerEvent.State(AssistantState.SPEAKING))
        try {
            // Kokoro va 2,7 veces más lento que hablar en el Redmi (medido): con PC, su voz;
            // sin PC, Piper en el teléfono, frase a frase (la primera suena mientras sigue).
            val fromPc = try {
                pcVoice(text)
            } catch (_: Exception) {
                false
            }
            if (fromPc) return
            for (sentence in sentences(speakable(text))) mouth.say(sentence, audio)
        } catch (e: Exception) {
            events(ServerEvent.Error("No pude hablar: ${e.message}"))
        } finally {
            events(ServerEvent.State(AssistantState.IDLE))
        }
    }
}

private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")

/** Corta en frases («Vale. Te recuerdo: …» → dos), sin dejar trozos vacíos. */
fun sentences(text: String): List<String> = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
