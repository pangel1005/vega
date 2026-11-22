package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate as rotateScope
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.ui.led.countdownText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Vega · Escritorio. Un escritorio de noche donde cada dato es un objeto con vida propia, dibujado con luz de neón:
 *   la radio late con la música · el PC tiene un ventilador que gira más deprisa con la carga y tres barras de LED
 *   el micrófono se enciende al escuchar · una nota con la agenda y el temporizador · un reloj · un mando que se ilumina
 * Vega es una pantalla sobre su pie, en el centro, con su cara de siempre. Abajo, en el borde de la mesa, las teclas.
 * Todo está dibujado con trazos y puntos (sin imágenes), en un lienzo de 562 × 184 dp.
 */

private val Cream = Color(0xFFF5F2ED)

@Composable
internal fun ColumnScope.SceneLayout(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    // Los objetos se animan con un reloj propio: el ventilador gira según la carga y la radio late con la música.
    var angle by remember { mutableFloatStateOf(0f) }
    var beat by remember { mutableFloatStateOf(0f) }
    val latest = rememberUpdatedState(x)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
                last = now
                val cur = latest.value
                val t = cur.m.ui.telemetry
                val load = max(t?.cpu ?: 0, t?.gpuUtil ?: 0) / 100f
                angle = (angle + dt * (70f + 700f * load)) % 360f
                val level = if (cur.m.ui.media?.playing == true) cur.m.musicLevel() else 0f
                beat += (level - beat) * min(1f, dt * 12f)
            }
        }
    }

    // El escritorio se dibuja en un lienzo de 562 × 184 dp y se escala para llenar el sitio que haya (el Redmi tiene 800 × 360 dp).
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
        val scale = min(maxWidth / 562.dp, maxHeight / 184.dp)
        Box(
            Modifier.requiredSize(562.dp, 184.dp).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        ) {
        Backdrop()
        // Reloj
        Clock(x, Modifier.offset(0.dp, 0.dp).size(138.dp, 56.dp))
        // Globo de Vega
        Bubble(x, Modifier.offset(146.dp, 0.dp).size(312.dp, 44.dp))
        // Nota
        Note(x, Modifier.offset(466.dp, 2.dp).size(96.dp, 84.dp))
        // Radio
        Radio(x, beat, Modifier.offset(6.dp, 84.dp).size(160.dp, 86.dp))
        // Micrófono
        Mic(x, Modifier.offset(172.dp, 110.dp).size(28.dp, 60.dp))
        // Vega
        Head(x, Modifier.offset(206.dp, 52.dp).size(172.dp, 98.dp))
        // PC
        Tower(x, angle, Modifier.offset(384.dp, 44.dp).size(78.dp, 126.dp))
        // Juego
        GameCorner(x, Modifier.offset(470.dp, 94.dp).size(92.dp, 76.dp))
        // La mesa
        Desk()
        }
    }
    KeyRow(
        favorites = ui.actions.filter { it.quick }, pending = ui.pending, secondsLeft = x.secondsLeft, enabled = x.canAct,
        onPress = x.press, onMore = x.onMore, modifier = Modifier.fillMaxWidth().height(50.dp).cached(), icons = m.icons, media = ui.media,
        compact = true,
    )
}

// ───────────────────────── trazos de neón ─────────────────────────

/** Un trazo con halo: tres pasadas, de ancha y tenue a fina y viva. */
private fun DrawScope.glow(color: Color, width: Float, block: (Stroke, Float) -> Unit) {
    block(Stroke(width * 3.4f, cap = StrokeCap.Round), 0.07f)
    block(Stroke(width * 2f, cap = StrokeCap.Round), 0.16f)
    block(Stroke(width, cap = StrokeCap.Round), 1f)
}

private fun DrawScope.neonRect(c: Color, tl: Offset, sz: Size, r: Float, w: Float = 1.4.dp.toPx()) =
    glow(c, w) { st, a -> drawRoundRect(c.copy(alpha = c.alpha * a), tl, sz, CornerRadius(r), style = st) }

