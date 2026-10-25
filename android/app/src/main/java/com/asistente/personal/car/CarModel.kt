package com.asistente.personal.car

/**
 * La pantalla de Vega en Android Auto, sin nada de la librería del coche: qué casillas hay, qué
 * dice cada una según cómo esté Vega y qué hace al pulsarla. Así se prueba sin coche.
 */

/** Una casilla. `kind` es «builtin» (algo de Vega) o «action» (un botón del PC, por su id). */
data class CarItem(val id: String, val label: String, val kind: String, val arg: String)

/** Casillas de serie, las mismas que trae config/car.toml (se usan mientras el PC no mande otras). */
val DEFAULT_CAR_ITEMS = listOf(
    CarItem("dormir", "Dormir", "builtin", "sleep"),
    CarItem("musica", "Pausa / Play", "builtin", "media"),
    CarItem("siguiente", "Siguiente", "builtin", "next"),
    CarItem("avisos", "Avisos del PC", "builtin", "notifications"),
    CarItem("pc", "Estado del PC", "builtin", "pc"),
    CarItem("ia", "IA: PC / móvil", "builtin", "brain"),
)

/** Lo que dice el lector del coche (OBD-II), si hay uno conectado. Todo es opcional. */
data class VehicleReading(
    val speedKmh: Int? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val fuelPercent: Int? = null,
    val volts: Double? = null,
)

data class CarPc(val cpu: Int, val gpuTemp: Int?, val ramUsed: Double, val ramTotal: Double)

/** Todo lo que necesita la pantalla del coche, ya resumido (igual que el estado de la pantalla del teléfono). */
data class CarSnapshot(
    val online: Boolean = false,
    val sleeping: Boolean = false,
    val brainOnPhone: Boolean = false,
    val media: String? = null,
    val playing: Boolean = false,
    /** Los avisos del PC, la más nueva primero: (título, texto). */
    val notes: List<Pair<String, String>> = emptyList(),
    val pc: CarPc? = null,
    val vehicle: VehicleReading? = null,
)

/** Letra pequeña bajo el nombre de la casilla: su estado ahora. */
fun subtitle(item: CarItem, s: CarSnapshot): String = when (item.kind to item.arg) {
    "builtin" to "sleep" -> if (s.sleeping) "Dormida · toca para despertar" else "Despierta"
    "builtin" to "brain" -> if (s.brainOnPhone) "Piensa en el móvil" else "Piensa en el PC"
    "builtin" to "media" -> s.media?.let { (if (s.playing) "Suena: " else "En pausa: ") + it } ?: "Nada suena"
    "builtin" to "next" -> s.media ?: ""
    "builtin" to "notifications" -> when (s.notes.size) {
        0 -> "Sin avisos"
        1 -> "1 aviso"
        else -> "${s.notes.size} avisos"
    }
    "builtin" to "pc" -> s.pc?.let { "Procesador ${it.cpu} %" + (it.gpuTemp?.let { t -> " · gráfica $t °" } ?: "") } ?: "Sin PC"
    "builtin" to "vehicle" -> s.vehicle?.let { v -> vehicleLine(v) } ?: "Sin lector OBD"
    else -> ""
}

/** «87 km/h · 2100 rpm · 90 °C» con lo que haya. */
fun vehicleLine(v: VehicleReading): String =
    listOfNotNull(
        v.speedKmh?.let { "$it km/h" },
        v.rpm?.let { "$it rpm" },
        v.coolantC?.let { "$it °C" },
        v.fuelPercent?.let { "$it % de gasolina" },
    ).joinToString(" · ").ifEmpty { "Sin datos" }

/** Lo que Vega sabe hacer por el coche; la pantalla del coche solo pide esto, nada más. */
interface CarCommands {
    fun toggleSleep()
    fun toggleBrain()
    fun mediaPlayPause()
    fun mediaNext()

    /** ¿Ese botón del PC pide confirmación (apagar, reiniciar…)? */
    fun needsConfirm(actionId: String): Boolean

    /** Pulsa un botón del PC; con `confirm` también acepta la confirmación (el coche ya la pidió). */
    fun pressAction(actionId: String, confirm: Boolean)
}

/** Qué pantalla abre una casilla, o `null` si hace algo directamente. */
enum class CarPage { NOTIFICATIONS, PC, VEHICLE }

/**
 * Pulsar una casilla: o hace algo en Vega (y devuelve `null`) o pide abrir una página. Un botón
 * del PC con confirmación no se ejecuta aquí: devuelve `Confirm` para que el coche pregunte antes.
 */
sealed interface CarTap {
    data object Done : CarTap
    data class Open(val page: CarPage) : CarTap
    data class Confirm(val item: CarItem) : CarTap
}

fun tap(item: CarItem, commands: CarCommands): CarTap {
    if (item.kind == "action") {
        return if (commands.needsConfirm(item.arg)) CarTap.Confirm(item) else {
            commands.pressAction(item.arg, confirm = false)
            CarTap.Done
        }
    }
    return when (item.arg) {
        "sleep" -> { commands.toggleSleep(); CarTap.Done }
        "brain" -> { commands.toggleBrain(); CarTap.Done }
        "media" -> { commands.mediaPlayPause(); CarTap.Done }
        "next" -> { commands.mediaNext(); CarTap.Done }
        "notifications" -> CarTap.Open(CarPage.NOTIFICATIONS)
        "pc" -> CarTap.Open(CarPage.PC)
        "vehicle" -> CarTap.Open(CarPage.VEHICLE)
        else -> CarTap.Done // una casilla desconocida no hace nada
    }
}
