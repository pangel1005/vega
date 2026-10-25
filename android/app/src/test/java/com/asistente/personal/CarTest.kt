package com.asistente.personal

import androidx.car.app.OnDoneCallback
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.asistente.personal.car.CarBackend
import com.asistente.personal.car.CarItem
import com.asistente.personal.car.CarPage
import com.asistente.personal.car.CarPc
import com.asistente.personal.car.CarSnapshot
import com.asistente.personal.car.CarTap
import com.asistente.personal.car.DEFAULT_CAR_ITEMS
import com.asistente.personal.car.NotificationsScreen
import com.asistente.personal.car.PcScreen
import com.asistente.personal.car.VegaCarScreen
import com.asistente.personal.car.VehicleReading
import com.asistente.personal.car.VehicleScreen
import com.asistente.personal.car.subtitle
import com.asistente.personal.car.tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Un Vega de mentira para el coche: guarda lo que se le pide. */
private class FakeBackend(
    var snap: CarSnapshot = CarSnapshot(online = true),
    var items: List<CarItem> = DEFAULT_CAR_ITEMS,
    private val confirming: Set<String> = emptySet(),
) : CarBackend {
    val calls = mutableListOf<String>()
    override fun snapshot() = snap
    override fun layout() = "Vega" to items
    override fun toggleSleep() { calls += "sleep" }
    override fun toggleBrain() { calls += "brain" }
    override fun mediaPlayPause() { calls += "media" }
    override fun mediaNext() { calls += "next" }
    override fun needsConfirm(actionId: String) = actionId in confirming
    override fun pressAction(actionId: String, confirm: Boolean) { calls += "action:$actionId:$confirm" }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CarTest {
    private val context get() = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())

    private fun click(item: GridItem) =
        item.onClickDelegate!!.sendClick(object : OnDoneCallback {
            override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}
            override fun onFailure(response: androidx.car.app.serialization.Bundleable) {}
        })

    // --- Lo que dice cada casilla ---

    @Test fun `cada casilla dice como esta Vega`() {
        val items = DEFAULT_CAR_ITEMS.associateBy { it.arg }
        val s = CarSnapshot(
            online = true, sleeping = true, brainOnPhone = true, media = "Ricky Bobby · Eladio", playing = true,
            notes = listOf("Ana" to "hola", "Luis" to "partida"), pc = CarPc(21, 62, 14.2, 32.0),
        )
        assertEquals("Dormida · toca para despertar", subtitle(items.getValue("sleep"), s))
        assertEquals("Despierta", subtitle(items.getValue("sleep"), s.copy(sleeping = false)))
        assertEquals("Piensa en el móvil", subtitle(items.getValue("brain"), s))
        assertEquals("Suena: Ricky Bobby · Eladio", subtitle(items.getValue("media"), s))
        assertEquals("En pausa: Ricky Bobby · Eladio", subtitle(items.getValue("media"), s.copy(playing = false)))
        assertEquals("Nada suena", subtitle(items.getValue("media"), CarSnapshot()))
        assertEquals("2 avisos", subtitle(items.getValue("notifications"), s))
        assertEquals("Sin avisos", subtitle(items.getValue("notifications"), CarSnapshot()))
        assertEquals("Procesador 21 % · gráfica 62 °", subtitle(items.getValue("pc"), s))
        assertEquals("Sin PC", subtitle(items.getValue("pc"), CarSnapshot()))
        val vehicle = CarItem("coche", "Coche", "builtin", "vehicle")
        assertEquals("Sin lector OBD", subtitle(vehicle, CarSnapshot()))
        assertEquals("87 km/h · 2100 rpm · 90 °C", subtitle(vehicle, s.copy(vehicle = VehicleReading(87, 2100, 90))))
    }

    // --- Lo que hace cada casilla ---

    @Test fun `pulsar una casilla hace lo suyo y nada mas`() {
        val b = FakeBackend()
        assertEquals(CarTap.Done, tap(CarItem("a", "A", "builtin", "sleep"), b))
        assertEquals(CarTap.Done, tap(CarItem("b", "B", "builtin", "brain"), b))
        assertEquals(CarTap.Done, tap(CarItem("c", "C", "builtin", "media"), b))
        assertEquals(CarTap.Done, tap(CarItem("d", "D", "builtin", "next"), b))
        assertEquals(CarTap.Open(CarPage.NOTIFICATIONS), tap(CarItem("e", "E", "builtin", "notifications"), b))
        assertEquals(CarTap.Open(CarPage.PC), tap(CarItem("f", "F", "builtin", "pc"), b))
        assertEquals(CarTap.Open(CarPage.VEHICLE), tap(CarItem("g", "G", "builtin", "vehicle"), b))
        assertEquals(CarTap.Done, tap(CarItem("h", "H", "builtin", "hackear"), b)) // desconocida: nada
        assertEquals(listOf("sleep", "brain", "media", "next"), b.calls)
    }

    @Test fun `un boton del PC que pide confirmacion no se pulsa sin preguntar`() {
        val b = FakeBackend(confirming = setOf("apagar"))
        val safe = CarItem("v", "Volumen +", "action", "volumen-mas")
        val risky = CarItem("p", "Apagar PC", "action", "apagar")
        assertEquals(CarTap.Done, tap(safe, b))
        assertEquals(CarTap.Confirm(risky), tap(risky, b))
        assertEquals(listOf("action:volumen-mas:false"), b.calls) // el de apagar aun no
    }

    // --- Las pantallas ---

    @Test fun `la pantalla principal es una cuadricula con las casillas y su estado`() {
        val b = FakeBackend(CarSnapshot(online = true, media = "Canción", playing = true))
        val screen = VegaCarScreen(context, b)
        ScreenController(screen).moveToState(Lifecycle.State.STARTED)
        val template = screen.onGetTemplate() as GridTemplate
        val items = template.singleList!!.items.map { it as GridItem }
        assertEquals("Vega", template.title.toString())
        assertEquals(DEFAULT_CAR_ITEMS.map { it.label }, items.map { it.title.toString() })
        assertEquals("Suena: Canción", items[1].text.toString())
        click(items[0]) // «Dormir»
        click(items[1]) // «Pausa / Play»
        assertEquals(listOf("sleep", "media"), b.calls)
    }

    @Test fun `no caben mas de seis casillas`() {
        val many = (1..9).map { CarItem("i$it", "Casilla $it", "builtin", "sleep") }
        val screen = VegaCarScreen(context, FakeBackend(items = many))
        ScreenController(screen).moveToState(Lifecycle.State.STARTED)
        assertEquals(6, (screen.onGetTemplate() as GridTemplate).singleList!!.items.size)
    }

    @Test fun `las casillas de avisos y estado abren su pagina`() {
        val b = FakeBackend(CarSnapshot(online = true, notes = listOf("Ana" to "¿Vienes?"), pc = CarPc(40, null, 10.0, 32.0)))
        val ctx = context
        val main = VegaCarScreen(ctx, b)
        val controller = ScreenController(main)
        controller.moveToState(Lifecycle.State.STARTED)
        val items = (main.onGetTemplate() as GridTemplate).singleList!!.items.map { it as GridItem }
        click(items[3]) // Avisos del PC
        click(items[4]) // Estado del PC
        val pushed = ctx.getCarService(androidx.car.app.testing.TestScreenManager::class.java).screensPushed
        assertEquals(listOf(VegaCarScreen::class.java, NotificationsScreen::class.java, PcScreen::class.java), pushed.map { it.javaClass }) // la principal y las dos páginas
        val notes = NotificationsScreen(ctx, b).onGetTemplate() as ListTemplate
        assertEquals("Ana", (notes.singleList!!.items[0] as androidx.car.app.model.Row).title.toString())
        val pc = PcScreen(ctx, b).onGetTemplate() as PaneTemplate
        assertEquals(2, pc.pane.rows.size) // sin temperatura de la gráfica: solo procesador y memoria
    }

    @Test fun `sin avisos, sin PC y sin lector OBD se dice claro`() {
        val ctx = context
        val empty = FakeBackend(CarSnapshot())
        assertTrue(NotificationsScreen(ctx, empty).onGetTemplate() is MessageTemplate)
        assertTrue(PcScreen(ctx, empty).onGetTemplate() is MessageTemplate)
        assertTrue(VehicleScreen(ctx, empty).onGetTemplate() is MessageTemplate)
        val vehicle = VehicleScreen(ctx, FakeBackend(CarSnapshot(vehicle = VehicleReading(speedKmh = 50, coolantC = 88))))
        assertEquals(2, (vehicle.onGetTemplate() as PaneTemplate).pane.rows.size)
    }
}
