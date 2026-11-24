package com.asistente.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Telemetry
import com.asistente.personal.state.CameraInfo
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.LocalRailInset
import com.asistente.personal.ui2.PcHistory
import com.asistente.personal.ui2.RailSide
import com.asistente.personal.ui2.pcCalm
import com.asistente.personal.ui2.railSide
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** El riel de estado (en la franja que deja el hueco de la cámara) y el mosaico del PC que se compacta cuando todo va bien. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class RailAndPcTest {
    @get:Rule val compose = createComposeRule()

    private val calm = Telemetry(cpu = 21, ramUsed = 12.0, ramTotal = 32.0, gpuTemp = 47, gpuUtil = 12, vramUsed = 5.7, vramTotal = 8.0)

    private fun open(ui: UiState, battery: Int = 91, charging: Boolean = false, micOn: Boolean = true, rail: Boolean = true) {
        val model = ScreenModel(
            ui = ui, clock = "14:05", date = "miércoles 4 nov", state = AssistantState.IDLE, listening = true, micOn = micOn,
            battery = battery, charging = charging, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
            nowMs = 1_000, appVersion = 430000,
        ).copy(clockMs = { 1_000 })
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalRailInset provides if (rail) 47.dp else null) { VegaScreen(model, ScreenActions()) }
        }
        compose.mainClock.advanceTimeBy(900)
    }

    private fun linked(t: Telemetry? = calm, game: GameInfo? = null, camera: CameraInfo = CameraInfo(true, false, "front")) =
        UiState(link = LinkStatus.OPEN, assistant = "Vega", state = AssistantState.IDLE, telemetry = t, telemetryAt = 1_000, camera = camera, game = game)

    // ── dónde cabe el riel ──

    @Test fun theRailGoesOnTheSideWithTheCameraCutout() {
        assertEquals(RailSide.END to 47.dp, railSide(0.dp, 47.dp))
        assertEquals(RailSide.START to 47.dp, railSide(47.dp, 0.dp)) // el móvil girado 180°
        assertEquals(RailSide.END to 47.dp, railSide(47.dp, 47.dp))
        assertNull(railSide(0.dp, 0.dp)) // sin hueco, no hay riel
        assertNull(railSide(12.dp, 20.dp)) // una franja demasiado fina
    }

    // ── el riel ──

    @Test fun theRailShowsLinkCameraMicAndBattery() {
        open(linked(camera = CameraInfo(true, true, "front")))
        compose.onNodeWithContentDescription("Conexión con el PC: en línea").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cámara encendida").assertIsDisplayed()
        compose.onNodeWithContentDescription("Micrófono abierto").assertIsDisplayed()
        compose.onNodeWithContentDescription("Batería del teléfono 91 %").assertIsDisplayed()
    }

    @Test fun theRailTellsWhenTheMicAndCameraAreOff() {
        open(linked(), micOn = false)
        compose.onNodeWithContentDescription("Cámara apagada").assertIsDisplayed()
        compose.onNodeWithContentDescription("Micrófono apagado").assertIsDisplayed()
    }

    @Test fun theRailReportsAProblemWithThePc() {
        open(UiState(link = LinkStatus.RECONNECTING, assistant = "Vega", state = AssistantState.IDLE))
        compose.onNodeWithContentDescription("Conexión con el PC: reconectando").assertIsDisplayed()
    }

    @Test fun theBatteryTellsWhenItIsCharging() {
        open(linked(), battery = 40, charging = true)
        compose.onNodeWithContentDescription("Batería del teléfono 40 %, cargando").assertIsDisplayed()
    }

    @Test fun withoutACutoutStripThereIsNoRail() {
        open(linked(), rail = false) // el simulador no tiene hueco de cámara
        compose.onNodeWithContentDescription("Batería del teléfono 91 %").assertDoesNotExist()
    }

    // ── el mosaico del PC ──

    @Test fun calmMeansNoGameCoolGpuAndRoomInVideoMemory() {
        assertTrue(pcCalm(calm, gaming = false))
        assertFalse(pcCalm(calm, gaming = true))
        assertFalse(pcCalm(null, gaming = false))
        assertFalse(pcCalm(calm.copy(cpu = 70), false))
        assertFalse(pcCalm(calm.copy(gpuTemp = 75), false))
        assertFalse(pcCalm(calm.copy(vramUsed = 7.3), false)) // 91 % del vídeo
        assertTrue(pcCalm(calm.copy(vramUsed = 7.1), false))
        assertTrue(pcCalm(calm.copy(gpuTemp = null, vramUsed = null, vramTotal = null), false))
    }

    @Test fun whenAllIsWellItSaysSoInsteadOfDrawingFourBars() {
        open(linked())
        compose.onNodeWithText("PC bien").assertIsDisplayed()
        compose.onNodeWithText("21 %").assertIsDisplayed()
        compose.onNodeWithText("GPU 47° · vídeo 5,7 de 8 GB").assertIsDisplayed()
        compose.onNodeWithText("CPU").assertDoesNotExist()
    }

    @Test fun whenSomethingRisesTheBarsComeBackShowingWhich() {
        open(linked(calm.copy(gpuTemp = 82)))
        compose.onNodeWithText("PC bien").assertDoesNotExist()
        compose.onNodeWithText("CPU").assertIsDisplayed()
        compose.onNodeWithText("82°").assertIsDisplayed()
    }

    @Test fun whilePlayingTheBarsStayEvenIfAllIsCool() {
        val game = GameInfo(
            key = "steam:730", name = "Counter-Strike 2", source = "Steam", played = 3_900.0, hours = 10.0, sizeGb = 33.0, art = "x",
            summary = "", genres = listOf("Acción"), developer = "Valve", released = "2012", rating = 86, tags = listOf("Multijugador"),
        )
        open(linked(game = game))
        compose.onNodeWithText("PC bien").assertDoesNotExist()
        compose.onNodeWithText("USO").assertIsDisplayed()
    }

    // ── capturas (para mirarlas) ──

    private fun shot(name: String, ui: UiState, clock: String = "21:48", charging: Boolean = false, battery: Int = 94) {
        val model = ScreenModel(
            ui = ui, clock = clock, date = "miércoles 4 nov", state = AssistantState.IDLE, listening = true, micOn = true,
            battery = battery, charging = charging, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
            nowMs = 1_000, appVersion = 430000,
        ).copy(clockMs = { 1_000 })
        compose.mainClock.autoAdvance = false
        compose.setContent { CompositionLocalProvider(LocalRailInset provides 47.dp) { VegaScreen(model, ScreenActions()) } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun shotNightCalm() = shot("N_riel_pc_bien", linked(camera = CameraInfo(true, true, "front")))
    @Test fun shotDayCalmCharging() = shot("D_riel_pc_bien", linked(), clock = "14:05", charging = true, battery = 62)
    @Test fun shotNightAlert() = shot("N_riel_pc_alerta", linked(calm.copy(gpuTemp = 82, vramUsed = 7.6)), battery = 12)
    @Test fun shotDayOffline() = shot("D_riel_sin_enlace", UiState(link = LinkStatus.RECONNECTING, retryInMs = 4_000, assistant = "Vega", state = AssistantState.IDLE), clock = "14:05")

    @Test fun shotSparkline() {
        val history = PcHistory().apply {
            val cpu = listOf(12, 15, 14, 22, 38, 31, 24, 19, 17, 21, 26, 33, 29, 22, 18, 16, 21, 24, 20, 21)
            val gpu = listOf(2, 3, 2, 5, 40, 58, 61, 35, 12, 8, 6, 22, 45, 30, 12, 9, 7, 12, 10, 12)
            cpu.indices.forEach { add(cpu[it], gpu[it]) }
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.background(com.asistente.personal.ui2.NightPal.bg).padding(16.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            ) {
                com.asistente.personal.ui2.PcCalm(com.asistente.personal.ui2.NightPal, calm, history, androidx.compose.ui.Modifier.size(223.dp, 122.dp))
                com.asistente.personal.ui2.PcCalm(com.asistente.personal.ui2.DayPal, calm, history, androidx.compose.ui.Modifier.size(223.dp, 122.dp))
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/capturas/riel_sparkline.png")
    }

    @Test fun theHistoryKeepsOnlyTheLastReadings() {
        val h = PcHistory(max = 3)
        for (i in 1..5) h.add(i * 10, i)
        assertEquals(listOf(30, 40, 50), h.cpu.toList())
        assertEquals(listOf(3, 4, 5), h.gpu.toList())
    }
}
