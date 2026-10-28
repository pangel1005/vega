package com.asistente.personal.ui

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.AnimatedVisibility
import kotlin.math.abs
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.styleIndex
import com.asistente.personal.styleName
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import com.asistente.personal.state.Message
import com.asistente.personal.state.Notice
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
import com.asistente.personal.ui.led.LedFaceView
import com.asistente.personal.ui.led.LedFont
import com.asistente.personal.ui.led.countdownText
import com.asistente.personal.ui.led.Glance
import com.asistente.personal.ui.led.LedOff
import com.asistente.personal.ui.led.Mood
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
    val faceColor: FaceColor = FaceColor.WARM,
    /** Carátula de lo que suena y su color; reloj del teléfono para avanzar la canción. */
    val mediaArt: ImageBitmap? = null,
    val mediaAccent: Color = Vega.Coral,
    val clockMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    val lyrics: com.asistente.personal.net.Lyrics? = null,
    /** Dónde piensa Vega: «IA: PC», «IA: teléfono» o cómo va la descarga de sus modelos. */
    val brainLabel: String = "IA: PC",
    val brainOnPhone: Boolean = false,
    /** Estilo «Cristal» (aurora y cristal) en vez de «Noche» (PROTOTIPO). */
    val glass: Boolean = false,
    /** Estilo «Nostromo»: terminal de fósforo ámbar en vez de la cara de LED. */
    val terminal: Boolean = false,
    /** Estilo «Orbe»: una esfera de luz que respira, en vez de la cara de LED. */
    val orb: Boolean = false,
    /** Contadores del oído de hoy (los lee la terminal cada pocos segundos). */
    val ears: () -> com.asistente.personal.local.EarsSnapshot = { com.asistente.personal.local.EarsSnapshot() },
    /** El próximo aviso del propio teléfono (alarma o recordatorio), para cuando el PC no lo cuenta. */
    val localNext: NextAlert? = null,
    /** Modo de lejos: tras 10 minutos sin nadie, solo la hora enorme y la última respuesta. */
    val far: Boolean = false,
    /** Memoria usada del teléfono (0 a 1), para la tarjeta «Cuerpo». */
    val phoneRam: () -> Float? = { null },
    /** Temperatura del teléfono en °C, para la tarjeta «Cuerpo». */
    val phoneTemp: () -> Float? = { null },
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
    val nextFaceColor: () -> Unit = {},
    /** Vega se duerme o se despierta: la actividad baja o devuelve el brillo de la pantalla. */
    val sleepChanged: (Boolean) -> Unit = {},
    val mediaControl: (String) -> Unit = {},
    val toggleBrain: () -> Unit = {},
    /** Quita una notificación del PC (null = todas). */
    val dismissNote: (Long?) -> Unit = {},
    val nextTouchFeel: () -> Unit = {},
    val toggleGlass: () -> Unit = {},
    val nextStyle: () -> Unit = {},
    /** Vuelve a cargar la IA del teléfono si falló. */
    val retryBrain: () -> Unit = {},
    val sleep: () -> Unit = {},
    val wake: () -> Unit = {},
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

/** Color de la luz de la cara: el elegido en reposo y al hablar; coral cuando te atiende. */
fun moodColor(mood: Mood, base: Color = Vega.Light): Color = when (mood) {
    Mood.LISTEN -> Vega.Coral
    Mood.THINK -> Vega.Amber
    Mood.HAPPY -> Vega.Mint
    Mood.ERROR, Mood.ALERT -> Vega.Red
    Mood.SLEEP -> Color(0xFF6E6760)
    Mood.OFFLINE -> Color(0xFF4A4642)
    else -> base
}

/** El arcoíris solo se ve en reposo y al hablar: los demás estados mantienen su color de aviso. */
fun showsRainbow(mood: Mood, faceColor: FaceColor) = faceColor == FaceColor.RAINBOW && (mood == Mood.IDLE || mood == Mood.TALK)

