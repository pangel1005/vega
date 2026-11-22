package com.asistente.personal

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.MediaInfo
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import com.asistente.personal.ui.accentOf
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Las distribuciones de la pantalla principal, dibujadas con el tamaño y la densidad reales del Redmi 15C
 * (582 × 262 dp a 440 ppp = 1600 × 720 píxeles). Guarda las imágenes en app/build/capturas/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w582dp-h262dp-land-440dpi")
class LayoutShotTest {
    @get:Rule val compose = createComposeRule()

    private val actions = listOf(
        DeckAction("spotify", "Spotify", "", "app", "green", false, quick = true),
        DeckAction("apagar", "Apagar PC", "", "power", "red", true, quick = true),
        DeckAction("grabar", "Grabar", "", "record", "red", false, quick = true),
        DeckAction("discord", "Discord", "", "app", "orange", false, quick = true),
        DeckAction("play", "Play / pausa", "", "play", "orange", false, quick = true),
        DeckAction("silencio", "Silenciar", "", "mute", "orange", false, quick = true),
    )
    private val rest = Telemetry(
        cpu = 21, ramUsed = 23.6, ramTotal = 31.9, gpuTemp = 62, gpuUtil = 12, vramUsed = 7.0, vramTotal = 8.0,
        upcoming = listOf(Upcoming("timer", "temporizador de 5 minutos", 299), Upcoming("reminder", "llamar a mamá", 3600)),
    )
    private val playing = Telemetry(cpu = 34, ramUsed = 23.6, ramTotal = 31.9, gpuTemp = 71, gpuUtil = 96, vramUsed = 6.4, vramTotal = 8.0)
    private val base = UiState(
        actions = actions, link = LinkStatus.OPEN, assistant = "Vega", model = "qwen3.5:4b",
        messages = listOf(
            Message(1, Role.USER, "¿Cómo está el PC?"),
            Message(2, Role.ASSISTANT, "El PC va bien: procesador al 21 %, la gráfica a 62 grados y con 7 de 8 GB ocupados."),
        ),
        telemetry = rest, telemetryAt = 1_000,
    )
    private val song = MediaInfo(
        app = "Spotify", title = "Human Nature", artist = "Madonna", album = "Bedtime Stories",
        playing = true, position = 197.0, duration = 293.9, art = "abc", canPrevious = true, canNext = true,
    )
    private val game = GameInfo(
        key = "steam:2767030", name = "Marvel Rivals", source = "Steam", played = 3_900.0, hours = 268.2, sizeGb = 92.1,
        art = "steam-2767030", summary = "", genres = listOf("Acción"), developer = "NetEase Games", released = "5 DIC 2024", rating = 79,
        tags = listOf("Multijugador"),
    )

    private fun art(from: Int, to: Int): ImageBitmap = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, from, to, android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 300f, 300f, paint)
    }.asImageBitmap()

    private fun model(ui: UiState, layout: Int) = ScreenModel(
        ui = ui, clock = "21:48", date = "jueves 22 oct", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true,
        pulse = 0, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 430000, layout = layout,
    )

    private fun shot(name: String, m: ScreenModel) {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(m, ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    private fun music(layout: Int): ScreenModel {
        val musicArt = art(0xFF6A3DE8.toInt(), 0xFF2EC5FF.toInt())
        return model(base.copy(media = song, mediaAt = 1_000), layout)
            .copy(mediaArt = musicArt, mediaAccent = accentOf(musicArt), clockMs = { 1_000 })
    }

    private fun playingGame(layout: Int): ScreenModel {
        val gameArt = art(0xFFE0455B.toInt(), 0xFF3A0F2A.toInt())
        return model(base.copy(telemetry = playing, game = game, gameAt = 1_000), layout)
            .copy(gameArt = gameArt, gameAccent = androidx.compose.ui.graphics.Color(0xFFE0455B), clockMs = { 91_000 })
    }

    @Test fun l0_reposo() = shot("L0_reposo", model(base, 0))
    @Test fun l0_musica() = shot("L0_musica", music(0))
    @Test fun l0_juego() = shot("L0_juego", playingGame(0))

    @Test fun l1_reposo() = shot("L1_reposo", model(base, 1))
    @Test fun l1_musica() = shot("L1_musica", music(1))
    @Test fun l1_juego() = shot("L1_juego", playingGame(1))

    @Test fun l2_reposo() = shot("L2_reposo", model(base, 2))
    @Test fun l2_musica() = shot("L2_musica", music(2))
    @Test fun l2_juego() = shot("L2_juego", playingGame(2))

    @Test fun l3_reposo() = shot("L3_reposo", model(base, 3))
    @Test fun l3_musica() = shot("L3_musica", music(3))
    @Test fun l3_juego() = shot("L3_juego", playingGame(3))
}
