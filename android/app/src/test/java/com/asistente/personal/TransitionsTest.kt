package com.asistente.personal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.MediaInfo
import com.asistente.personal.net.StreakInfo
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.Origins
import com.asistente.personal.ui2.ThemeMode
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Las pantallas nacen de lo que se toca: la música de su tarjeta, el gimnasio de la llama de la cara (y vuelven ahí al cerrar). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class TransitionsTest {
    @get:Rule val compose = createComposeRule()

    private val song = MediaInfo("Spotify", "Human Nature", "Madonna", "Bedtime Stories", true, 97.0, 293.9, "abc", true, true)
    private val streak = StreakInfo(count = 5, best = 9, total = 40, today = true, restLeft = 2, alive = true, heat = 1f, week = 3)

    @Before fun reset() {
        ThemeMode.mode = 0
        Origins.nowTile = null
        Origins.flame = null
    }

    private fun model(media: MediaInfo? = null) = ScreenModel(
        ui = UiState(link = LinkStatus.OPEN, assistant = "Vega", state = AssistantState.IDLE, media = media, streak = streak),
        clock = "19:30", date = "jueves 12 nov", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
        nowMs = 1_000, appVersion = 430000, layout = 10,
    ).copy(clockMs = { 1_000 })

    @Test fun theGymOpensAsACircleFromTheFlameAndClosesBackIntoIt() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        assertNotNull(Origins.flame) // la llama apuntó dónde está
        compose.onNodeWithTag("face-racha").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(230)
        compose.onRoot().captureRoboImage("build/capturas/transicion_gym_mitad.png")
        compose.mainClock.advanceTimeBy(800)
        compose.onNodeWithTag("gym-screen").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/capturas/transicion_gym_final.png")
        compose.onNodeWithTag("gym-close").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(160)
        compose.onRoot().captureRoboImage("build/capturas/transicion_gym_cerrando.png")
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("gym-screen").assertDoesNotExist()
    }

    @Test fun theMusicTitleNeverRunsUnderThePageDots() {
        // Con muchas páginas (aviso, Moodle, PC, juegos, música) los puntos ocupan más: el título se recorta antes de llegar.
        val notes = listOf(com.asistente.personal.net.PcNote(1, "WhatsApp", "Ana", "hola", 1.0, "w"))
        val tasks = listOf(com.asistente.personal.net.TaskItem("a", "Ensayo", "Cálculo", 4_000_000_000))
        val m = model(song.copy(app = "Opera")).let { it.copy(ui = it.ui.copy(pcNotes = notes, tasks = tasks)) }
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(m, ScreenActions()) }
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("build/capturas/musica_titulo_y_puntos.png")
        val title = compose.onNodeWithText("SONANDO · OPERA", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithText("Música", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("el título (${title.right}) pisa los puntos (${pill.left})", title.right < pill.left - 40f)
    }

    @Test fun musicGrowsFromItsCardAndShrinksBack() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(song), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        assertNotNull(Origins.nowTile)
        // Con música, el mosaico ya enseña su página: se toca la carátula (a la izquierda de la tarjeta).
        val tile = Origins.nowTile!!
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(tile.left + 110f, tile.center.y)) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(150)
        compose.onRoot().captureRoboImage("build/capturas/transicion_musica_mitad.png")
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().captureRoboImage("build/capturas/transicion_musica_final.png")
    }
}