fun moodLabel(mood: Mood, assistant: String): String = when (mood) {
    Mood.IDLE -> "Di «${assistant.ifEmpty { "Vega" }}»"
    Mood.LISTEN -> "Te escucho"
    Mood.THINK -> "Pensando"
    Mood.TALK -> "Hablando"
    Mood.HAPPY -> "Hecho"
    Mood.SLEEP -> "Micrófono apagado"
    Mood.ERROR -> "Algo falló"
    Mood.ALERT -> "Confirma"
    Mood.OFFLINE -> "Sin conexión con el PC"
}

/**
 * Pantalla de Vega (horizontal, 800×360 dp), estilo «Vega · noche»:
 *
 *  ┌──────────── cara de LED ─────────────┐ ┌── hora ──────────┐
 *  │ ● estado                             │ │ 14:32  jueves 22 │
 *  │            (48 × 20 puntos)          │ ├── estado ────────┤
 *  │ lo último que dijo                   │ │ PC · gráfica ·   │
 *  └──────────────────────────────────────┘ │ batería · aviso  │
 *  [tecla] [tecla] [tecla] [tecla] [tecla] [tecla] [más]
 */
@Composable
fun VegaScreen(m: ScreenModel, a: ScreenActions, modifier: Modifier = Modifier) {
    val ui = m.ui
    val canAct = ui.link == LinkStatus.OPEN
    var showMore by remember { mutableStateOf(false) }
    var showMusic by remember { mutableStateOf(false) }
    var showChat by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showMusic) { showMusic = false }
    if (ui.media == null && showMusic) showMusic = false
    androidx.activity.compose.BackHandler(enabled = showMore) { showMore = false }

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
        // Un recordatorio dura lo que tarda en pasar por la cara (y un poco más).
        val reminderMs = (LedFont.crossSeconds(n.text) * 1000).toLong() + 1500
        delay(if (n.reminder) maxOf(6000L, reminderMs) else if (n.ok) 2500 else 6000)
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

    // Barra de notificaciones: se abre al tocarla, y sola unos segundos cuando llega una nueva.
    var notesOpen by remember { mutableStateOf(false) }
    var notesPeek by remember { mutableStateOf(false) }
    val newestFresh = ui.pcNotes.firstOrNull { it.id in ui.freshNotes }?.id
    LaunchedEffect(newestFresh) {
        if (newestFresh == null) return@LaunchedEffect
        notesPeek = true
        delay(NOTE_PEEK_MS)
        notesPeek = false
    }
    if (ui.pcNotes.isEmpty() && notesOpen) notesOpen = false

    // Vega mira lo que pasa: las teclas al pulsar, los avisos (a la izquierda) cuando llega uno,
    // la música (a la derecha) cuando cambia la canción.
    var glance by remember { mutableStateOf<Glance?>(null) }
    fun look(x: Float, y: Float) {
        glance = Glance((glance?.seq ?: 0) + 1, x, y)
    }
    LaunchedEffect(newestFresh) { if (newestFresh != null) look(-1f, -0.2f) }
    val song = ui.media?.let { it.title + it.artist }
    LaunchedEffect(song) { if (song != null) look(1f, -0.3f) }
    val press: (String) -> Unit = { id ->
        look(0f, 1f)
        a.press(id)
    }

    val color = moodColor(mood, m.faceColor.color)
    // Su ánimo tiñe los bordes de la pantalla mientras escucha, piensa o habla.
    val aura by animateColorAsState(
        when (mood) {
            Mood.LISTEN, Mood.THINK, Mood.TALK -> color.copy(alpha = 0.38f)
            // En reposo con música, los bordes toman el color de la carátula.
            else -> if (mood == Mood.IDLE && ui.media?.playing == true) m.mediaAccent.copy(alpha = 0.3f) else Vega.Line
        },
        tween(450),
        label = "aura",
    )
    // Estilo «Cristal»: la aurora toma el color de su ánimo, el de la música y un violeta de fondo.
    val aurora = remember { Aurora() }
    // Sin animar el cambio de color: cada color nuevo obliga a repintar los cristales (medido:
    // repintarlos varias veces por segundo daba ~3 tirones por segundo en el Redmi).
    val auroraMusic = if (ui.media != null) m.mediaAccent else Color(0xFFB79BFF)
    androidx.compose.runtime.SideEffect {
        val next = listOf(color, auroraMusic, Vega.Sky)
        if (next != aurora.colors) aurora.colors = next
    }
    Box(modifier.fillMaxSize().background(Vega.Bg)) {
        if (m.glass) AuroraBackground(aurora)
        androidx.compose.runtime.CompositionLocalProvider(LocalAurora provides if (m.glass) aurora else null) {
        if (sleepFade < 1f && !m.far) Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - sleepFade
                scaleX = 1f - 0.05f * sleepFade
                scaleY = 1f - 0.05f * sleepFade
            },
        ) {
            if (m.orb) OrbScreen(
                m, secondsLeft, canAct, onPress = press, onMore = { showMore = true }, onAttention = a.attention,
            ) else if (m.terminal) NostromoScreen(
                m, secondsLeft, canAct, onPress = press, onMore = { showMore = true }, onAttention = a.attention,
            ) else Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ui.pcNotes.isNotEmpty()) {
                        NotesRail(
                            notes = ui.pcNotes,
                            fresh = ui.freshNotes,
                            icons = m.noteIcons,
                            nowMs = m.wallMs,
                            open = notesOpen || notesPeek,
                            onToggle = {
                                notesOpen = !(notesOpen || notesPeek)
                                notesPeek = false
                            },
                            onDismiss = a.dismissNote,
                        )
                    }
                    FaceCard(
                        mood, color, m, glance, aura,
                        onChat = { if (recentChat(ui.messages).isNotEmpty()) showChat = true },
                        confirmFraction = ui.pending?.let { if (it.expiresIn > 0) secondsLeft.toFloat() / it.expiresIn else 0f },
                        onRetry = a.retryBrain,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { a.attention() },
                    )
                    // Lo que casi no cambia se guarda como imagen (Offscreen): mientras la cara se anima,
                    // estas tarjetas no se vuelven a dibujar.
                    Column(Modifier.width(250.dp).fillMaxHeight().cached(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClockCard(m, a, Modifier.fillMaxWidth(), aura)
                        // Mientras suena algo en el PC hay dos páginas: estado del PC y reproductor. Se
                        // cambia deslizando el dedo o con el selector; al empezar la música pasa sola a ella.
                        val media = ui.media
                        var musicPage by remember { mutableStateOf(media != null) }
                        LaunchedEffect(media != null) { musicPage = media != null }
                        if (media != null) PageSwitch(musicPage, if (m.brainOnPhone) "Cuerpo" else "PC") { musicPage = it }
                        Box(
                            Modifier.fillMaxWidth().weight(1f).pointerInput(media != null) {
                                if (media == null) return@pointerInput
                                var dx = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dx = 0f },
                                    onDragEnd = { if (abs(dx) > 40.dp.toPx()) musicPage = dx < 0 },
                                ) { change, amount ->
                                    change.consume()
                                    dx += amount
                                }
                            },
                        ) {
                            AnimatedContent(
                                targetState = musicPage && media != null,
                                transitionSpec = {
                                    // La música entra por la derecha; el estado del PC, por la izquierda.
                                    val dir = if (targetState) 1 else -1
                                    (slideInHorizontally(tween(260)) { it * dir } + fadeIn(tween(200))) togetherWith
                                        (slideOutHorizontally(tween(260)) { -it * dir } + fadeOut(tween(160)))
                                },
                                label = "páginas",
                            ) { showPlayer ->
                                val playing = ui.media
                                if (showPlayer && playing != null) {
                                    NowPlayingCard(
                                        playing, livePosition(playing, ui.mediaAt, m.clockMs), m.mediaArt, m.mediaAccent,
                                        onControl = a.mediaControl, onOpen = { showMusic = true },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    if (m.brainOnPhone) BodyCard(m, ui.telemetry?.upcoming.orEmpty(), elapsed, Modifier.fillMaxSize(), aura)
                                    else StatusCard(ui.telemetry, m.battery, m.charging, ui.telemetry?.upcoming.orEmpty(), elapsed, Modifier.fillMaxSize(), aura)
                                }
                            }
                        }
                    }
                }
                KeyRow(
                    favorites = ui.actions.filter { it.quick },
                    pending = ui.pending,
                    secondsLeft = secondsLeft,
                    enabled = canAct,
                    onPress = press,
                    onMore = {
                        look(0.6f, 1f)
                        showMore = true
                    },
                    modifier = Modifier.fillMaxWidth().height(66.dp).cached(),
                    icons = m.icons,
                    media = ui.media,
                )
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
                    Deck(
                        actions = ui.actions,
                        pending = ui.pending,
                        secondsLeft = secondsLeft,
                        enabled = canAct,
                        onPress = a.press,
                        onClose = { showMore = false },
                        voiceLabel = ui.voices.firstOrNull { it.id == ui.voice }?.label,
                        onNextVoice = a.nextVoice,
                        faceColor = m.faceColor,
                        onNextFaceColor = a.nextFaceColor,
                        brainLabel = m.brainLabel,
                        brainOnPhone = m.brainOnPhone,
                        onToggleBrain = a.toggleBrain,
                        touchLabel = m.touchFeel.label,
                        onNextTouch = a.nextTouchFeel,
                        styleName = styleName(styleIndex(m.glass, m.terminal, m.orb)),
                        onNextStyle = a.nextStyle,
                        onOpenAndroid = a.openAndroid,
                        // Tocar dentro de la hoja (pero fuera de un botón) no la cierra.
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                        },
                        icons = m.icons,
                    )
                }
            }
        }
        }
        if (m.far && sleepFade == 0f) FarScreen(m)
        if (sleepFade > 0f) {
            SleepScreen(
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
        if (ui.pending != null && sleepFade == 0f && !m.far) AlertFrame()
        AnimatedVisibility(visible = showChat && sleepFade == 0f, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            ConversationOverlay(ui.messages, ui.assistant, onClose = { showChat = false })
        }
        // Pantalla «Sonando» (se abre tocando el mini reproductor).
        val playing = ui.media
        AnimatedVisibility(
            visible = showMusic && playing != null && sleepFade == 0f,
            enter = fadeIn(tween(220)) + androidx.compose.animation.scaleIn(tween(260), initialScale = 0.96f),
            exit = fadeOut(tween(180)) + androidx.compose.animation.scaleOut(tween(180), targetScale = 0.96f),
        ) {
            if (playing != null) {
                MusicScreen(
                    playing, ui.mediaAt, m.clockMs, m.mediaArt, m.mediaAccent, m.clock, m.lyrics,
                    onControl = a.mediaControl, onClose = { showMusic = false },
                )
            }
        }
    }
}

