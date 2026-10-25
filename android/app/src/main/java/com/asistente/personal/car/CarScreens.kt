package com.asistente.personal.car

import android.os.Handler
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.asistente.personal.R

/** Lo que las pantallas del coche necesitan de Vega (en el teléfono lo da el controlador; en las pruebas, uno de mentira). */
interface CarBackend : CarCommands {
    fun snapshot(): CarSnapshot

    /** Título y casillas de la pantalla (las del PC si las mandó; si no, las de serie). */
    fun layout(): Pair<String, List<CarItem>>
}

private fun icon(context: CarContext, res: Int): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(context, res)).build()

private fun iconFor(context: CarContext, item: CarItem): CarIcon = icon(
    context,
    when (item.kind to item.arg) {
        "builtin" to "sleep" -> R.drawable.ic_car_sleep
        "builtin" to "media" -> R.drawable.ic_car_media
        "builtin" to "next" -> R.drawable.ic_car_next
        "builtin" to "notifications" -> R.drawable.ic_car_bell
        "builtin" to "pc" -> R.drawable.ic_car_pc
        "builtin" to "brain" -> R.drawable.ic_car_brain
        "builtin" to "vehicle" -> R.drawable.ic_car_vehicle
        else -> R.drawable.ic_car_button
    },
)

/** Repinta la pantalla cuando cambia lo que enseña (cada ~2 s se compara; Android Auto limita los cambios). */
private class Refresher(private val screen: Screen, private val key: () -> String) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var last = ""
    private val tick = object : Runnable {
        override fun run() {
            val now = key()
            if (now != last) {
                last = now
                screen.invalidate()
            }
            handler.postDelayed(this, PERIOD_MS)
        }
    }

    init {
        screen.lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        last = key()
        handler.postDelayed(tick, PERIOD_MS)
    }

    override fun onStop(owner: LifecycleOwner) {
        handler.removeCallbacks(tick)
    }

    companion object {
        const val PERIOD_MS = 2_000L
    }
}

/** Pantalla principal de Vega en el coche: una cuadrícula con las casillas de config/car.toml. */
class VegaCarScreen(carContext: CarContext, private val backend: CarBackend) : Screen(carContext) {
    init {
        Refresher(this) { backend.snapshot().toString() + backend.layout().toString() }
    }

    override fun onGetTemplate(): Template {
        val (title, items) = backend.layout()
        val snap = backend.snapshot()
        val grid = ItemList.Builder()
        for (item in items.take(MAX_ITEMS)) {
            grid.addItem(
                GridItem.Builder()
                    .setTitle(item.label)
                    .setText(subtitle(item, snap).ifEmpty { " " })
                    .setImage(iconFor(carContext, item), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { onTap(item) }
                    .build(),
            )
        }
        return GridTemplate.Builder()
            .setTitle(title)
            .setHeaderAction(Action.APP_ICON)
            .setSingleList(grid.build())
            .build()
    }

    private fun onTap(item: CarItem) {
        when (val result = tap(item, backend)) {
            CarTap.Done -> invalidate()
            is CarTap.Open -> screenManager.push(
                when (result.page) {
                    CarPage.NOTIFICATIONS -> NotificationsScreen(carContext, backend)
                    CarPage.PC -> PcScreen(carContext, backend)
                    CarPage.VEHICLE -> VehicleScreen(carContext, backend)
                },
            )
            is CarTap.Confirm -> screenManager.push(ConfirmScreen(carContext, backend, result.item))
        }
    }

    companion object {
        /** Lo que cabe en una cuadrícula de Android Auto mientras se conduce. */
        const val MAX_ITEMS = 6
    }
}

/** Los avisos del PC, de solo lectura (en el coche no se contesta ni se descarta nada). */
class NotificationsScreen(carContext: CarContext, private val backend: CarBackend) : Screen(carContext) {
    init {
        Refresher(this) { backend.snapshot().notes.toString() }
    }

    override fun onGetTemplate(): Template {
        val notes = backend.snapshot().notes
        if (notes.isEmpty()) {
            return MessageTemplate.Builder("Sin avisos en el PC").setTitle("Avisos del PC").setHeaderAction(Action.BACK).build()
        }
        val list = ItemList.Builder()
        for ((title, body) in notes.take(MAX_ROWS)) {
            list.addItem(Row.Builder().setTitle(title).apply { if (body.isNotBlank()) addText(body) }.build())
        }
        return ListTemplate.Builder().setTitle("Avisos del PC").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }

    companion object {
        const val MAX_ROWS = 6
    }
}

/** El estado del PC: procesador, gráfica y memoria. */
class PcScreen(carContext: CarContext, private val backend: CarBackend) : Screen(carContext) {
    init {
        Refresher(this) { backend.snapshot().pc.toString() }
    }

    override fun onGetTemplate(): Template {
        val pc = backend.snapshot().pc ?: return MessageTemplate.Builder("Sin conexión con el PC").setTitle("Estado del PC")
            .setHeaderAction(Action.BACK).build()
        val pane = Pane.Builder()
            .addRow(Row.Builder().setTitle("Procesador").addText("${pc.cpu} %").build())
            .addRow(Row.Builder().setTitle("Memoria").addText("%.1f de %.0f GB".format(pc.ramUsed, pc.ramTotal)).build())
        pc.gpuTemp?.let { pane.addRow(Row.Builder().setTitle("Gráfica").addText("$it °C").build()) }
        return PaneTemplate.Builder(pane.build()).setTitle("Estado del PC").setHeaderAction(Action.BACK).build()
    }
}

/** Los datos del coche que da el lector OBD-II (solo lectura). */
class VehicleScreen(carContext: CarContext, private val backend: CarBackend) : Screen(carContext) {
    init {
        Refresher(this) { backend.snapshot().vehicle.toString() }
    }

    override fun onGetTemplate(): Template {
        val v = backend.snapshot().vehicle ?: return MessageTemplate.Builder("No hay un lector OBD-II conectado.")
            .setTitle("Datos del coche").setHeaderAction(Action.BACK).build()
        val pane = Pane.Builder()
        v.speedKmh?.let { pane.addRow(Row.Builder().setTitle("Velocidad").addText("$it km/h").build()) }
        v.rpm?.let { pane.addRow(Row.Builder().setTitle("Motor").addText("$it rpm").build()) }
        v.coolantC?.let { pane.addRow(Row.Builder().setTitle("Temperatura del motor").addText("$it °C").build()) }
        v.fuelPercent?.let { pane.addRow(Row.Builder().setTitle("Gasolina").addText("$it %").build()) }
        v.volts?.let { pane.addRow(Row.Builder().setTitle("Batería").addText("%.1f V".format(it)).build()) }
        return PaneTemplate.Builder(pane.build()).setTitle("Datos del coche").setHeaderAction(Action.BACK).build()
    }
}

/** «¿Seguro?» antes de un botón del PC que pide confirmación. */
class ConfirmScreen(carContext: CarContext, private val backend: CarBackend, private val item: CarItem) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        MessageTemplate.Builder("¿${item.label}?")
            .setTitle("Confirma")
            .setHeaderAction(Action.BACK)
            .addAction(
                Action.Builder().setTitle("Sí").setOnClickListener {
                    backend.pressAction(item.arg, confirm = true)
                    screenManager.pop()
                }.build(),
            )
            .addAction(Action.Builder().setTitle("No").setOnClickListener { screenManager.pop() }.build())
            .build()
}
