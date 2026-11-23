package com.asistente.personal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.MediaInfo
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.MusicScreen2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Toques simulados sobre el diseño nuevo: el mosaico de la derecha se desliza con precisión (un arrastre largo cambia de página,
 * un roce no, un empujón rápido sí) y la barra de la canción salta donde se toca y busca donde se suelta.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class Touch2Test {
    @get:Rule val compose = createComposeRule()

    private val song = MediaInfo("Spotify", "Human Nature", "Madonna", "Bedtime Stories", true, 197.0, 293.9, "abc", true, true)
    private val base = UiState(
        actions = listOf(DeckAction("spotify", "Spotify", "", "app", "green", false, quick = true)),
        link = LinkStatus.OPEN, assistant = "Vega", state = AssistantState.IDLE,
        telemetry = Telemetry(cpu = 21, ramUsed = 1.0, ramTotal = 2.0, gpuTemp = 62, gpuUtil = 12, vramUsed = 7.0, vramTotal = 8.0, upcoming = listOf(Upcoming("timer", "temporizador", 299))),
        telemetryAt = 1_000,
    )

    private fun model(ui: UiState, actions: ScreenActions = ScreenActions()) = ScreenModel(
        ui = ui, clock = "14:05", date = "jueves 22 oct", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 430000, layout = 10,
    ).copy(clockMs = { 1_000 }) to actions

    private fun open(ui: UiState, actions: ScreenActions = ScreenActions()) {
        val (m, a) = model(ui, actions)
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(m, a) }
        compose.mainClock.advanceTimeBy(900)
    }

    // ── el mosaico «ahora» ──

    @Test fun aLongDragMovesToTheNextPage() {
        open(base)
        compose.onNodeWithText("PRÓXIMO AVISO").assertIsDisplayed()
        compose.onRoot().performTouchInput { swipe(Offset(1450f, 420f), Offset(900f, 420f), 250) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("Sin juegos ahora mismo").assertIsDisplayed()
    }

    @Test fun aSmallGrazeDoesNotMoveThePage() {
        open(base)
        compose.onRoot().performTouchInput { swipe(Offset(1300f, 420f), Offset(1265f, 420f), 250) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("PRÓXIMO AVISO").assertIsDisplayed()
    }

    @Test fun aQuickFlickMovesEvenIfShort() {
        open(base)
        compose.onRoot().performTouchInput { swipe(Offset(1400f, 420f), Offset(1250f, 420f), 70) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("Sin juegos ahora mismo").assertIsDisplayed()
    }

    @Test fun draggingBackComesBack() {
        open(base)
        compose.onRoot().performTouchInput { swipe(Offset(1450f, 420f), Offset(900f, 420f), 250) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().performTouchInput { swipe(Offset(900f, 420f), Offset(1450f, 420f), 250) }
        compose.mainClock.advanceTimeBy(1_200)
        compose.onNodeWithText("PRÓXIMO AVISO").assertIsDisplayed()
    }

    // ── la barra de la canción en el mosaico ──

    @Test fun tappingTheTileBarSeeksThere() {
        val seeks = mutableListOf<Double>()
        open(base.copy(media = song, mediaAt = 1_000), ScreenActions(mediaSeek = { seeks.add(it) }))
        compose.onNodeWithTag("seek", useUnmergedTree = true).performTouchInput { click(percentOffset(0.5f, 0.5f)) }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(1, seeks.size)
        assertTrue("saltó a ${seeks[0]} y debía ser ~147", seeks[0] in 137.0..157.0)
    }

    @Test fun draggingTheTileBarSeeksWhereItIsReleasedAndDoesNotChangePage() {
        val seeks = mutableListOf<Double>()
        open(base.copy(media = song, mediaAt = 1_000), ScreenActions(mediaSeek = { seeks.add(it) }))
        compose.onNodeWithTag("seek", useUnmergedTree = true).performTouchInput { swipe(percentOffset(0.1f, 0.5f), percentOffset(0.8f, 0.5f), 300) }
        compose.mainClock.advanceTimeBy(1_200)
        assertEquals(1, seeks.size) // un solo salto, al soltar
        assertTrue("buscó ${seeks[0]} y debía ser ~235", seeks[0] in 225.0..245.0)
        compose.onNodeWithText("Human Nature").assertIsDisplayed() // la página no se movió
    }

    // ── la barra en la pantalla de música ──

    @Test fun tappingAndDraggingTheMusicScreenBar() {
        val seeks = mutableListOf<Double>()
        val a = ScreenActions(mediaSeek = { seeks.add(it) })
        val (m, _) = model(base.copy(media = song, mediaAt = 1_000))
        compose.mainClock.autoAdvance = false
        compose.setContent { MusicScreen2(m, a, DayPal) {} }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("seek").performTouchInput { click(percentOffset(0.25f, 0.5f)) }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithTag("seek").performTouchInput { swipe(percentOffset(0.2f, 0.5f), percentOffset(0.9f, 0.5f), 300) }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(2, seeks.size)
        assertTrue("toque: ${seeks[0]} ~73", seeks[0] in 65.0..82.0)
        assertTrue("arrastre: ${seeks[1]} ~264", seeks[1] in 254.0..274.0)
    }
}
