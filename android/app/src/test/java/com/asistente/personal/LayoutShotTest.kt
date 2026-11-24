package com.asistente.personal

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * La pantalla principal (Vega 2), dibujada con el tamaño y la densidad reales del Redmi 15C
 * (800 × 360 dp a 320 ppp = 1600 × 720 píxeles). Guarda las imágenes en app/build/capturas/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
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
        app = "Spotify", title = "Costa Azul", artist = "Luces de Neón", album = "Verano Eléctrico",
        playing = true, position = 197.0, duration = 293.9, art = "abc", canPrevious = true, canNext = true,
    )
    private val game = GameInfo(
        key = "steam:730", name = "Counter-Strike 2", source = "Steam", played = 3_900.0, hours = 1_240.5, sizeGb = 33.6,
        art = "steam-730", summary = "", genres = listOf("Acción"), developer = "Valve", released = "21 AGO 2012", rating = 86,
        tags = listOf("Multijugador"),
    )

    private fun art(from: Int, to: Int): ImageBitmap = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, from, to, android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 300f, 300f, paint)
    }.asImageBitmap()

    private fun model(ui: UiState) = ScreenModel(
        ui = ui, clock = "21:48", date = "jueves 22 oct", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true,
        pulse = 0, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 430000,
    )

    private fun shot(name: String, m: ScreenModel, afterMs: Long = 900) {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(m, ScreenActions()) }
        compose.mainClock.advanceTimeBy(afterMs)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    private fun music(): ScreenModel {
        val musicArt = art(0xFF6A3DE8.toInt(), 0xFF2EC5FF.toInt())
        return model(base.copy(media = song, mediaAt = 1_000))
            .copy(mediaArt = musicArt, mediaAccent = accentOf(musicArt), clockMs = { 1_000 })
    }

    private fun playingGame(): ScreenModel {
        val gameArt = art(0xFFE0455B.toInt(), 0xFF3A0F2A.toInt())
        return model(base.copy(telemetry = playing, game = game, gameAt = 1_000))
            .copy(gameArt = gameArt, gameAccent = androidx.compose.ui.graphics.Color(0xFFE0455B), clockMs = { 91_000 })
    }

    private fun wave(): () -> Float {
        var n = 0
        return { n++; (0.5f + 0.5f * kotlin.math.sin(n * 0.7f) * kotlin.math.sin(n * 0.23f)).coerceIn(0f, 1f) }
    }

    // ── Vega 2: noche (21:48) y día (14:05) ──
    private fun day(m: ScreenModel) = m.copy(clock = "14:05")
    private val hotTel get() = rest.copy(gpuTemp = 84, cpu = 93, gpuUtil = 90)

    @Test fun n_reposo() = shot("N_reposo", model(base), afterMs = 2_000)
    @Test fun n_musica() = shot("N_musica", music().copy(musicLevel = wave()), afterMs = 2_000)
    @Test fun n_juego() = shot("N_juego", playingGame(), afterMs = 2_000)
    @Test fun n_escuchando() = shot("N_escuchando", model(base).copy(state = AssistantState.LISTENING, pulse = 2), afterMs = 2_000)
    @Test fun n_calor() = shot("N_calor", model(base.copy(telemetry = hotTel)), afterMs = 2_000)
    @Test fun d_reposo() = shot("D_reposo", day(model(base)), afterMs = 2_000)
    @Test fun d_musica() = shot("D_musica", day(music()).copy(musicLevel = wave()), afterMs = 2_000)
    @Test fun d_juego() = shot("D_juego", day(playingGame()), afterMs = 2_000)
    @Test fun d_escuchando() = shot("D_escuchando", day(model(base)).copy(state = AssistantState.LISTENING, pulse = 2), afterMs = 2_000)
    @Test fun d_pensando() = shot("D_pensando", day(model(base)).copy(state = AssistantState.THINKING), afterMs = 2_000)
    @Test fun d_hablando() = shot("D_hablando", day(model(base)).copy(state = AssistantState.SPEAKING, pulse = 3), afterMs = 400)
    @Test fun d_camara_apagada() = shot("D_camara_apagada", day(model(base.copy(camera = com.asistente.personal.state.CameraInfo(true, false, "front")))), afterMs = 2_000)
    @Test fun d_camara_encendida() = shot("D_camara_encendida", day(model(base.copy(camera = com.asistente.personal.state.CameraInfo(true, true, "front")))), afterMs = 2_000)
    @Test fun n_camara_encendida() = shot("N_camara_encendida", model(base.copy(camera = com.asistente.personal.state.CameraInfo(true, true, "front"))), afterMs = 2_000)
    @Test fun n_reconectando() = shot("N_reconectando", model(base.copy(link = com.asistente.personal.net.LinkStatus.RECONNECTING, retryInMs = 4_000, telemetry = null)), afterMs = 2_000)
    @Test fun d_reconectando() = shot("D_reconectando", day(model(base.copy(link = com.asistente.personal.net.LinkStatus.RECONNECTING, retryInMs = 4_000, telemetry = null))), afterMs = 2_000)
    @Test fun n_sin_permiso() = shot("N_sin_permiso", model(base.copy(link = com.asistente.personal.net.LinkStatus.DENIED, telemetry = null)), afterMs = 2_000)
    @Test fun d_calor() = shot("D_calor", day(model(base.copy(telemetry = hotTel))), afterMs = 2_000)
    @Test fun d_bateria() = shot("D_bateria", day(model(base)).copy(battery = 8, charging = false), afterMs = 2_000)

    // ── la página «Juegos» (sin ninguno abierto): tus últimos juegos ──
    private val recents = listOf(
        com.asistente.personal.net.RecentGame("Counter-Strike 2", "a", 1_240.5, 1_000.0),
        com.asistente.personal.net.RecentGame("Rocket League", "b", 412.0, 1_000.0 - 86_400 * 2),
        com.asistente.personal.net.RecentGame("Valorant", "c", 90.5, 1_000.0 - 86_400 * 9),
    )
    private fun withRecents(m: ScreenModel) = m.copy(
        ui = m.ui.copy(recentGames = recents),
        recentArts = mapOf("a" to art(0xFFE0455B.toInt(), 0xFF3A0F2A.toInt()), "b" to art(0xFF2EC5FF.toInt(), 0xFF1B2B6B.toInt()), "c" to art(0xFFFF4655.toInt(), 0xFF111111.toInt())),
        wallMs = 1_000_000L,
    )

    /** Como el usuario: abre la pantalla y pulsa la pestaña indicada del mosaico «ahora». */
    private fun shotTap(name: String, m: ScreenModel, tab: String) {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(m, ScreenActions()) }
        compose.mainClock.advanceTimeBy(900)
        compose.onNodeWithContentDescription(tab).performClick()
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    @Test fun n_juegos() = shotTap("N_juegos", withRecents(model(base)), "Juegos")
    @Test fun d_juegos() = shotTap("D_juegos", withRecents(day(model(base))), "Juegos")
    @Test fun d_sin_juegos() = shotTap("D_sin_juegos", day(model(base)), "Juegos")

    // ── lo que faltaba traer: avisos del PC, «Actualizar» y la carga de la IA del teléfono ──
    private val notes = listOf(
        com.asistente.personal.net.PcNote(1, "WhatsApp", "Marta", "¿Cenamos hoy fuera? Te escribo luego con el sitio", 990.0, "wa"),
        com.asistente.personal.net.PcNote(2, "Discord", "Leo", "Entra a la sala de voz cuando puedas", 600.0, "dc"),
        com.asistente.personal.net.PcNote(3, "Correo", "Banco", "Tu extracto de octubre ya está disponible", 100.0, "ml"),
    )
    private fun withNotes(m: ScreenModel) = m.copy(ui = m.ui.copy(pcNotes = notes, freshNotes = setOf(1L), notesKnown = true), wallMs = 1_000_000L)

    @Test fun d_notas() = shot("D_notas", withNotes(day(model(base))), afterMs = 2_000)
    @Test fun n_notas() = shot("N_notas", withNotes(model(base)), afterMs = 2_000)
    @Test fun d_actualizar() = shot("D_actualizar", day(model(base)).copy(updateAvailable = true), afterMs = 2_000)
    @Test fun d_ia_carga() = shot("D_ia_carga", day(model(base)).copy(brainOnPhone = true, brainLabel = "Bajando IA 45 %"), afterMs = 2_000)
    @Test fun d_ia_fallo() = shot("D_ia_fallo", day(model(base)).copy(brainOnPhone = true, brainLabel = "IA: error"), afterMs = 2_000)
    @Test fun d_confirmar() = shot(
        "D_confirmar",
        day(model(base.copy(pending = com.asistente.personal.state.PendingConfirm("apagar", "n1", "Voy a apagar el PC.", 15)))),
        afterMs = 2_000,
    )
}
