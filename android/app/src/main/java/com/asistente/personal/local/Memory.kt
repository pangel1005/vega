package com.asistente.personal.local

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime

/** Recordatorio o temporizador guardado en el teléfono. */
data class Reminder(val id: Int, val kind: String, val text: String, val dueAt: LocalDateTime)

/**
 * Memoria del teléfono: lo que Vega sabe de ti, tus notas y tus recordatorios, para cuando
 * funciona sin el PC. Hace lo mismo que server/memory/personal.py, pero en un archivo JSON
 * pequeño (`file`; null = solo en memoria, para las pruebas).
 *
 * PROTOTIPO: aún no se sincroniza con la memoria del PC.
 */
class LocalMemory(private val file: File?) {
    private val facts = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private val reminders = mutableListOf<Reminder>()
    private var nextId = 1

    init {
        load()
    }

    @Synchronized fun remember(fact: String) {
        facts += fact.trim()
        save()
    }

    @Synchronized fun facts(): List<String> = facts.takeLast(MAX_FACTS)

    /** Borra los datos que contienen todas las palabras de `about`. Devuelve lo borrado. */
    @Synchronized fun forget(about: String): List<String> {
        val words = normalize(about).split(' ').filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()
        val gone = facts.filter { f -> words.all { it in normalize(f) } }
        facts.removeAll(gone.toSet())
        if (gone.isNotEmpty()) save()
        return gone
    }

    @Synchronized fun addNote(text: String) {
        notes += text.trim()
        save()
    }

    @Synchronized fun notes(limit: Int = 10): List<String> = notes.takeLast(limit)

    @Synchronized fun clearNotes(): Int {
        val n = notes.size
        notes.clear()
        save()
        return n
    }

    @Synchronized fun addReminder(kind: String, text: String, dueAt: LocalDateTime): Int {
        val id = nextId++
        reminders += Reminder(id, kind, text.trim(), dueAt)
        save()
        return id
    }

    @Synchronized fun pending(): List<Reminder> = reminders.sortedBy { it.dueAt }

    @Synchronized fun due(now: LocalDateTime): List<Reminder> = pending().filter { !it.dueAt.isAfter(now) }

    /** Entregado o cancelado: ya no está pendiente. */
    @Synchronized fun remove(id: Int) {
        if (reminders.removeAll { it.id == id }) save()
    }

    @Synchronized fun cancel(kind: String): Int {
        val n = reminders.count { it.kind == kind }
        reminders.removeAll { it.kind == kind }
        if (n > 0) save()
        return n
    }

    /** ¿Hay algo guardado sin PC que haya que pasarle? */
    @Synchronized fun isEmpty(): Boolean = facts.isEmpty() && notes.isEmpty() && reminders.isEmpty()

    /**
     * Lo guardado sin PC, en el formato de «memory_import» (`null` si no hay nada). Si llega
     * al PC, `handedOver` lo vacía: a partir de ahí lo guarda (y avisa) el PC.
     */
    @Synchronized fun exportForPc(handedOver: (String) -> Boolean) {
        if (isEmpty()) return
        val o = JSONObject()
            .put("type", "memory_import")
            .put("facts", JSONArray(facts))
            .put("notes", JSONArray(notes))
            .put("reminders", JSONArray(reminders.map { JSONObject().put("kind", it.kind).put("text", it.text).put("due_at", it.dueAt.toString()) }))
        if (!handedOver(o.toString())) return
        facts.clear()
        notes.clear()
        reminders.clear()
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            o.optJSONArray("facts")?.let { a -> for (i in 0 until a.length()) facts += a.getString(i) }
            o.optJSONArray("notes")?.let { a -> for (i in 0 until a.length()) notes += a.getString(i) }
            o.optJSONArray("reminders")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONObject(i)
                    reminders += Reminder(r.getInt("id"), r.getString("kind"), r.getString("text"), LocalDateTime.parse(r.getString("due_at")))
                }
            }
            nextId = (reminders.maxOfOrNull { it.id } ?: 0) + 1
        } catch (_: Exception) {
            // Archivo dañado: se empieza de cero antes que no arrancar.
        }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject()
            .put("facts", JSONArray(facts))
            .put("notes", JSONArray(notes))
            .put(
                "reminders",
                JSONArray(reminders.map { JSONObject().put("id", it.id).put("kind", it.kind).put("text", it.text).put("due_at", it.dueAt.toString()) }),
            )
        // Se escribe aparte y se renombra: un corte a medias no deja el archivo roto.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    companion object {
        const val MAX_FACTS = 30
    }
}

// Primera persona del usuario → segunda («me gusta» → «te gusta»). Igual que en el PC.
private val TO_YOU = mapOf(
    "me" to "te", "mi" to "tu", "mis" to "tus", "yo" to "tú", "conmigo" to "contigo", "mío" to "tuyo", "mía" to "tuya",
    "soy" to "eres", "estoy" to "estás", "tengo" to "tienes", "vivo" to "vives", "trabajo" to "trabajas",
    "prefiero" to "prefieres", "quiero" to "quieres", "necesito" to "necesitas", "odio" to "odias",
    "juego" to "juegas", "uso" to "usas", "estudio" to "estudias", "voy" to "vas", "hago" to "haces",
)
private val WORD = Regex("[\\p{L}\\p{N}_]+")

/** «me gusta el café y mi perro se llama Toby» → «te gusta el café y tu perro se llama Toby». */
fun toYou(text: String): String = WORD.replace(text) { m ->
    val word = m.value
    val new = TO_YOU[word.lowercase()] ?: word
    if (word[0].isUpperCase()) new.replaceFirstChar { it.uppercase() } else new
}
