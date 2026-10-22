package com.asistente.personal

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import com.asistente.personal.net.Voice
import com.asistente.personal.state.Message
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Dibuja la pantalla en el PC (sin teléfono) con datos de ejemplo y guarda la imagen en
 * app/build/capturas/. Sirve para revisar el diseño antes de instalarlo; no compara
 * píxeles con nada (no falla si cambia el diseño, solo si la pantalla no se puede dibujar).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi") // Redmi 15C en horizontal
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val actions = listOf(
        DeckAction("spotify", "Spotify", "", "app", "green", false, quick = true),
        DeckAction("apagar", "Apagar PC", "停止", "power", "red", true, quick = true),
        DeckAction("grabar", "Grabar", "録画", "record", "red", false, quick = true),
        DeckAction("discord", "Discord", "", "app", "orange", false, quick = true),
        DeckAction("play", "Play / pausa", "再生", "play", "orange", false, quick = true),
        DeckAction("silencio", "Silenciar", "消音", "mute", "orange", false, quick = true),
        DeckAction("reiniciar", "Reiniciar", "再起動", "restart", "red", true),
    )
    private val telemetry = Telemetry(
        cpu = 21, ramUsed = 23.6, ramTotal = 31.9, gpuTemp = 62, gpuUtil = 52, vramUsed = 7.0, vramTotal = 8.0,
        upcoming = listOf(
            Upcoming("timer", "temporizador de 5 minutos", 299),
            Upcoming("reminder", "llamar a mamá", 3600),
        ),
    )
    private val base = UiState(
        actions = actions,
        voices = listOf(Voice("kokoro:ef_dora", "Dora · natural")),
        voice = "kokoro:ef_dora",
        link = LinkStatus.OPEN,
        assistant = "Vega",
        model = "qwen3.5:4b",
        messages = listOf(
            Message(1, Role.USER, "¿Cómo está el PC?"),
            Message(2, Role.ASSISTANT, "El PC va bien: procesador al 21 %, la gráfica a 62 grados y con 7 de 8 GB ocupados."),
        ),
        telemetry = telemetry,
        telemetryAt = 1_000,
    )

    private fun model(ui: UiState, state: AssistantState = ui.state, pulse: Int = 0) = ScreenModel(
        ui = ui, clock = "02:29", date = "JUE 22.10.2026", state = state, listening = true, micOn = true,
        battery = 91, charging = true, updateAvailable = true, hasToken = true,
        pulse = pulse, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 423700,
    )

    /** El reloj de animación va a mano: las animaciones sin fin (rostro, cinta) no dejarían terminar nunca. */
    private fun shot(name: String, m: ScreenModel, afterMs: Long = 700) {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(m, ScreenActions()) }
        compose.mainClock.advanceTimeBy(afterMs)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun reposo() = shot("1_reposo", model(base))

    // Poco después de un pulso de voz: la boca y la corona están abiertas.
    @Test fun hablando() = shot("2_hablando", model(base, AssistantState.SPEAKING, pulse = 3), afterMs = 80)

    @Test fun pensando() = shot(
        "3_pensando",
        model(base.copy(messages = base.messages + Message(3, Role.USER, "Dame una idea para cenar")), AssistantState.THINKING),
    )

    @Test fun confirmar_apagado() = shot(
        "4_confirmar",
        model(base.copy(pending = PendingConfirm("apagar", "n", "¿Apagar el PC?", 10))),
    )

    @Test fun sin_enlace() = shot(
        "5_sin_enlace",
        model(base.copy(link = LinkStatus.RECONNECTING, retryInMs = 3000, telemetry = null)),
    )

    @Test fun aviso() = shot(
        "7_aviso",
        model(base.copy(notice = com.asistente.personal.state.Notice(1, "Te recuerdo: llamar a mamá.", ok = true))),
    )

    @Test fun primera_vez() = shot(
        "6_vacio",
        model(UiState(actions = actions, link = LinkStatus.OPEN, assistant = "Vega")),
    )
}
