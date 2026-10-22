package com.asistente.personal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.state.UiState
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

private val mono = TextStyle(fontFamily = Nerv.Mono, letterSpacing = 1.5.sp)

/**
 * Consola de Vega (horizontal, pensada para 800×360 dp):
 *
 *  ┌ VEGA · enlace ──── cinta de datos ──── micrófono · hora · ANDROID ┐
 *  │ telemetría │        rostro         │          panal            │
 *  │ (PC, GPU,  │   estado · registro    │   (6 favoritos + MÁS)     │
 *  │ teléfono,  │                        │                           │
 *  │ próximo)   │                        │                           │
 *  └ pie: modelo · voz · enlace ───────────────────────────────────────┘
 */
@Composable
fun VegaScreen(m: ScreenModel, a: ScreenActions, modifier: Modifier = Modifier) {
    val ui = m.ui
    val state = m.state
    val canAct = ui.link == LinkStatus.OPEN
    var showMore by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = showMore) { showMore = false }

    // Cuenta atrás de la confirmación (compartida por el panal y la hoja «MÁS»).
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
        delay(if (n.ok) 4500 else 6000)
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

    val accent = accentFor(state)
    val danger = ui.pending != null
    Box(modifier.fillMaxSize()) {
        HexBackground(accent, fast = state == AssistantState.THINKING)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Header(m, a, accent)
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Column(Modifier.width(166.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TelemetryRail(ui.telemetry, m.battery, m.charging, Modifier.fillMaxWidth())
                    NextUp(ui.telemetry?.upcoming.orEmpty(), elapsed, canAct, Modifier.fillMaxWidth().weight(1f))
                }
                Column(
                    Modifier.weight(1f).fillMaxHeight().padding(horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Tocar el rostro = «te escucho» (no hace falta decir el nombre).
                    Face(
                        state = state,
                        pulse = m.pulse,
                        pulseAmount = m.pulseAmount,
                        modifier = Modifier.weight(1f)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                a.attention()
                            },
                    )
                    StateTag(state, m.listening && m.micOn, ui.assistant)
                    Spacer(Modifier.height(5.dp))
                    LogPanel(ui.messages, ui.assistant, Modifier.fillMaxWidth())
                }
                Honeycomb(
                    favorites = ui.actions.filter { it.quick },
                    pending = ui.pending,
                    secondsLeft = secondsLeft,
                    enabled = canAct,
                    onPress = a.press,
                    onMore = { showMore = true },
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                )
            }
            Footer(m)
        }
        if (danger) {
            Box(Modifier.fillMaxSize().hazardFrame(Nerv.Warn))
            ConfirmBanner(ui.pending?.message.orEmpty(), secondsLeft, Modifier.align(Alignment.TopCenter).padding(top = 30.dp))
        }
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
        NoticeBar(ui.notice, Modifier.align(Alignment.TopCenter).padding(top = 34.dp).widthIn(max = 560.dp))
        if (ui.link != LinkStatus.OPEN) LinkLost(ui.link, ui.retryInMs, m.hasToken, Modifier.align(Alignment.Center))
    }
}

/** Cabecera: nombre, enlace, cinta de datos reales, micrófono, hora, actualizar y ANDROID. */
@Composable
private fun Header(m: ScreenModel, a: ScreenActions, accent: Color) {
    val ui = m.ui
    val (linkText, linkColor) = when (ui.link) {
        LinkStatus.OPEN -> "ENLACE USB" to Nerv.Green
        LinkStatus.CONNECTING -> "CONECTANDO" to Nerv.Amber
        LinkStatus.RECONNECTING -> "RECONECTANDO" to Nerv.Amber
        LinkStatus.DENIED -> "ACCESO DENEGADO" to Nerv.Red
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            m.ui.assistant.uppercase().ifEmpty { "· · ·" },
            style = TextStyle(fontFamily = Nerv.Serif, fontSize = 22.sp, color = Nerv.Ink, letterSpacing = 4.sp),
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text("個人支援システム", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 8.sp, color = Nerv.Orange))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).background(linkColor))
                Spacer(Modifier.width(4.dp))
                Text(linkText, style = mono.copy(fontSize = 9.sp, color = linkColor))
            }
        }
        Spacer(Modifier.width(12.dp))
        Ticker(tickerText(m), accent.copy(alpha = 0.85f), Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        val micColor = if (m.listening && m.micOn) Nerv.Teal else Nerv.Mute
        NervButton(
            if (!m.listening) "● MUDO" else if (m.micOn) "● OÍDO" else "● MIC…",
            micColor, enabled = true, onClick = a.toggleMic, ghost = true, small = true,
        )
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(m.clock, style = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 20.sp, color = Nerv.Ink))
            Text(m.date, style = mono.copy(fontSize = 8.sp, color = Nerv.Mute))
        }
        if (m.updateAvailable) {
            Spacer(Modifier.width(10.dp))
            NervButton("ACTUALIZAR", Nerv.Amber, enabled = true, onClick = a.update, small = true)
        }
        Spacer(Modifier.width(10.dp))
        NervButton("ANDROID", Nerv.Mute, enabled = true, onClick = a.openAndroid, ghost = true, small = true)
    }
}

