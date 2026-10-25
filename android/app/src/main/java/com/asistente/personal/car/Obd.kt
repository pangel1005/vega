package com.asistente.personal.car

/**
 * Datos del coche por OBD-II (un lector ELM327 por Bluetooth), SOLO LECTURA.
 *
 * Seguridad: el código no puede pedirle al lector nada que no esté en [Obd.ALLOWED]: el modo 01
 * («leer datos actuales») para unas pocas lecturas, y los ajustes del propio lector (AT…), que no
 * llegan al coche. No existe ningún camino para escribir, borrar errores ni enviar órdenes a
 * las centralitas del coche.
 */
object Obd {
    /** Ajustes del lector (no hablan con el coche): reinicio, sin eco, sin saltos de línea, sin espacios ni cabeceras, protocolo automático. */
    val SETUP = listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")

    /** Las lecturas que se piden (modo 01 = datos actuales). */
    const val RPM = "010C"
    const val SPEED = "010D"
    const val COOLANT = "0105"
    const val FUEL = "012F"
    const val VOLTS = "0142"

    val READINGS = listOf(SPEED, RPM, COOLANT, FUEL, VOLTS)

    /** Lo único que se puede enviar al lector. */
    val ALLOWED: Set<String> = (SETUP + READINGS).toSet()

    fun isAllowed(command: String): Boolean = command.uppercase().replace(" ", "") in ALLOWED

    /**
     * Valor de una lectura a partir de la respuesta cruda del lector («41 0C 1A F8», con o sin
     * espacios, saltos de línea o «SEARCHING…»), o null si no hay dato.
     */
    fun decode(command: String, response: String): Double? {
        val pid = command.uppercase().takeLast(2)
        val prefix = "41$pid"
        val hex = response.uppercase().lines()
            .map { line -> line.filter { it.isLetterOrDigit() } }
            .firstOrNull { it.startsWith(prefix) } ?: return null
        val data = hex.removePrefix(prefix).chunked(2).mapNotNull { it.takeIf { b -> b.length == 2 }?.toIntOrNull(16) }
        val a = data.getOrNull(0) ?: return null
        val b = data.getOrNull(1)
        return when (command.uppercase()) {
            SPEED -> a.toDouble() // km/h
            RPM -> if (b == null) null else (256 * a + b) / 4.0
            COOLANT -> a - 40.0 // °C
            FUEL -> a * 100.0 / 255.0 // %
            VOLTS -> if (b == null) null else (256 * a + b) / 1000.0 // V
            else -> null
        }
    }
}

/** Un lector de OBD-II: manda una orden y devuelve lo que contesta. */
interface ObdLink : AutoCloseable {
    fun send(command: String): String
}

/**
 * Pregunta al coche por sus lecturas. Cada orden pasa por [Obd.isAllowed]: una que no esté en la
 * lista no llega nunca al lector.
 */
class ObdReader(private val link: ObdLink) {
    fun open() {
        Obd.SETUP.forEach { ask(it) }
    }

    /** Lo que se pueda leer ahora; lo que el coche no conteste se queda en null. */
    fun read(): VehicleReading {
        fun value(cmd: String): Double? = try {
            Obd.decode(cmd, ask(cmd))
        } catch (e: java.io.IOException) {
            throw e // el lector se cayó: que el monitor reconecte
        } catch (_: Exception) {
            null
        }
        return VehicleReading(
            speedKmh = value(Obd.SPEED)?.toInt(),
            rpm = value(Obd.RPM)?.toInt(),
            coolantC = value(Obd.COOLANT)?.toInt(),
            fuelPercent = value(Obd.FUEL)?.toInt(),
            volts = value(Obd.VOLTS),
        )
    }

    private fun ask(command: String): String {
        require(Obd.isAllowed(command)) { "Orden no permitida al lector OBD: $command" }
        return link.send(command)
    }
}
