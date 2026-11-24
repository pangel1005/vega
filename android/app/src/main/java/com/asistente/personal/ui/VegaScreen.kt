package com.asistente.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Message
import com.asistente.personal.state.Notice
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.led.Glance
import com.asistente.personal.ui.led.Mood
import com.asistente.personal.ui2.AvisosSheet2
import com.asistente.personal.ui2.ChatOverlay2
import com.asistente.personal.ui2.DeckSheet2
import com.asistente.personal.ui2.Expand
import com.asistente.personal.ui2.GameScreen2
import com.asistente.personal.ui2.Grow
import com.asistente.personal.ui2.GymScreen2
import com.asistente.personal.ui2.HomeLayout2
import com.asistente.personal.ui2.LocalRailInset
import com.asistente.personal.ui2.MesaSheet2
import com.asistente.personal.ui2.MusicScreen2
import com.asistente.personal.ui2.Origins
import com.asistente.personal.ui2.RailHost
import com.asistente.personal.ui2.SleepScreen2
import com.asistente.personal.ui2.TableroSheet2
import com.asistente.personal.ui2.palFor
import kotlinx.coroutines.delay

/**
 * Todo lo que la pantalla necesita, sin depender del controlador: así se puede dibujar
 * en el PC con datos de prueba (pruebas de captura) y ver el diseño sin el teléfono.
 */
data class ScreenModel(
    val ui: UiState,
    val clock: String,
    val date: String,
    /** Estado ya resuelto (p. ej. HABLANDO mientras suena la voz aunque el PC haya terminado). */
    val state: AssistantState,
    val listening: Boolean,
    val micOn: Boolean,
    val battery: Int,
    val charging: Boolean,
    val updateAvailable: Boolean,
    val hasToken: Boolean,
    val pulse: Int,
    val pulseAmount: Float,
    /** Milisegundos (reloj del teléfono) ahora mismo: con ellos se cuentan atrás los avisos. */
    val nowMs: Long,
    /** Número de compilación de la app (para saber si ya se actualizó). */
    val appVersion: Int = 0,
    /** Iconos reales de las apps del panel, por id de botón. */
    val icons: Map<String, ImageBitmap> = emptyMap(),
    /** Carátula de lo que suena y su color; reloj del teléfono para avanzar la canción. */
    val mediaArt: ImageBitmap? = null,
    val mediaAccent: Color = Vega.Coral,
    /** Carátula del juego abierto en el PC y su color (sección «Juego»). */
    val gameArt: ImageBitmap? = null,
    val gameAccent: Color = Vega.Mint,
    /** Carátulas de los últimos juegos (por id) y lecturas del PC de la sesión de juego (para la gráfica). */
    val recentArts: Map<String, ImageBitmap> = emptyMap(),
    val gameSamples: List<GameSample> = emptyList(),
    val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    val lyrics: com.asistente.personal.net.Lyrics? = null,
    /** Dónde piensa Vega: «IA: PC», «IA: teléfono» o cómo va la descarga de sus modelos. */
    val brainLabel: String = "IA: PC",
    val brainOnPhone: Boolean = false,
    /** ¿El teléfono está enchufado por USB (al PC)? Sin enlace, decide si el problema es el cable o el servidor. */
    val usbPlugged: Boolean = true,
    /** Miniaturas de los fondos de la «Mesa», por id. */
    val wallpaperThumbs: Map<String, ImageBitmap> = emptyMap(),
    /** La pantalla de música enseña la portada plana y cuadrada (true) o la portada hecha disco giratorio (false). */
    val musicFlat: Boolean = false,
    /** Volumen real de lo que suena en el PC (0 a 1): hace latir la portada. */
    val musicLevel: () -> Float = { 0f },
    /** Las bandas de frecuencia de Spotify (graves a agudos); vacío si no hay. Para las barras alrededor de la portada. */
    val musicSpectrum: () -> FloatArray = { FloatArray(0) },
    /** El próximo aviso del propio teléfono (alarma o recordatorio), para cuando el PC no lo cuenta. */
    val localNext: NextAlert? = null,
    /** Iconos de las apps que mandan notificaciones en el PC, por id de icono. */
    val noteIcons: Map<String, ImageBitmap> = emptyMap(),
    /** Hora real (ms desde 1970) para «hace 5 min» en las notificaciones. */
    val wallMs: Long = System.currentTimeMillis(),
    /** Fin del temporizador del teléfono (reloj `clockMs`), cuando Vega piensa sin el PC. */
    val localTimerEndsAt: Long? = null,
    val touchFeel: TouchFeel = TouchFeel.SOFT,
    /** Dormida a mano (botón o «duérmete»): pantalla de noche hasta que se toca. */
    val sleeping: Boolean = false,
)

