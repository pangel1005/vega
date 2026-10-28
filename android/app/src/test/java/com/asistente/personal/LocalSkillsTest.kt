package com.asistente.personal

import com.asistente.personal.local.LocalMemory
import com.asistente.personal.local.Skills
import com.asistente.personal.local.normalize
import com.asistente.personal.local.parseAt
import com.asistente.personal.local.parseDuration
import com.asistente.personal.local.nameDistance
import com.asistente.personal.local.parseWake
import com.asistente.personal.local.wordsPerSecond
import com.asistente.personal.local.parseWhen
import com.asistente.personal.local.sayTime
import com.asistente.personal.local.speakable
import com.asistente.personal.local.spokenTime
import com.asistente.personal.local.toYou
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

/** Las habilidades del teléfono deben entender lo mismo que las del PC (server/tests). */
class LocalSkillsTest {
    private val now = LocalDateTime.of(2026, 10, 24, 15, 0) // sábado, 15:00

    private fun skills(memory: LocalMemory = LocalMemory(null), battery: Int? = 80) = Skills(memory, { now }, { battery })

    @Test fun normalizaComoElPc() {
        assertEquals("vega apagalo", normalize("¡Vega, Apágalo!"))
        assertEquals("manana a las 6", normalize("Mañana, a las 6."))
    }

    @Test fun despiertaConElNombreYMuletillas() {
        assertEquals("¿qué hora es?", parseWake("Oye, Vega: ¿qué hora es?", "Vega").command)
        assertTrue(parseWake("Bega, apunta pan", "Vega").woke)
        assertTrue(parseWake("Mega pon un temporizador", "Vega").woke)
        assertFalse(parseWake("La vega del río está bonita", "Vega").woke)
        assertFalse(parseWake("y entonces le dije que no", "Vega").woke)
        val alone = parseWake("Vega.", "Vega")
        assertTrue(alone.woke)
        assertEquals("", alone.command)
    }

    @Test fun entiendeCuando() {
        assertEquals(now.plusMinutes(20), parseWhen("llamar a mamá en 20 minutos", now)!!.at)
        assertEquals(now.plusMinutes(30), parseWhen("dentro de media hora", now)!!.at)
        assertEquals(now.plusMinutes(5), parseWhen("en cinco minutos", now)!!.at)
        // «a las 6» a las 15:00 → las 18:00 de hoy, no las 6 de mañana.
        assertEquals(now.withHour(18), parseAt("a las 6", now)!!.at)
        assertEquals(now.withHour(18).withMinute(30), parseAt("a las 6 y media de la tarde", now)!!.at)
        assertEquals(now.plusDays(1).withHour(8), parseAt("mañana a las 8", now)!!.at)
        assertEquals(now.plusDays(1).withHour(9), parseWhen("mañana", now)!!.at)
        assertEquals(now.withHour(18).withMinute(30), parseAt("a las 18:30", now)!!.at)
        assertNull(parseWhen("sin hora", now))
        assertEquals(300L, parseDuration("temporizador de 5 minutos")!!.first)
    }

    @Test fun diceLaHoraComoSeHabla() {
        assertEquals("seis y media de la tarde", sayTime(18, 30))
        assertEquals("diez menos cuarto de la mañana", sayTime(9, 45))
        assertEquals("Son las seis de la tarde.", speakable("Son las 18:00."))
        assertEquals("Es la una y cuarto de la tarde.", speakable("Es la 13:15."))
        assertEquals("en 20 minutos", spokenTime(now.plusMinutes(20), now))
        assertEquals("mañana a las 09:00", spokenTime(now.plusDays(1).withHour(9), now))
    }

    @Test fun recordatorios() {
        val memory = LocalMemory(null)
        val s = skills(memory)
        assertEquals("Vale, en 20 minutos te recuerdo: llamar a mamá.", s.handle("Recuérdame llamar a mamá en 20 minutos"))
        assertEquals("Tienes pendiente: llamar a mamá en 20 minutos.", s.handle("¿Qué recordatorios tengo?"))
        assertEquals(1, memory.due(now.plusMinutes(21)).size)
        assertEquals("Cancelado 1 recordatorio.", s.handle("cancela los recordatorios"))
        assertTrue(s.handle("recuérdame sacar la basura")!!.startsWith("¿Cuándo"))
    }

    @Test fun temporizadores() {
        val memory = LocalMemory(null)
        val s = skills(memory)
        assertEquals("Temporizador de 5 minutos en marcha.", s.handle("Pon un temporizador de 5 minutos"))
        assertEquals("temporizador de 5 minutos", memory.pending().single().text)
        assertEquals("Temporizador cancelado.", s.handle("cancela el temporizador"))
    }

