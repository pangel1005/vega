package com.asistente.personal

import com.asistente.personal.car.Obd
import com.asistente.personal.car.ObdLink
import com.asistente.personal.car.ObdReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Un ELM327 de mentira: contesta como el de verdad y apunta todo lo que le piden. */
private class FakeElm(private val replies: Map<String, String>, private val broken: Boolean = false) : ObdLink {
    val asked = mutableListOf<String>()
    override fun send(command: String): String {
        asked += command
        if (broken) throw IOException("sin lector")
        return replies[command] ?: "NO DATA"
    }
    override fun close() {}
}

class ObdTest {
    @Test fun `decodifica las lecturas con las formulas del estandar`() {
        assertEquals(87.0, Obd.decode(Obd.SPEED, "41 0D 57")!!, 0.0) // 0x57 = 87 km/h
        assertEquals(1726.0, Obd.decode(Obd.RPM, "41 0C 1A F8")!!, 0.0) // (256·26 + 248) / 4
        assertEquals(50.0, Obd.decode(Obd.COOLANT, "41 05 5A")!!, 0.0) // 90 - 40
        assertEquals(50.0, Obd.decode(Obd.FUEL, "41 2F 80")!!, 0.5) // 128 / 255
        assertEquals(14.4, Obd.decode(Obd.VOLTS, "41 42 38 40")!!, 0.001) // 14400 mV
    }

    @Test fun `entiende respuestas con ruido`() {
        assertEquals(1726.0, Obd.decode(Obd.RPM, "SEARCHING...\r\n410C1AF8\r\n")!!, 0.0) // sin espacios y con aviso delante
        assertEquals(87.0, Obd.decode(Obd.SPEED, "41 0D 57 \r\r")!!, 0.0)
        assertNull(Obd.decode(Obd.SPEED, "NO DATA"))
        assertNull(Obd.decode(Obd.RPM, "41 0C 1A")) // le falta un byte
        assertNull(Obd.decode(Obd.SPEED, "41 0C 1A F8")) // es la respuesta de otra lectura
        assertNull(Obd.decode(Obd.SPEED, ""))
    }

    @Test fun `solo se puede pedir lo de la lista`() {
        for (ok in listOf("010C", "010d", "01 0D", "ATZ", "ATSP0")) assertTrue(ok, Obd.isAllowed(ok))
        // Nada que escriba, borre errores o hable con otras centralitas: modos 02–0A, UDS, etc.
        for (bad in listOf("04", "0100 04", "0902", "2F0101", "3101", "1003", "14FFFF", "ATSH7E0", "ATWS", "ATPP")) {
            assertFalse(bad, Obd.isAllowed(bad))
        }
        val link = FakeElm(emptyMap())
        val reader = ObdReader(link)
        reader.open()
        reader.read()
        assertTrue(link.asked.all { Obd.isAllowed(it) })
        // Aunque algo intentara, el lector lo para antes de enviarlo.
        val ask = ObdReader::class.java.getDeclaredMethod("ask", String::class.java).apply { isAccessible = true }
        try {
            ask.invoke(reader, "04")
            fail("debió negarse")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.targetException is IllegalArgumentException)
        }
        assertFalse(link.asked.contains("04"))
    }

    @Test fun `lee el coche entero y lo que no contesta queda vacio`() {
        val link = FakeElm(mapOf("010D" to "41 0D 3C", "010C" to "41 0C 0B B8", "0105" to "41 05 7D"))
        val reader = ObdReader(link)
        reader.open()
        val r = reader.read()
        assertEquals(60, r.speedKmh) // 0x3C
        assertEquals(750, r.rpm) // (256·11 + 184) / 4
        assertEquals(85, r.coolantC) // 125 - 40
        assertNull(r.fuelPercent) // este coche no la da
        assertNull(r.volts)
        assertEquals(Obd.SETUP + Obd.READINGS, link.asked)
    }

    @Test fun `si el lector se cae lo dice con un error para reconectar`() {
        val reader = ObdReader(FakeElm(emptyMap(), broken = true))
        try {
            reader.read()
            fail("debió fallar")
        } catch (_: IOException) {
            // el monitor lo recoge y reintenta
        }
    }
}
