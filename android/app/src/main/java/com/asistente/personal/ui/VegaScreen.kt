package com.asistente.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.asistente.personal.ui.led.LedBar
import com.asistente.personal.ui.led.LedFaceView
import com.asistente.personal.ui.led.LedOff
import com.asistente.personal.ui.led.LedText
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

fun moodColor(mood: Mood): Color = when (mood) {
    Mood.LISTEN -> Nerv.Teal
    Mood.THINK -> Nerv.Amber
    Mood.HAPPY -> Nerv.Green
    Mood.ERROR, Mood.ALERT -> Nerv.Red
    Mood.SLEEP -> Color(0xFFB0581A)
    Mood.OFFLINE -> Nerv.Mute
    else -> Nerv.Orange
}

private fun moodLabel(mood: Mood, assistant: String): Pair<String, String> = when (mood) {
    Mood.IDLE -> "待機" to "DI «${assistant.uppercase().ifEmpty { "SU NOMBRE" }}»"
    Mood.LISTEN -> "受信" to "TE ESCUCHO"
    Mood.THINK -> "思考" to "PENSANDO"
    Mood.TALK -> "発声" to "HABLANDO"
    Mood.HAPPY -> "完了" to "HECHO"
    Mood.SLEEP -> "休止" to "MICRÓFONO EN SILENCIO"
    Mood.ERROR -> "異常" to "ALGO FALLÓ"
    Mood.ALERT -> "警告" to "CONFIRMA"
    Mood.OFFLINE -> "接続断" to "SIN ENLACE"
}

private val mono = TextStyle(fontFamily = Nerv.Mono, letterSpacing = 1.5.sp)
private val cond = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold)
private val PanelBg = Color(0xFF0A0807)
private val PanelLine = Color(0xFF2A211B)

/**
 * Pantalla de Vega (horizontal, 800×360 dp). Todo con aspecto de panel de LED:
 *
 *  ┌ VEGA · enlace ──────────────────────── micrófono · hora · ANDROID ┐
 *  │  PC      ┌──────── visor: cara de LED 48×20 ────────┐  AVISOS     │
 *  │  barras  │                                          │  cuenta     │
 *  │  LED     └──────────────────────────────────────────┘  batería    │
 *  │               VEGA ▸ lo último que dijo                            │
 *  └ [tecla] [tecla] [tecla] [tecla] [tecla] [tecla] [MÁS] ─────────────┘
 */
@Composable
fun VegaScreen(m: ScreenModel, a: ScreenActions, modifier: Modifier = Modifier) {
    val ui = m.ui
    val canAct = ui.link == LinkStatus.OPEN
    var showMore by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showMore) { showMore = false }

    // Cuenta atrás de la confirmación (compartida por las teclas y la hoja «MÁS»).
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
        val reminderMs = (com.asistente.personal.ui.led.LedFont.crossSeconds(n.text) * 1000).toLong() + 1500
        delay(if (n.reminder) maxOf(6000L, reminderMs) else if (n.ok) 2500 else 6000)
        a.noticeExpired(n.seq)
    }
    // Al pulsar un botón desde «MÁS», la hoja se cierra cuando llega el resultado.
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

    val mood = moodFor(m.state, ui.link, ui.pending, ui.notice, m.listening && m.micOn, m.hasToken)
    val color = moodColor(mood)
    Box(modifier.fillMaxSize().dotTexture()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 12.dp)) {
            Header(m, a)
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp)) {
                PcPanel(ui.telemetry, Modifier.width(104.dp).fillMaxHeight())
                Visor(
                    mood = mood, color = color, m = m,
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 10.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { a.attention() },
                )
                PhonePanel(m.battery, m.charging, ui.telemetry?.upcoming.orEmpty(), elapsed, Modifier.width(104.dp).fillMaxHeight())
            }
            Subtitle(m, Modifier.fillMaxWidth().height(36.dp))
            KeyRow(
                favorites = ui.actions.filter { it.quick },
                pending = ui.pending,
                secondsLeft = secondsLeft,
                enabled = canAct,
                onPress = a.press,
                onMore = { showMore = true },
                modifier = Modifier.fillMaxWidth().height(58.dp).padding(bottom = 8.dp),
            )
        }
        if (ui.pending != null) Box(Modifier.fillMaxSize().hazardFrame(Nerv.Warn))
        AnimatedVisibility(
            visible = showMore,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f),
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
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
        }
    }
}

/** Fondo casi negro con una rejilla de puntos muy tenue (se calcula una vez por tamaño). */
private fun Modifier.dotTexture(): Modifier = drawWithCache {
    val step = 14.dp.toPx()
    val pts = ArrayList<Offset>()
    var y = step / 2
    while (y < size.height) {
        var x = step / 2
        while (x < size.width) {
            pts.add(Offset(x, y))
            x += step
        }
        y += step
    }
    val w = 2.4.dp.toPx()
    onDrawBehind {
        drawRect(Color(0xFF050404))
        drawPoints(pts, PointMode.Points, Color(0xFF17110E), strokeWidth = w, cap = StrokeCap.Round)
    }
}

