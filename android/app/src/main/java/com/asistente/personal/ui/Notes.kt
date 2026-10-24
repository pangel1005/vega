package com.asistente.personal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.PcNote
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ancho de la barra de notificaciones plegada y abierta. */
val NOTES_FOLDED: Dp = 58.dp
val NOTES_OPEN: Dp = 290.dp

/** «ahora», «hace 5 min», «hace 2 h» o la hora (si es de otro día, la fecha corta). */
fun timeAgo(atSeconds: Double, nowMs: Long): String {
    val secs = (nowMs / 1000.0 - atSeconds).toLong().coerceAtLeast(0)
    return when {
        secs < 60 -> "ahora"
        secs < 3600 -> "hace ${secs / 60} min"
        secs < 6 * 3600 -> "hace ${secs / 3600} h"
        else -> SimpleDateFormat(if (secs < 24 * 3600) "HH:mm" else "d MMM", Locale.forLanguageTag("es"))
            .format(Date((atSeconds * 1000).toLong()))
    }
}

/** Color estable para cada app (el círculo con su inicial cuando no hay icono). */
private fun appColor(app: String): Color {
    val palette = listOf(Vega.Coral, Vega.Amber, Vega.Mint, Vega.Sky, Color(0xFFB794FF), Color(0xFFFF8FB1))
    return palette[(app.hashCode() and 0x7fffffff) % palette.size]
}

@Composable
private fun AppBadge(note: PcNote, icon: ImageBitmap?, size: Dp, fresh: Boolean = false) {
    Box(Modifier.size(size)) {
        if (icon != null) {
            Image(icon, note.app, Modifier.fillMaxSize().clip(RoundedCornerShape(size * 0.28f)))
        } else {
            val c = appColor(note.app)
            Box(
                Modifier.fillMaxSize().background(c.copy(alpha = 0.18f), RoundedCornerShape(size * 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(note.app.take(1).uppercase(), style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp, color = c))
            }
        }
        // Punto de «nueva» (llegó mientras mirabas).
        if (fresh) Box(Modifier.size(10.dp).align(Alignment.TopEnd).offset(3.dp, (-3).dp).background(Vega.Bg, CircleShape).padding(2.dp).background(Vega.Coral, CircleShape))
    }
}

/**
 * Notificaciones del PC en un lado de la pantalla.
 *
 * Plegada es una columna estrecha con el icono de cada app (y un punto en las nuevas); al
 * llegar una se abre sola unos segundos (`open` lo decide la pantalla). Abierta es una lista
 * con app, hora, título y texto; cada una se descarta con su ✕ (también en el PC).
 */
@Composable
fun NotesRail(
    notes: List<PcNote>,
    fresh: Set<Long>,
    icons: Map<String, ImageBitmap>,
    nowMs: Long,
    open: Boolean,
    onToggle: () -> Unit,
    onDismiss: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val width by animateDpAsState(if (open) NOTES_OPEN else NOTES_FOLDED, spring(dampingRatio = 0.86f, stiffness = 520f), label = "ancho avisos")
    val border by animateColorAsState(if (fresh.isNotEmpty()) Vega.Coral.copy(alpha = 0.55f) else Vega.Line, tween(400), label = "borde avisos")
    Box(modifier.width(width).fillMaxHeight().card(border).clip(CardShape)) {
        AnimatedContent(
            targetState = open,
            transitionSpec = { fadeIn(tween(180, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
            label = "avisos",
        ) { isOpen ->
            if (isOpen) OpenList(notes, fresh, icons, nowMs, onToggle, onDismiss)
            else Folded(notes, fresh, icons, onToggle)
        }
    }
}

@Composable
private fun Folded(notes: List<PcNote>, fresh: Set<Long>, icons: Map<String, ImageBitmap>, onToggle: () -> Unit) {
    val pressed = remember { mutableStateOf(false) }
    Column(
        Modifier.width(NOTES_FOLDED).fillMaxHeight().instantPress(true, pressed, onToggle).padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "${notes.size}",
            style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 18.sp, color = if (fresh.isEmpty()) Vega.Ink2 else Vega.Coral),
        )
        notes.take(5).forEach { AppBadge(it, icons[it.icon], 32.dp, fresh = it.id in fresh) }
        if (notes.size > 5) Text("+${notes.size - 5}", style = Vega.label)
    }
}

@Composable
private fun OpenList(
    notes: List<PcNote>,
    fresh: Set<Long>,
    icons: Map<String, ImageBitmap>,
    nowMs: Long,
    onToggle: () -> Unit,
    onDismiss: (Long?) -> Unit,
) {
    Column(Modifier.width(NOTES_OPEN).fillMaxHeight().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp)) {
            Text("Avisos del PC", maxLines = 1, style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Vega.Ink))
            Spacer(Modifier.width(6.dp))
            Text("${notes.size}", style = Vega.label.copy(color = Vega.Ink3))
            Spacer(Modifier.weight(1f))
            if (notes.isNotEmpty()) {
                Pill("Borrar", Vega.Ink2, { onDismiss(null) })
                Spacer(Modifier.width(6.dp))
            }
            Pill("Cerrar", Vega.Ink2, onToggle)
        }
        if (notes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Sin avisos", style = Vega.label.copy(color = Vega.Ink3))
            }
            return@Column
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            notes.forEach { NoteItem(it, it.id in fresh, icons[it.icon], nowMs) { onDismiss(it.id) } }
        }
    }
}

@Composable
private fun NoteItem(note: PcNote, fresh: Boolean, icon: ImageBitmap?, nowMs: Long, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth()
            .background(if (fresh) Vega.CardHi else Color(0xFF121214), shape)
            .border(1.dp, if (fresh) Vega.Coral.copy(alpha = 0.4f) else Vega.Line, shape)
            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
    ) {
        AppBadge(note, icon, 26.dp)
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(note.app, maxLines = 1, modifier = Modifier.weight(1f, fill = false), style = Vega.label.copy(fontSize = 10.sp, color = appColor(note.app)))
                Text("  ·  " + timeAgo(note.at, nowMs), maxLines = 1, style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink3))
            }
            Text(note.title, maxLines = 1, style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp, color = Vega.Ink))
            if (note.body.isNotEmpty()) {
                Text(note.body, maxLines = 2, style = TextStyle(fontFamily = Vega.Sans, fontSize = 12.sp, lineHeight = 15.sp, color = Vega.Ink2))
            }
        }
        // ✕ grande de tocar (32 dp), pequeño de ver.
        val pressed = remember { mutableStateOf(false) }
        Box(Modifier.size(32.dp).instantPress(true, pressed, onDismiss), contentAlignment = Alignment.Center) {
            Text("✕", style = TextStyle(fontFamily = Vega.Sans, fontSize = 12.sp, color = if (pressed.value) Vega.Ink else Vega.Ink3))
        }
    }
}

/** Cuánto se queda abierta la barra al llegar un aviso nuevo (luego se pliega sola). */
const val NOTE_PEEK_MS = 7_000L
