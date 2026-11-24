package com.asistente.personal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.asistente.personal.net.AssistantState
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** El icono de cámara en la cara de Vega: aparece si el PC puede, se enciende y se apaga con un toque. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class CameraUiTest {
    @get:Rule val compose = createComposeRule()

    private fun open(camera: CameraInfo, actions: ScreenActions = ScreenActions()) {
        val ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", state = AssistantState.IDLE, camera = camera)
        val model = ScreenModel(
            ui = ui, clock = "14:05", date = "martes 3 nov", state = AssistantState.IDLE, listening = true, micOn = true,
            battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
            nowMs = 1_000, appVersion = 430000,
        ).copy(clockMs = { 1_000 })
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model, actions) }
        compose.mainClock.advanceTimeBy(900)
    }

    @Test fun theIconIsNotShownIfThePcCannotDoIt() {
        open(CameraInfo(available = false))
        compose.onNodeWithContentDescription("Encender cámara").assertDoesNotExist()
    }

    @Test fun tappingTheIconTurnsTheCameraOn() {
        var taps = 0
        open(CameraInfo(available = true, on = false), ScreenActions(toggleCamera = { taps++ }))
        compose.onNodeWithContentDescription("Encender cámara").assertIsDisplayed()
        compose.onNodeWithContentDescription("Encender cámara").performTouchInput { click() }
        assertEquals(1, taps)
    }

    @Test fun whenOnTheIconOffersToTurnItOff() {
        open(CameraInfo(available = true, on = true))
        compose.onNodeWithContentDescription("Apagar cámara").assertIsDisplayed()
    }

    @Test fun theStateComesFromThePcAndBusyClearsWhenItAnswers() {
        var s = UiState()
        assertFalse(s.camera.available)
        s = reduce(s, Action.Server(ServerEvent.CameraState(true, false, "front")))
        assertEquals(CameraInfo(available = true, on = false, facing = "front"), s.camera)
        s = reduce(s, Action.CameraBusy)
        assertTrue(s.camera.busy)
        s = reduce(s, Action.Server(ServerEvent.CameraState(true, true, "back")))
        assertEquals(CameraInfo(available = true, on = true, facing = "back", busy = false), s.camera)
    }

    @Test fun theMessagesToThePcCarryTheSide() {
        val on = JSONObject(Protocol.camera(true, "back"))
        assertEquals("camera", on.getString("type")); assertTrue(on.getBoolean("on")); assertEquals("back", on.getString("facing"))
        val off = JSONObject(Protocol.camera(false))
        assertFalse(off.getBoolean("on")); assertFalse(off.has("facing"))
    }

    @Test fun theStateEventIsParsed() {
        val e = Protocol.parse("""{"type":"camera_state","available":true,"on":true,"facing":"back"}""")
        assertEquals(ServerEvent.CameraState(true, true, "back"), e)
    }
}
