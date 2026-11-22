package com.asistente.personal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as BitmapCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.ui.led.LedOff
import com.asistente.personal.ui.led.countdownText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Vega · Pista. La cara vive dentro de una pista de cuatro carriles de LED (como los anillos de actividad, pero en
 * rectángulo redondeado): cada carril se llena en el sentido del reloj desde arriba en el centro, y dice cuánto
 * de algo hay (CPU, gráfica, memoria de vídeo, batería; con música, el último pasa a ser lo que lleva la canción).
 *   izquierda — la hora, la leyenda de los carriles (para leerlos) y los botones del micro y de dormir
 *   centro    — la pista y, dentro, la cara de siempre con lo que dice
 *   derecha   — lo que importa ahora: la agenda, la canción o la partida
 *   abajo     — las teclas
 */

private class Lane(val name: String, val value: String, val f: Float, val color: Color, val hot: Boolean)

private val LANE_INSETS = listOf(5.dp, 16.dp, 27.dp, 38.dp)
private val LANE_RADIUS = 80.dp
private val DOT_GAP = 6.5.dp

@Composable
internal fun ColumnScope.TrackLayout(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val t = ui.telemetry
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else 0f
    val position = ui.media?.let { livePosition(it, ui.mediaAt, m.clockMs) }
    val session = ui.game?.let { liveSession(it, ui.gameAt, m.clockMs, tickMs = 15_000) }
    val cpu = Lane("CPU", t?.cpu?.let { "$it %" } ?: "—", (t?.cpu ?: 0) / 100f, Vega.Light, (t?.cpu ?: 0) >= 90)
    val temp = Lane("GPU", t?.gpuTemp?.let { "$it°" } ?: "—", (((t?.gpuTemp ?: 30) - 30) / 60f).coerceIn(0f, 1f), Vega.Amber, (t?.gpuTemp ?: 0) >= 80)
    val gpu = Lane("GPU", t?.gpuUtil?.let { "$it %" } ?: "—", (t?.gpuUtil ?: 0) / 100f, m.gameAccent, (t?.gpuUtil ?: 0) >= 95)
    val vid = Lane("VÍDEO", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", vram, Vega.Mint, vram >= 0.9f)
    val bat = Lane("BAT", if (m.battery >= 0) "${m.battery} %" else "—", if (m.battery >= 0) m.battery / 100f else 0f, if (m.charging) Vega.Mint else Vega.Light, lowBattery(m.battery, m.charging))
    val song = Lane("CANCIÓN", position?.let { clockTime(it) } ?: "—", if (ui.media != null && ui.media.duration > 0) ((position ?: 0.0) / ui.media.duration).toFloat().coerceIn(0f, 1f) else 0f, m.mediaAccent, false)
    val lanes = when {
        ui.game != null -> listOf(gpu, temp, vid, cpu)
        ui.media != null -> listOf(cpu, temp, vid, song)
        else -> listOf(cpu, temp, vid, bat)
    }
    val fills = lanes.map { animateFloatAsState(it.f.coerceIn(0f, 1f), tween(700), label = "carril").value }
    val colors = lanes.map { if (it.hot) Vega.Red else it.color }
    val latestFills = rememberUpdatedState(fills)
    val latestColors = rememberUpdatedState(colors)

    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wing = 150.dp
        val gap = 6.dp
        val trackW = maxWidth - (wing + gap) * 2
        val trackH = maxHeight
        // La pista.
        Box(
            Modifier.fillMaxSize().drawWithCache {
                val left = (wing + gap).toPx()
                val geo = LANE_INSETS.map { inset ->
                    val d = inset.toPx()
                    val w = trackW.toPx() - 2 * d
                    val h = trackH.toPx() - 2 * d
                    val r = (LANE_RADIUS.toPx() - d).coerceAtLeast(14.dp.toPx())
                    val per = perimeter(w, h, r)
                    val n = max(8, (per / DOT_GAP.toPx()).toInt())
                    List(n) { i ->
                        val p = pointAt(i * per / n, w, h, r)
                        Offset(left + d + p.x, d + p.y)
                    }
                }
                val dim = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)).also { bmp ->
                    CanvasDrawScope().draw(this, layoutDirection, BitmapCanvas(bmp), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
                        geo.forEach { drawPoints(it, PointMode.Points, LedOff, strokeWidth = 3.4.dp.toPx(), cap = StrokeCap.Round) }
                    }
                }
                onDrawBehind {
                    drawImage(dim)
                    val f = latestFills.value
                    val c = latestColors.value
                    for (i in geo.indices) {
                        val pts = geo[i]
                        val lit = (f[i] * pts.size + 0.5f).toInt().coerceIn(0, pts.size)
                        if (lit == 0) continue
                        val col = c[i]
                        // Del arranque a la cabeza el carril se hace más vivo; la cabeza lleva un halo.
                        val a = lit / 3
                        drawPoints(pts.subList(0, a), PointMode.Points, col.copy(alpha = 0.5f), strokeWidth = 3.6.dp.toPx(), cap = StrokeCap.Round)
                        drawPoints(pts.subList(a, 2 * a), PointMode.Points, col.copy(alpha = 0.78f), strokeWidth = 3.8.dp.toPx(), cap = StrokeCap.Round)
                        drawPoints(pts.subList(2 * a, lit), PointMode.Points, col, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                        drawPoints(pts.subList(max(0, lit - 3), lit), PointMode.Points, col.copy(alpha = 0.25f), strokeWidth = 9.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            },
        )

        // La cara, dentro de la pista.
        val inner = 56.dp
        FaceCard(
            x.mood, x.color, m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.offset(wing + gap + inner, inner).size(trackW - inner * 2, trackH - inner * 2).attention(x),
            bare = true, compactReply = true,
        )

        // Izquierda: la hora, la leyenda y los botones.
        Column(Modifier.width(wing).fillMaxHeight().padding(start = 10.dp, top = 2.dp, bottom = 2.dp)) {
            Text(m.clock, maxLines = 1, style = doto(38.sp, Vega.Ink))
            Spacer(Modifier.height(4.dp))
            Text(m.date, maxLines = 1, style = label10)
            val (link, linkColor) = linkText(m)
            Text(link, maxLines = 1, style = label10.copy(color = linkColor))
            Spacer(Modifier.height(6.dp))
            lanes.forEachIndexed { i, l ->
                Row(Modifier.height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(colors[i], CircleShape))
                    Spacer(Modifier.width(7.dp))
                    Column {
                        Text(l.name, maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, color = Vega.Ink3))
                        Text(l.value, maxLines = 1, style = doto(13.sp, if (l.hot) Vega.Red else Vega.Ink))
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val micOn = m.listening && m.micOn
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundKey("mic", if (micOn) Vega.Coral else Vega.Ink2, x.a.toggleMic, filled = micOn, size = 30.dp)
                RoundKey("sleep", Vega.Ink2, x.a.sleep, size = 30.dp)
            }
        }

        // Derecha: lo que importa ahora.
        Box(Modifier.offset(maxWidth - wing, 0.dp).width(wing).fillMaxHeight().padding(start = 8.dp, end = 4.dp, top = 4.dp)) {
            Context(x, position, session)
        }
    }
    KeyRow(
        favorites = ui.actions.filter { it.quick }, pending = ui.pending, secondsLeft = x.secondsLeft, enabled = x.canAct,
        onPress = x.press, onMore = x.onMore, modifier = Modifier.fillMaxWidth().height(52.dp).cached(), icons = m.icons, media = ui.media,
        compact = true,
    )
}

@Composable
private fun Context(x: LayoutArgs, position: Double?, session: Long?) {
    val m = x.m
    val ui = m.ui
    val media = ui.media
    val game = ui.game
    val open = { cb: () -> Unit -> Modifier.clickable(interactionSource = MutableInteractionSource(), indication = null, onClick = cb) }
    when {
        media != null -> Column(open(x.onOpenMusic).fillMaxSize()) {
            Text(if (media.playing) "SONANDO" else "EN PAUSA", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.mediaAccent))
            Spacer(Modifier.height(4.dp))
            Art(m.mediaArt, m.mediaAccent, Modifier.size(92.dp), 14.dp)
            Spacer(Modifier.height(6.dp))
            Text(media.title, maxLines = 2, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
            Text(media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp))
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Equalizer(media.playing, m.mediaAccent, Modifier.size(width = 16.dp, height = 12.dp))
                Spacer(Modifier.width(7.dp))
                Text(position?.let { clockTime(it) } ?: "", maxLines = 1, style = doto(16.sp, Vega.Ink))
            }
        }
        game != null -> Column(open(x.onOpenGame).fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(m.gameAccent, 6.dp)
                Spacer(Modifier.width(5.dp))
                Text("JUGANDO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.gameAccent))
            }
            Spacer(Modifier.height(4.dp))
            GameArt(m.gameArt, game.name, m.gameAccent, Modifier.size(92.dp), 14.dp, initialSize = 28.sp)
            Spacer(Modifier.height(6.dp))
            Text(game.name, maxLines = 2, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            Text(sessionClock(session ?: 0L), maxLines = 1, style = doto(22.sp, Vega.Ink))
            Text("de partida", maxLines = 1, style = label10)
        }
        else -> Column(Modifier.fillMaxSize()) {
            Text("HOY", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
            Spacer(Modifier.height(4.dp))
            val next = ui.telemetry?.upcoming?.firstOrNull()
            val local = timerLeft(m)
            when {
                next != null -> {
                    Text(countdown(next.dueIn - x.elapsed), maxLines = 1, style = doto(30.sp, Vega.Amber))
                    Text(if (next.kind == "timer") "temporizador" else next.text, maxLines = 3, style = Vega.label.copy(fontSize = 12.sp, lineHeight = 14.sp, color = Vega.Ink))
                    val more = (ui.telemetry?.upcoming?.size ?: 1) - 1
                    if (more > 0) Text("+$more más", maxLines = 1, style = label10)
                }
                local != null -> {
                    Text(countdownText(local), maxLines = 1, style = doto(30.sp, Vega.Amber))
                    Text("temporizador", maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink))
                }
                else -> Text("Sin avisos pendientes", maxLines = 3, style = Vega.label.copy(fontSize = 12.sp, lineHeight = 14.sp, color = Vega.Ink3))
            }
        }
    }
}

// ───────────────────────── geometría de la pista ─────────────────────────

/** Perímetro de un rectángulo de `w` × `h` con esquinas de radio `r`. */
private fun perimeter(w: Float, h: Float, r: Float): Float = 2 * (w - 2 * r) + 2 * (h - 2 * r) + 2 * PI.toFloat() * r

/**
 * El punto a `s` de distancia desde el centro del lado de arriba, en el sentido del reloj, sobre un rectángulo
 * redondeado de `w` × `h` (coordenadas desde su esquina de arriba a la izquierda).
 */
private fun pointAt(s0: Float, w: Float, h: Float, r: Float): Offset {
    val arc = PI.toFloat() * r / 2
    val segs = floatArrayOf(w / 2 - r, arc, h - 2 * r, arc, w - 2 * r, arc, h - 2 * r, arc, w / 2 - r)
    var s = s0
    for (k in segs.indices) {
        if (s <= segs[k] || k == segs.lastIndex) {
            val f = if (segs[k] > 0f) (s / segs[k]).coerceIn(0f, 1f) else 0f
            return when (k) {
                0 -> Offset(w / 2 + f * (w / 2 - r), 0f)
                1 -> arcPoint(w - r, r, r, -90f + 90f * f)
                2 -> Offset(w, r + f * (h - 2 * r))
                3 -> arcPoint(w - r, h - r, r, 90f * f)
                4 -> Offset(w - r - f * (w - 2 * r), h)
                5 -> arcPoint(r, h - r, r, 90f + 90f * f)
                6 -> Offset(0f, h - r - f * (h - 2 * r))
                7 -> arcPoint(r, r, r, 180f + 90f * f)
                else -> Offset(r + f * (w / 2 - r), 0f)
            }
        }
        s -= segs[k]
    }
    return Offset(w / 2, 0f)
}

private fun arcPoint(cx: Float, cy: Float, r: Float, deg: Float): Offset {
    val a = deg * PI.toFloat() / 180f
    return Offset(cx + r * cos(a), cy + r * sin(a))
}