/** Capa en caché: se dibuja una vez y se reutiliza hasta que su contenido cambia. */
private fun Modifier.cached(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

/** La cara de LED con su estado arriba y lo último que dijo abajo. */
@Composable
private fun FaceCard(
    mood: Mood, color: Color, m: ScreenModel, glance: Glance?, aura: Color, onChat: () -> Unit = {},
    confirmFraction: Float? = null, onRetry: () -> Unit = {}, modifier: Modifier = Modifier,
) {
    val border by animateColorAsState(
        if (mood == Mood.ALERT || mood == Mood.ERROR) Vega.Red.copy(alpha = 0.7f) else aura,
        tween(250),
        label = "borde",
    )
    val notice = m.ui.notice?.takeIf { it.reminder }
    // Un halo muy suave del color de Vega detrás de la cara: da profundidad y cambia con su
    // estado (escuchando, pensando, avisando…). Solo se redibuja cuando cambia el color.
    val music = mood == Mood.IDLE && m.ui.media?.playing == true
    val glow by animateColorAsState(if (notice != null) Vega.Amber else if (music) m.mediaAccent else color, tween(700), label = "halo")
    Column(
        modifier.card(border).drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.16f), glow.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.47f),
                    radius = size.minDimension * 0.78f,
                ),
            )
        }.padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(7.dp))
            Text(moodLabel(mood, m.ui.assistant), maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = color))
            Spacer(Modifier.weight(1f))
            // Con la IA en el teléfono (o mientras se prepara) se ve aquí, junto al nombre.
            if (m.brainOnPhone) {
                Text(m.brainLabel, maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Mint))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                m.ui.assistant.uppercase().ifEmpty { "VEGA" },
                style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp, color = Vega.Ink3),
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp)) {
            LedFaceView(
                mood = mood,
                pulse = m.pulse,
                pulseAmount = m.pulseAmount,
                color = if (notice != null) Vega.Amber else color, // un recordatorio pasa en ámbar
                notice = notice?.text,
                noticeKey = notice?.seq ?: 0,
                rainbow = notice == null && showsRainbow(mood, m.faceColor),
                countdown = timerLeft(m)?.let(::countdownText),
                glance = glance,
                modifier = Modifier.fillMaxSize(),
            )
            // Bajo la cara, según lo que pase: la cuenta atrás de una confirmación (roja), la carga de la IA
            // (verde, o un botón para reintentar si falló) o, mientras te escucha, la onda de tu voz.
            val load = if (m.brainOnPhone) brainLoad(m.brainLabel) else BrainLoad.None
            val bottom = Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.55f)
            when {
                confirmFraction != null -> LedBar(confirmFraction, Vega.Red, bottom.height(10.dp))
                load.busy -> LedBar(load.fraction, Vega.Mint, bottom.height(10.dp))
                load.failed -> Row(
                    bottom.height(18.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onRetry),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LedBar(1f, Vega.Red, Modifier.weight(1f).height(10.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reintentar", maxLines = 1, style = Vega.label.copy(color = Vega.Red))
                }
                mood == Mood.LISTEN -> VoiceScope(m.pulse, m.pulseAmount, color, bottom.height(20.dp))
            }
        }
        // Tocar la respuesta abre la conversación reciente en letra grande.
        Subtitle(
            m,
            Modifier.fillMaxWidth().height(56.dp).cached() // 42 dp cortaba la segunda línea
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onChat),
        )
    }
}