private fun DrawScope.neonCircle(c: Color, center: Offset, radius: Float, w: Float = 1.4.dp.toPx()) =
    glow(c, w) { st, a -> drawCircle(c.copy(alpha = c.alpha * a), radius, center, style = st) }

private fun DrawScope.neonLine(c: Color, a0: Offset, b0: Offset, w: Float = 1.4.dp.toPx()) =
    glow(c, w) { st, a -> drawLine(c.copy(alpha = c.alpha * a), a0, b0, st.width, StrokeCap.Round) }

private fun DrawScope.neonPath(c: Color, path: Path, w: Float = 1.4.dp.toPx()) =
    glow(c, w) { st, a -> drawPath(path, c.copy(alpha = c.alpha * a), style = st) }

// ───────────────────────── fondo y mesa ─────────────────────────

@Composable
private fun Backdrop() {
    Box(
        Modifier.fillMaxSize().drawBehind {
            // La pared: una rejilla de puntos casi invisible.
            val step = 9.dp.toPx()
            val pts = ArrayList<Offset>()
            var y = step / 2
            while (y < 168.dp.toPx()) {
                var x = step / 2
                while (x < size.width) {
                    pts.add(Offset(x, y))
                    x += step
                }
                y += step
            }
            drawPoints(pts, PointMode.Points, Color.White.copy(alpha = 0.05f), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
            // La luz de una lámpara, arriba a la derecha.
            drawRect(
                Brush.radialGradient(
                    listOf(Color(0xFFFFCE8C).copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.9f, 0f), radius = size.width * 0.55f,
                ),
            )
        },
    )
}

@Composable
private fun Desk() {
    Canvas(Modifier.fillMaxSize()) {
        val y = 170.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(Color(0xFF17151A), Color(0xFF09080B)), startY = y, endY = size.height), Offset(0f, y), Size(size.width, size.height - y))
        neonLine(Cream.copy(alpha = 0.85f), Offset(0f, y), Offset(size.width, y), 1.6.dp.toPx())
    }
}

// ───────────────────────── objetos ─────────────────────────

@Composable
private fun Clock(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    Box(modifier.drawBehind { neonRect(Cream.copy(alpha = 0.7f), Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), 10.dp.toPx()) }) {
        Column(Modifier.padding(start = 9.dp, top = 6.dp)) {
            Text(m.clock, maxLines = 1, style = doto(26.sp, Vega.Ink))
            Text(m.date, maxLines = 1, style = label10)
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
            RoundKey("sleep", Vega.Ink2, x.a.sleep, size = 26.dp)
        }
    }
}

@Composable
private fun Bubble(x: LayoutArgs, modifier: Modifier) {
    val color = x.color
    Box(
        modifier.drawBehind {
            neonRect(color.copy(alpha = 0.9f), Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 2.dp.toPx()), 12.dp.toPx())
            // La colita del globo, hacia Vega.
            val cx = size.width / 2
            val p = Path().apply {
                moveTo(cx - 6.dp.toPx(), size.height - 1.dp.toPx())
                lineTo(cx, size.height + 6.dp.toPx())
                lineTo(cx + 6.dp.toPx(), size.height - 1.dp.toPx())
            }
            neonPath(color.copy(alpha = 0.9f), p)
        }.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onChat)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Subtitle(x.m, Modifier.align(Alignment.CenterStart).fillMaxWidth(), compact = true)
    }
}

@Composable
private fun Note(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val next = ui.telemetry?.upcoming?.firstOrNull()
    val local = timerLeft(m)
    Box(
        modifier.rotate(2.2f).background(Color(0xFFFFD166), RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Text("HOY", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Color(0xFF2A1D05)))
            Spacer(Modifier.height(2.dp))
            when {
                next != null -> {
                    Text(countdown(next.dueIn - x.elapsed), maxLines = 1, style = doto(20.sp, Color(0xFF2A1D05)))
                    Text(if (next.kind == "timer") "temporizador" else next.text, maxLines = 2, style = label10.copy(color = Color(0xFF3A2A08), lineHeight = 12.sp))
                    val more = (ui.telemetry?.upcoming?.size ?: 1) - 1
                    if (more > 0) Text("+$more más", maxLines = 1, style = label10.copy(fontSize = 9.sp, color = Color(0xFF5A4410)))
                }
                local != null -> {
                    Text(countdownText(local), maxLines = 1, style = doto(20.sp, Color(0xFF2A1D05)))
                    Text("temporizador", maxLines = 1, style = label10.copy(color = Color(0xFF3A2A08)))
                }
                else -> Text("Sin avisos pendientes", maxLines = 3, style = label10.copy(color = Color(0xFF3A2A08), lineHeight = 12.sp))
            }
        }
    }
}

