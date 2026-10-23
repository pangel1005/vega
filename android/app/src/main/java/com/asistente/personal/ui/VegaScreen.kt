package com.asistente.personal.ui

import androidx.compose.animation.AnimatedVisibility
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
    val asleep = m.hasToken && ui.link != LinkStatus.OPEN && ui.link != LinkStatus.DENIED && (ui.pcOff != null || longOffline)
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
    else moodFor(m.state, ui.link, ui.pending, ui.notice, m.listening && m.micOn, m.hasToken)
    val color = moodColor(mood, m.faceColor.color)
    Box(modifier.fillMaxSize().background(Vega.Bg)) {
        if (sleepFade < 1f) Box(
            Modifier.fillMaxSize().graphicsLayer {
                alpha = 1f - sleepFade
                scaleX = 1f - 0.05f * sleepFade
                scaleY = 1f - 0.05f * sleepFade
            },
        ) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FaceCard(
                        mood, color, m,
                        Modifier.weight(1f).fillMaxHeight()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { a.attention() },
                    )
                    // Lo que casi no cambia se guarda como imagen (Offscreen): mientras la cara se anima,
                    // estas tarjetas no se vuelven a dibujar.
                    Column(Modifier.width(250.dp).fillMaxHeight().cached(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ClockCard(m, a, Modifier.fillMaxWidth())
                        // Mientras suena algo en el PC, el mini reproductor ocupa el sitio del estado del PC.
                        val media = ui.media
                        if (media != null) {
                            NowPlayingCard(
                                media, livePosition(media, ui.mediaAt, m.clockMs), m.mediaArt, m.mediaAccent,
                                onControl = a.mediaControl, onOpen = { showMusic = true },
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        } else {
                            StatusCard(ui.telemetry, m.battery, m.charging, ui.telemetry?.upcoming.orEmpty(), elapsed, Modifier.fillMaxWidth().weight(1f))
                        }
                    }
                }
                KeyRow(
                    favorites = ui.actions.filter { it.quick },
                    pending = ui.pending,
                    secondsLeft = secondsLeft,
                    enabled = canAct,
                    onPress = a.press,
                    onMore = { showMore = true },
                    modifier = Modifier.fillMaxWidth().height(66.dp).cached(),
                    icons = m.icons,
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
                        // Tocar dentro de la hoja (pero fuera de un botón) no la cierra.
                        modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
                        },
                        icons = m.icons,
                    )
                }
            }
        }
        if (sleepFade > 0f) SleepScreen(m, Modifier.graphicsLayer { alpha = sleepFade })
        // Pantalla «Sonando» (se abre tocando el mini reproductor).
        val playing = ui.media
        AnimatedVisibility(
            visible = showMusic && playing != null && sleepFade == 0f,
            enter = fadeIn(tween(220)) + androidx.compose.animation.scaleIn(tween(260), initialScale = 0.96f),
            exit = fadeOut(tween(180)) + androidx.compose.animation.scaleOut(tween(180), targetScale = 0.96f),
        ) {
            if (playing != null) {
                MusicScreen(
                    playing, livePosition(playing, ui.mediaAt, m.clockMs), m.mediaArt, m.mediaAccent, m.clock,
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
private fun FaceCard(mood: Mood, color: Color, m: ScreenModel, modifier: Modifier = Modifier) {
    val border by animateColorAsState(
        if (mood == Mood.ALERT || mood == Mood.ERROR) Vega.Red.copy(alpha = 0.7f) else Vega.Line,
        tween(250),
        label = "borde",
    )
    val notice = m.ui.notice?.takeIf { it.reminder }
    Column(modifier.card(border).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(color)
            Spacer(Modifier.width(7.dp))
            Text(moodLabel(mood, m.ui.assistant), maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = color))
            Spacer(Modifier.weight(1f))
            Text(
                m.ui.assistant.uppercase().ifEmpty { "VEGA" },
                style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 2.sp, color = Vega.Ink3),
            )
        }
        LedFaceView(
            mood = mood,
            pulse = m.pulse,
            pulseAmount = m.pulseAmount,
            color = if (notice != null) Vega.Amber else color, // un recordatorio pasa en ámbar
            notice = notice?.text,
            noticeKey = notice?.seq ?: 0,
            rainbow = notice == null && showsRainbow(mood, m.faceColor),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp),
        )
        Subtitle(m, Modifier.fillMaxWidth().height(42.dp).cached())
    }
}

private const val SLEEP_AFTER_MS = 20_000L
private const val SLEEP_FADE_MS = 1200

/**
 * Vega dormida (PC apagado): cara dormida, reloj grande del teléfono (sirve de reloj de
 * noche) y qué pasa. Se despierta sola cuando el PC vuelve a contestar.
 */