/**
 * Segundos que le quedan al temporizador más próximo, contados cada segundo: el del PC (llega
 * con la telemetría) o el del teléfono (IA sin PC). Null si no hay ninguno en marcha.
 */
@Composable
private fun timerLeft(m: ScreenModel): Long? {
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

/**
 * Vega dormida (PC apagado): cara dormida, reloj grande del teléfono (sirve de reloj de
 * noche) y qué pasa. Se despierta sola cuando el PC vuelve a contestar.
 */
@Composable
private fun SleepScreen(m: ScreenModel, modifier: Modifier = Modifier) {
    val message = if (m.sleeping) "Dormida · toca la pantalla para despertarme" else when (m.ui.pcOff) {
        "restart" -> "El PC se está reiniciando · vuelvo enseguida"
        "sleep" -> "El PC está en suspensión · me despierto cuando lo despiertes"
        else -> "El PC está apagado · me despierto cuando lo enciendas"
    }
    Row(
        modifier.fillMaxSize().background(Vega.Bg).safeDrawingPadding().padding(horizontal = 28.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LedFaceView(
            mood = Mood.SLEEP, pulse = 0, pulseAmount = 0f, color = Color(0xFF6E6760),
            notice = null, noticeKey = 0, modifier = Modifier.weight(1f).fillMaxHeight(0.7f),
        )
        Spacer(Modifier.width(28.dp))
        Column(Modifier.width(250.dp)) {
            Text(m.clock, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 64.sp, color = Color(0xFF8A847C)))
            Text(m.date, style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink3))
            Spacer(Modifier.height(18.dp))
            Text(message, style = Vega.body.copy(fontSize = 15.sp, lineHeight = 19.sp, color = Vega.Ink2))
            nextAlert(m.ui.telemetry?.upcoming.orEmpty(), m.ui.telemetryAt, m.nowMs, m.wallMs, m.localNext)?.let {
                Spacer(Modifier.height(10.dp))
                Text(nextAlertLabel(it, m.wallMs), style = Vega.label.copy(fontSize = 13.sp, color = Vega.Amber))
            }
            if (m.battery >= 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Teléfono ${m.battery} %" + if (m.charging) " · cargando" else "",
                    style = Vega.label.copy(color = if (m.battery <= 15 && !m.charging) Vega.Red else Vega.Ink3),
                )
            }
        }
    }
}

