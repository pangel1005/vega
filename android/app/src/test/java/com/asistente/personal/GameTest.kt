package com.asistente.personal

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.GameNews
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.RecentGame
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.Telemetry
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.GameCard
import com.asistente.personal.ui.GameIdleCard
import com.asistente.personal.ui.GameSample
import com.asistente.personal.ui.GameScreen
import com.asistente.personal.ui.GameSection
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.SessionStats
import com.asistente.personal.ui.SidePage
import com.asistente.personal.ui.Vega
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui.agoLabel
import com.asistente.personal.ui.firstSidePage
import com.asistente.personal.ui.friendsLabel
import com.asistente.personal.ui.gameSections
import com.asistente.personal.ui.hoursLabel
import com.asistente.personal.ui.nextSidePage
import com.asistente.personal.ui.playedLabel
import com.asistente.personal.ui.playersLabel
import com.asistente.personal.ui.ratingColor
import com.asistente.personal.ui.sessionClock
import com.asistente.personal.ui.sessionSeconds
import com.asistente.personal.ui.sessionStats
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
    private val accent = Color(0xFFE0455B)

    // --- Lo que se cuenta ---

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

    @Test fun phrasesForPeople() {
        assertEquals("54 309", playersLabel(54_309))
        assertEquals("1 234 567", playersLabel(1_234_567))
        val now = 1_000_000L
        assertEquals("hoy", agoLabel(now - 3_600.0, now))
        assertEquals("ayer", agoLabel(now - 100_000.0, now))
        assertEquals("hace 4 días", agoLabel(now - 4 * 86_400.0, now))
        assertEquals("hace 3 semanas", agoLabel(now - 22 * 86_400.0, now))
        assertEquals("hace 5 meses", agoLabel(now - 150 * 86_400.0, now))
        assertEquals("hoy", agoLabel(now + 500.0, now)) // un reloj adelantado no da días negativos
        assertEquals("", friendsLabel(emptyList()))
        assertEquals("Ana está jugando", friendsLabel(listOf("Ana")))
        assertEquals("Ana y Beto están jugando", friendsLabel(listOf("Ana", "Beto")))
        assertEquals("Ana, Beto y 2 más están jugando", friendsLabel(listOf("Ana", "Beto", "Cris", "Dani")))
    }

    @Test fun theSessionStatsSummarizeTheReadings() {
        assertEquals(SessionStats(null, null, null), sessionStats(emptyList()))
        val s = sessionStats(listOf(GameSample(90, 70, 6.0, 30), GameSample(100, 78, 6.4, 40), GameSample(null, null, null, 20)))
        assertEquals(SessionStats(gpuAvg = 95, tempMax = 78, vramMax = 6.4), s)
    }

    @Test fun sectionsAppearOnlyWhenTheyHaveSomething() {
        val bare = game.copy(players = null, news = emptyList(), friends = emptyList(), achievements = null, achUnlocked = null)
        assertEquals(listOf(GameSection.SESSION, GameSection.GAME), gameSections(bare))
        assertEquals(
            listOf(GameSection.SESSION, GameSection.GAME, GameSection.COMMUNITY, GameSection.ACHIEVEMENTS),
            gameSections(bare.copy(players = 10, achievements = 49)),
        )
    }

    // --- Lo que llega del PC ---

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

    @Test fun theNewFieldsAreRead() {
        val e = Protocol.parse(
            """{"type":"game","active":true,"key":"steam:1","name":"X","source":"Steam","played":1.0,"achievements":49,""" +
                """"ach_unlocked":12,"ach_recent":["Doble baja"],"played_2w":4.3,"players":54309,"friends":["Ana"],""" +
                """"news":[{"title":"Parche","at":1792654321}],"account":true,""" +
                """"recent":[{"name":"Deadlock","art":"steam-1","hours":31.0,"last_played":1793236852}]}""",
        ) as ServerEvent.Game
        val g = e.game!!
        assertEquals(12, g.achUnlocked)
        assertEquals(49, g.achievements)
        assertEquals(54_309, g.players)
        assertEquals(listOf("Ana"), g.friends)
        assertEquals("Parche", g.news.single().title)
        assertEquals(4.3, g.played2w!!, 0.001)
        assertTrue(e.account)
        assertEquals("Deadlock", e.recent.single().name)
        // Sin juego abierto: solo llegan los últimos jugados.
        val idle = Protocol.parse(
            """{"type":"game","active":false,"recent":[{"name":"A","art":"","hours":null,"last_played":5}],"account":false}""",
        ) as ServerEvent.Game
        assertNull(idle.game)
        assertNull(idle.recent.single().hours)
    }

    @Test fun theGameIsForgottenWhenTheLinkIsLost() {
        var s = reduce(UiState(link = LinkStatus.OPEN), Action.Game(game, at = 7))
        assertEquals(game, s.game)
        assertEquals(7L, s.gameAt)
        s = reduce(s, Action.Link(LinkStatus.RECONNECTING))
        assertNull(s.game) // sin enlace no se sabe si sigue abierto
    }

    @Test fun thePagesAreFixedExceptMusic() {
        // «Juego» es fija: sin juego abierto enseña «Sin juegos ahora mismo»; la música solo aparece mientras suena algo.
        assertEquals(listOf(SidePage.BODY, SidePage.GAME), sidePages(music = false))
        assertEquals(listOf(SidePage.BODY, SidePage.MUSIC, SidePage.GAME), sidePages(music = true))
        assertEquals(SidePage.GAME, firstSidePage(music = true, game = true)) // lo que haces ahora es jugar
        assertEquals(SidePage.MUSIC, firstSidePage(music = true, game = false))
        assertEquals(SidePage.BODY, firstSidePage(music = false, game = false))
        val all = sidePages(true)
        assertEquals(SidePage.GAME, nextSidePage(all, SidePage.MUSIC, forward = true))
        assertEquals(SidePage.GAME, nextSidePage(all, SidePage.GAME, forward = true)) // en el extremo se queda
        assertEquals(SidePage.BODY, nextSidePage(all, SidePage.MUSIC, forward = false))
        assertEquals("Cuerpo", SidePage.BODY.label(brainOnPhone = true))
        assertEquals("Juego", SidePage.GAME.label(brainOnPhone = false))
    }

    // --- Cómo se ve ---

    private fun fakeArt() = android.graphics.Bitmap.createBitmap(460, 215, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 460f, 215f, 0xFFB3122E.toInt(), 0xFF1B2A6B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 460f, 215f, paint)
    }.asImageBitmap()

    private fun model(ui: UiState, art: ImageBitmap? = fakeArt()) = ScreenModel(
        ui = ui, clock = "21:48", date = "miércoles 28 oct", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = true, updateAvailable = false, hasToken = true,
        pulse = 0, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 430000,
    ).copy(gameArt = art, gameAccent = accent, clockMs = { 91_000 })

    @Test fun theMainScreenOpensOnTheGamePageAndTheSwitchHasTabs() {
        val ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", telemetry = telemetry, telemetryAt = 1_000, game = game, gameAt = 1_000)
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(ui), ScreenActions()) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithText("JUGANDO").assertExists()
        compose.onNodeWithText("Marvel Rivals").assertExists()
        compose.onNodeWithText("Juego").assertExists() // el selector de páginas
        compose.onRoot().captureRoboImage("build/capturas/63_pagina_juego.png")
    }

    @Test fun withoutAGameTheTabStaysAndSaysSo() {
        val recent = listOf(
            RecentGame("Marvel Rivals", "steam-2767030", 268.2, 1_793_165_618.0),
            RecentGame("Deadlock", "steam-1422450", 31.0, 1_793_236_852.0),
            RecentGame("Overwatch", "", null, 1_793_156_017.0),
        )
        val ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", telemetry = telemetry, telemetryAt = 1_000, recentGames = recent)
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(ui).copy(recentArts = mapOf("steam-2767030" to fakeArt())), ScreenActions()) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("JUGANDO").assertDoesNotExist()
        compose.onNodeWithText("Juego").assertExists() // la pestaña sigue ahí
        compose.onNodeWithText("Juego").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Sin juegos ahora mismo").assertExists()
        compose.onNodeWithText("Deadlock").assertExists()
        compose.onRoot().captureRoboImage("build/capturas/67_sin_juegos.png")
    }

    @Test fun idleCardWithoutAnyHistoryInvitesToOpenAGame() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameIdleCard(emptyList(), emptyMap(), 1_793_300_000, androidx.compose.ui.Modifier.width(250.dp).height(200.dp))
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Sin juegos ahora mismo").assertExists()
        compose.onNodeWithText("Abre uno en Steam o Epic").assertExists()
        compose.onRoot().captureRoboImage("build/capturas/68_sin_juegos_vacio.png")
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

    private val rich = game.copy(
        achievements = 49, achUnlocked = 12, achRecent = listOf("Doble baja", "Primera sangre"), played2w = 4.3, players = 54_309,
        friends = listOf("Ana", "Beto"),
        news = listOf(
            GameNews("Marvel Rivals Version 20261022 Patch Notes", 1_792_654_321.0),
            GameNews("Art Vision Vol. 06 // Behind The Scenes", 1_792_751_439.0),
        ),
    )
    private val samples = (0 until 120).map { GameSample(gpu = 70 + (it * 7) % 30, temp = 55 + it / 8, vram = 6.0 + (it % 5) * 0.1, cpu = 30 + it % 15) }
    private val handy = listOf("captura", "grabar", "discord", "silenciar")
        .map { DeckAction(it, it.replaceFirstChar { c -> c.uppercase() }, "", "app", "orange", false) }

    private fun bigScreen(section: GameSection, name: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameScreen(
                rich, receivedAt = 0, now = { 0 }, art = fakeArt(), accent = accent, clock = "21:48", telemetry = telemetry, onClose = {},
                samples = samples, actions = handy, account = true, wallSeconds = 1_792_900_000, startSection = section,
            )
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun bigScreenSession() {
        bigScreen(GameSection.SESSION, "69_sesion")
        compose.onNodeWithText("Captura").assertExists()
        compose.onNodeWithText("últimas 2 semanas: 4,3 h", substring = true).assertExists()
    }

    @Test fun bigScreenCommunity() {
        bigScreen(GameSection.COMMUNITY, "70_comunidad")
        compose.onNodeWithText("54 309").assertExists()
        compose.onNodeWithText("Ana y Beto están jugando").assertExists()
    }

    @Test fun bigScreenAchievements() {
        bigScreen(GameSection.ACHIEVEMENTS, "71_logros")
        compose.onNodeWithText("★ Doble baja").assertExists()
    }

    @Test fun achievementsWithoutAccountSayHowToConnect() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameScreen(
                game.copy(achievements = 49), receivedAt = 0, now = { 0 }, art = null, accent = accent, clock = "21:48",
                telemetry = null, onClose = {}, account = false, startSection = GameSection.ACHIEVEMENTS,
            )
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("49 logros en total").assertExists()
        compose.onNodeWithText("Conecta tu cuenta de Steam", substring = true).assertExists()
    }

    @Test fun pressingAHandyButtonSendsItsId() {
        val pressed = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameScreen(rich, 0, { 0 }, null, accent, "21:48", null, onClose = {}, actions = handy, onPress = { pressed += it })
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Captura").performTouchInput { down(center); up() }
        assertEquals(listOf("captura"), pressed)
    }

    @Test fun bigScreenWithoutStoreInformationStillShowsWhatSteamKnows() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            GameScreen(
                game.copy(summary = "", genres = emptyList(), tags = emptyList(), developer = "", released = "", rating = null),
                receivedAt = 0, now = { 0 }, art = null, accent = accent, clock = "21:48", telemetry = null, onClose = {},
                startSection = GameSection.GAME,
            )
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Marvel Rivals").assertExists()
        compose.onNodeWithText("268 h jugadas en total · 92,1 GB en disco").assertExists()
        compose.onRoot().captureRoboImage("build/capturas/66_juego_sin_informacion.png")
    }
}
