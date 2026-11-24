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

/** Cuánto tiempo (ms) puede esperar una frase que llegó con Vega ocupada antes de que ya no valga la pena. */
const val WAITING_MS = 3_000L

/** Tras hablar, cuánto tiempo puede volver su voz como eco por el micrófono. */
const val ECHO_MEMORY_MS = 6_000L

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
/** Lo que contesta el PC a una orden que entendió el teléfono; `sleep`: dormirse después de decirlo. */
data class PcAnswer(val text: String, val sleep: Boolean = false)

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
    private val pc: (String) -> PcAnswer? = { null },
    /**
     * Con el PC conectado, le pide que diga el texto con su voz (Kokoro, más natural). Devuelve
     * true si ya empezó a sonar; false si hay que hablar con la voz del teléfono (Piper).
     */
    private val pcVoice: (String) -> Boolean = { false },
    /** Charla libre con el modelo pequeño del teléfono, para lo que no resuelve nada más (o null). */
    private val talker: Talker? = null,
    /** «Vega, duérmete»: el teléfono pasa a dormir (apaga el micrófono y libera los modelos). */
    private val onSleep: () -> Unit = {},
    /** Registro de cada paso (en el teléfono, logcat «Vega»); nunca lleva lo que se dijo. */
    private val log: (String) -> Unit = {},
    /**
     * Filtro barato de «Vega»: si la frase no puede empezar llamándola, se descarta sin gastar
     * Whisper en ella (en el teléfono casi todo lo que oye es ruido o conversación ajena).
     */
    private val gate: Gate? = null,
    /** Cuántas frases oyó hoy, cuántas pasaron el filtro y cuántas la llamaron (solo cifras). */
    val stats: EarsStats = EarsStats(),
    private val runner: (Runnable) -> Unit = Executors.newSingleThreadExecutor { Thread(it, "vega-local") }::execute,
) {
    private val segmenter = Segmenter()
    private val busy = AtomicBoolean(false)
    @Volatile private var listenUntil = 0L
    @Volatile private var attentive = false
    /** La cara está en «escuchando» (tras tocarla o tras contestar) y hay que devolverla a reposo. */
    @Volatile private var listening = false

    @Volatile private var closed = false

    /**
     * Libera Whisper, la voz y el modelo de charla (~600 MB). Espera a que acabe lo que esté
     * haciendo (todo va en el mismo hilo) y después ya no atiende más audio.
     */
    fun close() {
        if (closed) return // cerrar dos veces liberaría dos veces los modelos nativos
        closed = true
        runner(Runnable {
            (ears as? AutoCloseable)?.close()
            (mouth as? AutoCloseable)?.close()
            (talker as? AutoCloseable)?.close()
        })
    }

    /** La última frase que llegó mientras trabajaba (con cuándo): si sigue fresca, se atiende después. */
    @Volatile private var waiting: Pair<ByteArray, Long>? = null

    /** Audio del micrófono (desde su hilo). */
    fun hear(pcm: ByteArray, length: Int = pcm.size) {
        if (closed) return
        for (phrase in segmenter.feed(pcm, length)) {
            if (!busy.compareAndSet(false, true)) {
                waiting = phrase to clock() // antes se tiraba: una llamada a «Vega» justo tras un ruido se perdía
                continue
            }
            start(phrase)
        }
    }

    private fun start(phrase: ByteArray) {
        runner(Runnable {
            try {
                process(phrase)
            } finally {
                busy.set(false)
                resumeWaiting()
            }
        })
    }

    private fun resumeWaiting() {
        val (phrase, since) = waiting ?: return
        waiting = null
        if (closed || clock() - since > WAITING_MS) return // ya es vieja: no tiene sentido contestarla
        if (busy.compareAndSet(false, true)) start(phrase)
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
        stats.phrase()
        val started = clock()
        val following = attentive || clock() < listenUntil
        if (!following && gate != null) {
            val t0 = clock()
            val pass = gate.passes(samples.copyOf(minOf(samples.size, WakeGate.WINDOW)))
            val p = gate.lastScore // la probabilidad, no lo dicho: sirve para recalibrar el filtro con datos reales
            log("filtro de «$name»: ${if (pass) "pasa" else "descartada"} en ${clock() - t0} ms (frase de ${samples.size / 16} ms, p=${"%.3f".format(p)})")
            if (!pass) return
            stats.passedFilter()
        }
        val text = try {
            // Whisper tarda según lo larga que sea la frase (Redmi: 1,2 s una de 1 s; 5,5 s una de
            // 8 s). Las largas casi nunca son para Vega (charla, la tele): se mira solo el
            // principio, que es donde va el nombre, y se transcribe entera solo si la llaman.
            if (!following && gate == null && samples.size > PEEK_IF_LONGER) {
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
        if (wake.woke) stats.call()
        val asked = wake.woke || attentive // la llamaste o tocaste la cara: una orden seguro
        // Solo cifras (nunca lo dicho): palabras por segundo y a cuántas letras está de «Vega» la mejor de las dos primeras.
        val pace = "%.1f".format(wordsPerSecond(text, samples.size / 16_000.0))
        log("frase de ${samples.size / 16} ms → texto en $elapsed ms; nombre: ${wake.woke} (a ${nameDistance(text, name) ?: "-"} letras), $pace palabras/s, seguimiento: $following")
        if (!wake.woke && !following) return // no era para Vega: no se guarda
        // Su propia voz que vuelve por el micrófono (eco de la sala): se ignora. Sin esto se
        // contestaba a sí misma en bucle.
        if (!wake.woke && isEcho(text)) {
            log("era su eco: se ignora")
            return
        }
        attentive = false
        val command = if (wake.woke) wake.command else text
        if (command.isEmpty() && !wake.woke) return
        listening = false
        val seconds = samples.size / 16_000.0
        if (command.isEmpty()) {
            // Solo «Vega»: contesta y espera la orden sin pedir otra vez el nombre. Lo siguiente
            // que digas es una orden seguro (como tocar la cara): si no es una habilidad, charla.
            // Un tilín al momento (no un «¿Sí?» hablado, que tardaba ~2 s y se te montaba encima: casi siempre
            // sigues con la orden sin esperar) y la cara escuchando, como al tocarla.
            events(ServerEvent.Transcript(text, seconds, elapsed))
            audio(chimePcm(), CHIME_RATE)
            log("solo el nombre: tilín y a la espera de la orden")
            attentive = true
            openFollowUp()
            return
        }
        if (isSleepCommand(command)) {
            reply("Hasta luego. Toca la pantalla cuando quieras despertarme.")
            onSleep()
            return
        }
        events(ServerEvent.State(AssistantState.THINKING))
        val fromPc = try {
            pc(command)
        } catch (_: Exception) {
            null
        }
        val answer = fromPc?.text ?: skills.handle(command)
        log("respuesta: ${if (fromPc != null) "del PC" else if (answer != null) "del teléfono" else "ninguna"}")
        if (answer == null && !asked) {
            // En los segundos de seguimiento (sin «Vega») se oye de todo: la tele, gente hablando.
            // Lo que no entiende no es una orden: se calla en vez de decir «no sé» (eso abría
            // otro seguimiento y Vega hablaba sin parar).
            events(ServerEvent.State(AssistantState.IDLE))
            return
        }
        events(ServerEvent.Transcript(text, seconds, elapsed))
        when {
            answer != null -> {
                reply(answer)
                if (fromPc?.sleep == true) onSleep() // «buenas noches» → «no» a apagar: «Que descanses» y a dormir
            }
            talker != null -> talk(command)
            else -> reply(Skills.UNKNOWN)
        }
    }

    /**
     * Charla con el modelo del teléfono: dice cada frase en cuanto está escrita (no espera a
     * la respuesta entera) y corta a las tres frases, que en voz ya es mucho.
     */
    private fun talk(question: String) {
        val started = clock()
        var pending = ""
        var spoken = 0
        val full = try {
            talker?.reply(question) { piece ->
                events(ServerEvent.Token(piece))
                pending += piece
                val cut = lastSentenceEnd(pending)
                if (cut > 0) {
                    val sentence = pending.substring(0, cut).trim()
                    pending = pending.substring(cut)
                    if (sentence.isNotEmpty()) {
                        if (spoken == 0) log("charla: primera frase en ${clock() - started} ms")
                        voice(sentence)
                        spoken++
                    }
                }
                spoken < MAX_TALK_SENTENCES
            }
        } catch (e: Exception) {
            null
        }
        if (full == null && spoken == 0) {
            reply(Skills.UNKNOWN)
            return
        }
        if (pending.isNotBlank() && spoken < MAX_TALK_SENTENCES) voice(pending.trim())
        log("charla: terminada en ${clock() - started} ms")
        events(ServerEvent.Done(full ?: ""))
        events(ServerEvent.State(AssistantState.IDLE))
    }

    /** Lo último que dijo (palabras normalizadas) y hasta cuándo puede volver como eco. */
    @Volatile private var lastSaid: Set<String> = emptySet()
    @Volatile private var lastSaidUntil = 0L

    /** ¿Lo oído es casi todo palabras de lo que ella acaba de decir? */
    private fun isEcho(heard: String): Boolean {
        if (clock() > lastSaidUntil) return false
        val words = normalize(heard).split(' ').filter { it.length > 1 }
        if (words.isEmpty()) return true
        return words.count { it in lastSaid } >= words.size * 0.6
    }

    private fun reply(text: String) {
        events(ServerEvent.Done(text))
        speak(text)
    }

    private fun speak(text: String) {
        try {
            voice(text)
        } finally {
            events(ServerEvent.State(AssistantState.IDLE))
        }
    }

    /** Dice un texto (sin volver a reposo al acabar: la charla dice varias frases seguidas). */
    private fun voice(raw: String) {
        val text = raw.replace(MARKDOWN, "") // el modelo a veces escribe **negritas** o # títulos
        // Como se oye (speakable: «15:00» suena «tres de la tarde») y como se escribe. Se suma
        // a lo anterior si sigue hablando (charla de varias frases).
        val words = normalize(speakable(text) + " " + text).split(' ').filter { it.length > 1 }.toSet()
        lastSaid = if (clock() < lastSaidUntil) lastSaid + words else words
        lastSaidUntil = clock() + ECHO_MEMORY_MS + text.length * 80L // lo que tarda en decirlo, y un poco más
        events(ServerEvent.State(AssistantState.SPEAKING))
        try {
            // Kokoro va 2,7 veces más lento que hablar en el Redmi (medido): con PC, su voz;
            // sin PC, Piper en el teléfono, frase a frase (la primera suena mientras sigue).
            val fromPc = try {
                pcVoice(text)
            } catch (_: Exception) {
                false
            }
            log("voz: ${if (fromPc) "la del PC" else "la del teléfono"}")
            if (fromPc) return
            for (sentence in sentences(speakable(text))) mouth.say(sentence, audio)
        } catch (e: Exception) {
            events(ServerEvent.Error("No pude hablar: ${e.message}"))
        }
    }
}

private val SENTENCE_END = Regex("(?<=[.!?…])\\s+")
private val MARKDOWN = Regex("[*#_`]+")

private val SLEEP_COMMAND = Regex("\\b(duermete|vete a dormir|a dormir|modo (de )?(sueno|descanso))\\b")

/** ¿La orden es «duérmete»? (Igual que la habilidad del PC.) */
fun isSleepCommand(command: String): Boolean = SLEEP_COMMAND.containsMatchIn(normalize(command))

/** Como mucho, cuántas frases dice en una charla. */
const val MAX_TALK_SENTENCES = 3

/** Dónde acaba la última frase completa de `text` (0 si aún no hay ninguna). */
fun lastSentenceEnd(text: String): Int = SENTENCE_END.findAll(text).lastOrNull()?.range?.last?.plus(1) ?: 0

/** Corta en frases («Vale. Te recuerdo: …» → dos), sin dejar trozos vacíos. */
fun sentences(text: String): List<String> = text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }
