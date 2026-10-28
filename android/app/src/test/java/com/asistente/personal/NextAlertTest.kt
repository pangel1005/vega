package com.asistente.personal

import com.asistente.personal.net.Upcoming
import com.asistente.personal.ui.NextAlert
import com.asistente.personal.ui.nextAlert
import com.asistente.personal.ui.nextAlertLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NextAlertTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private val now = at(28, 3, 0)

    @Test fun `el aviso mas cercano gana y los temporizadores no cuentan`() {
        val upcoming = listOf(
            Upcoming("timer", "temporizador de 5 minutos", 299), // tiene su cuenta atrás
            Upcoming("reminder", "es la hora de la alarma", 4 * 3600), // 07:00
            Upcoming("reminder", "llamar a mamá", 9 * 3600),
        )
        val next = nextAlert(upcoming, telemetryAt = 1_000, nowMs = 1_000, wallMs = now, local = null)
        assertEquals(NextAlert("es la hora de la alarma", at(28, 7, 0)), next)
    }

    @Test fun `con el PC apagado vale el aviso del telefono y gana el mas cercano de los dos`() {
        val local = NextAlert("es la hora de la alarma", at(28, 7, 0))
        assertEquals(local, nextAlert(emptyList(), 0, 0, now, local))
        val pc = listOf(Upcoming("reminder", "sacar la basura", 3600)) // 04:00, antes que la alarma
        assertEquals("sacar la basura", nextAlert(pc, 0, 0, now, local)?.text)
        assertNull(nextAlert(emptyList(), 0, 0, now, null))
        assertNull(nextAlert(listOf(Upcoming("timer", "t", 10)), 0, 0, now, null))
    }

    @Test fun `la lectura del PC se corrige con lo que paso desde que llego`() {
        // Llegó hace 60 s con «en 3600 s»: faltan 3540 s.
        val next = nextAlert(listOf(Upcoming("reminder", "x", 3600)), telemetryAt = 1_000, nowMs = 61_000, wallMs = now, local = null)
        assertEquals(now + 3_540_000, next?.atMs)
    }

    @Test fun `la etiqueta dice la hora y el dia`() {
        assertEquals("Alarma 07:00", nextAlertLabel(NextAlert("es la hora de la alarma", at(28, 7, 0)), now, zone))
        assertEquals("Alarma mañana 07:00", nextAlertLabel(NextAlert("alarma", at(29, 7, 0)), now, zone))
        assertEquals("18:30 · llamar a mamá", nextAlertLabel(NextAlert("llamar a mamá", at(28, 18, 30)), now, zone))
        assertEquals("30/10 09:05 · dentista", nextAlertLabel(NextAlert("dentista", at(30, 9, 5)), now, zone))
    }
}