class ScreenActions(
    val toggleMic: () -> Unit = {},
    val openAndroid: () -> Unit = {},
    val update: () -> Unit = {},
    val attention: () -> Unit = {},
    val press: (String) -> Unit = {},
    val nextVoice: () -> Unit = {},
    val confirmExpired: (String) -> Unit = {},
    val noticeExpired: (Int) -> Unit = {},
    /** Vega se duerme o se despierta: la actividad baja o devuelve el brillo de la pantalla. */
    val sleepChanged: (Boolean) -> Unit = {},
    val mediaControl: (String) -> Unit = {},
    /** Salta a un punto de la canción (segundos desde el principio). */
    val mediaSeek: (Double) -> Unit = {},
    val toggleBrain: () -> Unit = {},
    /** Quita una notificación del PC (null = todas). */
    val dismissNote: (Long?) -> Unit = {},
    val nextTouchFeel: () -> Unit = {},
    /** Doble toque en la pantalla de música: cambia entre el disco y la portada plana. */
    val toggleMusicFlat: () -> Unit = {},
    /** Vuelve a cargar la IA del teléfono si falló. */
    val retryBrain: () -> Unit = {},
    val sleep: () -> Unit = {},
    val wake: () -> Unit = {},
    /** Enciende o apaga la cámara del teléfono como cámara de Windows. */
    val toggleCamera: () -> Unit = {},
    /** Reintenta la conexión con el PC ahora mismo (un toque en la cara cuando no hay enlace). */
    val retryNow: () -> Unit = {},
    /** Elige la cámara: "front" o "back". */
    val cameraFacing: (String) -> Unit = {},
    /** La «Mesa»: pide el estado, mueve el volumen de un programa (clave, nivel 0–1, ¿al soltar?), lo silencia, y el fondo. */
    val deskRefresh: () -> Unit = {},
    val deskVolume: (String, Float, Boolean) -> Unit = { _, _, _ -> },
    val deskMute: (String, Boolean) -> Unit = { _, _ -> },
    val deskWallpaper: (String) -> Unit = {},
    val deskRandom: () -> Unit = {},
    val deskMore: () -> Unit = {},
    val deskPause: (Boolean) -> Unit = {},
    /** El tablero de Moodle: marca una entrega (por su uid) como hecha. */
    val taskDone: (String) -> Unit = {},
    /** La racha del gimnasio: enciende o apaga la llama de un día (AAAA-MM-DD; null = hoy). */
    val streakLight: (String?) -> Unit = {},
    val streakOut: (String?) -> Unit = {},
)

/** Qué cara pone Vega según lo que está pasando de verdad. */
fun moodFor(
    state: AssistantState,
    link: LinkStatus,
    pending: PendingConfirm?,
    notice: Notice?,
    listening: Boolean,
    hasToken: Boolean = true,
): Mood = when {
    link == LinkStatus.DENIED || !hasToken -> Mood.ERROR
    link != LinkStatus.OPEN -> Mood.OFFLINE
    pending != null -> Mood.ALERT
    notice != null && !notice.ok -> Mood.ERROR
    state == AssistantState.ERROR -> Mood.ERROR
    state == AssistantState.LISTENING -> Mood.LISTEN
    state == AssistantState.THINKING || state == AssistantState.ACTING -> Mood.THINK
    state == AssistantState.SPEAKING -> Mood.TALK
    notice != null && !notice.reminder -> Mood.HAPPY // un botón salió bien
    !listening -> Mood.SLEEP
    else -> Mood.IDLE
}

fun moodLabel(mood: Mood, assistant: String): String = when (mood) {
    Mood.IDLE -> "Di «${assistant.ifEmpty { "Vega" }}»"
    Mood.LISTEN -> "Te escucho"
    Mood.THINK -> "Pensando"
    Mood.TALK -> "Hablando"
    Mood.HAPPY -> "Hecho"
    Mood.SLEEP -> "Micrófono apagado"
    Mood.ERROR -> "Algo falló"
    Mood.ALERT -> "Confirma"
    Mood.OFFLINE -> "Sin conexión"
}

