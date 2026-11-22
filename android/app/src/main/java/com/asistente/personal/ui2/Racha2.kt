package com.asistente.personal.ui2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.StreakInfo
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.instantPress
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin

// ───────────────────────── la racha del gimnasio ─────────────────────────

/** Los colores del fuego: rojo por fuera, naranja, amarillo y casi blanco en el corazón. */
private val Ember = Color(0xFFB8321F)
private val FireRed = Color(0xFFFF4B2B)
private val FireOrange = Color(0xFFFF8A1F)
private val FireYellow = Color(0xFFFFC93C)
private val FireCore = Color(0xFFFFF4C2)

private const val EDIT_DAYS = 30L

/** Las 6 semanas del calendario (de lunes a domingo), terminando en la semana de hoy. */
internal fun rachaGrid(today: LocalDate): List<LocalDate> {
    val monday = today.with(DayOfWeek.MONDAY)
    val start = monday.minusWeeks(5)
    return List(42) { start.plusDays(it.toLong()) }
}

/** Lo que dice la llama con palabras, según cómo esté. */
internal fun rachaStatus(s: StreakInfo?): String = when {
    s == null -> "Leyendo tu racha…"
    s.total == 0 -> "Toca la llama al volver del gimnasio"
    s.today -> "¡Encendida hoy! Mañana puedes descansar"
    !s.alive || s.count == 0 -> "Se apagó · tócala para empezar otra"
    s.restLeft == 0 -> "¡Hoy o se apaga!"
    s.restLeft == 1 -> "Puedes descansar 1 día más"
    else -> "Puedes descansar ${s.restLeft} días más"
}

internal fun daysWord(n: Int) = if (n == 1) "día" else "días"

/**
 * Una llama dibujada a mano: tres capas (roja, naranja y amarilla) que se mecen y titilan. `heat` (0–1) dice lo viva que está:
 * a 1 arde alta; a 0,35 son brasas pequeñas; a 0 se ve apagada (solo el contorno y una brasa gris).
 */
@Composable
internal fun Flame(heat: Float, modifier: Modifier = Modifier, burst: Float = 0f, still: Boolean = false) {
    val t = if (still) 0.3f else {
        val inf = rememberInfiniteTransition(label = "llama")
        inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing), RepeatMode.Restart), label = "mecer").value
    }
    val shown by animateFloatAsState(heat, spring(dampingRatio = 0.55f, stiffness = 120f), label = "calor")
    Canvas(modifier) { drawFlame(shown.coerceIn(0f, 1f), t, burst) }
}

private fun DrawScope.drawFlame(heat: Float, t: Float, burst: Float) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val base = h * 0.94f
    val phase = (t * 2 * PI).toFloat()
    if (heat <= 0.01f) {
        // Apagada: el contorno de la llama y una brasa gris.
        val out = flamePath(cx, base, w * 0.52f, h * 0.70f, 0f, 0f)
        drawPath(out, Color(0x26888888))  // la silueta, rellena: se ve aunque sea pequeña
        drawPath(out, Color(0x66888888), style = Stroke(width = maxOf(1.5f, w * 0.03f)))
        drawOval(Color(0x66777777), topLeft = Offset(cx - w * 0.12f, base - h * 0.05f), size = androidx.compose.ui.geometry.Size(w * 0.24f, h * 0.06f))
        return
    }
    val grow = 0.45f + 0.55f * heat + 0.12f * burst
    // El resplandor de detrás.
    drawCircle(
        Brush.radialGradient(listOf(FireOrange.copy(alpha = 0.35f * heat + 0.25f * burst), Color.Transparent), Offset(cx, base - h * 0.32f * grow), w * 0.55f),
        radius = w * 0.55f, center = Offset(cx, base - h * 0.32f * grow),
    )
    val layers = listOf(
        Triple(1.00f, lerp(Ember, FireRed, heat), lerp(Ember, FireOrange, heat)),
        Triple(0.74f, lerp(Ember, FireOrange, heat), lerp(FireOrange, FireYellow, heat)),
        Triple(0.46f, lerp(FireOrange, FireYellow, heat), lerp(FireYellow, FireCore, heat)),
    )
    layers.forEachIndexed { i, (k, top, bottom) ->
        val sway = sin(phase + i * 1.3f) * w * 0.035f * (1f - i * 0.25f)
        val flick = sin(phase * 2f + i * 0.9f) * h * 0.025f
        val fw = w * 0.62f * k * (0.75f + 0.25f * grow)
        val fh = h * 0.86f * k * grow + flick
        val path = flamePath(cx, base - (1f - k) * h * 0.04f, fw, fh, sway, sway * 0.4f)
        drawPath(path, Brush.verticalGradient(listOf(top, bottom), startY = base - fh, endY = base))
    }
    // Chispas que suben al encenderla.
    if (burst > 0.02f) {
        for (i in 0 until 7) {
            val x = cx + (i - 3) * w * 0.07f + sin(phase + i) * w * 0.02f
            val y = base - h * (0.35f + 0.6f * ((burst + i * 0.13f) % 1f))
            drawCircle(FireYellow.copy(alpha = (burst * (1f - (i % 3) * 0.25f)).coerceIn(0f, 1f)), radius = w * 0.018f, center = Offset(x, y))
        }
    }
}