/** Selector «PC | Música» (solo mientras suena algo). Salta al tocar. */
@Composable
private fun PageSwitch(music: Boolean, firstLabel: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(Vega.Card, RoundedCornerShape(50)).padding(2.dp),
    ) {
        for ((label, isMusic) in listOf(firstLabel to false, "Música" to true)) {
            val pressed = remember { mutableStateOf(false) }
            val selected = music == isMusic
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (selected) Vega.CardHi else Color.Transparent, RoundedCornerShape(50))
                    .instantPress(enabled = true, pressed = pressed) { onChange(isMusic) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = Vega.label.copy(color = if (selected) Vega.Ink else Vega.Ink3))
            }
        }
    }
}

/** Hora en grande (tipografía de puntos), fecha y los botones del sistema. */
@Composable
private fun ClockCard(m: ScreenModel, a: ScreenActions, modifier: Modifier = Modifier, border: Color = Vega.Line) {
    val ui = m.ui
    Column(modifier.card(border).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(m.clock, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 36.sp, lineHeight = 36.sp, color = Vega.Ink))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(m.date, maxLines = 1, style = Vega.label)
                val (link, linkColor) = when (ui.link) {
                    LinkStatus.OPEN -> "En línea" to Vega.Mint
                    LinkStatus.CONNECTING -> "Conectando…" to Vega.Amber
                    LinkStatus.RECONNECTING -> "Reconectando…" to Vega.Amber
                    LinkStatus.DENIED -> "Acceso denegado" to Vega.Red
                }
                if (lowBattery(m.battery, m.charging)) Text("Batería ${m.battery} %", maxLines = 1, style = Vega.label.copy(color = Vega.Red))
                else Text(link, maxLines = 1, style = Vega.label.copy(color = linkColor))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val micOn = m.listening && m.micOn
            Pill(if (micOn) "Oyendo" else "Micro apagado", if (micOn) Vega.Coral else Vega.Ink2, a.toggleMic, filled = micOn)
            if (m.updateAvailable) Pill("Actualizar", Vega.Amber, a.update, filled = true)
            Pill("Dormir", Vega.Ink2, a.sleep)
        }
    }
}

