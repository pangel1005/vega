package com.asistente.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role

/** Rectángulo con dos esquinas cortadas (portafolio: .btn). */
class Chamfer(private val cut: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val c = with(density) { cut.toPx() }
        return Outline.Generic(
            Path().apply {
                moveTo(0f, 0f); lineTo(size.width - c, 0f); lineTo(size.width, c)
                lineTo(size.width, size.height); lineTo(c, size.height); lineTo(0f, size.height - c); close()
            },
        )
    }
}

/** Esquinas en L del marco HUD (rojas arriba-izquierda y abajo-derecha). */
fun Modifier.hudFrame(line: Color): Modifier = drawBehind {
    val l = 18.dp.toPx()
    val thick = 3.dp.toPx()
    val thin = 1.dp.toPx()
    fun corner(x: Float, y: Float, dx: Float, dy: Float, c: Color, w: Float) {
        drawLine(c, Offset(x, y), Offset(x + dx * l, y), w)
        drawLine(c, Offset(x, y), Offset(x, y + dy * l), w)
    }
    corner(0f, 0f, 1f, 1f, Nerv.Red, thick)
    corner(size.width, 0f, -1f, 1f, line, thin)
    corner(0f, size.height, 1f, -1f, line, thin)
    corner(size.width, size.height, -1f, -1f, Nerv.Red, thick)
}

private val mono = TextStyle(fontFamily = Nerv.Mono, letterSpacing = 2.sp)

@Composable
fun StatusBar(assistant: String, model: String, link: LinkStatus, retryInMs: Long?, clock: String) {
    val (jp, es, color) = when (link) {
        LinkStatus.OPEN -> Triple("接続", "ENLACE ESTABLE", Nerv.Green)
        LinkStatus.CONNECTING -> Triple("接続中", "CONECTANDO", Nerv.Amber)
        LinkStatus.RECONNECTING -> Triple("再接続", "RECONECTANDO", Nerv.Amber)
        LinkStatus.DENIED -> Triple("拒否", "ACCESO DENEGADO", Nerv.Red)
    }
    val retry = if (link == LinkStatus.RECONNECTING && retryInMs != null) " · ${(retryInMs + 999) / 1000} S" else ""
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            assistant.uppercase().ifEmpty { "· · ·" },
            style = TextStyle(fontFamily = Nerv.Serif, fontSize = 26.sp, color = Nerv.Ink, letterSpacing = 3.sp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            if (model.isNotEmpty()) "MODELO ${model.uppercase()}" else "SISTEMA DE ASISTENCIA",
            style = mono.copy(fontSize = 10.sp, color = Nerv.Mute),
            modifier = Modifier.padding(bottom = 4.dp).weight(1f),
        )
        Box(Modifier.padding(bottom = 6.dp).size(7.dp).background(color))
        Spacer(Modifier.size(6.dp))
        Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 12.sp, color = color), modifier = Modifier.padding(bottom = 3.dp))
        Spacer(Modifier.size(4.dp))
        Text("$es$retry", style = mono.copy(fontSize = 11.sp, color = color), modifier = Modifier.padding(bottom = 3.dp))
        Spacer(Modifier.size(14.dp))
        Text(clock, style = mono.copy(fontSize = 11.sp, color = Nerv.Mute), modifier = Modifier.padding(bottom = 3.dp))
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Nerv.Orange.copy(alpha = 0.24f)))
}

