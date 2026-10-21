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
import androidx.compose.ui.text.style.TextOverflow
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
fun StatusBar(assistant: String, model: String, link: LinkStatus, retryInMs: Long?, clock: String, onAndroid: () -> Unit, update: (() -> Unit)? = null) {
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
        if (update != null) {
            Spacer(Modifier.size(14.dp))
            NervButton("ACTUALIZAR", Nerv.Amber, enabled = true, onClick = update, small = true)
        }
        Spacer(Modifier.size(14.dp))
        // Salida al Android normal (útil cuando el asistente es la pantalla de inicio).
        NervButton("ANDROID", Nerv.Mute, enabled = true, onClick = onAndroid, ghost = true, small = true)
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Nerv.Orange.copy(alpha = 0.24f)))
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

/** Texto simple sin Material (más ligero). Recorta con «…» si no cabe. */
@Composable
fun Text(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis) {
    androidx.compose.foundation.text.BasicText(text, modifier, style, overflow = overflow, maxLines = maxLines)
}