@Composable
private fun Header(m: ScreenModel, a: ScreenActions) {
    val ui = m.ui
    val (linkText, linkColor) = when (ui.link) {
        LinkStatus.OPEN -> "ENLACE USB" to Nerv.Green
        LinkStatus.CONNECTING -> "CONECTANDO" to Nerv.Amber
        LinkStatus.RECONNECTING -> "RECONECTANDO" to Nerv.Amber
        LinkStatus.DENIED -> "ACCESO DENEGADO" to Nerv.Red
    }
    Row(Modifier.fillMaxWidth().height(34.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            ui.assistant.uppercase().ifEmpty { "· · ·" },
            style = TextStyle(fontFamily = Nerv.Serif, fontSize = 20.sp, color = Nerv.Ink, letterSpacing = 4.sp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(6.dp).background(linkColor, CircleShape))
        Spacer(Modifier.width(5.dp))
        Text(linkText, style = mono.copy(fontSize = 9.sp, color = linkColor))
        Spacer(Modifier.weight(1f))
        val micColor = if (m.listening && m.micOn) Nerv.Teal else Nerv.Mute
        NervButton(
            if (!m.listening) "● MICRO APAGADO" else if (m.micOn) "● ESCUCHANDO" else "● MICRO…",
            micColor, enabled = true, onClick = a.toggleMic, ghost = true, small = true,
        )
        Spacer(Modifier.width(12.dp))
        Text(m.clock, style = cond.copy(fontSize = 19.sp, color = Nerv.Ink))
        Spacer(Modifier.width(6.dp))
        Text(m.date, style = mono.copy(fontSize = 8.sp, color = Nerv.Mute))
        if (m.updateAvailable) {
            Spacer(Modifier.width(10.dp))
            NervButton("ACTUALIZAR", Nerv.Amber, enabled = true, onClick = a.update, small = true)
        }
        Spacer(Modifier.width(10.dp))
        NervButton("ANDROID", Nerv.Mute, enabled = true, onClick = a.openAndroid, ghost = true, small = true)
    }
}

/** El visor: marco de aparato con la cara de LED dentro y el estado en la tira de arriba. */
@Composable
private fun Visor(mood: Mood, color: Color, m: ScreenModel, modifier: Modifier = Modifier) {
    val shape = remember { Chamfer(14.dp) }
    val tagShape = remember { Chamfer(4.dp) }
    val (jp, es) = moodLabel(mood, m.ui.assistant)
    val notice = m.ui.notice?.takeIf { it.reminder }
    Column(
        modifier
            .background(PanelBg, shape)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(color, tagShape).padding(horizontal = 5.dp)) {
                Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 10.sp, color = Color(0xFF0A0A0A)))
            }
            Spacer(Modifier.width(7.dp))
            Text(es, maxLines = 1, style = mono.copy(fontSize = 9.sp, letterSpacing = 2.5.sp, color = color))
            Spacer(Modifier.weight(1f))
            Pilots(color)
        }
        LedFaceView(
            mood = mood,
            pulse = m.pulse,
            pulseAmount = m.pulseAmount,
            color = color,
            notice = notice?.text,
            noticeKey = notice?.seq ?: 0,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 4.dp),
        )
    }
}

/** Tres pilotos en la esquina del visor; el primero, encendido en el color del estado. */
@Composable
private fun Pilots(color: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(5.dp).background(color, CircleShape))
        Box(Modifier.size(5.dp).background(PanelLine, CircleShape))
        Box(Modifier.size(5.dp).background(PanelLine, CircleShape))
    }
}

/** Panel izquierdo: el PC en barras de LED (procesador, gráfica, memoria de la gráfica). */
@Composable
private fun PcPanel(t: Telemetry?, modifier: Modifier = Modifier) {
    SidePanel("PC", "演算", modifier) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            val temp = t?.gpuTemp
            val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
            Meter("CPU", t?.cpu?.let { "$it%" }, t?.cpu?.div(100f), Nerv.Orange)
            // Temperatura de 30 °C (vacía) a 90 °C (llena).
            Meter("GPU", temp?.let { "$it°" }, temp?.let { (it - 30) / 60f }, Nerv.Teal)
            Meter("VRAM", t?.vramUsed?.let { gb(it) }, vram, Nerv.Teal)
        }
        Text(
            if (t != null) "RAM ${gb(t.ramUsed)}/${gb(t.ramTotal)}" else "SIN DATOS",
            maxLines = 1,
            style = mono.copy(fontSize = 8.sp, letterSpacing = 0.5.sp, color = Nerv.Mute, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        )
    }
}

@Composable
private fun Meter(label: String, value: String?, fraction: Float?, color: Color) {
    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value ?: "--", style = cond.copy(fontSize = 12.sp, color = if (value == null) Nerv.Dim else Nerv.Ink))
        LedBar(fraction, color, Modifier.width(14.dp).weight(1f).padding(vertical = 2.dp))
        Text(label, style = mono.copy(fontSize = 7.sp, letterSpacing = 0.5.sp, color = Nerv.Mute))
    }
}