@Composable
fun ChatPanel(
    assistant: String,
    messages: List<Message>,
    canSend: Boolean,
    busy: Boolean,
    accent: Color,
    onSend: (String) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) list.scrollToItem(messages.lastIndex, Int.MAX_VALUE / 2)
    }
    var text by remember { mutableStateOf("") }
    val line = accent.copy(alpha = 0.24f)
    val submit = {
        val clean = text.trim()
        if (clean.isNotEmpty() && canSend && !busy) {
            onSend(clean)
            text = ""
        }
    }

    Column(modifier.background(Nerv.Bg3.copy(alpha = 0.82f)).border(1.dp, Nerv.Ink.copy(alpha = 0.1f)).hudFrame(line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("REGISTRO", style = mono.copy(fontSize = 11.sp, color = Nerv.Mute))
            Spacer(Modifier.size(8.dp))
            Text("交信記録", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 13.sp, color = accent))
            Spacer(Modifier.weight(1f))
            NervButton("NUEVA", accent, ghost = true, enabled = canSend && !busy, onClick = onReset, small = true)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.size(4.dp)) }
            if (messages.isEmpty()) {
                item {
                    Text(
                        "SIN MENSAJES. HABLA O ESCRIBE PARA EMPEZAR.",
                        style = mono.copy(fontSize = 11.sp, color = Nerv.Dim, textAlign = TextAlign.Center),
                        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    )
                }
            }
            items(messages, key = { it.id }) { MessageRow(it, assistant, accent) }
            item { Spacer(Modifier.size(4.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(line))
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(4000) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = Nerv.Cond, fontSize = 17.sp, color = Nerv.Ink),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.weight(1f).background(Color(0xFF0B0A09)).border(1.dp, Nerv.Ink.copy(alpha = 0.1f))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            if (canSend) "Escribe a ${assistant.ifEmpty { "tu asistente" }}…" else "Sin conexión…",
                            style = TextStyle(fontFamily = Nerv.Cond, fontSize = 17.sp, color = Nerv.Dim),
                        )
                    }
                    inner()
                },
            )
            Spacer(Modifier.size(8.dp))
            NervButton("ENVIAR", accent, enabled = canSend && !busy && text.isNotBlank(), onClick = submit)
        }
    }
}

@Composable
private fun MessageRow(m: Message, assistant: String, accent: Color) {
    val who = when (m.role) {
        Role.USER -> "TÚ"
        Role.ERROR -> "非常事態 · ERROR"
        Role.NOTE -> "AVISO"
        Role.ASSISTANT -> assistant.uppercase().ifEmpty { "ASISTENTE" }
    } + (m.meta?.let { " · $it" } ?: "")
    val bar = when (m.role) {
        Role.USER -> Nerv.Mute
        Role.ERROR -> Nerv.Red
        Role.NOTE -> Nerv.Amber
        Role.ASSISTANT -> accent
    }
    val user = m.role == Role.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (user) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .background(if (m.role == Role.ERROR) Nerv.Red.copy(alpha = 0.1f) else Nerv.Bg2)
                .drawBehind {
                    val x = if (user) size.width - 1.dp.toPx() else 1.dp.toPx()
                    drawLine(bar, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            horizontalAlignment = if (user) Alignment.End else Alignment.Start,
        ) {
            Text(who, style = mono.copy(fontSize = 10.sp, color = if (m.role == Role.ERROR) Nerv.Red else Nerv.Mute))
            Text(
                m.text + if (m.streaming) " ▌" else "",
                style = TextStyle(
                    fontFamily = Nerv.Cond, fontSize = 18.sp, lineHeight = 22.sp,
                    color = if (m.role == Role.NOTE) Nerv.Mute else Nerv.Ink,
                    textAlign = if (user) TextAlign.End else TextAlign.Start,
                ),
            )
        }
    }
}

@Composable
fun NervButton(
    label: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    ghost: Boolean = false,
    small: Boolean = false,
) {
    val shape = Chamfer(if (small) 7.dp else 10.dp)
    val bg = if (ghost) Color.Transparent else accent
    val fg = if (ghost) accent else Color(0xFF0A0A0A)
    Box(
        Modifier.clip(shape).background(bg.copy(alpha = if (enabled) bg.alpha else bg.alpha * 0.35f))
            .then(if (ghost) Modifier.border(1.dp, accent.copy(alpha = 0.3f), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (small) 5.dp else 9.dp),
    ) {
        Text(
            label,
            style = TextStyle(
                fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = if (small) 12.sp else 15.sp,
                letterSpacing = 3.sp, color = fg.copy(alpha = if (enabled) 1f else 0.4f),
            ),
        )
    }
}

/** Texto simple sin Material (más ligero). */
@Composable
fun Text(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(text, modifier, style)
}