@Composable
private fun SleepScreen(m: ScreenModel, modifier: Modifier = Modifier) {
    val message = when (m.ui.pcOff) {
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

/** Hora en grande (tipografía de puntos), fecha y los botones del sistema. */
@Composable
private fun ClockCard(m: ScreenModel, a: ScreenActions, modifier: Modifier = Modifier) {
    val ui = m.ui
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 10.dp)) {
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
                Text(link, maxLines = 1, style = Vega.label.copy(color = linkColor))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val micOn = m.listening && m.micOn
            Pill(if (micOn) "Oyendo" else "Micro apagado", if (micOn) Vega.Coral else Vega.Ink2, a.toggleMic, filled = micOn)
            if (m.updateAvailable) Pill("Actualizar", Vega.Amber, a.update, filled = true)
            Pill("Android", Vega.Ink2, a.openAndroid)
        }
    }
}

/** PC, gráfica y batería en barras de puntos, y el próximo aviso con cuenta atrás. */
@Composable
private fun StatusCard(t: Telemetry?, battery: Int, charging: Boolean, upcoming: List<Upcoming>, elapsed: Long, modifier: Modifier = Modifier) {
    Column(modifier.card().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
        StatRow("Procesador", t?.cpu?.let { "$it %" }, t?.cpu?.div(100f))
        // Temperatura de 30 °C (vacía) a 90 °C (llena).
        StatRow("Gráfica", t?.gpuTemp?.let { "$it °C" }, t?.gpuTemp?.let { (it - 30) / 60f })
        StatRow("Memoria gráfica", t?.vramUsed?.let { "${gb(it)} GB" }, vram)
        StatRow("Batería", if (battery >= 0) "$battery %" else null, if (battery >= 0) battery / 100f else null, invert = true, charging = charging)
        Spacer(Modifier.weight(1f))
        val next = upcoming.firstOrNull()
        if (next == null) {
            Text("Sin recordatorios pendientes", maxLines = 1, style = Vega.label.copy(color = Vega.Ink3))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(countdown(next.dueIn - elapsed), style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Vega.Amber))
                Spacer(Modifier.width(8.dp))
                Text(
                    next.text + if (upcoming.size > 1) "  +${upcoming.size - 1}" else "",
                    maxLines = 2,
                    style = Vega.label.copy(fontSize = 12.sp, lineHeight = 14.sp, color = Vega.Ink),
                )
            }
        }
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
    Canvas(modifier) {
        val n = 10
        val step = size.width / n
        val r = minOf(step, size.height) * 0.42f
        val lit = if (fraction == null) 0 else (fraction.coerceIn(0f, 1f) * n + 0.5f).toInt()
        val f = fraction ?: 0f
        val color = when {
            fraction == null -> LedOff
            charging -> Vega.Mint // cargando: en verde
            !invert && f >= 0.9f || invert && f <= 0.15f -> Vega.Red
            !invert && f >= 0.75f || invert && f <= 0.3f -> Vega.Amber
            else -> Vega.Light
        }
        for (i in 0 until n) drawCircle(if (i < lit) color else LedOff, r, Offset(step * (i + 0.5f), size.height / 2))
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
    val (who, text, color) = when {
        ui.link == LinkStatus.DENIED || !m.hasToken ->
            Triple("Error", "Sin token válido: ábrela desde el PC con scripts/android-install.ps1", Vega.Red)
        ui.link != LinkStatus.OPEN -> Triple(
            "Conexión",
            "No llego al PC" + (ui.retryInMs?.let { " · reintento en ${(it + 999) / 1000} s" } ?: "") + ". Revisa el cable USB.",
            Vega.Amber,
        )
        pending != null -> Triple("Confirma", "${pending.message} Toca la tecla otra vez o di «sí».", Vega.Red)
        notice != null -> Triple(if (notice.ok) "Aviso" else "Error", notice.text, if (notice.ok) Vega.Amber else Vega.Red)
        else -> lastLine(ui.messages, ui.assistant)
    }
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        if (who.isNotEmpty()) Text(who, style = Vega.label.copy(fontSize = 10.sp, color = color))
        Text(text, maxLines = 2, style = Vega.body.copy(fontSize = 15.sp, lineHeight = 18.sp))
    }
}

private fun lastLine(messages: List<Message>, assistant: String): Triple<String, String, Color> {
    val last = messages.lastOrNull() ?: return Triple("", "Di «${assistant.ifEmpty { "Vega" }}» y lo que necesites.", Vega.Ink2)
    return when (last.role) {
        Role.USER -> Triple("Tú", last.text, Vega.Ink2)
        Role.ASSISTANT -> Triple(assistant.ifEmpty { "Asistente" }, last.text + if (last.streaming) " ▍" else "", Vega.Coral)
        Role.ERROR -> Triple("Error", last.text, Vega.Red)
        Role.NOTE -> Triple("Aviso", last.text, Vega.Amber)
    }
}
