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

    // IA en el teléfono: se indica junto al nombre, y la pastilla de «Más» lo cambia.
    @Test fun ia_en_el_telefono() {
        val m = model(base.copy(messages = listOf(Message(1, Role.USER, "Vega, ¿qué hora es?"), Message(2, Role.ASSISTANT, "Son las 02:29."))))
            .copy(brainLabel = "IA: teléfono", brainOnPhone = true)
        shot("21_ia_telefono", m)
    }

    @Test fun hoja_mas_ia_telefono() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(base).copy(brainLabel = "Bajando IA 42 %", brainOnPhone = true), ScreenActions()) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Más").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/capturas/22_hoja_mas_ia.png")
    }

    // Notificaciones del PC: plegadas a un lado y abiertas (con una recién llegada).
    private val notes = listOf(
        com.asistente.personal.net.PcNote(3, "WhatsApp", "Ana", "¿Vienes a cenar esta noche? Hay pizza", 1_761_300_000.0 - 40, ""),
        com.asistente.personal.net.PcNote(2, "Discord", "#general", "Luis: ¿alguien para una partida?", 1_761_300_000.0 - 600, ""),
        com.asistente.personal.net.PcNote(1, "Outlook", "Factura de octubre", "Tu factura ya está disponible", 1_761_300_000.0 - 7200, ""),
    )

    @Test fun avisos_plegados() = shot(
        "23_avisos_plegados",
        model(base.copy(pcNotes = notes, notesKnown = true)).copy(wallMs = 1_761_300_000_000),
    )

    @Test fun avisos_abiertos() = shot(
        "24_avisos_abiertos",
        model(base.copy(pcNotes = notes, freshNotes = setOf(3L), notesKnown = true)).copy(wallMs = 1_761_300_000_000),
        afterMs = 900,
    )

    // Estilo «Cristal» (prototipo): en reposo, escuchando, con música y con la hoja «Más».
    @Test fun cristal_reposo() = shot("25_cristal_reposo", model(base.copy(pcNotes = notes, notesKnown = true)).copy(glass = true, wallMs = 1_761_300_000_000))

    @Test fun cristal_escuchando() = shot("26_cristal_escuchando", model(base, AssistantState.LISTENING, pulse = 2).copy(glass = true), afterMs = 120)

    @Test fun cristal_hoja_mas() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(base).copy(glass = true), ScreenActions()) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Más").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/capturas/27_cristal_mas.png")
    }

    // Dormida a mano (botón «Dormir» o «Vega, duérmete»): se despierta tocando.
    @Test fun dormida_a_mano() = shot("28_dormida_a_mano", model(base).copy(sleeping = true), afterMs = 1600)

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

    private val song = com.asistente.personal.net.MediaInfo(
        app = "Spotify", title = "Human Nature", artist = "Madonna", album = "Bedtime Stories",
        playing = true, position = 197.0, duration = 293.9, art = "abc", canPrevious = true, canNext = true,
    )

    /** Carátula de prueba: un degradado morado-azul (en el teléfono es la de verdad). */
    private fun fakeArt() = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val paint = android.graphics.Paint().apply {
            shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, 0xFF6A3DE8.toInt(), 0xFF2EC5FF.toInt(), android.graphics.Shader.TileMode.CLAMP)
        }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 300f, 300f, paint)
    }.asImageBitmap()

    // Algo suena en el PC: el mini reproductor ocupa el sitio del estado del PC.
    @Test fun mini_reproductor() {
        val art = fakeArt()
        shot(
            "16_mini_reproductor",
            model(base.copy(media = song, mediaAt = 1_000)).copy(mediaArt = art, mediaAccent = com.asistente.personal.ui.accentOf(art), clockMs = { 1_000 }),
        )
    }

    @Test fun pantalla_sonando() {
        val art = fakeArt()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VegaScreen(
                model(base.copy(media = song, mediaAt = 1_000)).copy(mediaArt = art, mediaAccent = com.asistente.personal.ui.accentOf(art), clockMs = { 1_000 }),
                ScreenActions(),
            )
        }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("Human Nature").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(500)
        compose.onRoot().captureRoboImage("build/capturas/17_sonando.png")
    }

    // Modo letra: a la izquierda la canción compacta; a la derecha la letra con la línea actual.
    @Test fun pantalla_letra() {
        val art = fakeArt()
        val lyrics = com.asistente.personal.net.Lyrics(
            synced = true,
            lines = listOf(
                0.0 to "Help me, I've broken apart my insides",
                184.0 to "Did I say something true?",
                190.0 to "Oops, I didn't know I couldn't talk about sex",
                195.5 to "I musta been crazy",
                199.0 to "To say exactly what I want from a guy",
                204.0 to "I'm not your bitch, don't hang your shit on me",
                209.0 to "",
                214.0 to "Express yourself, don't repress yourself",
            ).map { (t, text) -> com.asistente.personal.net.LyricLine(t, text) },
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            com.asistente.personal.ui.MusicScreen(
                song, receivedAt = 1_000, now = { 1_000 }, art = art, accent = com.asistente.personal.ui.accentOf(art),
                clock = "02:29", lyrics = lyrics, onControl = {}, onClose = {}, startWithLyrics = true,
            )
        }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().captureRoboImage("build/capturas/18_letra.png")
    }

    // Transición: al tocar «Letra», la carátula se encoge y viaja, y la letra entra deslizándose.
    @Test fun transicion_a_letra() {
        val art = fakeArt()
        val lyrics = com.asistente.personal.net.Lyrics(
            synced = true,
            lines = listOf(190.0 to "Oops, I didn't know", 195.5 to "I musta been crazy", 199.0 to "To say exactly what I want")
                .map { (t, text) -> com.asistente.personal.net.LyricLine(t, text) },
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            com.asistente.personal.ui.MusicScreen(
                song, receivedAt = 1_000, now = { 1_000 }, art = art, accent = com.asistente.personal.ui.accentOf(art),
                clock = "02:29", lyrics = lyrics, onControl = {}, onClose = {},
            )
        }
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("Letra").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(200) // a mitad de camino
        compose.onRoot().captureRoboImage("build/capturas/19_transicion_mitad.png")
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/capturas/20_transicion_final.png")
        compose.onNodeWithText("I musta been crazy").assertExists()
    }

    // --- Estilo «Nostromo» ---

    private val conversation = base.copy(
        model = "qwen3.5:4b",
        messages = listOf(
            Message(1, Role.USER, "¿cómo está el PC?"),
            Message(2, Role.ASSISTANT, "El PC va bien: procesador al 21 %, la gráfica a 62 grados y con 7 de 8 GB ocupados."),
        ),
    )

    @Test fun nostromo_reposo() = shot(
        "29_nostromo_reposo",
        model(conversation).copy(terminal = true, brainOnPhone = true, brainLabel = "IA: teléfono", ears = { com.asistente.personal.local.EarsSnapshot(684, 642, 3) }),
        afterMs = 1500,
    )

    @Test fun nostromo_escuchando() = shot(
        "30_nostromo_escuchando",
        model(conversation, AssistantState.LISTENING).copy(terminal = true),
        afterMs = 600,
    )

    @Test fun nostromo_confirmar() = shot(
        "31_nostromo_confirmar",
        model(conversation.copy(pending = PendingConfirm("apagar", "n1", "¿Seguro?", 8))).copy(terminal = true),
        afterMs = 600,
    )

    @Test fun nostromo_hoja_mas() {
        compose.mainClock.autoAdvance = false
        compose.setContent { VegaScreen(model(base).copy(terminal = true), ScreenActions()) }
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("F7 MÁS").performTouchInput { down(center); up() }
        compose.mainClock.advanceTimeBy(600)
        compose.onRoot().captureRoboImage("build/capturas/32_nostromo_hoja_mas.png")
    }
}
