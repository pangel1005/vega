package com.asistente.personal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientModeTest {
    @Test fun `no baja el brillo antes de tiempo ni justo antes`() {
        assertFalse(ambientDue(lastActiveMs = 0, nowMs = AMBIENT_AFTER_MS - 1))
        assertFalse(ambientDue(lastActiveMs = 5_000, nowMs = 5_000))
    }

    @Test fun `baja el brillo al cumplirse los diez minutos sin actividad`() {
        assertTrue(ambientDue(lastActiveMs = 0, nowMs = AMBIENT_AFTER_MS))
        assertTrue(ambientDue(lastActiveMs = 1_000, nowMs = 1_000 + 11 * 60_000L))
    }

    @Test fun `cualquier actividad reinicia la cuenta`() {
        var last = 0L
        val now = 9 * 60_000L
        assertFalse(ambientDue(last, now))
        last = now // un toque, un aviso…
        assertFalse(ambientDue(last, now + 9 * 60_000L))
        assertTrue(ambientDue(last, now + AMBIENT_AFTER_MS))
    }

    @Test fun `el brillo de ambiente es bajo pero no apaga la pantalla`() {
        assertTrue(AMBIENT_BRIGHTNESS in 0.01f..0.1f)
        assertEquals(10 * 60_000L, AMBIENT_AFTER_MS)
    }
}