/** PC, gráfica y batería en barras de puntos, y el próximo aviso con cuenta atrás. */
@Composable
private fun StatusCard(
    t: Telemetry?, battery: Int, charging: Boolean, upcoming: List<Upcoming>, elapsed: Long, modifier: Modifier = Modifier,
    border: Color = Vega.Line,
) {
    Column(modifier.card(border).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
        StatRow("Procesador", t?.cpu?.let { "$it %" }, t?.cpu?.div(100f))
        // Temperatura de 30 °C (vacía) a 90 °C (llena).
        StatRow("Gráfica", t?.gpuTemp?.let { "$it °C" }, t?.gpuTemp?.let { (it - 30) / 60f })
        StatRow("Memoria gráfica", t?.vramUsed?.let { "${gb(it)} GB" }, vram)
        StatRow("Batería", if (battery >= 0) "$battery %" else null, if (battery >= 0) battery / 100f else null, invert = true, charging = charging)
        Spacer(Modifier.weight(1f))
        if (upcoming.isEmpty()) Text("Sin recordatorios pendientes", maxLines = 1, style = Vega.label.copy(color = Vega.Ink3))
        else UpcomingList(upcoming, elapsed, extra = 2)
    }
}

/** El próximo aviso en grande con su cuenta atrás y, debajo, hasta `extra` más en una línea cada uno (con `extra` = 0, solo «+n»). */
@Composable
private fun UpcomingList(upcoming: List<Upcoming>, elapsed: Long, extra: Int) {
    val next = upcoming.first()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(countdown(next.dueIn - elapsed), style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Vega.Amber))
        Spacer(Modifier.width(8.dp))
        Text(
            next.text + if (extra == 0 && upcoming.size > 1) "  +${upcoming.size - 1}" else "",
            maxLines = 2,
            style = Vega.label.copy(fontSize = 12.sp, lineHeight = 14.sp, color = Vega.Ink),
        )
    }
    upcoming.drop(1).take(extra).forEach { u ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(countdown(u.dueIn - elapsed), style = Vega.label.copy(fontSize = 12.sp, color = Vega.Amber.copy(alpha = 0.8f)), modifier = Modifier.width(52.dp))
            Text(u.text, maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2))
        }
    }
}