@Composable
private fun Radio(x: LayoutArgs, beat: Float, modifier: Modifier) {
    val m = x.m
    val media = m.ui.media
    val color = m.mediaAccent
    val active = media != null
    val alpha = if (active) 1f else 0.45f
    val c = (if (active) color else Cream).copy(alpha = alpha)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            neonRect(c, Offset(1.dp.toPx(), 4.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 4.dp.toPx()), 9.dp.toPx())
            // Dos altavoces de puntos: los anillos se agrandan con el volumen.
            for (cx in listOf(26.dp.toPx(), size.width - 26.dp.toPx())) {
                val center = Offset(cx, 50.dp.toPx())
                neonCircle(c, center, 19.dp.toPx() * (1f + 0.07f * beat))
                val pts = ArrayList<Offset>()
                for ((rad, n) in listOf(5.dp.toPx() to 6, 10.dp.toPx() to 12, 15.dp.toPx() to 18)) {
                    for (i in 0 until n) {
                        val a = (2 * PI * i / n).toFloat()
                        val r = rad * (1f + 0.12f * beat)
                        pts.add(Offset(center.x + r * cos(a), center.y + r * sin(a)))
                    }
                }
                drawPoints(pts, PointMode.Points, c, strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
            }
            // La ventanita y la perilla.
            neonRect(c.copy(alpha = alpha * 0.8f), Offset(52.dp.toPx(), 14.dp.toPx()), Size(56.dp.toPx(), 28.dp.toPx()), 4.dp.toPx(), 1.dp.toPx())
            neonCircle(c, Offset(size.width / 2, 62.dp.toPx()), 8.dp.toPx())
            drawLine(c, Offset(size.width / 2, 62.dp.toPx()), Offset(size.width / 2 + 4.dp.toPx(), 57.dp.toPx()), 1.6.dp.toPx(), StrokeCap.Round)
            // Una antena.
            neonLine(c.copy(alpha = alpha * 0.8f), Offset(size.width - 22.dp.toPx(), 4.dp.toPx()), Offset(size.width - 8.dp.toPx(), -2.dp.toPx()), 1.dp.toPx())
        }
        Column(Modifier.offset(54.dp, 16.dp).size(52.dp, 26.dp)) {
            if (media != null) {
                Text(media.title, maxLines = 1, style = label10.copy(fontSize = 10.sp, color = Vega.Ink))
                Text(media.artist, maxLines = 1, style = label10.copy(fontSize = 9.sp, color = Vega.Ink3))
            } else {
                Text("RADIO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
                Text("apagada", maxLines = 1, style = label10.copy(fontSize = 9.sp, color = Vega.Ink3))
            }
        }
    }
}

