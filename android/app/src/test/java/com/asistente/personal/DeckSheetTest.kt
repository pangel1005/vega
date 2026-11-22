package com.asistente.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.DeckSheet2
import com.asistente.personal.ui2.ThemeMode
import com.asistente.personal.ui2.deckGroups
import com.asistente.personal.ui2.deckLabel
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** La hoja «Más»: los botones salen agrupados y los ajustes se eligen de un toque. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class DeckSheetTest {
    @get:Rule val compose = createComposeRule()

    private val actions = listOf(
        DeckAction("discord", "Discord", "", "app", "", false),
        DeckAction("volumen-mas", "Volumen +", "", "volume-up", "", false),
        DeckAction("bloquear", "Bloquear", "", "lock", "", false, power = "lock"),
        DeckAction("apagar", "Apagar PC", "", "power", "red", true, power = "shutdown"),
        DeckAction("silenciar", "Silenciar", "", "mute", "", false),
        DeckAction("captura", "Captura", "", "screenshot", "", false),
        DeckAction("cancelar-apagado", "Cancelar apagado", "", "cancel", "green", false, power = "cancel"),
    )

    @Before fun reset() { ThemeMode.mode = 0 }

    @Test fun buttonsAreGroupedByKindAndOrdered() {
        val groups = deckGroups(actions)
        assertEquals(listOf("Equipo", "Sonido", "Extras", "Apps"), groups.map { it.first })
        assertEquals(listOf("apagar", "bloquear", "cancelar-apagado"), groups[0].second.map { it.id })
        assertEquals(listOf("silenciar", "volumen-mas"), groups[1].second.map { it.id })
    }

    @Test fun emptyGroupsAreNotShown() {
        assertEquals(listOf("Sonido"), deckGroups(actions.filter { it.id == "silenciar" }).map { it.first })
    }

    @Test fun longNamesAreShortenedToTheFirstWord() {
        assertEquals("Cancelar", deckLabel("Cancelar apagado"))
        assertEquals("Limpiar disco", deckLabel("Limpiar disco"))
        assertEquals("Reiniciar", deckLabel("Reiniciar"))
    }

    private fun open(brainOnPhone: Boolean, toggled: () -> Unit) {
        val model = ScreenModel(
            ui = UiState(actions = actions, link = LinkStatus.OPEN, assistant = "Vega", state = AssistantState.IDLE),
            clock = "14:05", date = "martes 3 nov", state = AssistantState.IDLE, listening = true, micOn = true,
            battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
            nowMs = 1_000, appVersion = 430000, layout = 10, brainOnPhone = brainOnPhone,
            brainLabel = if (brainOnPhone) "IA: teléfono" else "IA: PC",
        )
        compose.setContent { Box(Modifier.fillMaxSize()) { DeckSheet2(model, ScreenActions(toggleBrain = toggled), DayPal, 0, true) {} } }
    }

    @Test fun tappingTheOtherBrainOptionSwitchesIt() {
        var toggles = 0
        open(brainOnPhone = false) { toggles++ }
        compose.onNodeWithText("En el teléfono").performClick()
        assertEquals(1, toggles)
    }

    @Test fun tappingTheBrainOptionAlreadyChosenDoesNothing() {
        var toggles = 0
        open(brainOnPhone = true) { toggles++ }
        compose.onNodeWithText("En el teléfono").performClick()
        assertEquals(0, toggles)
    }

    @Test fun theThemeIsChosenDirectly() {
        open(brainOnPhone = false) {}
        compose.onNodeWithText("Noche").performClick()
        assertEquals(2, ThemeMode.mode)
        compose.onNodeWithText("Día").performClick()
        assertEquals(1, ThemeMode.mode)
        compose.onNodeWithText("Auto").performClick()
        assertEquals(0, ThemeMode.mode)
    }
}
