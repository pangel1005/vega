package com.asistente.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.local.EarsSnapshot
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import kotlinx.coroutines.delay

/** Estilo «Nostromo»: una terminal de fósforo ámbar que cuenta, en vivo, qué oye, piensa y hace Vega. */
object Phosphor {
    val Bg = Color(0xFF0B0700)
    val Amber = Color(0xFFFFB000)
    val Dim = Color(0xFFB07A00)
    val Faint = Color(0xFF5A3D00)
    val Alert = Color(0xFFFF5A3A)
    val Ok = Color(0xFFFFD266)
}

/** El texto de una línea de estado: «> OÍDO ........ ACTIVO», con puntos de relleno hasta la columna. */
fun statusLine(label: String, value: String): String = "> " + (label + " ").padEnd(11, '.') + " " + value

/** Cómo se llama lo que hace Vega ahora, en el idioma de la terminal. */
fun terminalStatus(state: AssistantState, sleeping: Boolean, micOn: Boolean): String = when {
    sleeping -> "REPOSO"
    state == AssistantState.LISTENING -> "ESCUCHANDO"
    state == AssistantState.THINKING -> "PENSANDO"
    state == AssistantState.SPEAKING -> "HABLANDO"
    state == AssistantState.ACTING -> "ACTUANDO"
    state == AssistantState.ERROR -> "ERROR"
    micOn -> "A LA ESCUCHA"
    else -> "SILENCIO"
}

fun oidoLine(sleeping: Boolean, micOn: Boolean, brainOnPhone: Boolean, ears: EarsSnapshot): String = when {
    sleeping -> "DORMIDO"
    !micOn -> "MICRÓFONO APAGADO"
    brainOnPhone -> "ACTIVO · ${ears.heard} FRASES · ${ears.calls} LLAMADAS"
    else -> "ACTIVO"
}

/** La conversación como líneas de log (lo último abajo). `limit` líneas como mucho. */
fun logLines(messages: List<Message>, limit: Int): List<Pair<String, Boolean>> =
    messages.filter { it.role != Role.NOTE }.takeLast(limit).map {
        val who = when (it.role) {
            Role.USER -> "USUARIO"
            Role.ASSISTANT -> "VEGA"
            else -> "ERROR"
        }
        "> $who: ${it.text.replace('\n', ' ')}" to (it.role == Role.ASSISTANT)
    }

private fun Modifier.phosphor(): Modifier = drawWithContent {
    drawContent()
    // Líneas de barrido (cada 3 dp) y viñeta: la pantalla de una consola vieja.
    val step = 3.dp.toPx()
    var y = 0f
    while (y < size.height) {
        drawRect(Color(0x24000000), Offset(0f, y), Size(size.width, 1.2f))
        y += step
    }
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color(0x8C000000)), center = center, radius = size.maxDimension * 0.75f))
}

@Composable
private fun glow(size: Int, color: Color = Phosphor.Amber): TextStyle =
    TextStyle(fontFamily = Vega.Terminal, fontSize = size.sp, lineHeight = (size + 1).sp, color = color, shadow = Shadow(color.copy(alpha = 0.55f), blurRadius = 9f))