/**
 * Cuánto se queda a la vista un recordatorio: lo que se tarda en leerlo (unos 0,27 s por letra) y un poco
 * más, nunca menos de 6 s.
 */
internal fun reminderMs(text: String): Long = maxOf(6_000L, 3_000L + text.length * 267L)

/**
 * Pantalla de Vega (horizontal, 800×360 dp), diseño Vega 2 (`ui2`): el mosaico de la portada
 * y, encima, las hojas y pantallas que se abren desde él (Más, Mesa, avisos, racha, tablero,
 * música y juego), la pantalla de dormir y el marco rojo de las confirmaciones.
 */
@Composable
fun VegaScreen(m: ScreenModel, a: ScreenActions, modifier: Modifier = Modifier) {
    val ui = m.ui
    val canAct = ui.link == LinkStatus.OPEN
    val pal = palFor(m.clock)
    var showMore by remember { mutableStateOf(false) }
    var showMesa by remember { mutableStateOf(false) }
    var showTablero by remember { mutableStateOf(false) }
    var showRacha by remember { mutableStateOf(false) }
    var showAvisos by remember { mutableStateOf(false) }
    var showMusic by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    var showGame by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showMusic) { showMusic = false }
    androidx.activity.compose.BackHandler(enabled = showGame) { showGame = false }
    if (ui.media == null && showMusic) showMusic = false
    if (ui.game == null && showGame) showGame = false
    androidx.activity.compose.BackHandler(enabled = showMore) { showMore = false }
    androidx.activity.compose.BackHandler(enabled = showMesa) { showMesa = false }
    androidx.activity.compose.BackHandler(enabled = showTablero) { showTablero = false }
    androidx.activity.compose.BackHandler(enabled = showRacha) { showRacha = false }
    androidx.activity.compose.BackHandler(enabled = showAvisos) { showAvisos = false }

    // Cuenta atrás de la confirmación (compartida por las teclas y la hoja «Más»).
    var secondsLeft by remember { mutableIntStateOf(ui.pending?.expiresIn ?: 0) }
    LaunchedEffect(ui.pending?.nonce) {
        val p = ui.pending ?: return@LaunchedEffect
        for (s in p.expiresIn downTo 1) {
            secondsLeft = s
            delay(1000)
        }
        a.confirmExpired(p.nonce)
    }
    LaunchedEffect(ui.notice?.seq) {
        val n = ui.notice ?: return@LaunchedEffect
        delay(if (n.reminder) reminderMs(n.text) else if (n.ok) 2500 else 6000)
        a.noticeExpired(n.seq)
    }
    // Al pulsar un botón desde «Más», la hoja se cierra cuando llega el resultado.
    LaunchedEffect(ui.notice?.seq) { if (ui.notice != null && ui.pending == null) showMore = false }

    // Segundos desde la última lectura del PC: solo late cada segundo si hay avisos pendientes.
    var elapsed by remember { mutableLongStateOf(0L) }
    val counting = ui.telemetry?.upcoming?.isNotEmpty() == true
    LaunchedEffect(ui.telemetryAt, counting) {
        elapsed = ((m.nowMs - ui.telemetryAt) / 1000).coerceAtLeast(0)
        while (counting) {
            delay(1000)
            elapsed++
        }
    }

    // Vega se duerme si mandaste apagar el PC y se cortó el enlace, o si lleva 20 s sin enlace
    // (el PC se apagó por otro lado). Así no se queda con «reconectando» toda la noche.
    var longOffline by remember { mutableStateOf(false) }
    LaunchedEffect(ui.link) {
        longOffline = false
        if (ui.link == LinkStatus.CONNECTING || ui.link == LinkStatus.RECONNECTING) {
            delay(SLEEP_AFTER_MS)
            longOffline = true
        }
    }
    // Con la IA en el teléfono no se duerme: sigue escuchando y contestando sin el PC.
    val asleep = m.sleeping || (!m.brainOnPhone && m.hasToken && ui.link != LinkStatus.OPEN && ui.link != LinkStatus.DENIED &&
        (ui.pcOff != null || longOffline))
    // Al volver el PC se despierta contenta un momento.
    var waking by remember { mutableStateOf(false) }
    var wasAsleep by remember { mutableStateOf(false) }
    LaunchedEffect(asleep) {
        if (asleep) delay(SLEEP_FADE_MS.toLong()) // baja el brillo cuando ya terminó de dormirse
        a.sleepChanged(asleep)
        if (!asleep && wasAsleep) {
            waking = true
            delay(2500)
            waking = false
        }
        wasAsleep = asleep
    }
    // Transición suave entre despierta y dormida: la pantalla activa se apaga y se encoge un
    // poco mientras aparece la de dormir (y al revés al despertar).
    val sleepFade by animateFloatAsState(
        if (asleep) 1f else 0f, tween(SLEEP_FADE_MS, easing = FastOutSlowInEasing), label = "dormirse",
    )

    val mood = if (waking && ui.link == LinkStatus.OPEN) Mood.HAPPY
    else moodFor(m.state, if (m.brainOnPhone) LinkStatus.OPEN else ui.link, ui.pending, ui.notice, m.listening && m.micOn, m.hasToken)

    // Vega mira lo que pasa: las teclas al pulsar, los avisos (a la izquierda) cuando llega uno,
    // la música (a la derecha) cuando cambia la canción.
    var glance by remember { mutableStateOf<Glance?>(null) }
    fun look(x: Float, y: Float) {
        glance = Glance((glance?.seq ?: 0) + 1, x, y)
    }
    val newestFresh = ui.pcNotes.firstOrNull { it.id in ui.freshNotes }?.id
    LaunchedEffect(newestFresh) { if (newestFresh != null) look(-1f, -0.2f) }
    val song = ui.media?.let { it.title + it.artist }
    LaunchedEffect(song) { if (song != null) look(1f, -0.3f) }
    val press: (String) -> Unit = { id ->
        look(0f, 1f)
        a.press(id)
    }

    Box(modifier.fillMaxSize().background(pal.bg)) {
        if (sleepFade < 1f) Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - sleepFade
                scaleX = 1f - 0.05f * sleepFade
                scaleY = 1f - 0.05f * sleepFade
            },
        ) {
            // El riel de estado vive en la franja que deja el hueco de la cámara.
            RailHost(m)
            Column(
                Modifier.fillMaxSize().safeDrawingPadding()
                    .then(LocalRailInset.current?.let { Modifier.padding(end = it) } ?: Modifier)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeLayout2(
                    LayoutArgs(
                        m, a, mood, glance, elapsed, secondsLeft, canAct, press,
                        onMore = {
                            look(0.6f, 1f)
                            showMore = true
                        },
                        onChat = { if (recentChat(ui.messages).isNotEmpty()) showChat = true },
                        onOpenMusic = { showMusic = true },
                        onOpenGame = { showGame = true },
                        onMesa = { showMesa = true },
                        onTablero = { showTablero = true },
                        onRacha = { showRacha = true },
                        onOpenNotes = { showAvisos = true },
                        confirmFraction = ui.pending?.let { if (it.expiresIn > 0) secondsLeft.toFloat() / it.expiresIn else 0f },
                    ),
                )
            }
            AnimatedVisibility(
                visible = showMesa,
                enter = fadeIn(tween(120)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 900f)) { it / 4 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 4 },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xB3000000))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                showMesa = false
                            }
                        }
                        .safeDrawingPadding().padding(10.dp),
                ) {
                    MesaSheet2(
                        m, a, pal, onClose = { showMesa = false },
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                        },
                    )
                }
            }
            // Todos los avisos del PC: crecen desde su tarjeta del mosaico.
            Expand(visible = showAvisos, origin = Origins.nowTile) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp)) {
                    AvisosSheet2(
                        m, a, pal, onClose = { showAvisos = false },
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                        },
                    )
                }
            }
            Expand(visible = showRacha, origin = Origins.flame, grow = Grow.CIRCLE) {
                GymScreen2(
                    m, a, onClose = { showRacha = false },
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                    },
                )
            }
            AnimatedVisibility(
                visible = showTablero,
                enter = fadeIn(tween(120)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 900f)) { it / 4 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 4 },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xB3000000))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                showTablero = false
                            }
                        }
                        .safeDrawingPadding().padding(10.dp),
                ) {
                    TableroSheet2(
                        m, a, pal, onClose = { showTablero = false },
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                        },
                    )
                }
            }
            AnimatedVisibility(
                visible = showMore,
                enter = fadeIn(tween(120)) + slideInVertically(spring(dampingRatio = 0.85f, stiffness = 900f)) { it / 4 },
                exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 4 },
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xB3000000))
                        // Se traga los toques: antes atravesaban el fondo y pulsaban las teclas de debajo.
                        // Tocar fuera de la hoja la cierra.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown().consume()
                                showMore = false
                            }
                        }
                        .safeDrawingPadding().padding(10.dp),
                ) {
                    DeckSheet2(m, a, pal, secondsLeft, canAct, onClose = { showMore = false })
                }
            }
        }
        if (sleepFade > 0f) {
            SleepScreen2(
                m,
                Modifier.graphicsLayer { alpha = sleepFade }
                    // Se traga los toques (las teclas de debajo no deben pulsarse); si está dormida a
                    // mano, tocar la despierta.
                    .pointerInput(m.sleeping) {
                        awaitEachGesture {
                            awaitFirstDown().consume()
                            if (m.sleeping) a.wake()
                        }
                    },
            )
        }
        if (ui.pending != null && sleepFade == 0f) AlertFrame()
        AnimatedVisibility(visible = showChat && sleepFade == 0f, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            ChatOverlay2(m, pal, onClose = { showChat = false })
        }
        // La pantalla del juego crece desde su tarjeta del mosaico y vuelve a ella al cerrar.
        val playingGame = ui.game
        Expand(visible = showGame && playingGame != null && sleepFade == 0f, origin = Origins.nowTile) {
            if (playingGame != null) GameScreen2(m, a, pal, onClose = { showGame = false }, samples = m.gameSamples, canAct = canAct)
        }
        // La de la música, igual.
        val playing = ui.media
        Expand(visible = showMusic && playing != null && sleepFade == 0f, origin = Origins.nowTile) {
            if (playing != null) MusicScreen2(m, a, pal, onClose = { showMusic = false })
        }
    }
}

