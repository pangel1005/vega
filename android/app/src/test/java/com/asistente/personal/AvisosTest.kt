package com.asistente.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.PcNote
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.AvisosSheet2
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.NightPal
import com.asistente.personal.ui2.Pal
import com.asistente.personal.ui2.ThemeMode
import com.asistente.personal.ui2.groupNotes
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

/** Todos los avisos del PC, agrupados por app, y la tarjeta del mosaico sin filas pegadas. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class AvisosTest {
    @get:Rule val compose = createComposeRule()

    private val now = 1_794_500_000.0
    private val notes = listOf(
        PcNote(1, "WhatsApp", "Laura Méndez", "Sticker", now - 30, "w"),
        PcNote(2, "Controlador GeForce Game Ready", "Actualización disponible", "Versión 591.12: mejoras para Marvel Rivals", now - 3600, "n"),
        PcNote(3, "Gmail", "Carlos Rivas", "El mié, 11 nov 2026 a la(s) 5:12 p. m. escribió: ¿vienes mañana a la reunión?", now - 7200, "g"),
        PcNote(4, "WhatsApp", "Mamá", "¿Ya comiste?", now - 600, "w"),
        PcNote(5, "Discord", "#general", "Pedro: partida a las 9", now - 900, "d"),
        PcNote(6, "WhatsApp", "Grupo Uni", "Mañana hay examen de cálculo", now - 5000, "w"),
    )

    @Before fun reset() { ThemeMode.mode = 0 }

    private fun model(n: List<PcNote>, link: LinkStatus = LinkStatus.OPEN) = ScreenModel(
        ui = UiState(link = link, assistant = "Vega", state = AssistantState.IDLE, pcNotes = n, freshNotes = setOf(1L)),
        clock = "12:41", date = "jueves 12 nov", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
        nowMs = 1_000, appVersion = 430000, wallMs = (now * 1000).toLong(),
    ).copy(clockMs = { 1_000 })

    private fun open(m: ScreenModel, a: ScreenActions = ScreenActions(), p: Pal = DayPal) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { AvisosSheet2(m, a, p, {}, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(300)
    }

    @Test fun notesAreGroupedByAppNewestFirst() {
        val g = groupNotes(notes)
        assertEquals(listOf("WhatsApp", "Discord", "Controlador GeForce Game Ready", "Gmail"), g.map { it.app })
        assertEquals(listOf(1L, 4L, 6L), g[0].notes.map { it.id })
    }

    @Test fun theSheetShowsEveryNoteInItsColumn() {
        open(model(notes))
        compose.onNodeWithText("Avisos del PC").assertIsDisplayed()
        compose.onNodeWithText("6 avisos · 4 apps").assertIsDisplayed()
        compose.onNodeWithTag("avisos-app-WhatsApp").assertIsDisplayed()
        listOf(1L, 2L, 3L, 4L, 5L).forEach { compose.onNodeWithTag("aviso-$it").assertIsDisplayed() }
    }

    @Test fun oneCanBeRemoved() {
        val gone = mutableListOf<Long?>()
        open(model(notes), ScreenActions(dismissNote = { gone += it }))
        compose.onNodeWithTag("aviso-quitar-4").performTouchInput { click() }
        assertEquals(listOf<Long?>(4L), gone)
    }

    @Test fun clearingAllAsksTwice() {
        val gone = mutableListOf<Long?>()
        open(model(notes), ScreenActions(dismissNote = { gone += it }))
        compose.onNodeWithTag("avisos-clear").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        assertTrue(gone.isEmpty())
        compose.onNodeWithText("¿Borrar los 6? Toca").assertIsDisplayed()
        compose.onNodeWithTag("avisos-clear").performTouchInput { click() }
        compose.waitForIdle()
        assertEquals(listOf<Long?>(null), gone) // null = todos
    }

    @Test fun emptyAndOffline() {
        open(model(emptyList()))
        compose.onNodeWithText("Todo leído: no hay avisos en el PC.").assertIsDisplayed()
    }

    @Test fun withoutLinkNothingCanBeRemoved() {
        open(model(notes, LinkStatus.RECONNECTING))
        compose.onNodeWithTag("avisos-clear").assertDoesNotExist()
        compose.onNodeWithTag("aviso-quitar-1").assertDoesNotExist()
    }

    @Test fun theTileOpensTheSheet() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(notes), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithContentDescriptionOrPage()
        compose.onNodeWithTag("now-notes").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.5f, height * 0.85f)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithText("Avisos del PC").assertIsDisplayed()
    }

    /** La página de avisos se asoma sola a lo nuevo; si no está delante, se toca su punto. */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onNodeWithContentDescriptionOrPage() {
        val dot = onAllNodes(androidx.compose.ui.test.hasContentDescription("PC")).fetchSemanticsNodes()
        if (dot.isNotEmpty()) onNode(androidx.compose.ui.test.hasContentDescription("PC")).performTouchInput { click() }
        waitForIdle()
        mainClock.advanceTimeBy(900)
    }

    // ── capturas ──

    @Test fun shotDay() {
        open(model(notes))
        compose.onRoot().captureRoboImage("build/capturas/avisos_dia.png")
    }

    @Test fun shotNight() {
        open(model(notes), p = NightPal)
        compose.onRoot().captureRoboImage("build/capturas/avisos_noche.png")
    }

    @Test fun shotTile() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(notes), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithContentDescriptionOrPage()
        compose.onRoot().captureRoboImage("build/capturas/avisos_tarjeta.png")
    }
}