/** Con la IA en el teléfono: cómo está el propio teléfono (cerebro, oído, PC, memoria, batería) y el próximo aviso. */
@Composable
private fun BodyCard(m: ScreenModel, upcoming: List<Upcoming>, elapsed: Long, modifier: Modifier = Modifier, border: Color = Vega.Line) {
    val ears by produceState(com.asistente.personal.local.EarsSnapshot(), m.ears) {
        while (true) {
            value = m.ears()
            delay(2_000)
        }
    }
    val ram by produceState<Float?>(null, m.phoneRam) {
        while (true) {
            value = m.phoneRam()
            delay(5_000)
        }
    }
    val temp by produceState<Float?>(null, m.phoneTemp) {
        while (true) {
            value = m.phoneTemp()
            delay(10_000)
        }
    }
    val t = m.ui.telemetry.takeIf { m.ui.link == LinkStatus.OPEN }
    Column(modifier.card(border).padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        brainLoad(m.brainLabel).let { TextRow("Cerebro", m.brainLabel.removePrefix("IA: "), if (it.failed) Vega.Red else if (it.busy) Vega.Amber else Vega.Mint) }
        TextRow("Oído hoy", if (m.micOn) earsText(ears.heard, ears.calls) else "micro apagado", if (m.micOn) Vega.Ink else Vega.Amber)
        TextRow("PC", t?.let { "CPU ${it.cpu} %" + (it.gpuTemp?.let { g -> " · $g °C" } ?: "") } ?: "sin enlace", if (t != null) Vega.Ink else Vega.Ink3)
        StatRow("Memoria", ram?.let { "${(it * 100).toInt()} %" }, ram)
        StatRow("Temperatura", temp?.let { "${it.toInt()} °C" }, temp?.let(::tempFraction))
        StatRow("Batería", if (m.battery >= 0) "${m.battery} %" else null, if (m.battery >= 0) m.battery / 100f else null, invert = true, charging = m.charging)
        Spacer(Modifier.weight(1f))
        val next = upcoming.firstOrNull()
        val local = timerLeft(m)
        when {
            next != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countdown(next.dueIn - elapsed), style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Vega.Amber))
                Spacer(Modifier.width(8.dp))
                Text(next.text + if (upcoming.size > 1) "  +${upcoming.size - 1}" else "", maxLines = 2, style = Vega.label.copy(fontSize = 12.sp, lineHeight = 14.sp, color = Vega.Ink))
            }
            local != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countdownText(local), style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Vega.Amber))
                Spacer(Modifier.width(8.dp))
                Text("temporizador", maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink))
            }
            else -> Text("Sin recordatorios pendientes", maxLines = 1, style = Vega.label.copy(color = Vega.Ink3))
        }
    }
}