/** Capa en caché: se dibuja una vez y se reutiliza hasta que su contenido cambia. */
internal fun Modifier.cached(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/**
 * Segundos que le quedan al temporizador más próximo, contados cada segundo: el del PC (llega
 * con la telemetría) o el del teléfono (IA sin PC). Null si no hay ninguno en marcha.
 */
@Composable
internal fun timerLeft(m: ScreenModel): Long? {
    val pc = m.ui.telemetry?.upcoming?.firstOrNull { it.kind == "timer" }
    val endsAt = when {
        pc != null -> m.ui.telemetryAt + pc.dueIn * 1000L
        else -> m.localTimerEndsAt
    } ?: return null
    var now by remember { mutableLongStateOf(m.clockMs()) }
    LaunchedEffect(endsAt) {
        while (true) {
            now = m.clockMs()
            delay(1000 - now % 1000)
        }
    }
    return ((endsAt - now + 999) / 1000).takeIf { it > 0 }
}

private const val SLEEP_AFTER_MS = 20_000L
private const val SLEEP_FADE_MS = 1200

/** Lo último que se dijo; `typeKey` (el mensaje) hace que se escriba letra a letra. */
internal data class Line(val who: String, val text: String, val color: Color, val typeKey: Int? = null)

internal fun lastLine(messages: List<Message>, assistant: String): Line {
    val last = messages.lastOrNull() ?: return Line("", "Di «${assistant.ifEmpty { "Vega" }}» y lo que necesites.", Vega.Ink2)
    return when (last.role) {
        Role.USER -> Line("Tú", last.text, Vega.Ink2)
        Role.ASSISTANT -> {
            // Encima, lo que le dijiste: así se lee como una conversación.
            val asked = messages.getOrNull(messages.size - 2)?.takeIf { it.role == Role.USER }?.text
            Line(
                if (asked != null) "Tú: $asked" else assistant.ifEmpty { "Asistente" },
                last.text + if (last.streaming) " ▍" else "",
                if (asked != null) Vega.Ink3 else Vega.Coral,
                typeKey = last.id,
            )
        }
        Role.ERROR -> Line("Error", last.text, Vega.Red)
        Role.NOTE -> Line("Aviso", last.text, Vega.Amber)
    }
}