@Composable
private fun Mic(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val listening = m.listening && m.micOn
    val hearing = listening && (x.mood == com.asistente.personal.ui.led.Mood.LISTEN)
    val c = Cream.copy(alpha = if (listening) 0.95f else 0.4f)
    Canvas(
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { x.a.toggleMic() },
    ) {
        val w = size.width
        neonRect(c, Offset(w * 0.28f, 1.dp.toPx()), Size(w * 0.44f, 28.dp.toPx()), 6.dp.toPx())
        val arc = Path().apply {
            addArc(androidx.compose.ui.geometry.Rect(Offset(w * 0.1f, 8.dp.toPx()), Size(w * 0.8f, 28.dp.toPx())), 0f, 180f)
        }
        neonPath(c, arc)
        neonLine(c, Offset(w / 2, 36.dp.toPx()), Offset(w / 2, size.height - 3.dp.toPx()))
        neonLine(c, Offset(w * 0.2f, size.height - 2.dp.toPx()), Offset(w * 0.8f, size.height - 2.dp.toPx()))
        // El piloto: rojo mientras te escucha.
        val led = if (hearing) Vega.Red else if (listening) Color(0xFF3A6B50) else Color(0xFF3A3A40)
        drawCircle(led.copy(alpha = if (hearing) 0.35f else 0f), 8.dp.toPx(), Offset(w / 2, 12.dp.toPx()))
        drawCircle(led, 3.dp.toPx(), Offset(w / 2, 12.dp.toPx()))
    }
}

@Composable
private fun Head(x: LayoutArgs, modifier: Modifier) {
    val color = x.color
    Box(
        modifier.drawBehind {
            neonRect(Cream.copy(alpha = 0.9f), Offset(1.5.dp.toPx(), 1.5.dp.toPx()), Size(size.width - 3.dp.toPx(), size.height - 3.dp.toPx()), 18.dp.toPx(), 1.8.dp.toPx())
            // Cuello y pie.
            val cx = size.width / 2
            neonLine(Cream.copy(alpha = 0.7f), Offset(cx - 7.dp.toPx(), size.height), Offset(cx - 7.dp.toPx(), size.height + 15.dp.toPx()))
            neonLine(Cream.copy(alpha = 0.7f), Offset(cx + 7.dp.toPx(), size.height), Offset(cx + 7.dp.toPx(), size.height + 15.dp.toPx()))
            neonLine(Cream.copy(alpha = 0.7f), Offset(cx - 30.dp.toPx(), size.height + 17.dp.toPx()), Offset(cx + 30.dp.toPx(), size.height + 17.dp.toPx()), 1.8.dp.toPx())
        }.background(Color(0xFF09090B), RoundedCornerShape(18.dp)).drawBehind {
            // Su luz cae sobre la mesa.
            drawRect(
                Brush.radialGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent), center = Offset(size.width / 2, size.height + 22.dp.toPx()), radius = size.width * 0.8f),
                Offset(-40.dp.toPx(), size.height), Size(size.width + 80.dp.toPx(), 30.dp.toPx()),
            )
        },
    ) {
        FaceCard(
            x.mood, x.color, x.m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { x.a.attention() },
            bare = true, showHeader = false, showReply = false,
            belowFace = {
                Text(
                    moodLabel(x.mood, x.m.ui.assistant), maxLines = 1,
                    style = Vega.label.copy(fontSize = 11.sp, color = x.color), modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
            },
        )
    }
}

