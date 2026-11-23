package com.asistente.personal.ui2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.TaskItem
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import kotlinx.coroutines.delay

// ───────────────────────── el tablero de Moodle ─────────────────────────

private fun Lane.color(p: Pal): Color = when (this) {
    Lane.OVERDUE -> p.red
    Lane.TODAY -> p.coral
    Lane.WEEK -> p.sun
    Lane.LATER -> p.sky
}

private fun Lane.empty(): String = when (this) {
    Lane.OVERDUE -> "Nada vencido"
    Lane.TODAY -> "Nada para hoy"
    Lane.WEEK -> "Nada esta semana"
    Lane.LATER -> "Nada más lejos"
}

/**
 * El tablero de Moodle: tus entregas en cuatro columnas (vencidas, hoy, esta semana, después). Cada tarjeta lleva la materia y cuánto falta;
 * el visto la marca como entregada (pide confirmar con un segundo toque) y el PC deja de avisar de ella.
 */
@Composable
internal fun TableroSheet2(m: ScreenModel, a: ScreenActions, p: Pal, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val items = m.ui.tasks
    val now = m.wallMs / 1000
    val lanes = board(items.orEmpty(), now)
    val pending = items.orEmpty().count { it.due > now }
    val overdue = lanes.getValue(Lane.OVERDUE).size
    val canAct = m.ui.link == LinkStatus.OPEN
    Column(modifier.clip(RoundedCornerShape(34.dp)).background(p.bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tablero · Moodle", maxLines = 1, style = Type2.body(20.sp, p.ink, FontWeight.Bold))
            Spacer(Modifier.width(12.dp))
            if (items != null) {
                Text(
                    "$pending por entregar" + if (overdue > 0) " · $overdue vencida${if (overdue == 1) "" else "s"}" else "",
                    maxLines = 1, style = Type2.body(13.sp, if (overdue > 0) p.readable(p.red) else p.ink2),
                )
            }
            Spacer(Modifier.weight(1f))
            RoundBtn("cancel", 34.dp, p.tile2, p.ink, onClose, Modifier.testTag("tablero-close"))
        }
        if (items.isNullOrEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.tile), contentAlignment = Alignment.Center) {
                Text(
                    if (items == null) (if (canAct) "Leyendo Moodle…" else "Sin conexión con el PC.") else "No hay entregas en Moodle.",
                    maxLines = 2, style = Type2.body(14.sp, p.ink2).copy(textAlign = TextAlign.Center),
                )
            }
        } else {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lane.entries.forEach { lane ->
                    LaneColumn(lane, lanes.getValue(lane), now, canAct, p, a, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun LaneColumn(lane: Lane, tasks: List<TaskItem>, now: Long, canAct: Boolean, p: Pal, a: ScreenActions, modifier: Modifier) {
    val color = lane.color(p)
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(p.tile).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(lane.label, maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.weight(1f))
            Text("${tasks.size}", maxLines = 1, style = Type2.num(14.sp, if (tasks.isEmpty()) p.ink3 else p.ink))
        }
        if (tasks.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(lane.empty(), maxLines = 2, style = Type2.body(12.sp, p.ink3).copy(textAlign = TextAlign.Center))
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tasks.forEach { TaskCard(it, lane, color, now, canAct, p, a) }
            }
        }
    }
}

@Composable
private fun TaskCard(t: TaskItem, lane: Lane, color: Color, now: Long, canAct: Boolean, p: Pal, a: ScreenActions) {
    var armed by remember(t.uid) { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3000)
            armed = false
        }
    }
    Column(
        Modifier.fillMaxWidth().testTag("tablero-card-${t.uid}").clip(RoundedCornerShape(16.dp)).background(p.tile2).padding(start = 10.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
    ) {
        Text(t.title, maxLines = 3, style = Type2.body(13.sp, p.ink, FontWeight.SemiBold, 16.sp), modifier = Modifier.padding(end = 6.dp))
        if (t.course.isNotEmpty()) Text(t.course, maxLines = 1, style = Type2.body(11.sp, p.ink3), modifier = Modifier.padding(end = 6.dp))
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (armed) {
                // La pregunta ocupa el sitio de la hora: en una columna estrecha no caben las dos cosas.
                Text(
                    "¿Entregada? Toca", maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.Bold).copy(textAlign = TextAlign.Center),
                    modifier = Modifier.weight(1f).padding(end = 6.dp).testTag("tablero-confirm-${t.uid}").clip(RoundedCornerShape(50)).background(p.mint)
                        .clickable { a.taskDone(t.uid) }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            } else {
                Text(
                    dueLabel(t.due, now), maxLines = 1, style = Type2.body(12.sp, if (lane == Lane.OVERDUE) Color.White else p.ink, FontWeight.SemiBold),
                    modifier = Modifier.background(if (lane == Lane.OVERDUE) p.red else color.copy(alpha = if (p.night) 0.30f else 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
                Spacer(Modifier.weight(1f))
                if (canAct) {
                    Box(Modifier.semantics { contentDescription = "Marcar «${t.title}» como entregada" }) {
                        RoundBtn("check", 28.dp, p.bg, p.ink2, { armed = true }, Modifier.testTag("tablero-done-${t.uid}"), hit = 40.dp)
                    }
                }
            }
        }
    }
}
