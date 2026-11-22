package com.asistente.personal.ui2

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.PcNote
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.timeAgo
import kotlinx.coroutines.delay

// ───────────────────────── los avisos del PC, todos ─────────────────────────

/** Un grupo de avisos: los de una misma app, del más nuevo al más viejo. */
internal data class NoteGroup(val app: String, val icon: String, val notes: List<PcNote>)

/** Los avisos agrupados por app; primero la app con el aviso más reciente. */
internal fun groupNotes(notes: List<PcNote>): List<NoteGroup> =
    notes.groupBy { it.app.ifBlank { "Otros" } }
        .map { (app, list) -> list.sortedByDescending { it.at }.let { NoteGroup(app, it.first().icon, it) } }
        .sortedByDescending { it.notes.first().at }

/** Un color por app (el mismo siempre), para la barrita de su columna. */
private fun appColor(app: String, p: Pal): Color {
    val a = app.lowercase()
    return when {
        "whatsapp" in a -> Color(0xFF25D366)
        "discord" in a -> Color(0xFF5865F2)
        "gmail" in a || "correo" in a || "outlook" in a -> Color(0xFFEA4335)
        "telegram" in a -> Color(0xFF29A9EB)
        "steam" in a -> Color(0xFF66C0F4)
        "nvidia" in a || "geforce" in a -> Color(0xFF76B900)
        else -> listOf(p.coral, p.lilac, p.sky, p.sun, p.mint)[Math.floorMod(app.hashCode(), 5)]
    }
}

/**
 * Todos los avisos del PC en columnas, una por app (como el tablero de Moodle): cada tarjeta dice quién, qué y hace cuánto, y se
 * quita con su «×». «Borrar todos» pide un segundo toque. Con muchas apps, las columnas se deslizan de lado.
 */
@Composable
internal fun AvisosSheet2(m: ScreenModel, a: ScreenActions, p: Pal, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val notes = m.ui.pcNotes
    val groups = groupNotes(notes)
    val canAct = m.ui.link == LinkStatus.OPEN
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }
    Column(modifier.clip(RoundedCornerShape(34.dp)).background(p.bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Avisos del PC", maxLines = 1, style = Type2.body(20.sp, p.ink, FontWeight.Bold))
            Spacer(Modifier.width(12.dp))
            Text(
                if (notes.isEmpty()) "ninguno" else "${notes.size} aviso${if (notes.size == 1) "" else "s"} · ${groups.size} app${if (groups.size == 1) "" else "s"}",
                maxLines = 1, style = Type2.body(13.sp, p.ink2),
            )
            Spacer(Modifier.weight(1f))
            if (notes.isNotEmpty() && canAct) {
                Text(
                    if (armed) "¿Borrar los ${notes.size}? Toca" else "Borrar todos",
                    maxLines = 1,
                    style = Type2.body(13.sp, if (armed) Color.White else p.ink, FontWeight.SemiBold),
                    modifier = Modifier.testTag("avisos-clear").clip(RoundedCornerShape(50))
                        .background(if (armed) p.red else p.tile2)
                        .clickable {
                            if (armed) {
                                armed = false
                                a.dismissNote(null)
                            } else armed = true
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            RoundBtn("cancel", 34.dp, p.tile2, p.ink, onClose, Modifier.testTag("avisos-close"))
        }
        if (groups.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.tile), contentAlignment = Alignment.Center) {
                Text(
                    if (m.ui.link == LinkStatus.OPEN) "Todo leído: no hay avisos en el PC." else "Sin conexión con el PC.",
                    maxLines = 2, style = Type2.body(14.sp, p.ink2).copy(textAlign = TextAlign.Center),
                )
            }
        } else {
            // Hasta cuatro columnas reparten el ancho; con más, cada una mide lo mismo y se desliza de lado.
            val fit = groups.size <= 4
            Row(
                Modifier.fillMaxWidth().weight(1f).then(if (fit) Modifier else Modifier.horizontalScroll(rememberScrollState())),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { g ->
                    AppColumn(g, m, canAct, p, a, if (fit) Modifier.weight(1f).fillMaxHeight() else Modifier.width(196.dp).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun AppColumn(g: NoteGroup, m: ScreenModel, canAct: Boolean, p: Pal, a: ScreenActions, modifier: Modifier) {
    val color = appColor(g.app, p)
    Column(modifier.testTag("avisos-app-${g.app}").clip(RoundedCornerShape(24.dp)).background(p.tile).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(g.app, m.noteIcons[g.icon], color, p, 22.dp)
            Spacer(Modifier.width(7.dp))
            Text(g.app.uppercase(), maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text("${g.notes.size}", maxLines = 1, style = Type2.num(14.sp, p.ink))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.notes.forEach { n -> NoteCard(n, color, m, canAct, p, a) }
        }
    }
}

@Composable
private fun NoteCard(n: PcNote, color: Color, m: ScreenModel, canAct: Boolean, p: Pal, a: ScreenActions) {
    val fresh = n.id in m.ui.freshNotes
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).testTag("aviso-${n.id}").clip(RoundedCornerShape(16.dp)).background(p.tile2),
    ) {
        // Una barrita del color de la app a la izquierda (más viva si el aviso es nuevo).
        Box(Modifier.width(4.dp).fillMaxHeight().background(color.copy(alpha = if (fresh) 1f else 0.55f)))
        Column(Modifier.weight(1f).padding(start = 9.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n.title.ifEmpty { n.app }, maxLines = 1, style = Type2.body(13.sp, p.ink, FontWeight.SemiBold), modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Text(timeAgo(n.at, m.wallMs), maxLines = 1, style = Type2.body(11.sp, if (fresh) p.readable(p.coral) else p.ink3, if (fresh) FontWeight.Bold else FontWeight.Medium))
            }
            if (n.body.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(n.body.replace('\n', ' '), maxLines = 3, style = Type2.body(12.sp, p.ink2, line = 15.sp))
            }
        }
        if (canAct) RoundBtn("cancel", 24.dp, Color.Transparent, p.ink3, { a.dismissNote(n.id) }, Modifier.testTag("aviso-quitar-${n.id}"), hit = 40.dp)
        else Spacer(Modifier.width(8.dp))
    }
}

/** El icono de la app (el del PC si llegó; si no, su inicial sobre su color). */
@Composable
internal fun AppIcon(app: String, icon: ImageBitmap?, color: Color, p: Pal, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        if (icon != null) Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(app.take(1).uppercase(), maxLines = 1, style = Type2.body((size.value * 0.5f).sp, p.ink2, FontWeight.Bold))
    }
}