/** Lo que corre por la cinta: solo datos reales del momento. */
fun tickerText(m: ScreenModel): String {
    val ui = m.ui
    val t = ui.telemetry
    val parts = mutableListOf<String>()
    parts += "${m.ui.assistant.uppercase().ifEmpty { "ASISTENTE" }} EN LÍNEA"
    if (t != null) {
        parts += "PROCESADOR ${t.cpu} %"
        t.gpuTemp?.let { parts += "GRÁFICA $it °C" }
        if (t.vramUsed != null && t.vramTotal != null) parts += "MEMORIA GRÁFICA ${gb(t.vramUsed)}/${gb(t.vramTotal)} GB"
        t.upcoming.firstOrNull()?.let { parts += "PRÓXIMO: ${it.text.uppercase()}" }
    } else {
        parts += if (ui.link == LinkStatus.OPEN) "LEYENDO EL PC…" else "SIN ENLACE CON EL PC"
    }
    if (m.battery >= 0) parts += "TELÉFONO ${m.battery} %${if (m.charging) " CARGANDO" else ""}"
    ui.messages.lastOrNull { it.role == Role.USER }?.let { parts += "ÚLTIMA ORDEN: «${it.text}»" }
    return parts.joinToString("   ◆   ")
}

/** Estado con etiqueta de color: «待機 DI «VEGA»», «受信 TE ESCUCHO»… */
@Composable
private fun StateTag(state: AssistantState, listening: Boolean, assistant: String) {
    val (jp, es) = when (state) {
        AssistantState.IDLE -> "待機" to if (listening) "DI «${assistant.uppercase().ifEmpty { "SU NOMBRE" }}»" else "MICRÓFONO EN SILENCIO"
        AssistantState.LISTENING -> "受信" to "TE ESCUCHO"
        AssistantState.THINKING -> "審議中" to "PENSANDO"
        AssistantState.SPEAKING -> "発声" to "RESPONDIENDO"
        AssistantState.ACTING -> "実行" to "EJECUTANDO"
        AssistantState.ERROR -> "非常事態" to "ERROR"
    }
    Tag(jp, es, accentFor(state))
}

/** Registro: tu última orden y la última respuesta, en un panel con cabecera. */
@Composable
private fun LogPanel(messages: List<Message>, assistant: String, modifier: Modifier = Modifier) {
    val shape = remember { Chamfer(8.dp) }
    val last = messages.lastOrNull()
    // Lo último que dijiste antes de esa respuesta (si la respuesta es lo último).
    val you = messages.lastOrNull { it.role == Role.USER }
    Column(
        modifier
            .height(66.dp)
            .background(Color(0xCC0B0A09), shape)
            .border(1.dp, Nerv.Orange.copy(alpha = 0.28f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("通信記録", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 8.sp, color = Nerv.Orange))
            Spacer(Modifier.width(6.dp))
            Text("REGISTRO", style = mono.copy(fontSize = 8.sp, color = Nerv.Mute))
            Spacer(Modifier.weight(1f))
            if (you != null && last != you) {
                Text("TÚ ▸ ${you.text}", maxLines = 1, style = mono.copy(fontSize = 9.sp, color = Nerv.Mute), modifier = Modifier.widthIn(max = 220.dp))
            }
        }
        if (last == null) {
            Text("Di «${assistant.ifEmpty { "Vega" }}» y lo que necesites.", style = TextStyle(fontFamily = Nerv.Cond, fontSize = 16.sp, color = Nerv.Mute))
            return@Column
        }
        val (who, color) = when (last.role) {
            Role.USER -> "TÚ" to Nerv.Mute
            Role.ASSISTANT -> assistant.uppercase().ifEmpty { "ASISTENTE" } to Nerv.Orange
            Role.ERROR -> "ERROR" to Nerv.Red
            Role.NOTE -> "AVISO" to Nerv.Amber
        }
        Row(Modifier.padding(top = 2.dp)) {
            Text("$who ▸ ", style = mono.copy(fontSize = 10.sp, color = color), modifier = Modifier.padding(top = 3.dp))
            Text(
                last.text + if (last.streaming) " ▌" else "",
                maxLines = 2,
                style = TextStyle(
                    fontFamily = Nerv.Cond, fontSize = 16.sp, lineHeight = 18.sp,
                    color = if (last.role == Role.USER) Nerv.Mute else Nerv.Ink,
                ),
            )
        }
    }
}

