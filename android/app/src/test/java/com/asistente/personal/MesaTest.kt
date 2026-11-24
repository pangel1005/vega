package com.asistente.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.percentOffset
import androidx.compose.ui.test.swipe
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeskApp
import com.asistente.personal.net.DeskChoice
import com.asistente.personal.net.DeskInfo
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.state.Action
import com.asistente.personal.state.CameraInfo
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.MesaSheet2
import com.asistente.personal.ui2.NightPal
import com.asistente.personal.ui2.Pal
import com.asistente.personal.ui2.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La «Mesa»: volumen por programa, fondo de pantalla y cámara al alcance del dedo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class MesaTest {
    @get:Rule val compose = createComposeRule()

    private val apps = listOf(
        DeskApp("discord", "Discord", 0.8f, false),
        DeskApp("spotify", "Spotify", 0.5f, true),
        DeskApp("navegador", "Navegador", 1f, false),
        DeskApp("fortniteclient-win64-shipping", "Fortnite", 0.6f, false),
    )
    private val desk = DeskInfo(
        apps = apps, wallpaperAvailable = true, wallpaper = "Akane in the rain",
        suggestions = listOf(DeskChoice("1001", "Mountain sunrise"), DeskChoice("1002", "Neon street"), DeskChoice("1003", "Pixel forest")),
    )

    @Before fun reset() { ThemeMode.mode = 0 }

    private fun model(desk: DeskInfo?, link: LinkStatus = LinkStatus.OPEN, camera: CameraInfo = CameraInfo(), thumbs: Map<String, ImageBitmap> = emptyMap()) = ScreenModel(
        ui = UiState(link = link, assistant = "Vega", state = AssistantState.IDLE, desk = desk, camera = camera),
        clock = "14:05", date = "miércoles 4 nov", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
        nowMs = 1_000, appVersion = 430000, wallpaperThumbs = thumbs,
    ).copy(clockMs = { 1_000 })

    private fun open(m: ScreenModel, a: ScreenActions = ScreenActions(), p: Pal = DayPal, onClose: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { MesaSheet2(m, a, p, onClose, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(300)
    }

    // ── el estado ──

    @Test fun theStateComesFromThePc() {
        val s = reduce(UiState(), Action.Server(ServerEvent.DeskState(desk)))
        assertEquals(desk, s.desk)
    }

    @Test fun movingTheVolumeShowsAtOnceAndUnmutesLikeTheMixer() {
        var s = reduce(UiState(), Action.Server(ServerEvent.DeskState(desk)))
        s = reduce(s, Action.DeskVolume("discord", 0.3f))
        assertEquals(0.3f, s.desk!!.apps[0].volume, 0f)
        s = reduce(s, Action.DeskVolume("spotify", 0.4f))
        assertFalse(s.desk!!.apps[1].muted) // subirle el volumen a algo silenciado es querer oírlo
        s = reduce(s, Action.DeskVolume("spotify", 0f))
        s = reduce(s, Action.DeskMute("spotify", true))
        assertTrue(s.desk!!.apps[1].muted)
        assertEquals(1f, s.desk!!.apps[2].volume, 0f) // el resto no se toca
    }

    @Test fun pauseShowsAtOnceAndNothingBreaksWithoutTheDesk() {
        var s = reduce(UiState(), Action.Server(ServerEvent.DeskState(desk)))
        assertTrue(reduce(s, Action.DeskPaused(true)).desk!!.paused)
        s = UiState()
        assertNull(reduce(reduce(reduce(s, Action.DeskVolume("a", 1f)), Action.DeskMute("a", true)), Action.DeskPaused(true)).desk)
    }

    // ── el protocolo ──

    @Test fun theStateEventIsParsed() {
        val e = Protocol.parse(
            """{"type":"desk_state","apps":[{"key":"discord","label":"Discord","volume":0.8,"muted":false},
            {"key":"spotify","label":"Spotify","volume":0.5,"muted":true}],
            "wallpaper":{"available":true,"current":"Akane","paused":true,"suggestions":[{"id":"1001","title":"Mountain"}]}}""",
        ) as ServerEvent.DeskState
        assertEquals(listOf("discord", "spotify"), e.desk.apps.map { it.key })
        assertEquals(0.8f, e.desk.apps[0].volume, 0.001f)
        assertTrue(e.desk.apps[1].muted)
        assertTrue(e.desk.wallpaperAvailable); assertTrue(e.desk.paused)
        assertEquals(listOf(DeskChoice("1001", "Mountain")), e.desk.suggestions)
    }

    @Test fun anEmptyStateIsParsedWithoutBreaking() {
        val e = Protocol.parse("""{"type":"desk_state","apps":[],"wallpaper":{"available":false}}""") as ServerEvent.DeskState
        assertEquals(DeskInfo(), e.desk)
    }

    @Test fun theMessagesToThePcAreWellFormed() {
        val v = JSONObject(Protocol.desk("volume", app = "discord", level = 0.30000001f))
        assertEquals("desk", v.getString("type")); assertEquals("discord", v.getString("app")); assertEquals(0.3, v.getDouble("level"), 0.0)
        assertEquals(1.0, JSONObject(Protocol.desk("volume", app = "x", level = 3f)).getDouble("level"), 0.0)
        assertTrue(JSONObject(Protocol.desk("mute", app = "x", muted = true)).getBoolean("muted"))
        assertEquals("1001", JSONObject(Protocol.desk("wallpaper", id = "1001")).getString("id"))
        assertFalse(JSONObject(Protocol.desk("get")).has("app"))
    }

    // ── la hoja ──

    @Test fun everyProgramAppearsWithItsVolume() {
        open(model(desk))
        listOf("Discord", "Spotify", "Navegador", "Fortnite", "80", "50", "100", "60").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    @Test fun theSheetAsksForTheStateWhenItOpens() {
        var asked = 0
        open(model(desk), ScreenActions(deskRefresh = { asked++ }))
        assertTrue(asked >= 1)
        compose.mainClock.advanceTimeBy(2_100)
        assertTrue(asked >= 2) // y se refresca sola mientras está abierta
    }

    @Test fun tappingTheBarJumpsThereAndReleasingConfirms() {
        val calls = mutableListOf<Triple<String, Float, Boolean>>()
        open(model(desk), ScreenActions(deskVolume = { k, l, f -> calls += Triple(k, l, f) }))
        compose.onNodeWithTag("vol-discord").performTouchInput { click(percentOffset(0.5f, 0.5f)) }
        assertTrue(calls.isNotEmpty())
        val last = calls.last()
        assertEquals("discord", last.first); assertTrue(last.third)
        assertEquals(0.5f, last.second, 0.05f)
        assertTrue(calls.dropLast(1).none { it.third }) // solo el último es «al soltar»
    }

    @Test fun draggingFollowsTheFingerToTheEnds() {
        val calls = mutableListOf<Triple<String, Float, Boolean>>()
        open(model(desk), ScreenActions(deskVolume = { k, l, f -> calls += Triple(k, l, f) }))
        compose.onNodeWithTag("vol-navegador").performTouchInput { swipe(percentOffset(0.1f, 0.5f), percentOffset(0.99f, 0.5f), 300) }
        assertEquals(1f, calls.last().second, 0.02f)
        assertTrue(calls.last().third)
        assertTrue(calls.size > 3) // fue avisando por el camino
        calls.clear()
        compose.onNodeWithTag("vol-navegador").performTouchInput { swipe(percentOffset(0.9f, 0.5f), percentOffset(0.0f, 0.5f), 300) }
        assertEquals(0f, calls.last().second, 0.02f)
    }

    @Test fun touchingOneBarNeverMovesAnother() {
        val calls = mutableListOf<Triple<String, Float, Boolean>>()
        open(model(desk), ScreenActions(deskVolume = { k, l, f -> calls += Triple(k, l, f) }))
        compose.onNodeWithTag("vol-spotify").performTouchInput { click(percentOffset(0.3f, 0.5f)) }
        assertTrue(calls.all { it.first == "spotify" })
    }

    @Test fun muteButtonsToggleEachProgram() {
        val calls = mutableListOf<Pair<String, Boolean>>()
        open(model(desk), ScreenActions(deskMute = { k, m -> calls += k to m }))
        compose.onNodeWithTag("mute-discord").performTouchInput { click() }
        compose.onNodeWithTag("mute-spotify").performTouchInput { click() } // estaba silenciado: lo activa
        assertEquals(listOf("discord" to true, "spotify" to false), calls)
    }

    @Test fun tappingASuggestedWallpaperPutsIt() {
        val picked = mutableListOf<String>()
        open(model(desk), ScreenActions(deskWallpaper = { picked += it }))
        compose.onNodeWithText("Akane in the rain").assertIsDisplayed()
        compose.onNodeWithTag("wall-1002").performTouchInput { click() }
        assertEquals(listOf("1002"), picked)
    }

    @Test fun otherRandomAndPauseButtonsWork() {
        var more = 0; var random = 0; val pauses = mutableListOf<Boolean>()
        open(model(desk), ScreenActions(deskMore = { more++ }, deskRandom = { random++ }, deskPause = { pauses += it }))
        compose.onNodeWithTag("wall-more").performTouchInput { click() }
        compose.onNodeWithTag("wall-random").performTouchInput { click() }
        compose.onNodeWithTag("wall-pause").performTouchInput { click() }
        assertEquals(1, more); assertEquals(1, random); assertEquals(listOf(true), pauses)
    }

    @Test fun whenPausedTheButtonOffersToResume() {
        val pauses = mutableListOf<Boolean>()
        open(model(desk.copy(paused = true)), ScreenActions(deskPause = { pauses += it }))
        compose.onNodeWithText("Seguir").assertIsDisplayed()
        compose.onNodeWithTag("wall-pause").performTouchInput { click() }
        assertEquals(listOf(false), pauses)
    }

    @Test fun theCameraRowIsOnlyThereIfThePcCanDoIt() {
        open(model(desk))
        compose.onNodeWithTag("mesa-camera").assertDoesNotExist()
    }

    @Test fun theCameraCanBeTurnedOnAndTheSideChosen() {
        var toggles = 0; val sides = mutableListOf<String>()
        open(model(desk, camera = CameraInfo(available = true)), ScreenActions(toggleCamera = { toggles++ }, cameraFacing = { sides += it }))
        compose.onNodeWithTag("mesa-camera").performTouchInput { click() }
        compose.onNodeWithText("Trasera").performTouchInput { click() }
        assertEquals(1, toggles); assertEquals(listOf("back"), sides)
    }

    @Test fun closeButtonCloses() {
        var closed = 0
        open(model(desk), onClose = { closed++ })
        compose.onNodeWithTag("mesa-close").performTouchInput { click() }
        assertEquals(1, closed)
    }

    // ── cuando no hay nada que enseñar, se dice la verdad ──

    @Test fun beforeTheFirstReadingItSaysSo() {
        open(model(null))
        compose.onAllNodesWithText("Leyendo el PC…").assertCountEquals(2) // en las dos tarjetas
    }

    @Test fun withoutALinkItSaysThereIsNoConnection() {
        open(model(null, link = LinkStatus.RECONNECTING))
        compose.onAllNodesWithText("Sin conexión con el PC.").assertCountEquals(2)
    }

    @Test fun nothingPlayingAndNoWallpaperEngineAreExplained() {
        open(model(DeskInfo()))
        compose.onNodeWithText("Nada suena en el PC", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Wallpaper Engine no está en el PC.").assertIsDisplayed()
    }

    // ── abrir la Mesa desde la pantalla de inicio ──

    @Test fun theDockHasAMesaKeyThatOpensTheSheet() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(desk), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("VOLUMEN").assertDoesNotExist()
        compose.onNodeWithTag("dock-mesa").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("VOLUMEN").assertIsDisplayed()
        compose.onNodeWithTag("mesa-close").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("VOLUMEN").assertDoesNotExist()
    }

    // ── capturas (para mirarlas) ──

    private fun thumbs(): Map<String, ImageBitmap> = listOf("1001" to 0xFFE08A4A.toInt(), "1002" to 0xFF2D8FD6.toInt(), "1003" to 0xFF5BB36A.toInt()).associate { (id, c) ->
        val bmp = android.graphics.Bitmap.createBitmap(256, 144, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        canvas.drawColor(c)
        canvas.drawCircle(190f, 40f, 26f, android.graphics.Paint().apply { color = 0xFFFFFFFF.toInt() })
        id to bmp.asImageBitmap()
    }

    private fun shot(name: String, m: ScreenModel, p: Pal) {
        open(m, p = p)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun shotDay() = shot("mesa_dia", model(desk, camera = CameraInfo(available = true, on = true), thumbs = thumbs()), DayPal)

    @Test fun shotNight() = shot("mesa_noche", model(desk.copy(paused = true), camera = CameraInfo(available = true), thumbs = thumbs()), NightPal)

    @Test fun shotEmpty() = shot("mesa_vacia", model(DeskInfo()), DayPal)

    @Test fun shotSixPrograms() = shot(
        "mesa_seis",
        model(desk.copy(apps = apps + listOf(DeskApp("whatsapp", "WhatsApp", 0.2f, false), DeskApp("obs", "OBS", 0.9f, false))), thumbs = thumbs()),
        DayPal,
    )
}