@Composable
fun NostromoScreen(
    m: ScreenModel,
    secondsLeft: Int,
    canAct: Boolean,
    onPress: (String) -> Unit,
    onMore: () -> Unit,
    onAttention: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = m.ui
    val ears by produceState(EarsSnapshot(), m.ears) {
        while (true) {
            value = m.ears()
            delay(2_000)
        }
    }
    val status = terminalStatus(m.state, m.sleeping, m.micOn)
    val telemetry = ui.telemetry
    val timer = (m.localTimerEndsAt ?: telemetry?.upcoming?.firstOrNull { it.kind == "timer" }?.let { m.ui.telemetryAt + it.dueIn * 1000L })
        ?.let { ((it - m.clockMs() + 999) / 1000).toInt() }?.takeIf { it > 0 }
    val lines = listOf(
        statusLine("OÍDO", oidoLine(m.sleeping, m.micOn, m.brainOnPhone, ears)),
        statusLine("CEREBRO", m.brainLabel.removePrefix("IA: ").uppercase() + if (!m.brainOnPhone && ui.model.isNotEmpty()) " · ${ui.model.uppercase()}" else ""),
        statusLine(
            "PC",
            if (ui.link == LinkStatus.OPEN && telemetry != null) "EN LÍNEA · CPU ${telemetry.cpu}%" + (telemetry.gpuTemp?.let { " · GPU $it°C" } ?: "")
            else if (m.brainOnPhone) "SIN ENLACE · TRABAJO SOLA" else "SIN ENLACE",
        ),
        statusLine(
            "AVISO",
            buildString {
                append(timer?.let { "%d:%02d TEMPORIZADOR".format(it / 60, it % 60) } ?: "NINGUNO")
                if (ui.pcNotes.isNotEmpty()) append(" · ${ui.pcNotes.size} AVISOS DEL PC")
            },
        ),
        statusLine("MÚSICA", ui.media?.let { (it.artist + " — " + it.title).trim(' ', '—').uppercase() } ?: "—"),
    )
    val cursor by produceState(true) {
        while (true) {
            delay(520)
            value = !value
        }
    }
    val log = logLines(ui.messages, 4)
    // La respuesta de Vega se escribe letra a letra, como en una terminal de verdad.
    val lastKey = ui.messages.lastOrNull()?.id
    var shown by remember(lastKey) { mutableIntStateOf(if (ui.messages.lastOrNull()?.role == Role.ASSISTANT) 0 else Int.MAX_VALUE) }
    LaunchedEffect(lastKey, log.lastOrNull()?.first?.length) {
        while (shown < (log.lastOrNull()?.first?.length ?: 0)) {
            delay(16)
            shown += 2
        }
    }

    Box(modifier.fillMaxSize().background(Phosphor.Bg).phosphor()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("VEGA//OS 4.2 · TERMINAL 01 · ${m.date.uppercase()}", glow(18, Phosphor.Dim), Modifier.weight(1f))
                Text(
                    "● $status", glow(19, if (m.state == AssistantState.ERROR) Phosphor.Alert else Phosphor.Amber),
                    Modifier.semantics { contentDescription = "Estado: $status" },
                )
                Spacer(Modifier.width(18.dp))
                Text(m.clock, glow(34, Phosphor.Ok))
            }
            Spacer(Modifier.height(6.dp))
            lines.forEach { Text(it, glow(20), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectTapGestures { onAttention() } },
                verticalArrangement = Arrangement.Bottom,
            ) {
                log.forEachIndexed { i, (line, vega) ->
                    val last = i == log.lastIndex
                    val text = if (last && vega) line.take(shown) else line
                    Text(
                        text + if (last && cursor) " █" else "",
                        glow(20, if (vega) Phosphor.Amber else Phosphor.Dim),
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (log.isEmpty()) Text("> DI «${ui.assistant.ifEmpty { "VEGA" }.uppercase()}» Y LO QUE NECESITES." + if (cursor) " █" else "", glow(20, Phosphor.Dim))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val favorites = ui.actions.filter { it.quick }.take(6)
                favorites.forEachIndexed { i, action ->
                    val pending = ui.pending?.id == action.id
                    val pressed = remember { mutableStateOf(false) }
                    val color = if (pending) Phosphor.Alert else Phosphor.Amber
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .background(if (pressed.value) color.copy(alpha = 0.22f) else Color.Transparent)
                            .border(1.dp, color.copy(alpha = if (pending) 1f else 0.7f))
                            .instantPress(canAct, pressed) { onPress(action.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (pending) "¿SEGURO? $secondsLeft" else "F${i + 1} ${action.label.substringBefore(" /").uppercase()}", glow(17, color), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val morePressed = remember { mutableStateOf(false) }
                Box(
                    Modifier.width(78.dp).fillMaxHeight()
                        .background(if (morePressed.value) Phosphor.Amber.copy(alpha = 0.22f) else Color.Transparent)
                        .border(1.dp, Phosphor.Faint)
                        .instantPress(true, morePressed, onMore),
                    contentAlignment = Alignment.Center,
                ) { Text("F7 MÁS", glow(17, Phosphor.Dim), maxLines = 1) }
            }
        }
    }
}