    @Test fun notasYMemoria() {
        val s = skills()
        assertEquals("Apuntado: comprar leche y pan para mañana.", s.handle("Apunta comprar leche y pan para mañana."))
        assertEquals("Tienes 1 nota: comprar leche y pan para mañana.", s.handle("Lee mis notas"))
        assertEquals("Entendido, lo recordaré: tu perro se llama Toby.", s.handle("Recuerda que mi perro se llama Toby"))
        assertEquals("Tu perro se llama Toby.", s.handle("¿Cómo se llama mi perro?"))
        assertEquals("Hecho, lo he olvidado: tu perro se llama Toby.", s.handle("olvida que mi perro se llama Toby"))
        assertEquals("te gusta el café", toYou("me gusta el café"))
    }

    @Test fun horaFechaYBateria() {
        val s = skills()
        assertEquals("Son las 15:00.", s.handle("¿Qué hora es?"))
        assertEquals("Hoy es sábado 24 de octubre.", s.handle("¿Qué día es hoy?"))
        assertEquals("Me queda un 80 por ciento de batería.", s.handle("¿Cuánta batería tienes?"))
        assertNull(s.handle("¿Cuál es la capital de Francia?"))
    }

    @Test fun laMemoriaSobreviveAlReinicio() {
        val file = File(Files.createTempDirectory("vega").toFile(), "memoria.json")
        LocalMemory(file).apply {
            remember("me gusta el café")
            addNote("pan")
            addReminder("reminder", "llamar", now.plusHours(1))
        }
        val again = LocalMemory(file)
        assertEquals(listOf("me gusta el café"), again.facts())
        assertEquals(listOf("pan"), again.notes())
        assertEquals("llamar", again.pending().single().text)
        assertEquals(2, again.addReminder("timer", "t", now)) // los números no se repiten
    }

    @Test fun cuentasAzarYCharla() {
        val s = Skills(LocalMemory(null), { now }, { 80 }, kotlin.random.Random(1))
        assertEquals("Son 100.", s.handle("¿Cuánto es 25 por 4?"))
        assertEquals("Son 2,4.", s.handle("cuánto es 12 entre 5"))
        assertEquals("Son 7.", s.handle("cuánto es tres más cuatro"))
        assertEquals("El 15 por ciento de 80 es 12.", s.handle("¿Cuánto es el 15 por ciento de 80?"))
        assertEquals("No se puede dividir entre cero.", s.handle("cuánto es 5 entre 0"))
        assertTrue(s.handle("lanza una moneda")!!.startsWith("Ha salido cr") || s.handle("lanza una moneda")!!.startsWith("Ha salido ca"))
        assertTrue(s.handle("tira un dado")!!.matches(Regex("Ha salido un [1-6]\\.")))
        assertTrue(s.handle("cuéntame un chiste")!!.startsWith("¿"))
        assertEquals("¡Hola! ¿En qué te ayudo?", s.handle("Hola"))
        assertEquals("De nada.", s.handle("gracias"))
    }

    @Test fun cuantoQuedaDelTemporizador() {
        val memory = LocalMemory(null)
        val s = skills(memory)
        assertEquals("No hay ningún temporizador en marcha.", s.handle("¿Cuánto le queda al temporizador?"))
        s.handle("pon un temporizador de 2 minutos")
        assertEquals("Le queda 2 minutos.", s.handle("¿cuánto falta del temporizador?"))
    }

    @Test fun loGuardadoSinPcPasaAlPc() {
        val memory = LocalMemory(null)
        memory.addNote("pan")
        memory.addReminder("reminder", "llamar", now.plusHours(1))
        var sent = ""
        memory.exportForPc { false } // no llegó: se conserva
        assertEquals(listOf("pan"), memory.notes())
        memory.exportForPc { sent = it; true }
        assertTrue(sent.contains("\"memory_import\"") && sent.contains("\"pan\"") && sent.contains("2026-10-24T16:00"))
        assertTrue(memory.isEmpty())
    }

    @Test fun `la distancia al nombre es solo una cifra y no depende de lo demas`() {
        assertEquals(0, nameDistance("Vega, abre discord", "Vega"))
        assertEquals(1, nameDistance("Mega abre discord", "Vega")) // Whisper falló por una letra
        assertEquals(0, nameDistance("oye vega abre discord", "Vega")) // también vale la segunda palabra
        assertEquals(4, nameDistance("abre discord ahora", "Vega") ?: -1) // «abre» está a 4 letras: nada que ver
        assertEquals(null, nameDistance("   ", "Vega"))
    }

    @Test fun `las palabras por segundo se cuentan sin guardar la frase`() {
        assertEquals(2.0, wordsPerSecond("abre discord", 1.0), 1e-9)
        assertEquals(3.0, wordsPerSecond("Vega, sube el volumen ya", 5 / 3.0), 1e-9)
        assertEquals(0.0, wordsPerSecond("hola", 0.0), 0.0) // sin duración no se divide por cero
        assertEquals(0.0, wordsPerSecond("", 2.0), 0.0)
    }
}
