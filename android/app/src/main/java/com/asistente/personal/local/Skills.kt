package com.asistente.personal.local

import java.time.LocalDateTime

/**
 * Habilidades del teléfono: lo que Vega resuelve sola, sin el PC ni modelo de lenguaje.
 * Son las mismas reglas que server/skills/router.py (memoria, recordatorios,
 * temporizadores, notas) y, además, la hora, la fecha y la batería, que en el PC
 * contestaba el modelo.
 *
 * Se prueban en orden; la primera que reconoce la frase responde. Si ninguna la reconoce,
 * `handle` devuelve null y Vega explica qué sabe hacer sin el PC.
 */
class Skills(
    private val memory: LocalMemory,
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val battery: () -> Int? = { null },
    private val random: kotlin.random.Random = kotlin.random.Random.Default,
) {
    fun handle(text: String): String? {
        val said = normalize(text)
        val skills = listOf(::forget, ::whatYouKnow, ::remember, ::timer, ::reminders, ::notes, ::clock, ::date, ::phoneBattery, ::math, ::chance, ::joke, ::chat, ::help, ::aboutMe)
        for (skill in skills) skill(text, said)?.let { return it }
        return null
    }

    // --- Memoria ---

    private fun remember(text: String, said: String): String? {
        val m = REMEMBER.find(text) ?: return null
        // «A y que B» son dos datos: así se pueden olvidar por separado.
        val facts = m.groups["fact"]!!.value.split(Regex(",?\\s+y\\s+que\\s+", I)).map { it.trim(' ', '.', ',') }.filter { it.isNotEmpty() }
        facts.forEach(memory::remember)
        return "Entendido, lo recordaré: " + list(facts.map(::toYou)) + "."
    }

    /** Preguntas sobre el usuario («¿cómo se llama mi perro?»): si está en su memoria, la da. */
    private fun aboutMe(text: String, said: String): String? {
        if (!Regex("\\b(me|mi|mis|yo)\\b").containsMatchIn(said) || !isQuestion(text, said)) return null
        val words = said.split(' ').filter { it.length > 3 && it !in STOPWORDS }.toSet()
        if (words.isEmpty()) return null
        val found = memory.facts().filter { f -> normalize(f).split(' ').any { it in words } }
        if (found.isEmpty()) return null
        val answer = list(found.map(::toYou))
        return answer.replaceFirstChar { it.uppercase() } + "."
    }

    private fun whatYouKnow(text: String, said: String): String? {
        if (!Regex("\\bque (sabes|recuerdas) (de|sobre) mi\\b").containsMatchIn(said)) return null
        val facts = memory.facts()
        if (facts.isEmpty()) return "Todavía no sé nada de ti. Dime «recuerda que…» y lo guardo."
        return "Esto es lo que sé de ti: " + facts.joinToString("; ") { toYou(it) } + "."
    }

    private fun forget(text: String, said: String): String? {
        val m = FORGET.find(text) ?: return null
        val gone = memory.forget(m.groups["about"]!!.value)
        if (gone.isEmpty()) return "No tenía guardado nada sobre eso."
        return "Hecho, lo he olvidado: " + gone.joinToString("; ") { toYou(it) } + "."
    }

    // --- Recordatorios y temporizadores ---

    private fun timer(text: String, said: String): String? {
        if (!TIMER.containsMatchIn(text)) return null
        if (Regex("\\bcuanto (le )?(falta|queda)").containsMatchIn(said)) {
            val timers = memory.pending().filter { it.kind == "timer" }
            if (timers.isEmpty()) return "No hay ningún temporizador en marcha."
            return "Le queda " + remaining(java.time.Duration.between(now(), timers.first().dueAt).seconds) + "."
        }
        if (Regex("\\b(cancela|quita|para|deten|borra)\\b").containsMatchIn(said)) {
            return if (memory.cancel("timer") > 0) "Temporizador cancelado." else "No había ningún temporizador en marcha."
        }
        val (secs, span) = parseDuration(text) ?: return "¿De cuánto tiempo? Dime, por ejemplo, «temporizador de 5 minutos»."
        val raw = text.substring(span.first, span.last + 1).trim()
        val label = if (raw.lowercase().startsWith("de ")) raw else "de $raw"
        memory.addReminder("timer", "temporizador $label", now().plusSeconds(secs))
        return "Temporizador $label en marcha."
    }

    private fun reminders(text: String, said: String): String? {
        if (Regex("\\b(que|mis) recordatorios\\b|\\bque tengo pendiente\\b").containsMatchIn(said) && "cancela" !in said) {
            val pending = memory.pending().filter { it.kind == "reminder" }
            if (pending.isEmpty()) return "No tienes recordatorios pendientes."
            val t = now()
            return "Tienes pendiente: " + list(pending.map { "${it.text} ${spokenTime(it.dueAt, t)}" }) + "."
        }
        if (Regex("\\b(cancela|borra|quita)\\b.*\\brecordatorios?\\b").containsMatchIn(said)) {
            val n = memory.cancel("reminder")
            return if (n > 0) "Cancelado${s(n)} $n recordatorio${s(n)}." else "No había recordatorios pendientes."
        }
        val m = REMIND.find(text) ?: return null
        val rest = m.groups["rest"]!!.value
        val t = now()
        val whenAt = parseWhen(rest, t) ?: return "¿Cuándo te lo recuerdo? Dime, por ejemplo, «en 20 minutos» o «a las 6»."
        val what = cut(rest, whenAt.span).ifEmpty { "lo que me pediste" }
        memory.addReminder("reminder", what, whenAt.at)
        return "Vale, ${spokenTime(whenAt.at, t)} te recuerdo: $what."
    }

    // --- Notas ---

    private fun notes(text: String, said: String): String? {
        if (Regex("\\b(lee|leeme|dime|cuales son|que tengo en)\\b.*\\bnotas\\b|\\bque notas tengo\\b").containsMatchIn(said)) {
            val notes = memory.notes()
            if (notes.isEmpty()) return "No tienes notas."
            return "Tienes ${notes.size} nota${s(notes.size)}: " + notes.joinToString("; ") + "."
        }
        if (Regex("\\b(borra|elimina|limpia)\\b.*\\bnotas\\b").containsMatchIn(said)) {
            val n = memory.clearNotes()
            return if (n > 0) "Borrada${s(n)} $n nota${s(n)}." else "No tenías notas."
        }
        val m = NOTE.find(text) ?: return null
        val note = m.groups["text"]!!.value.trim(' ', '.')
        memory.addNote(note)
        return "Apuntado: $note."
    }

    // --- Hora, fecha y batería (en el PC las contestaba el modelo) ---

    private fun clock(text: String, said: String): String? {
        if (!Regex("\\bque hora (es|son|tienes)\\b|\\b(dime|sabes) la hora\\b|^la hora$").containsMatchIn(said)) return null
        val t = now()
        val article = if (t.hour % 12 == 1) "Es la" else "Son las"
        return "$article %d:%02d.".format(t.hour, t.minute)
    }

    private fun date(text: String, said: String): String? {
        if (!Regex("\\bque (dia|fecha) es\\b|\\ba que (dia )?estamos\\b|\\bque dia (es )?hoy\\b|\\bdime la fecha\\b").containsMatchIn(said)) return null
        val t = now()
        return "Hoy es ${DAYS[t.dayOfWeek.value - 1]} ${t.dayOfMonth} de ${MONTHS[t.monthValue - 1]}."
    }

    private fun phoneBattery(text: String, said: String): String? {
        if (!Regex("\\bbateria\\b").containsMatchIn(said)) return null
        val b = battery() ?: return "No sé cuánta batería me queda."
        return "Me queda un $b por ciento de batería."
    }

    // --- Cuentas, azar, chistes y charla corta ---

    /** «¿cuánto es 25 por 4?», «12 entre 5», «el 15 por ciento de 80». */
    private fun math(text: String, said: String): String? {
        PERCENT.find(said)?.let { m ->
            val p = number(m.groupValues[1]) ?: return null
            val of = number(m.groupValues[2]) ?: return null
            return "El ${show(p)} por ciento de ${show(of)} es ${show(p * of / 100)}."
        }
        val m = OPERATION.find(said) ?: return null
        val a = number(m.groupValues[1]) ?: return null
        val b = number(m.groupValues[3]) ?: return null
        val result = when (m.groupValues[2].split(' ')[0]) {
            "mas" -> a + b
            "menos" -> a - b
            "por", "multiplicado", "x" -> a * b
            else -> if (b == 0.0) return "No se puede dividir entre cero." else a / b
        }
        return "Son ${show(result)}."
    }

    private fun chance(text: String, said: String): String? = when {
        Regex("\\b(lanza|tira|echa)\\b.*\\bmoneda\\b|\\bcara o cruz\\b").containsMatchIn(said) ->
            if (random.nextBoolean()) "Ha salido cara." else "Ha salido cruz."
        Regex("\\b(lanza|tira|echa)\\b.*\\bdado\\b").containsMatchIn(said) -> "Ha salido un ${random.nextInt(1, 7)}."
        else -> null
    }

    private fun joke(text: String, said: String): String? {
        if (!Regex("\\bchistes?\\b|\\balgo gracioso\\b").containsMatchIn(said)) return null
        return JOKES[random.nextInt(JOKES.size)]
    }

    private fun chat(text: String, said: String): String? = when {
        Regex("^(hola|buenas|buenos dias|buenas tardes|buenas noches)\\b").containsMatchIn(said) && said.split(' ').size <= 3 ->
            "¡Hola! ¿En qué te ayudo?"
        Regex("^(muchas )?gracias\\b").containsMatchIn(said) -> "De nada."
        Regex("\\bcomo estas\\b|\\bque tal estas\\b").containsMatchIn(said) -> "Muy bien, gracias. Aquí, lista para ayudarte."
        Regex("\\bquien eres\\b|\\bcomo te llamas\\b").containsMatchIn(said) -> "Soy Vega, tu asistente. Ahora mismo pienso en el teléfono."
        else -> null
    }

    private fun help(text: String, said: String): String? {
        if (!Regex("\\bque (sabes|puedes) hacer\\b|\\bque haces\\b|\\bayuda\\b").containsMatchIn(said)) return null
        return HELP
    }

    companion object {
        /** Lo que Vega dice cuando no entiende algo sin el PC. */
        const val HELP = "Sin el PC puedo decirte la hora, poner recordatorios y temporizadores, apuntar notas, " +
            "recordar cosas de ti, hacer cuentas y contarte un chiste."
        const val UNKNOWN = "Eso aún no lo sé hacer sin el PC. $HELP"

        private val I = RegexOption.IGNORE_CASE
        private val REMEMBER = Regex("^\\s*(?:recuerda|acu[ée]rdate(?:\\s+de)?|guarda|memoriza)\\s+que\\s+(?<fact>.+)$", I)
        private val FORGET = Regex("^\\s*olvida\\s+(?:que|lo\\s+de|lo\\s+que\\s+te\\s+dije\\s+de)\\s+(?<about>.+)$", I)
        private val REMIND = Regex("^\\s*(?:recu[ée]rdame|av[íi]same|recordarme)\\b[\\s,:]*(?<rest>.*)$", I)
        private val NOTE = Regex("^\\s*(?:apunta|anota|toma\\s+nota(?:\\s+de)?|nota)\\b[\\s:,]*(?:que\\s+)?(?<text>.+)$", I)
        private val TIMER = Regex("\\b(?:temporizador|cron[óo]metro|cuenta\\s+atr[áa]s|alarma)\\b", I)
        private val QUESTION = Regex("^(como|cual|cuales|cuando|donde|quien|que|cuanto|cuantos|sabes|recuerdas|dime)\\b")
        private val STOPWORDS = setOf(
            "como", "cual", "cuales", "cuando", "donde", "quien", "sabes", "recuerdas", "dime", "llama", "llaman",
            "esta", "estan", "tengo", "tiene", "gusta", "gustan", "sobre", "para", "porque",
        )
        private val DAYS = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")

        private val NUMBER_WORD = "(\\d+(?:[.,]\\d+)?|cero|" + NUMBERS.keys.sortedByDescending { it.length }.joinToString("|") + ")"
        private val OPERATION = Regex(
            "$NUMBER_WORD (mas|menos|por|x|multiplicado por|entre|dividido entre|dividido por|dividido) $NUMBER_WORD\\b",
        )
        private val PERCENT = Regex("$NUMBER_WORD por ciento de $NUMBER_WORD\\b")

        private fun number(word: String): Double? = when {
            word == "cero" -> 0.0
            word[0].isDigit() -> word.replace(',', '.').toDoubleOrNull()
            else -> NUMBERS[word]?.toDouble()
        }

        /** 100.0 → «100»; 2.5 → «2,5»; 3.3333 → «3,33». */
        private fun show(x: Double): String {
            val rounded = Math.round(x * 100) / 100.0
            return if (rounded == Math.floor(rounded) && kotlin.math.abs(rounded) < 1e15) rounded.toLong().toString()
            else rounded.toString().replace('.', ',')
        }

        /** 125 s → «2 minutos y 5 segundos». */
        private fun remaining(seconds: Long): String {
            val s = seconds.coerceAtLeast(0)
            val h = s / 3600
            val m = s % 3600 / 60
            val sec = s % 60
            val parts = buildList {
                if (h > 0) add("$h hora${if (h == 1L) "" else "s"}")
                if (m > 0) add("$m minuto${if (m == 1L) "" else "s"}")
                if (sec > 0 && h == 0L) add("$sec segundo${if (sec == 1L) "" else "s"}")
            }
            return if (parts.isEmpty()) "nada" else list(parts)
        }

        private val JOKES = listOf(
            "¿Por qué el libro de matemáticas está triste? Porque tiene muchos problemas.",
            "¿Qué le dice una iguana a su hermana gemela? Somos iguanitas.",
            "¿Cómo se despiden los químicos? Ácido un placer.",
            "¿Por qué la computadora fue al médico? Porque tenía un virus.",
            "¿Qué le dijo el uno al diez? Para ser como yo, tienes que ser sincero.",
            "¿Qué hace una abeja en el gimnasio? ¡Zumba!",
            "¿Cuál es el colmo de un electricista? Que sus hijos no le sigan la corriente.",
            "¿Qué le dice un semáforo a otro? No me mires, que me estoy cambiando.",
        )

        private fun isQuestion(text: String, said: String) = '?' in text || QUESTION.containsMatchIn(said)

        private fun s(n: Int) = if (n == 1) "" else "s"

        private fun list(items: List<String>): String =
            if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " y " + items.last()

        /** Quita del texto el trozo que describía la hora y limpia lo que sobra. */
        private fun cut(text: String, span: IntRange): String {
            var rest = (text.substring(0, span.first) + " " + text.substring(span.last + 1)).trim(' ', ',', '.', ';', ':', '¡', '!', '¿', '?')
            rest = rest.replace(Regex("^(?:que|de|a)\\s+", I), "")
            return rest.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }
    }
}
