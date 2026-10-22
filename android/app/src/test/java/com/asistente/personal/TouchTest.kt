package com.asistente.personal

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Las teclas y los botones responden al TOCAR (sin esperar a soltar), y solo una vez por toque. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class TouchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `teclas y actualizar saltan al tocar`() {
        val pressed = mutableListOf<String>()
        var updates = 0
        var mic = 0
        val ui = UiState(
            actions = listOf(DeckAction("spotify", "Spotify", "", "app", "green", false, quick = true)),
            link = LinkStatus.OPEN, assistant = "Vega",
        )
        val m = ScreenModel(
            ui = ui, clock = "02:29", date = "jueves 22 oct", state = AssistantState.IDLE, listening = true, micOn = true,
            battery = 91, charging = false, updateAvailable = true, hasToken = true, pulse = 0, pulseAmount = 0f, nowMs = 0,
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VegaScreen(m, ScreenActions(press = { pressed += it }, update = { updates++ }, toggleMic = { mic++ }))
        }
        compose.mainClock.advanceTimeBy(100)

        // Solo apoyar el dedo (sin soltar) ya pulsa.
        compose.onNodeWithText("Spotify").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(16)
        assertEquals(listOf("spotify"), pressed)
        compose.onNodeWithText("Spotify").performTouchInput { up() }
        assertEquals(listOf("spotify"), pressed) // soltar no repite

        compose.onNodeWithText("Actualizar").performTouchInput { down(center); up() }
        assertEquals(1, updates)
        compose.onNodeWithText("Oyendo").performTouchInput { down(center); up() }
        assertEquals(1, mic)
    }
}