/** Pie: qué está usando ahora mismo (modelo, voz) y una escala decorativa. */
@Composable
private fun Footer(m: ScreenModel) {
    val ui = m.ui
    val voice = ui.voices.firstOrNull { it.id == ui.voice }?.label
    Row(
        Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 28.dp, height = 3.dp).background(Nerv.Red))
        Spacer(Modifier.width(8.dp))
        val bits = buildList {
            if (ui.model.isNotEmpty()) add("MODELO ${ui.model.uppercase()}")
            if (voice != null) add("VOZ ${voice.uppercase()}")
            add("LOCAL · SIN NUBE")
        }
        Text(bits.joinToString("  //  "), maxLines = 1, style = mono.copy(fontSize = 8.sp, color = Nerv.Dim))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(6.dp)) { Ruler(Nerv.Dim, marks = 60) }
        Spacer(Modifier.width(10.dp))
        if (m.appVersion > 0) Text("APP ${m.appVersion}", style = mono.copy(fontSize = 8.sp, color = Nerv.Dim))
    }
}

/** Aviso grande arriba cuando hay que confirmar algo serio (apagar, reiniciar…). */
@Composable
private fun ConfirmBanner(message: String, secondsLeft: Int, modifier: Modifier = Modifier) {
    val shape = remember { Chamfer(10.dp) }
    Row(
        modifier
            .background(Color(0xF00A0A0A), shape)
            .border(1.5.dp, Nerv.Red, shape)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.background(Nerv.Red).padding(horizontal = 6.dp, vertical = 1.dp)) {
            Text("警告", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 16.sp, color = Color(0xFF0A0A0A)))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(message.ifEmpty { "¿Seguro?" }, maxLines = 1, style = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Nerv.Ink))
            Text("TOCA EL BOTÓN OTRA VEZ O DI «SÍ» · $secondsLeft S", style = mono.copy(fontSize = 9.sp, color = Nerv.Red))
        }
    }
}

/** Sin enlace con el PC: panel en el centro con lo que está pasando. */
@Composable
private fun LinkLost(link: LinkStatus, retryInMs: Long?, hasToken: Boolean, modifier: Modifier = Modifier) {
    val shape = remember { Chamfer(12.dp) }
    val denied = link == LinkStatus.DENIED || !hasToken
    val color = if (denied) Nerv.Red else Nerv.Amber
    val blink by animateFloatAsState(1f, tween(300), label = "aparecer")
    Column(
        modifier
            .graphicsLayer { alpha = blink }
            .background(Color(0xF00A0A0A), shape)
            .border(1.5.dp, color, shape)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (denied) "接続拒否" else "接続断", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 22.sp, color = color))
        Text(
            when {
                denied -> "SIN TOKEN VÁLIDO"
                link == LinkStatus.CONNECTING -> "CONECTANDO CON EL PC"
                else -> "SIN ENLACE CON EL PC"
            },
            style = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = 2.sp, color = Nerv.Ink),
        )
        Text(
            when {
                denied -> "ÁBRELA DESDE EL PC CON scripts/android-install.ps1"
                retryInMs != null -> "REINTENTO EN ${(retryInMs + 999) / 1000} S · REVISA EL CABLE USB"
                else -> "REVISA EL CABLE USB Y QUE EL PC ESTÉ ENCENDIDO"
            },
            style = mono.copy(fontSize = 9.sp, color = Nerv.Mute, textAlign = TextAlign.Center),
        )
    }
}