/** Nombre a la izquierda y valor (de color) a la derecha, sin barra. */
@Composable
private fun TextRow(name: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, maxLines = 1, style = Vega.label)
        Text(value, maxLines = 1, style = Vega.label.copy(color = color, textAlign = androidx.compose.ui.text.style.TextAlign.End), modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

/**
 * Una línea de estado: nombre, valor y 10 puntos que se encienden. Los últimos puntos
 * pasan a ámbar y rojo (o al revés con `invert`, para la batería: poca = mal).
 */
@Composable
private fun StatRow(name: String, value: String?, fraction: Float?, invert: Boolean = false, charging: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, maxLines = 1, style = Vega.label, modifier = Modifier.weight(1f))
        DotBar(fraction, invert, charging, Modifier.width(62.dp).height(7.dp))
        Text(value ?: "—", maxLines = 1, style = Vega.label.copy(color = Vega.Ink), modifier = Modifier.width(50.dp).padding(start = 8.dp))
    }
}

@Composable
private fun DotBar(fraction: Float?, invert: Boolean, charging: Boolean, modifier: Modifier = Modifier) {
    val off = com.asistente.personal.ui.led.ledOff
    Canvas(modifier) {
        val n = 10
        val step = size.width / n
        val r = minOf(step, size.height) * 0.42f
        val lit = if (fraction == null) 0 else (fraction.coerceIn(0f, 1f) * n + 0.5f).toInt()
        val f = fraction ?: 0f
        val color = when {
            fraction == null -> off
            charging -> Vega.Mint // cargando: en verde
            !invert && f >= 0.9f || invert && f <= 0.15f -> Vega.Red
            !invert && f >= 0.75f || invert && f <= 0.3f -> Vega.Amber
            else -> Vega.Light
        }
        for (i in 0 until n) drawCircle(if (i < lit) color else off, r, Offset(step * (i + 0.5f), size.height / 2))
    }
}

/**
 * Lo último que dijo Vega (o tu orden mientras piensa). Si no hay enlace, hay que confirmar
 * algo o llega un aviso, dice eso.
 */
@Composable
private fun Subtitle(m: ScreenModel, modifier: Modifier = Modifier) {
    val ui = m.ui
    val pending = ui.pending
    val notice = ui.notice
    val line = when {
        ui.link == LinkStatus.DENIED || !m.hasToken ->
            Line("Error", "Sin token válido: ábrela desde el PC con scripts/android-install.ps1", Vega.Red)
        ui.link != LinkStatus.OPEN && !m.brainOnPhone -> Line(
            "Conexión",
            "No llego al PC" + (ui.retryInMs?.let { " · reintento en ${(it + 999) / 1000} s" } ?: "") + ". Revisa el cable USB.",
            Vega.Amber,
        )
        pending != null -> Line("Confirma", "${pending.message} Toca la tecla otra vez o di «sí».", Vega.Red)
        notice != null -> Line(if (notice.ok) "Aviso" else "Error", notice.text, if (notice.ok) Vega.Amber else Vega.Red)
        else -> lastLine(ui.messages, ui.assistant)
    }
    // Su respuesta se escribe letra a letra (rápido: ~110 por segundo), como si la dijera.
    val typing = line.typeKey
    var shown by remember(typing) { mutableIntStateOf(if (typing == null) Int.MAX_VALUE else 0) }
    LaunchedEffect(typing, line.text.length) {
        while (shown < line.text.length) {
            delay(18)
            shown += 2
        }
    }
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        if (line.who.isNotEmpty()) Text(line.who, maxLines = 1, style = Vega.label.copy(fontSize = 10.sp, color = line.color))
        Text(line.text.take(shown), maxLines = 2, style = Vega.body.copy(fontSize = 15.sp, lineHeight = 18.sp))
    }
}

/** Lo que muestra el subtítulo; `typeKey` (el mensaje) hace que se escriba letra a letra. */
private data class Line(val who: String, val text: String, val color: Color, val typeKey: Int? = null)

private fun lastLine(messages: List<Message>, assistant: String): Line {
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