@Composable
private fun Tower(x: LayoutArgs, angle: Float, modifier: Modifier) {
    val m = x.m
    val t = m.ui.telemetry
    val mint = Vega.Mint
    val c = mint.copy(alpha = if (t != null) 0.9f else 0.4f)
    val cpu = (t?.cpu ?: 0) / 100f
    val temp = (((t?.gpuTemp ?: 30) - 30) / 60f).coerceIn(0f, 1f)
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else 0f
    val tempText = t?.gpuTemp?.let { "$it°" } ?: "—"
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            neonRect(c, Offset(1.dp.toPx(), 1.dp.toPx()), Size(size.width - 2.dp.toPx(), size.height - 1.dp.toPx()), 9.dp.toPx())
            // El ventilador: un aro y tres aspas que giran.
            val center = Offset(size.width / 2, 27.dp.toPx())
            neonCircle(c, center, 19.dp.toPx())
            rotateScope(angle, center) {
                for (k in 0 until 3) {
                    rotateScope(k * 120f, center) {
                        val blade = Path().apply {
                            moveTo(center.x, center.y)
                            cubicTo(center.x + 9.dp.toPx(), center.y - 4.dp.toPx(), center.x + 9.dp.toPx(), center.y - 15.dp.toPx(), center.x, center.y - 17.dp.toPx())
                            cubicTo(center.x - 4.dp.toPx(), center.y - 12.dp.toPx(), center.x - 3.dp.toPx(), center.y - 5.dp.toPx(), center.x, center.y)
                        }
                        drawPath(blade, c.copy(alpha = 0.85f))
                    }
                }
            }
            drawCircle(Color(0xFF09090B), 3.dp.toPx(), center)
            // Tres barras de LED: CPU, gráfica y memoria de vídeo.
            val bw = 11.dp.toPx()
            val top = 56.dp.toPx()
            val h = 32.dp.toPx()
            for ((i, f) in listOf(cpu, temp, vram).withIndex()) {
                val bx = 14.dp.toPx() + i * 19.dp.toPx()
                val hot = f >= 0.85f
                val col = if (hot) Vega.Red else mint
                neonRect(c.copy(alpha = 0.55f), Offset(bx, top), Size(bw, h), 3.dp.toPx(), 1.dp.toPx())
                val fill = (h - 3.dp.toPx()) * f.coerceIn(0f, 1f)
                drawRoundRect(col, Offset(bx + 1.5.dp.toPx(), top + h - 1.5.dp.toPx() - fill), Size(bw - 3.dp.toPx(), fill), CornerRadius(2.dp.toPx()))
            }
        }
        Row(Modifier.offset(0.dp, 91.dp).fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            for (l in listOf("C", "G", "V")) Text(l, maxLines = 1, style = label10.copy(fontSize = 9.sp, color = Vega.Ink3), modifier = Modifier.width(14.dp))
        }
        Text(
            tempText, maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (temp >= 0.75f) Vega.Red else Vega.Ink),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp),
        )
    }
}

@Composable
private fun GameCorner(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val game = ui.game
    val c = (if (game != null) m.gameAccent else Cream).copy(alpha = if (game != null) 1f else 0.4f)
    Box(modifier) {
        Column(Modifier.offset(0.dp, 0.dp).fillMaxWidth()) {
            if (game != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(m.gameAccent, 6.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 12.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold))
                }
                Text(sessionClock(liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)), maxLines = 1, style = doto(18.sp, Vega.Ink))
            } else {
                Text("JUEGO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
                Text("ninguno abierto", maxLines = 1, style = label10.copy(color = Vega.Ink3))
            }
        }
        // El mando, en la mesa.
        Canvas(Modifier.align(Alignment.BottomStart).offset(8.dp, 0.dp).size(58.dp, 34.dp)) {
            val w = size.width
            val h = size.height
            val body = Path().apply {
                moveTo(w * 0.22f, h * 0.18f)
                lineTo(w * 0.78f, h * 0.18f)
                cubicTo(w * 0.98f, h * 0.18f, w * 1.02f, h * 0.6f, w * 0.94f, h * 0.86f)
                cubicTo(w * 0.9f, h * 1.0f, w * 0.78f, h * 0.98f, w * 0.7f, h * 0.84f)
                lineTo(w * 0.62f, h * 0.7f)
                lineTo(w * 0.38f, h * 0.7f)
                lineTo(w * 0.3f, h * 0.84f)
                cubicTo(w * 0.22f, h * 0.98f, w * 0.1f, h * 1.0f, w * 0.06f, h * 0.86f)
                cubicTo(w * -0.02f, h * 0.6f, w * 0.02f, h * 0.18f, w * 0.22f, h * 0.18f)
                close()
            }
            neonPath(c, body)
            // Cruceta y botones: se encienden jugando.
            val lit = game != null
            neonLine(c, Offset(w * 0.22f, h * 0.42f), Offset(w * 0.38f, h * 0.42f), 1.2.dp.toPx())
            neonLine(c, Offset(w * 0.3f, h * 0.3f), Offset(w * 0.3f, h * 0.54f), 1.2.dp.toPx())
            for ((px, py) in listOf(0.68f to 0.33f, 0.78f to 0.45f)) {
                drawCircle(if (lit) m.gameAccent else c, 2.2.dp.toPx(), Offset(w * px, h * py))
            }
        }
    }
}
