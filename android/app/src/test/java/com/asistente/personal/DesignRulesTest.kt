package com.asistente.personal

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.CameraInfo
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.NightPal
import com.asistente.personal.ui2.Pal
import com.asistente.personal.ui2.contrast
import com.asistente.personal.ui2.readable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Reglas del diseño que no se pueden romper sin querer: el texto se lee (contraste WCAG 4,5), los botones se pueden pulsar con el
 * dedo (48 dp), dormir a Vega no ocurre por un roce y la pantalla no miente cuando no hay conexión.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class DesignRulesTest {
    @get:Rule val compose = createComposeRule()

    // ── contraste ──

    private fun grounds(p: Pal) = listOf("papel" to p.tile, "papel 2" to p.tile2, "fondo" to p.bg)

    @Test fun everyInkReadsOnEveryGround() {
        for ((name, p) in listOf("día" to DayPal, "noche" to NightPal)) {
            for ((inkName, ink) in listOf("tinta" to p.ink, "tinta 2" to p.ink2, "tinta 3" to p.ink3)) {
                for ((groundName, ground) in grounds(p)) {
                    val c = contrast(ink, ground)
                    assertTrue("$name: $inkName sobre $groundName da %.2f (hace falta 4,5)".format(c), c >= 4.5f)
                }
            }
        }
    }

    @Test fun anAccentUsedAsTextAlwaysReads() {
        for ((name, p) in listOf("día" to DayPal, "noche" to NightPal)) {
            for ((accentName, accent) in listOf("coral" to p.coral, "sol" to p.sun, "lila" to p.lilac, "menta" to p.mint, "cielo" to p.sky, "rojo" to p.red)) {
                val text = p.readable(accent)
                for ((groundName, ground) in grounds(p)) {
                    val c = contrast(text, ground)
                    assertTrue("$name: $accentName como texto sobre $groundName da %.2f".format(c), c >= 4.5f)
                }
            }
        }
    }

    @Test fun anAccentThatAlreadyReadsIsLeftAlone() {
        assertEquals(NightPal.mint, NightPal.readable(NightPal.mint))
    }

    // ── botones ──

    private fun open(ui: UiState, actions: ScreenActions = ScreenActions(), usb: Boolean = true) {
        val model = ScreenModel(
            ui = ui, clock = "14:05", date = "martes 3 nov", state = AssistantState.IDLE, listening = true, micOn = true,
            battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
            nowMs = 1_000, appVersion = 430000, usbPlugged = usb,
        ).copy(clockMs = { 1_000 })
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model, actions) }
        compose.mainClock.advanceTimeBy(900)
    }

    private val linked = UiState(link = LinkStatus.OPEN, assistant = "Vega", state = AssistantState.IDLE, camera = CameraInfo(true, false, "front"))

    @Test fun theRoundButtonsRespondOver48dp() {
        open(linked)
        for (name in listOf("Encender cámara", "Apagar micrófono", "Dormir")) {
            compose.onNodeWithContentDescription(name).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun aQuickTapDoesNotPutVegaToSleepButHoldingDoes() {
        var sleeps = 0
        open(linked, ScreenActions(sleep = { sleeps++ }))
        compose.onNodeWithContentDescription("Dormir").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(300)
        assertEquals(0, sleeps)
        compose.onNodeWithText("Mantén para dormir").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dormir").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(700)
        compose.onNodeWithContentDescription("Dormir").performTouchInput { up() }
        assertEquals(1, sleeps)
    }

    @Test fun releasingBeforeTheHoldEndsDoesNotSleep() {
        var sleeps = 0
        open(linked, ScreenActions(sleep = { sleeps++ }))
        compose.onNodeWithContentDescription("Dormir").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("Dormir").performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(0, sleeps)
    }

    // ── estados honestos ──

    private val offline = UiState(link = LinkStatus.RECONNECTING, retryInMs = 4_000, assistant = "Vega", state = AssistantState.IDLE)

    @Test fun withTheCableOnItBlamesTheServerNotTheCable() {
        open(offline, usb = true)
        compose.onNodeWithText("El PC no responde. ¿Está Vega encendida en el PC? · reintento en 4 s", substring = true).assertIsDisplayed()
    }

    @Test fun withoutTheCableItAsksForTheCable() {
        open(offline, usb = false)
        compose.onNodeWithText("Sin cable USB al PC. Conéctalo para seguir.", substring = true).assertIsDisplayed()
    }

    @Test fun withoutConnectionItDoesNotClaimEverythingIsCalm() {
        open(offline)
        compose.onNodeWithText("Sin datos del PC").assertIsDisplayed()
        compose.onNodeWithText("Todo tranquilo").assertDoesNotExist()
    }

    @Test fun withConnectionAndNothingPendingItStillSaysCalm() {
        open(linked)
        compose.onNodeWithText("Todo tranquilo").assertIsDisplayed()
    }

    @Test fun tappingTheFaceWithoutConnectionRetriesNow() {
        var retries = 0
        var attentions = 0
        open(offline, ScreenActions(retryNow = { retries++ }, attention = { attentions++ }))
        compose.onNodeWithText("Sin conexión").performTouchInput { click() }
        assertEquals(1, retries)
        assertEquals(0, attentions)
    }
}