/** Panel derecho: el próximo aviso con cuenta atrás en LED y la batería del teléfono. */
@Composable
private fun PhonePanel(battery: Int, charging: Boolean, upcoming: List<Upcoming>, elapsed: Long, modifier: Modifier = Modifier) {
    SidePanel("AVISOS", "予定", modifier) {
        val next = upcoming.firstOrNull()
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center) {
            if (next == null) {
                Text(
                    "NADA\nPENDIENTE",
                    style = mono.copy(fontSize = 9.sp, color = Nerv.Dim, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LedText(countdown(next.dueIn - elapsed), Nerv.Amber, Modifier.fillMaxWidth().height(26.dp))
                Text(
                    next.text,
                    maxLines = 2,
                    style = cond.copy(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 13.sp, color = Nerv.Ink, textAlign = TextAlign.Center),
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                )
                if (upcoming.size > 1) {
                    Text(
                        "+${upcoming.size - 1} MÁS",
                        style = mono.copy(fontSize = 8.sp, color = Nerv.Mute, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (battery >= 0) "$battery%" else "--", style = cond.copy(fontSize = 12.sp, color = if (charging) Nerv.Green else Nerv.Ink))
            Spacer(Modifier.width(4.dp))
            Text(if (charging) "CARGANDO" else "BATERÍA", style = mono.copy(fontSize = 7.sp, letterSpacing = 0.5.sp, color = Nerv.Mute))
        }
        BatteryDots(battery, charging)
    }
}

/** Batería en 10 LED. */
@Composable
private fun BatteryDots(battery: Int, charging: Boolean) {
    val lit = if (battery < 0) 0 else (battery + 9) / 10
    val color = if (battery in 0..15 && !charging) Nerv.Red else Nerv.Green
    Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        for (i in 0 until 10) Box(Modifier.size(6.dp).background(if (i < lit) color else LedOff, CircleShape))
    }
}

@Composable
private fun SidePanel(title: String, jp: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = remember { Chamfer(10.dp) }
    Column(
        modifier
            .background(PanelBg, shape)
            .border(1.dp, PanelLine, shape)
            .padding(horizontal = 8.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = mono.copy(fontSize = 9.sp, color = Nerv.Mute))
            Spacer(Modifier.weight(1f))
            Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 9.sp, color = Nerv.Orange.copy(alpha = 0.7f)))
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * Subtítulo: lo último que dijo Vega (o tu orden mientras piensa). Si no hay enlace, o hay
 * que confirmar algo, o llega un aviso, dice eso.
 */
@Composable
private fun Subtitle(m: ScreenModel, modifier: Modifier = Modifier) {
    val ui = m.ui
    val pending = ui.pending
    val notice = ui.notice
    val (who, text, color) = when {
        ui.link == LinkStatus.DENIED || !m.hasToken ->
            Triple("ERROR", "Sin token válido: ábrela desde el PC con scripts/android-install.ps1", Nerv.Red)
        ui.link != LinkStatus.OPEN -> Triple(
            "ENLACE",
            "Sin conexión con el PC" + (ui.retryInMs?.let { " · reintento en ${(it + 999) / 1000} s" } ?: "") + ". Revisa el cable USB.",
            Nerv.Amber,
        )
        pending != null -> Triple("CONFIRMA", "${pending.message} Toca la tecla otra vez o di «sí».", Nerv.Red)
        notice != null -> Triple(if (notice.ok) "AVISO" else "ERROR", notice.text, if (notice.ok) Nerv.Amber else Nerv.Red)
        else -> lastLine(ui.messages, ui.assistant)
    }
    Row(modifier.padding(horizontal = 116.dp), verticalAlignment = Alignment.CenterVertically) {
        if (who.isNotEmpty()) Text("$who ▸ ", style = mono.copy(fontSize = 10.sp, color = color))
        Text(text, maxLines = 2, style = TextStyle(fontFamily = Nerv.Cond, fontSize = 16.sp, lineHeight = 17.sp, color = Nerv.Ink))
    }
}

private fun lastLine(messages: List<Message>, assistant: String): Triple<String, String, Color> {
    val last = messages.lastOrNull() ?: return Triple("", "Di «${assistant.ifEmpty { "Vega" }}» y lo que necesites.", Nerv.Mute)
    return when (last.role) {
        Role.USER -> Triple("TÚ", last.text, Nerv.Mute)
        Role.ASSISTANT -> Triple(assistant.uppercase().ifEmpty { "ASISTENTE" }, last.text + if (last.streaming) " ▌" else "", Nerv.Orange)
        Role.ERROR -> Triple("ERROR", last.text, Nerv.Red)
        Role.NOTE -> Triple("AVISO", last.text, Nerv.Amber)
    }
}
