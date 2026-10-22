package com.asistente.personal

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import com.asistente.personal.net.Voice
import com.asistente.personal.state.Message
import com.asistente.personal.state.Notice
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
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
    ) + (1..9).map { DeckAction("extra$it", "Botón $it", "", "app", "orange", false) }
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
        ui = ui, clock = "02:29", date = "jueves 22 oct", state = state, listening = true, micOn = true,
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

    // Poco después de un pulso de voz: la boca está abierta.
    @Test fun hablando() = shot("2_hablando", model(base, AssistantState.SPEAKING, pulse = 3), afterMs = 120)

    @Test fun escuchando() = shot("3_escuchando", model(base, AssistantState.LISTENING, pulse = 2), afterMs = 120)

    @Test fun pensando() = shot(
        "4_pensando",
        model(base.copy(messages = base.messages + Message(3, Role.USER, "Dame una idea para cenar")), AssistantState.THINKING),
    )

    @Test fun contenta() = shot("5_contenta", model(base.copy(notice = Notice(1, "Abriendo Spotify.", ok = true))))

    @Test fun confirmar_apagado() = shot(
        "6_confirmar",
        model(base.copy(pending = PendingConfirm("apagar", "n", "¿Apagar el PC?", 10))),
    )

    // El recordatorio pasa por la cara en letras de LED.
    @Test fun recordatorio() = shot(
        "7_recordatorio",
        model(base.copy(notice = Notice(1, "Te recuerdo: llamar a mamá.", ok = true, reminder = true))),
        afterMs = 2200,
    )

    @Test fun dormida() = shot("8_microfono_apagado", model(base).copy(listening = false))

    @Test fun sin_enlace() = shot(
        "9_sin_enlace",
        model(base.copy(link = LinkStatus.RECONNECTING, retryInMs = 3000, telemetry = null)),
    )

    @Test fun error() = shot("10_error", model(base.copy(notice = Notice(1, "No pude abrir Discord.", ok = false))))

    // La hoja «Más» abierta, con 16 botones (como config/actions.toml).
    @Test fun hoja_mas() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(base), ScreenActions()) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Más").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/capturas/11_hoja_mas.png")
    }

    // Cara en arcoíris y en violeta (opciones de «Más»); teclas con el icono real de la app.
    @Test fun arcoiris() = shot("12_arcoiris", model(base).copy(faceColor = com.asistente.personal.ui.FaceColor.RAINBOW, icons = fakeIcons()))

    @Test fun violeta() = shot("13_violeta", model(base, AssistantState.SPEAKING, pulse = 3).copy(faceColor = com.asistente.personal.ui.FaceColor.VIOLET), afterMs = 120)

    /** Iconos de prueba (círculos de color) en lugar de los de los .exe del PC. */
    private fun fakeIcons(): Map<String, androidx.compose.ui.graphics.ImageBitmap> {
        fun circle(c: Int) = android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888).also {
            android.graphics.Canvas(it).drawCircle(48f, 48f, 44f, android.graphics.Paint().apply { color = c; isAntiAlias = true })
        }.asImageBitmap()
        return mapOf("spotify" to circle(0xFF1ED760.toInt()), "discord" to circle(0xFF5865F2.toInt()))
    }

    // PC apagado desde Vega: se duerme (reloj de noche) en vez de «reconectando».
    @Test fun dormida_pc_apagado() = shot(
        "15_dormida",
        model(base.copy(link = LinkStatus.RECONNECTING, retryInMs = 3000, telemetry = null, pcOff = "shutdown"), AssistantState.IDLE),
        afterMs = 1500,
    )

    @Test fun hoja_mas_colores() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(base).copy(faceColor = com.asistente.personal.ui.FaceColor.RAINBOW, icons = fakeIcons()), ScreenActions()) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Más").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/capturas/14_hoja_mas_colores.png")
    }
}