/** El contorno de una llama: redonda abajo, en punta arriba y con la punta algo torcida (`sway`). */
internal fun flamePath(cx: Float, base: Float, width: Float, height: Float, sway: Float, belly: Float): Path {
    val top = base - height
    val half = width / 2f
    return Path().apply {
        moveTo(cx + sway, top)
        cubicTo(cx + sway + half * 0.25f, top + height * 0.28f, cx + half + belly, top + height * 0.45f, cx + half * 0.92f, top + height * 0.72f)
        cubicTo(cx + half * 0.80f, base - height * 0.04f, cx + half * 0.30f, base, cx, base)
        cubicTo(cx - half * 0.30f, base, cx - half * 0.80f, base - height * 0.04f, cx - half * 0.92f, top + height * 0.72f)
        cubicTo(cx - half + belly, top + height * 0.45f, cx + sway - half * 0.25f, top + height * 0.28f, cx + sway, top)
        close()
    }
}

/**
 * La hoja «Racha»: una llama grande que se enciende al tocarla (al volver del gimnasio), los días seguidos, la mejor racha, el total y
 * un calendario de seis semanas donde cada día de gimnasio es una llamita. Tocar un día pasado lo enciende o lo apaga (por si se te olvidó).
 */
@Composable
internal fun RachaSheet2(m: ScreenModel, a: ScreenActions, p: Pal, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = m.ui.streak
    val canAct = m.ui.link == LinkStatus.OPEN
    val today = Instant.ofEpochMilli(m.wallMs).atZone(ZoneId.systemDefault()).toLocalDate()
    val burst = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Column(modifier.clip(RoundedCornerShape(34.dp)).background(p.bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Racha", maxLines = 1, style = Type2.body(20.sp, p.ink, FontWeight.Bold))
            Spacer(Modifier.width(10.dp))
            Text("del gimnasio", maxLines = 1, style = Type2.body(14.sp, p.ink2))
            Spacer(Modifier.weight(1f))
            RoundBtn("cancel", 34.dp, p.tile2, p.ink, onClose, Modifier.testTag("racha-close"))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // La llama grande.
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(26.dp))
                    .background(if (p.night) Color(0xFF1C1410) else Color(0xFFFFF1E2)).padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val lit = s?.today == true
                Box(
                    Modifier.weight(1f).aspectRatio(0.8f).testTag("flame")
                        .semantics { contentDescription = if (lit) "Llama encendida hoy" else "Encender la llama de hoy" }
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = canAct && !lit) {
                            a.streakLight(null)
                            scope.launch {
                                burst.snapTo(0.01f)
                                burst.animateTo(1f, tween(900))
                                burst.snapTo(0f)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Flame(s?.heat ?: 0f, Modifier.fillMaxSize(), burst = burst.value)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${s?.count ?: 0}", maxLines = 1, style = Type2.num(46.sp, if ((s?.count ?: 0) > 0) p.readable(p.coral) else p.ink3), modifier = Modifier.testTag("racha-count"))
                    Spacer(Modifier.width(6.dp))
                    Text(daysWord(s?.count ?: 0) + " seguidos", maxLines = 1, style = Type2.body(14.sp, p.ink2), modifier = Modifier.padding(bottom = 6.dp))
                }
                Text(
                    rachaStatus(s), maxLines = 1, style = Type2.body(13.sp, if (s?.restLeft == 0 && s.alive && !s.today) p.readable(p.red) else p.ink2, FontWeight.SemiBold),
                    modifier = Modifier.testTag("racha-status"),
                )
                if (lit && canAct) {
                    Text(
                        "Apagar hoy", maxLines = 1, style = Type2.body(12.sp, p.ink3),
                        modifier = Modifier.testTag("flame-undo").clip(RoundedCornerShape(50)).clickable { a.streakOut(null) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            // Los números y el calendario.
            Column(Modifier.weight(1.25f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("MEJOR", "${s?.best ?: 0}", daysWord(s?.best ?: 0), p, Modifier.weight(1f))
                    Stat("TOTAL", "${s?.total ?: 0}", if ((s?.total ?: 0) == 1) "vez" else "veces", p, Modifier.weight(1f))
                    Stat("SEMANA", "${s?.week ?: 0}", if ((s?.week ?: 0) == 1) "vez" else "veces", p, Modifier.weight(1f))
                }
                Calendar(s, today, canAct, p, a, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, p: Pal, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(p.tile).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, maxLines = 1, style = Type2.label(p.ink3))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, maxLines = 1, style = Type2.num(24.sp, p.ink))
            Spacer(Modifier.width(4.dp))
            Text(unit, maxLines = 1, style = Type2.body(12.sp, p.ink2), modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

@Composable
private fun Calendar(s: StreakInfo?, today: LocalDate, canAct: Boolean, p: Pal, a: ScreenActions, modifier: Modifier) {
    val lit = s?.days.orEmpty().toSet()
    val grid = rachaGrid(today)
    Column(modifier.clip(RoundedCornerShape(22.dp)).background(p.tile).padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth()) {
            listOf("L", "M", "X", "J", "V", "S", "D").forEach {
                Text(it, maxLines = 1, style = Type2.label(p.ink3).copy(textAlign = TextAlign.Center), modifier = Modifier.weight(1f))
            }
        }
        grid.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                week.forEach { day ->
                    val iso = day.toString()
                    val on = iso in lit
                    val future = day.isAfter(today)
                    val editable = canAct && !future && !day.isBefore(today.minusDays(EDIT_DAYS))
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(26.dp).testTag("day-$iso").clip(CircleShape)
                                .then(if (day == today) Modifier.border(2.dp, p.readable(p.coral), CircleShape) else Modifier)
                                .background(if (on) FireOrange.copy(alpha = if (p.night) 0.28f else 0.22f) else Color.Transparent)
                                .clickable(enabled = editable) { if (on) a.streakOut(iso) else a.streakLight(iso) },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (on) Flame(1f, Modifier.size(width = 14.dp, height = 18.dp), still = true)
                            else if (day.dayOfMonth == 1) Text(
                                // El primer día de cada mes lleva el nombre del mes: así se lee dónde empieza.
                                day.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.forLanguageTag("es")).take(3), maxLines = 1,
                                style = Type2.body(10.sp, if (future) p.ink3.copy(alpha = 0.4f) else p.ink, FontWeight.Bold).copy(textAlign = TextAlign.Center),
                            )
                            else Text(
                                "${day.dayOfMonth}", maxLines = 1,
                                style = Type2.body(11.sp, if (future) p.ink3.copy(alpha = 0.4f) else p.ink2).copy(textAlign = TextAlign.Center),
                            )
                        }
                    }
                }
            }
        }
    }
}
