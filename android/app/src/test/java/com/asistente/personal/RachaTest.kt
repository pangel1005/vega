package com.asistente.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.StreakInfo
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.NightPal
import com.asistente.personal.ui2.Pal
import com.asistente.personal.ui2.RachaSheet2
import com.asistente.personal.ui2.ThemeMode
import com.asistente.personal.ui2.rachaGrid
import com.asistente.personal.ui2.rachaStatus
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/** La racha del gimnasio: la llama que se enciende, el calendario y la tecla de la fila de abajo. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class RachaTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 12) // jueves
    private val wall = today.atTime(19, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
    private fun d(n: Long) = today.plusDays(n).toString()

    private val lit = StreakInfo(
        count = 5, best = 9, total = 40, today = true, rest = 0, restLeft = 2, alive = true, heat = 1f, week = 3,
        days = listOf(d(-20), d(-19), d(-17), d(-16), d(-14), d(-9), d(-7), d(-5), d(-2), d(0)),
    )
    private val embers = lit.copy(today = false, rest = 2, restLeft = 0, heat = 0.35f, days = lit.days - d(0), count = 4)
    private val out = StreakInfo(count = 0, best = 9, total = 40, alive = false, days = listOf(d(-20), d(-19)))

    @Before fun reset() {
        ThemeMode.mode = 0
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

    private fun model(s: StreakInfo?, link: LinkStatus = LinkStatus.OPEN) = ScreenModel(
        ui = UiState(link = link, assistant = "Vega", state = AssistantState.IDLE, streak = s),
        clock = "19:30", date = "jueves 12 nov", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
        nowMs = 1_000, appVersion = 430000, wallMs = wall,
    ).copy(clockMs = { 1_000 })

    private fun open(m: ScreenModel, a: ScreenActions = ScreenActions(), p: Pal = DayPal, onClose: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { RachaSheet2(m, a, p, onClose, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(300)
    }

    // ── lógica ──

    @Test fun theCalendarIsSixWeeksFromMondayEndingThisWeek() {
        val grid = rachaGrid(today)
        assertEquals(42, grid.size)
        assertEquals(DayOfWeek.MONDAY, grid.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 11, 15), grid.last()) // el domingo de esta semana
        assertTrue(today in grid)
    }

    @Test fun theStatusTalksLikeAPerson() {
        assertEquals("Leyendo tu racha…", rachaStatus(null))
        assertEquals("Toca la llama al volver del gimnasio", rachaStatus(StreakInfo()))
        assertEquals("¡Encendida hoy! Mañana puedes descansar", rachaStatus(lit))
        assertEquals("¡Hoy o se apaga!", rachaStatus(embers))
        assertEquals("Puedes descansar 1 día más", rachaStatus(embers.copy(restLeft = 1)))
        assertEquals("Se apagó · tócala para empezar otra", rachaStatus(out))
    }

    @Test fun theEventIsParsedAndMessagesAreWellFormed() {
        val e = Protocol.parse(
            """{"type":"streak","count":5,"best":9,"total":40,"today":true,"rest":0,"rest_left":2,"alive":true,"heat":1.0,"week":3,
            "days":["2026-11-10","2026-11-12"]}""",
        ) as ServerEvent.Streak
        assertEquals(5, e.streak.count); assertEquals(2, e.streak.restLeft); assertEquals(1f, e.streak.heat, 0f)
        assertEquals(listOf("2026-11-10", "2026-11-12"), e.streak.days)
        val m = JSONObject(Protocol.streak("light"))
        assertEquals("streak", m.getString("type")); assertEquals("light", m.getString("action")); assertFalse(m.has("day"))
        assertEquals("2026-11-10", JSONObject(Protocol.streak("out", "2026-11-10")).getString("day"))
    }

    @Test fun lightingShowsAtOnceAndOutUndoesIt() {
        var s = reduce(UiState(), Action.Server(ServerEvent.Streak(embers)))
        s = reduce(s, Action.StreakDay(d(0), on = true, isToday = true))
        assertTrue(s.streak!!.today); assertEquals(5, s.streak!!.count); assertEquals(1f, s.streak!!.heat, 0f)
        assertTrue(d(0) in s.streak!!.days)
        s = reduce(s, Action.StreakDay(d(0), on = false, isToday = true))
        assertFalse(s.streak!!.today); assertEquals(4, s.streak!!.count)
        s = reduce(s, Action.StreakDay(d(-1), on = true, isToday = false))
        assertTrue(d(-1) in s.streak!!.days); assertEquals(4, s.streak!!.count) // el PC manda la cuenta buena
        assertEquals(1, reduce(UiState(), Action.StreakDay(d(0), true, true)).streak!!.count) // sin lectura aún
    }

    // ── la hoja ──

    @Test fun theSheetShowsTheFlameNumbersAndCalendar() {
        open(model(lit))
        compose.onNodeWithText("Racha").assertIsDisplayed()
        compose.onNodeWithTag("racha-count").assertTextEquals("5")
        compose.onNodeWithText("¡Encendida hoy! Mañana puedes descansar").assertIsDisplayed()
        compose.onNodeWithText("MEJOR").assertIsDisplayed()
        compose.onNodeWithTag("day-${d(0)}").assertIsDisplayed()
        compose.onNodeWithTag("flame-undo").assertIsDisplayed()
    }

    @Test fun tappingTheFlameLightsToday() {
        val calls = mutableListOf<String?>()
        open(model(embers), ScreenActions(streakLight = { calls += it }))
        compose.onNodeWithText("¡Hoy o se apaga!").assertIsDisplayed()
        compose.onNodeWithTag("flame").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf<String?>(null), calls)
    }

    @Test fun whenAlreadyLitTodayTheFlameDoesNotRepeat() {
        val calls = mutableListOf<String?>()
        open(model(lit), ScreenActions(streakLight = { calls += it }))
        compose.onNodeWithTag("flame").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun pastDaysToggleAndFutureOnesDoNot() {
        val lights = mutableListOf<String?>(); val outs = mutableListOf<String?>()
        open(model(lit), ScreenActions(streakLight = { lights += it }, streakOut = { outs += it }))
        compose.onNodeWithTag("day-${d(-1)}").performTouchInput { click() } // ayer estaba apagado: lo enciende
        compose.onNodeWithTag("day-${d(-2)}").performTouchInput { click() } // anteayer ardía: lo apaga
        compose.onNodeWithTag("day-${d(2)}").performTouchInput { click() } // el futuro no se toca
        compose.waitForIdle()
        assertEquals(listOf<String?>(d(-1)), lights)
        assertEquals(listOf<String?>(d(-2)), outs)
    }

    @Test fun withoutLinkNothingCanBeLit() {
        val calls = mutableListOf<String?>()
        open(model(embers, LinkStatus.RECONNECTING), ScreenActions(streakLight = { calls += it }))
        compose.onNodeWithTag("flame").performTouchInput { click() }
        compose.onNodeWithTag("day-${d(-1)}").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun closeButtonCloses() {
        var closed = false
        open(model(lit), onClose = { closed = true })
        compose.onNodeWithTag("racha-close").performTouchInput { click() }
        assertTrue(closed)
    }

    // ── capturas (para mirarlas) ──

    private fun shot(name: String, m: ScreenModel, p: Pal) {
        open(m, p = p)
        compose.mainClock.advanceTimeBy(700) // la llama a media oscilación
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun shotLitDay() = shot("racha_encendida_dia", model(lit), DayPal)

    @Test fun shotLitNight() = shot("racha_encendida_noche", model(lit), NightPal)

    @Test fun shotEmbers() = shot("racha_brasas", model(embers), DayPal)

    @Test fun shotOut() = shot("racha_apagada", model(out), DayPal)
}
