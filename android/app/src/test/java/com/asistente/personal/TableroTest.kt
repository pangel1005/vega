package com.asistente.personal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.TaskItem
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.Lane
import com.asistente.personal.ui2.NightPal
import com.asistente.personal.ui2.Pal
import com.asistente.personal.ui2.TableroSheet2
import com.asistente.personal.ui2.ThemeMode
import com.asistente.personal.ui2.bigCountdown
import com.asistente.personal.ui2.board
import com.asistente.personal.ui2.dueLabel
import com.asistente.personal.ui2.laneOf
import com.github.takahirom.roborazzi.captureRoboImage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import java.time.ZonedDateTime

/** El tablero de Moodle: las entregas en columnas, el visto con confirmación y la página «Moodle» del mosaico. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class TableroTest {
    @get:Rule val compose = createComposeRule()

    private val zone = ZoneId.of("UTC")

    // Miércoles 11 de noviembre de 2026, 19:30 UTC.
    private val now = ZonedDateTime.of(2026, 11, 11, 19, 30, 0, 0, zone).toEpochSecond()
    private fun at(days: Long, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 11, h, m, 0, 0, zone).plusDays(days).toEpochSecond()

    private val tasks = listOf(
        TaskItem("a", "Ensayo Unidad 1 (2 puntos)", "Cálculo I", at(0, 18)),
        TaskItem("b", "Práctica 1 (3 puntos)", "Cálculo I", at(0, 9)),
        TaskItem("c", "UD 2. Act 1. Informe sobre las funciones administrativas", "Administración", at(0, 23, 0)),
        TaskItem("d", "Fundamentos de Internet Society", "Redes", at(1, 0)),
        TaskItem("e", "Pruebín Unidad 1", "Cálculo I", at(7, 18, 45)),
        TaskItem("f", "Cloud Computing – Huawei", "Redes", at(14, 0)),
        TaskItem("g", "Sprint 1 (20 puntos)", "Ingeniería de software", at(34, 23, 59)),
    )

    @Before fun reset() {
        ThemeMode.mode = 0
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC")) // las columnas dependen de la zona del teléfono
    }

    private fun model(tasks: List<TaskItem>?, link: LinkStatus = LinkStatus.OPEN) = ScreenModel(
        ui = UiState(link = link, assistant = "Vega", state = AssistantState.IDLE, tasks = tasks),
        clock = "19:30", date = "miércoles 11 nov", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f,
        nowMs = 1_000, appVersion = 430000, layout = 10, wallMs = now * 1000,
    ).copy(clockMs = { 1_000 })

    private fun open(m: ScreenModel, a: ScreenActions = ScreenActions(), p: Pal = DayPal, onClose: () -> Unit = {}) {
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize()) { TableroSheet2(m, a, p, onClose, Modifier.fillMaxSize()) } }
        compose.mainClock.advanceTimeBy(300)
    }

    // ── repartir en columnas ──

    @Test fun eachTaskFallsInItsLane() {
        assertEquals(Lane.OVERDUE, laneOf(at(0, 18), now, zone))
        assertEquals(Lane.OVERDUE, laneOf(now, now, zone)) // justo ahora ya venció
        assertEquals(Lane.TODAY, laneOf(at(0, 23, 59), now, zone))
        assertEquals(Lane.WEEK, laneOf(at(1, 0), now, zone)) // pasada la medianoche ya es mañana
        assertEquals(Lane.WEEK, laneOf(at(6, 23), now, zone))
        assertEquals(Lane.LATER, laneOf(at(7, 18, 45), now, zone))
    }

    @Test fun theBoardSortsEachLaneAndShowsTheMostRecentOverdueFirst() {
        val b = board(tasks, now, zone)
        assertEquals(listOf("a", "b"), b.getValue(Lane.OVERDUE).map { it.uid }) // 18:00 antes que 9:00
        assertEquals(listOf("c"), b.getValue(Lane.TODAY).map { it.uid })
        assertEquals(listOf("d"), b.getValue(Lane.WEEK).map { it.uid })
        assertEquals(listOf("e", "f", "g"), b.getValue(Lane.LATER).map { it.uid })
        assertEquals(Lane.entries.toList(), b.keys.toList())
        assertTrue(board(emptyList(), now, zone).values.all { it.isEmpty() })
    }

    @Test fun timeLabelsReadNaturally() {
        assertEquals("en 20 min", dueLabel(now + 1200, now, zone))
        assertEquals("en 1 min", dueLabel(now + 5, now, zone))
        assertEquals("en 3 h", dueLabel(at(0, 23), now, zone))
        assertEquals("mañana 00:00", dueLabel(at(1, 0), now, zone))
        assertEquals("mié 18 · 18:45", dueLabel(at(7, 18, 45), now, zone))
        assertEquals("hace 1 h", dueLabel(at(0, 18), now, zone))
        assertEquals("hace 10 h", dueLabel(at(0, 9), now, zone))
        assertEquals("hace 2 d", dueLabel(at(-2, 18), now, zone))
        assertEquals("ahora", dueLabel(now - 10, now, zone))
        assertEquals("vencida", bigCountdown(now - 1, now))
        assertEquals("20 min", bigCountdown(now + 1200, now))
        assertEquals("3 h 30 min", bigCountdown(at(0, 23), now))
        assertEquals("6 d 23 h", bigCountdown(at(7, 18, 30), now))
    }

    // ── el estado y el protocolo ──

    @Test fun theTasksComeFromThePcAndDoneRemovesOneAtOnce() {
        var s = reduce(UiState(), Action.Server(ServerEvent.Tasks(tasks)))
        assertEquals(7, s.tasks!!.size)
        s = reduce(s, Action.TaskDone("c"))
        assertEquals(listOf("a", "b", "d", "e", "f", "g"), s.tasks!!.map { it.uid })
        assertNull(reduce(UiState(), Action.TaskDone("c")).tasks) // sin lectura no inventa nada
        // Se conservan sin enlace: las fechas siguen valiendo.
        assertEquals(6, reduce(s, Action.Link(LinkStatus.RECONNECTING, 1000)).tasks!!.size)
    }

    @Test fun theEventIsParsedAndMessagesAreWellFormed() {
        val e = Protocol.parse(
            """{"type":"tasks","items":[{"uid":"1@m","title":"Ensayo","course":"Cálculo I","due":1794434400.0},
            {"uid":"2@m","title":"Sin curso","due":1794434460.5}]}""",
        ) as ServerEvent.Tasks
        assertEquals(TaskItem("1@m", "Ensayo", "Cálculo I", 1794434400), e.items[0])
        assertEquals("", e.items[1].course)
        assertEquals(0, (Protocol.parse("""{"type":"tasks","items":[]}""") as ServerEvent.Tasks).items.size)
        val d = JSONObject(Protocol.tasks("done", "1@m"))
        assertEquals("tasks", d.getString("type")); assertEquals("done", d.getString("action")); assertEquals("1@m", d.getString("uid"))
        assertFalse(JSONObject(Protocol.tasks("get")).has("uid"))
    }

    // ── la hoja ──

    @Test fun theSheetShowsFourLanesWithTheirTasks() {
        open(model(tasks))
        compose.onNodeWithText("Tablero · Moodle").assertIsDisplayed()
        compose.onNodeWithText("5 por entregar · 2 vencidas").assertIsDisplayed()
        listOf("VENCIDAS", "HOY", "ESTA SEMANA", "DESPUÉS").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithTag("tablero-card-a").assertIsDisplayed()
        compose.onNodeWithTag("tablero-card-c").assertIsDisplayed()
    }

    @Test fun doneAsksForConfirmationBeforeMarkingAndTheAskTimesOut() {
        val done = mutableListOf<String>()
        open(model(tasks), ScreenActions(taskDone = { done += it }))
        compose.onNodeWithTag("tablero-done-c").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        assertTrue(done.isEmpty()) // un solo toque no basta
        compose.onNodeWithTag("tablero-confirm-c").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3200)
        compose.onNodeWithTag("tablero-confirm-c").assertDoesNotExist() // se arrepiente solo
        compose.onNodeWithTag("tablero-done-c").performTouchInput { click() }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithTag("tablero-confirm-c").performTouchInput { click() }
        assertEquals(listOf("c"), done)
    }

    @Test fun withoutLinkTheTasksStayButCannotBeMarked() {
        open(model(tasks, LinkStatus.RECONNECTING))
        compose.onNodeWithTag("tablero-card-c").assertIsDisplayed()
        compose.onNodeWithTag("tablero-done-c").assertDoesNotExist()
    }

    @Test fun emptyAndNotYetReadAreExplained() {
        open(model(emptyList()))
        compose.onNodeWithText("No hay entregas en Moodle.").assertIsDisplayed()
    }

    @Test fun notYetReadSaysSo() {
        open(model(null))
        compose.onNodeWithText("Leyendo Moodle…").assertIsDisplayed()
    }

    @Test fun closeButtonCloses() {
        var closed = false
        open(model(tasks), onClose = { closed = true })
        compose.onNodeWithTag("tablero-close").performTouchInput { click() }
        assertTrue(closed)
    }

    // ── el mosaico de la pantalla de inicio ──

    @Test fun theMoodlePageOpensTheBoard() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(tasks), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithContentDescription("Moodle").performClick() // el punto de la página
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithTag("now-tasks").assertIsDisplayed()
        compose.onNodeWithText("PRÓXIMA ENTREGA").assertIsDisplayed()
        compose.onNodeWithText("2 vencidas").assertIsDisplayed()
        compose.onNodeWithText("3 h 30 min").assertIsDisplayed()
        compose.onNodeWithTag("now-tasks").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Tablero · Moodle").assertIsDisplayed()
        compose.onNodeWithTag("tablero-close").performTouchInput { click() }
        compose.mainClock.advanceTimeBy(600)
        compose.onNodeWithText("Tablero · Moodle").assertDoesNotExist()
    }

    @Test fun withoutTasksThereIsNoMoodlePage() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(null), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithContentDescription("Moodle").assertDoesNotExist()
    }

    // ── capturas (para mirarlas) ──

    private fun shot(name: String, m: ScreenModel, p: Pal) {
        open(m, p = p)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun shotDay() = shot("tablero_dia", model(tasks), DayPal)

    @Test fun shotNight() = shot("tablero_noche", model(tasks), NightPal)

    @Test fun shotEmpty() = shot("tablero_vacio", model(emptyList()), DayPal)

    @Test fun shotTile() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(tasks), ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithContentDescription("Moodle").performClick()
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("build/capturas/tablero_mosaico.png")
    }
}
