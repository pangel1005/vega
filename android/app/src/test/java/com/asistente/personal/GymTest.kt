package com.asistente.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
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
import com.asistente.personal.net.StreakInfo
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.GymScreen2
import com.asistente.personal.ui2.ThemeMode
import com.asistente.personal.ui2.phase
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZoneId

/** Vega en el gimnasio: la llama de la cara, la pantalla que se enciende y su contador. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class GymTest {
    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 12)
    private val wall = today.atTime(19, 30).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
    private fun d(n: Long) = today.plusDays(n).toString()

    private val asleep = StreakInfo(
        count = 4, best = 9, total = 39, today = false, rest = 1, restLeft = 1, alive = true, heat = 0.6f, week = 2,
        days = listOf(d(-7), d(-5), d(-4), d(-2)),
    )
    private val lit = asleep.copy(count = 5, total = 40, today = true, rest = 0, restLeft = 2, heat = 1f, week = 3, days = asleep.days + d(0))

    @Before fun reset() {
        ThemeMode.mode = 0
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

    private fun model(s: StreakInfo?, link: LinkStatus = LinkStatus.OPEN) = ScreenModel(
        ui = UiState(link = link, assistant = "Vega", state = AssistantState.IDLE, streak = s),
        clock = "19:30", date = "jueves 12 nov", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
        nowMs = 1_000, appVersion = 430000, layout = 10, wallMs = wall,
    ).copy(clockMs = { 1_000 })

    private fun open(m: ScreenModel, a: ScreenActions = ScreenActions(), onClose: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { GymScreen2(m, a, onClose, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(400)
    }

    @Test fun phasesAreSmoothAndClamped() {
        assertEquals(0f, phase(0.1f, 0.3f, 0.6f), 0f)
        assertEquals(1f, phase(0.9f, 0.3f, 0.6f), 0f)
        assertEquals(0.5f, phase(0.45f, 0.3f, 0.6f), 0.001f)
    }

    // ── la llama de la cara ──

    @Test fun theFaceCarriesTheFlameAndItOpensTheGym() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(lit), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("face-racha").assertIsDisplayed()
        compose.onNodeWithTag("gym-screen").assertDoesNotExist()
        compose.onNodeWithTag("face-racha").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("gym-screen").assertIsDisplayed()
        compose.onNodeWithTag("gym-count").assertTextEquals("5")
        compose.onNodeWithTag("gym-close").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("gym-screen").assertDoesNotExist()
    }

    @Test fun theFaceWarnsWhenTodayIsTheLastChance() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(asleep.copy(rest = 2, restLeft = 0, heat = 0.35f)), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithText("¡hoy!").assertIsDisplayed()
    }

    @Test fun thereIsNoDockKeyAnymore() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(lit), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("dock-racha").assertDoesNotExist()
    }

    // ── la pantalla del gimnasio ──

    @Test fun asleepTappingVegaLightsToday() {
        val calls = mutableListOf<String?>()
        open(model(asleep), ScreenActions(streakLight = { calls += it }))
        compose.onNodeWithText("Toca a Vega al volver del gimnasio").assertIsDisplayed()
        compose.onNodeWithTag("gym-count").assertTextEquals("4")
        compose.onNodeWithTag("gym-vega").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf<String?>(null), calls)
    }

    @Test fun litTappingVegaDoesNotCountTwice() {
        val calls = mutableListOf<String?>()
        open(model(lit), ScreenActions(streakLight = { calls += it }))
        compose.onNodeWithText("Toca a Vega al volver del gimnasio").assertDoesNotExist()
        compose.onNodeWithTag("gym-vega").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun withoutLinkVegaCannotBeLit() {
        val calls = mutableListOf<String?>()
        open(model(asleep, LinkStatus.RECONNECTING), ScreenActions(streakLight = { calls += it }))
        compose.onNodeWithText("Sin conexión con el PC").assertIsDisplayed()
        compose.onNodeWithTag("gym-vega").performTouchInput { click() }
        compose.waitForIdle()
        assertTrue(calls.isEmpty())
    }

    @Test fun calendarAndUndo() {
        val outs = mutableListOf<String?>()
        open(model(lit), ScreenActions(streakOut = { outs += it }))
        compose.onNodeWithTag("gym-undo").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf<String?>(null), outs)
        compose.onNodeWithTag("gym-calendar").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("del gimnasio").assertIsDisplayed()
        compose.onNodeWithTag("racha-close").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("del gimnasio").assertDoesNotExist()
    }

    @Test fun closeButtonCloses() {
        var closed = false
        open(model(lit), onClose = { closed = true })
        compose.onNodeWithTag("gym-close").performTouchInput { click() }
        assertTrue(closed)
    }

    // ── capturas: dormida, encendiéndose y encendida ──

    @Test fun shotsOfTheIgnition() {
        val state = mutableStateOf(model(asleep))
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { GymScreen2(state.value, ScreenActions(), {}, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("build/capturas/gym_dormida.png")
        state.value = model(lit) // la toca: se enciende
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().captureRoboImage("build/capturas/gym_encendiendo.png")
        compose.mainClock.advanceTimeBy(2200)
        compose.onRoot().captureRoboImage("build/capturas/gym_encendida.png")
    }

    @Test fun shotOfTheFace() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(lit), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("build/capturas/gym_cara.png")
    }
}
