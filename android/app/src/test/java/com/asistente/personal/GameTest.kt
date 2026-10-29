package com.asistente.personal

import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.Telemetry
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.GameCard
import com.asistente.personal.ui.GameScreen
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.SidePage
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui.Vega
import com.asistente.personal.ui.firstSidePage
import com.asistente.personal.ui.hoursLabel
import com.asistente.personal.ui.nextSidePage
import com.asistente.personal.ui.playedLabel
import com.asistente.personal.ui.ratingColor
import com.asistente.personal.ui.sessionClock
import com.asistente.personal.ui.sessionSeconds
import com.asistente.personal.ui.sidePages
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La sección «Juego»: lo que dice el PC, cómo se cuenta la sesión y cómo se ve. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class GameTest {
    @get:Rule val compose = createComposeRule()

    private val game = GameInfo(
        key = "steam:2767030", name = "Marvel Rivals", source = "Steam", played = 3_900.0, hours = 268.2, sizeGb = 92.1,
        art = "steam-2767030",
        summary = "¡Marvel Rivals es un juego de disparos de JcJ por equipos de superhéroes! Reúne un equipo de personajes de Marvel y combina sus poderes.",
        genres = listOf("Acción", "Aventura"), developer = "NetEase Games", released = "5 DIC 2024", rating = 79,
        tags = listOf("Multijugador", "JcJ"),
    )
    private val telemetry = Telemetry(cpu = 34, ramUsed = 23.6, ramTotal = 31.9, gpuTemp = 71, gpuUtil = 96, vramUsed = 6.4, vramTotal = 8.0)

    @Test fun theSessionKeepsCountingOnThePhone() {
        // El PC dijo «lleva 3 900 s» hace 90 s: ahora van 3 990.
        assertEquals(3_990, sessionSeconds(game, receivedAt = 10_000, now = 100_000))
        assertEquals(3_900, sessionSeconds(game, receivedAt = 10_000, now = 9_000)) // un reloj atrasado no resta
    }

    @Test fun timesAreWrittenForPeople() {
        assertEquals("menos de 1 min", playedLabel(40))
        assertEquals("12 min", playedLabel(12 * 60 + 59))
        assertEquals("1 h 05 min", playedLabel(3_900))
        assertEquals("4:09", sessionClock(249))
        assertEquals("1:05:09", sessionClock(3_909))
        assertEquals("45 min", hoursLabel(0.75))
        assertEquals("7,5 h", hoursLabel(7.5))
        assertEquals("268 h", hoursLabel(268.2))
        assertEquals(Vega.Mint, ratingColor(83))
        assertEquals(Vega.Amber, ratingColor(60))
        assertEquals(Vega.Red, ratingColor(30))
    }

    @Test fun theGameEventIsReadWithAndWithoutInformation() {
        val full = Protocol.parse(
            """{"type":"game","active":true,"key":"steam:1","name":"Hollow Knight","source":"Steam","played":65.0,""" +
                """"hours":12.5,"size_gb":5.2,"art":"steam-1","summary":"Un metroidvania.","genres":["Acción"],""" +
                """"developer":"Team Cherry","released":"24 FEB 2017","rating":87,"tags":["Un jugador"]}""",
        ) as ServerEvent.Game
        val g = full.game!!
        assertEquals("Hollow Knight", g.name)
        assertEquals(12.5, g.hours!!, 0.001)
        assertEquals(listOf("Acción"), g.genres)
        assertEquals(87, g.rating)
        // Epic o sin conexión a la tienda: llega solo lo que hay y nada se rompe.
        val lean = (Protocol.parse("""{"type":"game","active":true,"key":"epic:S","name":"Rocket League","source":"Epic","played":3.0,"hours":null,"rating":null}""") as ServerEvent.Game).game!!
        assertNull(lean.hours)
        assertNull(lean.rating)
        assertTrue(lean.genres.isEmpty() && lean.summary.isEmpty())
        assertNull((Protocol.parse("""{"type":"game","active":false}""") as ServerEvent.Game).game)
    }

    @Test fun theGameIsForgottenWhenTheLinkIsLost() {
        var s = reduce(UiState(link = LinkStatus.OPEN), Action.Game(game, at = 7))
        assertEquals(game, s.game)
        assertEquals(7L, s.gameAt)
        s = reduce(s, Action.Link(LinkStatus.RECONNECTING))
        assertNull(s.game) // sin enlace no se sabe si sigue abierto
    }

    @Test fun thePagesAppearWithWhatIsHappening() {
        assertEquals(listOf(SidePage.BODY), sidePages(music = false, game = false))
        assertEquals(listOf(SidePage.BODY, SidePage.MUSIC), sidePages(music = true, game = false))
        assertEquals(listOf(SidePage.BODY, SidePage.MUSIC, SidePage.GAME), sidePages(music = true, game = true))
        assertEquals(SidePage.GAME, firstSidePage(music = true, game = true)) // lo que haces ahora es jugar
        assertEquals(SidePage.MUSIC, firstSidePage(music = true, game = false))
        assertEquals(SidePage.BODY, firstSidePage(music = false, game = false))
        val all = sidePages(true, true)
        assertEquals(SidePage.GAME, nextSidePage(all, SidePage.MUSIC, forward = true))
        assertEquals(SidePage.GAME, nextSidePage(all, SidePage.GAME, forward = true)) // en el extremo se queda
        assertEquals(SidePage.BODY, nextSidePage(all, SidePage.MUSIC, forward = false))
        assertEquals("Cuerpo", SidePage.BODY.label(brainOnPhone = true))
        assertEquals("Juego", SidePage.GAME.label(brainOnPhone = false))
    }

    private fun fakeArt() = android.graphics.Bitmap.createBitmap(460, 215, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 460f, 215f, 0xFFB3122E.toInt(), 0xFF1B2A6B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 460f, 215f, paint)
    }.asImageBitmap()

    private val accent = Color(0xFFE0455B)

    private fun model(ui: UiState, art: androidx.compose.ui.graphics.ImageBitmap? = fakeArt()) = ScreenModel(
        ui = ui, clock = "21:48", date = "miércoles 28 oct", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = true, updateAvailable = false, hasToken = true,
        pulse = 0, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 430000,
    ).copy(gameArt = art, gameAccent = accent, clockMs = { 91_000 })

    @Test fun theMainScreenOpensOnTheGamePageAndTheSwitchHasThreeTabs() {
        val ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", telemetry = telemetry, telemetryAt = 1_000, game = game, gameAt = 1_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(ui), ScreenActions()) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("JUGANDO").assertExists()
        compose.onNodeWithText("Marvel Rivals").assertExists()
        compose.onNodeWithText("Juego").assertExists() // el selector de páginas
        compose.onRoot().captureRoboImage("build/capturas/63_pagina_juego.png")
    }

    @Test fun theSwitchIsHiddenWhenThereIsNothingToSwitchTo() {
        val ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", telemetry = telemetry, telemetryAt = 1_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(ui), ScreenActions()) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("JUGANDO").assertDoesNotExist()
    }

    @Test fun touchingTheCardOpensTheBigScreen() {
        val ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", telemetry = telemetry, telemetryAt = 1_000, game = game, gameAt = 1_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(ui), ScreenActions()) }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Marvel Rivals").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("EN ESTA SESIÓN").assertExists()
        compose.onNodeWithText("Volver").assertExists()
        compose.onRoot().captureRoboImage("build/capturas/64_pantalla_juego.png")
        compose.onNodeWithText("Volver").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("EN ESTA SESIÓN").assertDoesNotExist()
    }

    @Test fun cardWithoutArtShowsTheInitial() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameCard(
                game.copy(name = "Rocket League", source = "Epic"), 125, null, accent, telemetry, onOpen = {},
                modifier = androidx.compose.ui.Modifier.width(250.dp).height(200.dp),
            )
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("R").assertExists()
        compose.onNodeWithText("Epic · 2 min").assertExists()
        compose.onRoot().captureRoboImage("build/capturas/65_juego_sin_caratula.png")
    }

    @Test fun bigScreenWithoutStoreInformationStillShowsWhatSteamKnows() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameScreen(
                game.copy(summary = "", genres = emptyList(), tags = emptyList(), developer = "", released = "", rating = null),
                receivedAt = 0, now = { 0 }, art = null, accent = accent, clock = "21:48", telemetry = null, onClose = {},
            )
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Marvel Rivals").assertExists()
        compose.onNodeWithText("268 h jugadas en total · 92,1 GB en disco").assertExists()
        compose.onRoot().captureRoboImage("build/capturas/66_juego_sin_informacion.png")
    }
}
