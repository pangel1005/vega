package com.asistente.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.GameNews
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.LyricLine
import com.asistente.personal.net.Lyrics
import com.asistente.personal.net.MatchInfo
import com.asistente.personal.net.MediaInfo
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import com.asistente.personal.net.Voice
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.GameSample
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.accentOf
import com.asistente.personal.ui2.ChatOverlay2
import com.asistente.personal.ui2.DayPal
import com.asistente.personal.ui2.DeckSheet2
import com.asistente.personal.ui2.GameScreen2
import com.asistente.personal.ui2.MusicScreen2
import com.asistente.personal.ui2.NightPal
import com.asistente.personal.ui2.Pal
import com.asistente.personal.ui2.SleepScreen2
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Las pantallas grandes del diseño nuevo (hoja «Más», música, juego, conversación y dormir), de día y de noche, al tamaño del Redmi. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class Screens2ShotTest {
    @get:Rule val compose = createComposeRule()

    private val actions = listOf(
        DeckAction("apagar", "Apagar PC", "", "power", "red", true, quick = true),
        DeckAction("reiniciar", "Reiniciar", "", "restart", "red", true),
        DeckAction("suspender", "Suspender", "", "sleep", "amber", false),
        DeckAction("bloquear", "Bloquear", "", "lock", "amber", false, quick = true),
        DeckAction("captura", "Captura", "", "screenshot", "teal", false),
        DeckAction("grabar", "Grabar", "", "record", "red", false, quick = true),
        DeckAction("silencio", "Silenciar", "", "mute", "orange", false, quick = true),
        DeckAction("vol_mas", "Subir volumen", "", "volume-up", "teal", false),
        DeckAction("vol_menos", "Bajar volumen", "", "volume-down", "teal", false),
        DeckAction("play", "Play / pausa", "", "play", "orange", false, quick = true),
        DeckAction("discord", "Discord", "", "app", "orange", false),
        DeckAction("spotify", "Spotify", "", "app", "green", false),
    )
    /** Los 19 botones del panel real (config/actions.toml), para ver la hoja «Más» como se verá en el teléfono. */
    private val realActions = listOf(
        DeckAction("apagar", "Apagar PC", "", "power", "red", true, quick = true, power = "shutdown"),
        DeckAction("reiniciar", "Reiniciar", "", "restart", "amber", true, power = "restart"),
        DeckAction("cancelar-apagado", "Cancelar apagado", "", "cancel", "green", false, power = "cancel"),
        DeckAction("suspender", "Suspender", "", "sleep", "", true, power = "sleep"),
        DeckAction("grabar", "Grabar", "", "record", "red", false),
        DeckAction("captura", "Captura", "", "screenshot", "", false),
        DeckAction("bloquear", "Bloquear", "", "lock", "", false, quick = true, power = "lock"),
        DeckAction("volumen-menos", "Volumen −", "", "volume-down", "", false),
        DeckAction("volumen-mas", "Volumen +", "", "volume-up", "", false),
        DeckAction("silenciar", "Silenciar", "", "mute", "", false, quick = true),
        DeckAction("siguiente", "Siguiente", "", "next", "", false),
        DeckAction("limpiar-disco", "Limpiar disco", "", "restart", "amber", true),
        DeckAction("modo-noche", "Modo noche", "", "sleep", "amber", false),
        DeckAction("play-pausa", "Play / Pausa", "", "play", "", false, quick = true),
        DeckAction("discord", "Discord", "", "app", "", false, quick = true),
        DeckAction("spotify", "Spotify", "", "app", "green", false, quick = true),
        DeckAction("liberar-ram", "Liberar RAM", "", "restart", "teal", false),
        DeckAction("steam", "Steam", "", "app", "", false),
        DeckAction("opera", "Opera GX", "", "app", "red", false),
    )
    private val tel = Telemetry(
        cpu = 34, ramUsed = 23.6, ramTotal = 31.9, gpuTemp = 71, gpuUtil = 96, vramUsed = 6.4, vramTotal = 8.0,
        upcoming = listOf(Upcoming("timer", "temporizador", 299)),
    )
    private val song = MediaInfo("Spotify", "Costa Azul", "Luces de Neón", "Verano Eléctrico", true, 197.0, 293.9, "abc", true, true)
    private val game = GameInfo(
        key = "steam:730", name = "Counter-Strike 2", source = "Steam", played = 3_900.0, hours = 1_240.5, sizeGb = 33.6, art = "x",
        summary = "Durante más de dos décadas, Counter-Strike ha ofrecido una experiencia competitiva de primer nivel, moldeada por millones de jugadores de todo el mundo.",
        genres = listOf("Acción", "Disparos"), developer = "Valve", released = "21 AGO 2012", rating = 86, tags = listOf("Multijugador", "Competitivo"),
        achievements = 167, achUnlocked = 41, achRecent = listOf("Primera baja", "Desactivador"), players = 842_300,
        news = listOf(GameNews("Actualización de la temporada", 1_000_000.0), GameNews("Nuevo mapa en rotación", 990_000.0)), friends = listOf("Marta", "Leo"),
        match = MatchInfo(
            "Competitivo · Mirage", "7 – 4", 612.0, 1,
            listOf("Vida" to "62", "Dinero" to "3400", "K / D / A" to "12 / 7 / 3", "Arma" to "AK-47"),
            listOf("¡Bomba plantada!", "Ronda 12 · en juego"), "", listOf("#F0B34B", "#5C9DFF"),
        ),
    )
    private val base = UiState(
        actions = actions, voices = listOf(Voice("kokoro:ef_dora", "Dora · natural")), voice = "kokoro:ef_dora", link = LinkStatus.OPEN, assistant = "Vega",
        messages = listOf(
            Message(1, Role.USER, "¿Cómo está el PC?"),
            Message(2, Role.ASSISTANT, "El PC va bien: procesador al 21 %, la gráfica a 62 grados y con 7 de 8 GB ocupados."),
            Message(3, Role.USER, "Pon un temporizador de cinco minutos"),
            Message(4, Role.ASSISTANT, "Hecho. Temporizador de 5 minutos en marcha."),
        ),
        telemetry = tel, telemetryAt = 1_000, state = AssistantState.IDLE,
    )

    private fun art(from: Int, to: Int): ImageBitmap = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888).also { bmp ->
        val paint = android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, from, to, android.graphics.Shader.TileMode.CLAMP) }
        android.graphics.Canvas(bmp).drawRect(0f, 0f, 300f, 300f, paint)
    }.asImageBitmap()

    private fun model(ui: UiState, night: Boolean) = ScreenModel(
        ui = ui, clock = if (night) "21:48" else "14:05", date = "jueves 22 oct", state = AssistantState.IDLE, listening = true, micOn = true,
        battery = 91, charging = false, updateAvailable = false, hasToken = true, pulse = 0, pulseAmount = 0.9f, nowMs = 1_000, appVersion = 430000, layout = 10,
    )

    private fun shot(name: String, night: Boolean, afterMs: Long = 1_500, content: @Composable (Pal) -> Unit) {
        val p = if (night) NightPal else DayPal
        compose.mainClock.autoAdvance = false
        compose.setContent { Box(Modifier.fillMaxSize().background(p.bg)) { content(p) } }
        compose.mainClock.advanceTimeBy(afterMs)
        compose.onRoot().captureRoboImage("build/capturas/$name.png")
    }

    private fun music(night: Boolean): ScreenModel {
        val a = art(0xFF6A3DE8.toInt(), 0xFF2EC5FF.toInt())
        return model(base.copy(media = song, mediaAt = 1_000), night).copy(mediaArt = a, mediaAccent = accentOf(a), clockMs = { 1_000 }, musicLevel = { 0.6f })
    }

    private fun playing(night: Boolean) = model(base.copy(game = game, gameAt = 1_000), night)
        .copy(gameArt = art(0xFFE0455B.toInt(), 0xFF3A0F2A.toInt()), gameAccent = Color(0xFFE0455B), clockMs = { 91_000 })

    private val lyrics = Lyrics(
        true,
        listOf(
            "Las luces del puerto se encienden despacio",
            "y el mar le devuelve su azul a la noche",
            "no hay prisa en las calles ni miedo en el viento",
            "solo el eco lejano de un viejo motor",
            "llévame contigo hasta donde el mapa se acaba",
            "donde el asfalto se vuelve de sal",
            "que el verano nos busque en la ventanilla",
            "y el cielo se rinda sin hacer ruido",
            "cantamos bajito para no despertar",
            "la ciudad que se duerme debajo del puente",
            "las estrellas se apagan de una en una",
            "pero tu risa se queda encendida",
            "costa azul, costa azul",
            "guárdame un sitio en la orilla",
            "que al amanecer nos encuentre despiertos",
        ).mapIndexed { i, t -> LyricLine(i * 14.0, t) },
    )
    private val samples = (0..300).map { GameSample(60 + (it % 37), 55 + it / 20, 6.0, 30) }

    @Test fun musica_dia() = shot("S2_musica_dia", false) { p -> MusicScreen2(music(false), ScreenActions(), p) {} }
    @Test fun musica_noche() = shot("S2_musica_noche", true) { p -> MusicScreen2(music(true), ScreenActions(), p) {} }
    @Test fun letra_noche() = shot("S2_letra_noche", true) { p -> MusicScreen2(music(true).copy(lyrics = lyrics), ScreenActions(), p, startWithLyrics = true) {} }
    @Test fun juego_dia() = shot("S2_juego_dia", false) { p -> GameScreen2(playing(false), ScreenActions(), p, {}, samples, true) }
    @Test fun juego_noche() = shot("S2_juego_noche", true) { p -> GameScreen2(playing(true), ScreenActions(), p, {}, samples, true) }
    @Test fun mas_dia() = shot("S2_mas_dia", false) { p -> Box(Modifier.fillMaxSize().padding(10.dp)) { DeckSheet2(model(base, false), ScreenActions(), p, 0, true) {} } }
    @Test fun mas_noche() = shot("S2_mas_noche", true) { p -> Box(Modifier.fillMaxSize().padding(10.dp)) { DeckSheet2(model(base, true), ScreenActions(), p, 0, true) {} } }
    @Test fun mas_camara_dia() = shot("S2_mas_camara_dia", false) { p -> Box(Modifier.fillMaxSize().padding(10.dp)) { DeckSheet2(model(base.copy(actions = realActions, camera = com.asistente.personal.state.CameraInfo(true, true, "front")), false), ScreenActions(), p, 0, true) {} } }
    @Test fun mas_real_dia() = shot("S2_mas_real_dia", false) { p -> Box(Modifier.fillMaxSize().padding(10.dp)) { DeckSheet2(model(base.copy(actions = realActions), false), ScreenActions(), p, 0, true) {} } }
    @Test fun mas_real_noche() = shot("S2_mas_real_noche", true) { p -> Box(Modifier.fillMaxSize().padding(10.dp)) { DeckSheet2(model(base.copy(actions = realActions), true).copy(brainOnPhone = true, brainLabel = "IA: teléfono"), ScreenActions(), p, 0, true) {} } }
    @Test fun charla_dia() = shot("S2_charla_dia", false) { p -> ChatOverlay2(model(base, false), p) {} }
    @Test fun charla_noche() = shot("S2_charla_noche", true) { p -> ChatOverlay2(model(base, true), p) {} }
    @Test fun dormida() = shot("S2_dormida", true) { _ -> SleepScreen2(model(base, true).copy(sleeping = true)) }
}
