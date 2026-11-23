package com.asistente.personal.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.ui.led.Glance
import com.asistente.personal.ui.led.Mood
import com.asistente.personal.ui.led.countdownText
import com.asistente.personal.ui.led.ledOff

/*
 * Tres maneras de repartir la pantalla principal con las mismas piezas de Vega (tarjetas negras, números de
 * puntos, barras de LED). Se eligen con ScreenModel.layout (1, 2 o 3); el 0 es la de siempre.
 *
 *  1 · Panel   — una sola columna a la derecha con PC, música, juego y agenda a la vez; la que importa se despliega.
 *  2 · Flancos — una sola tarjeta ancha: medidores de LED a la izquierda, la cara en el centro, hora y contexto a la derecha.
 *  3 · Cara    — la cara con más altura y una franja de datos debajo; las teclas pasan a una cuadrícula a la derecha.
 */

/** Todo lo que necesitan las distribuciones, tal como lo calcula VegaScreen. */
internal class LayoutArgs(
    val m: ScreenModel,
    val a: ScreenActions,
    val mood: Mood,
    val color: Color,
    val glance: Glance?,
    val aura: Color,
    val elapsed: Long,
    val secondsLeft: Int,
    val canAct: Boolean,
    val press: (String) -> Unit,
    val onMore: () -> Unit,
    val onChat: () -> Unit,
    val onOpenMusic: () -> Unit,
    val onOpenGame: () -> Unit,
    val confirmFraction: Float?,
    /** Abre la «Mesa» (volumen por programa y fondo de pantalla del PC). */
    val onMesa: () -> Unit = {},
    /** Abre el tablero de Moodle (entregas por columnas). */
    val onTablero: () -> Unit = {},
    /** Abre la racha del gimnasio (la llama). */
    val onRacha: () -> Unit = {},
    /** Abre todos los avisos del PC (agrupados por app). */
    val onOpenNotes: () -> Unit = {},
)

internal fun doto(size: TextUnit, color: Color) =
    TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = size, lineHeight = size, color = color)

internal val label10 get() = Vega.label.copy(fontSize = 10.sp)
internal val es = java.util.Locale("es", "ES")

/** Línea de puntos: el separador de Vega (como las barras, hecho de LED apagados). */
@Composable
internal fun LedRule(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val off = ledOff
    Canvas(if (vertical) modifier.width(3.dp) else modifier.height(3.dp)) {
        val step = 6.dp.toPx()
        val r = 0.9.dp.toPx()
        if (vertical) {
            var y = step / 2
            while (y < size.height) {
                drawCircle(off, r, Offset(size.width / 2, y))
                y += step
            }
        } else {
            var x = step / 2
            while (x < size.width) {
                drawCircle(off, r, Offset(x, size.height / 2))
                x += step
            }
        }
    }
}

/** Un medidor vertical de dos columnas de puntos que se llenan de abajo arriba, con su cifra arriba y su nombre abajo. */
@Composable
private fun DotMeterV(fraction: Float?, name: String, value: String, modifier: Modifier = Modifier, invert: Boolean = false) {
    val off = ledOff
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, maxLines = 1, style = doto(13.sp, Vega.Ink))
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.weight(1f).width(16.dp)) {
            val rows = 14
            val step = size.height / rows
            val r = minOf(step, size.width / 2) * 0.42f
            val f = (fraction ?: 0f).coerceIn(0f, 1f)
            val lit = (f * rows + 0.5f).toInt()
            val color = when {
                fraction == null -> off
                !invert && f >= 0.9f || invert && f <= 0.15f -> Vega.Red
                !invert && f >= 0.75f || invert && f <= 0.3f -> Vega.Amber
                else -> Vega.Light
            }
            for (i in 0 until rows) for (c in 0..1) {
                drawCircle(if (i >= rows - lit) color else off, r, Offset(size.width * (0.25f + 0.5f * c), step * (i + 0.5f)))
            }
            // Marcas a un cuarto, la mitad y tres cuartos: se lee el valor sin mirar la cifra.
            for (q in 1..3) {
                val y = size.height * q / 4f
                drawLine(Vega.Ink3.copy(alpha = 0.55f), Offset(-6.dp.toPx(), y), Offset(-2.dp.toPx(), y), 1.dp.toPx())
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(name, maxLines = 1, style = label10.copy(fontSize = 9.sp, color = Vega.Ink3))
    }
}

/** Nombre, barra de diez puntos y cifra, en una línea corta (para columnas estrechas). */
@Composable
private fun MiniStat(name: String, value: String, fraction: Float?, modifier: Modifier = Modifier, invert: Boolean = false, charging: Boolean = false) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, maxLines = 1, style = label10.copy(color = Vega.Ink3), modifier = Modifier.width(33.dp))
        DotBar(fraction, invert, charging, Modifier.weight(1f).height(6.dp))
        Text(value, maxLines = 1, style = label10.copy(color = Vega.Ink), modifier = Modifier.padding(start = 5.dp))
    }
}

internal fun linkText(m: ScreenModel): Pair<String, Color> = when (m.ui.link) {
    LinkStatus.OPEN -> "En línea" to Vega.Mint
    LinkStatus.CONNECTING -> "Conectando…" to Vega.Amber
    LinkStatus.RECONNECTING -> "Reconectando…" to Vega.Amber
    LinkStatus.DENIED -> "Acceso denegado" to Vega.Red
}

/** La hora de puntos y el micrófono, para la cabecera de la cara. */
@Composable
private fun RowScope.HeaderTools(m: ScreenModel, a: ScreenActions, withTime: Boolean, withSleep: Boolean = false) {
    if (withTime) {
        Text(m.clock, maxLines = 1, style = doto(18.sp, Vega.Ink))
        Spacer(Modifier.width(8.dp))
    }
    val micOn = m.listening && m.micOn
    Pill(if (micOn) "Oyendo" else "Micro apagado", if (micOn) Vega.Coral else Vega.Ink2, a.toggleMic, filled = micOn)
    if (withSleep) {
        Spacer(Modifier.width(5.dp))
        Pill("Dormir", Vega.Ink2, a.sleep)
    }
}

@Composable
private fun ColumnScope.MainKeys(x: LayoutArgs) {
    val ui = x.m.ui
    KeyRow(
        favorites = ui.actions.filter { it.quick }, pending = ui.pending, secondsLeft = x.secondsLeft, enabled = x.canAct,
        onPress = x.press, onMore = x.onMore, modifier = Modifier.fillMaxWidth().height(66.dp).cached(), icons = x.m.icons, media = ui.media,
    )
}

@Composable
internal fun Modifier.attention(x: LayoutArgs): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        // Sin enlace con el PC (y la IA no está en el teléfono), un toque en la cara reintenta ya.
        if (x.m.ui.link != com.asistente.personal.net.LinkStatus.OPEN && !x.m.brainOnPhone) x.a.retryNow() else x.a.attention()
    }

/** Lo siguiente que va a avisar: el del PC, el del teléfono o nada. */
@Composable
internal fun AgendaText(x: LayoutArgs, bigSize: TextUnit, textSize: TextUnit, lines: Int = 1) {
    val m = x.m
    val next = m.ui.telemetry?.upcoming?.firstOrNull()
    val local = timerLeft(m)
    when {
        next != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(countdown(next.dueIn - x.elapsed), style = doto(bigSize, Vega.Amber))
            Spacer(Modifier.width(7.dp))
            Text(
                if (next.kind == "timer") "temporizador" else next.text, maxLines = lines,
                style = Vega.label.copy(fontSize = textSize, lineHeight = textSize * 1.15f, color = Vega.Ink),
            )
        }
        local != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(countdownText(local), style = doto(bigSize, Vega.Amber))
            Spacer(Modifier.width(7.dp))
            Text("temporizador", maxLines = 1, style = Vega.label.copy(fontSize = textSize, color = Vega.Ink))
        }
        else -> Text("Sin recordatorios pendientes", maxLines = 1, style = Vega.label.copy(fontSize = textSize, color = Vega.Ink3))
    }
}

// ───────────────────────── 1 · Panel ─────────────────────────

@Composable
internal fun ColumnScope.PanelLayout(x: LayoutArgs) {
    val m = x.m
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FaceCard(
            x.mood, x.color, m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.weight(1f).fillMaxHeight().attention(x),
            headerRight = { HeaderTools(m, x.a, withTime = true) },
        )
        PanelCard(x, Modifier.width(250.dp).fillMaxHeight().cached())
    }
    MainKeys(x)
}

private enum class RowMode { NORMAL, COMPACT, EXPANDED }

@Composable
private fun PanelCard(x: LayoutArgs, modifier: Modifier) {
    val ui = x.m.ui
    val focus = when {
        ui.media != null -> 1
        ui.game != null -> 2
        else -> 0
    }
    val other = if (focus == 0) RowMode.NORMAL else RowMode.COMPACT
    Column(
        modifier.card(x.aura).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        PcRow(x, other)
        LedRule(Modifier.fillMaxWidth())
        MusicRow(x, if (focus == 1) RowMode.EXPANDED else other)
        LedRule(Modifier.fillMaxWidth())
        GameRow(x, if (focus == 2) RowMode.EXPANDED else other)
        LedRule(Modifier.fillMaxWidth())
        AgendaRow(x, other)
    }
}

@Composable
private fun PcRow(x: LayoutArgs, mode: RowMode) {
    val m = x.m
    val t = m.ui.telemetry
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniStat("CPU", t?.cpu?.let { "$it %" } ?: "—", t?.cpu?.div(100f), Modifier.weight(1f))
            MiniStat("GPU", t?.gpuTemp?.let { "$it°" } ?: "—", t?.gpuTemp?.let { (it - 30) / 60f }, Modifier.weight(1f))
        }
        if (mode != RowMode.COMPACT) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("VRAM", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", vram, Modifier.weight(1f))
                MiniStat("BAT", if (m.battery >= 0) "${m.battery} %" else "—", if (m.battery >= 0) m.battery / 100f else null, Modifier.weight(1f), invert = true, charging = m.charging)
            }
        }
    }
}

@Composable
private fun MusicRow(x: LayoutArgs, mode: RowMode) {
    val m = x.m
    val media = m.ui.media
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (media == null) {
            if (mode == RowMode.NORMAL) {
                Text("MÚSICA", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
                Text("Nada suena ahora", maxLines = 1, style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink3))
            } else Text("MÚSICA · nada suena", maxLines = 1, style = label10.copy(color = Vega.Ink3))
        } else if (mode == RowMode.EXPANDED) {
            Row(
                Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenMusic),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Art(m.mediaArt, m.mediaAccent, Modifier.size(50.dp), 10.dp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Equalizer(media.playing, m.mediaAccent, Modifier.size(width = 13.dp, height = 9.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(media.app.uppercase(), maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.mediaAccent))
                    }
                    Text(media.title, maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
                    Text(media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp))
                    Spacer(Modifier.height(4.dp))
                    Progress(livePosition(media, m.ui.mediaAt, m.clockMs), media.duration, m.mediaAccent, Modifier.fillMaxWidth())
                }
            }
        } else {
            if (mode == RowMode.NORMAL) Text("SONANDO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.mediaAccent))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Equalizer(media.playing, m.mediaAccent, Modifier.size(width = 13.dp, height = 9.dp))
                Spacer(Modifier.width(6.dp))
                Text(media.title, maxLines = 1, style = Vega.body.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f, fill = false))
                Text("  " + media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp), modifier = Modifier.weight(1f, fill = false))
            }
        }
    }
}

@Composable
private fun GameRow(x: LayoutArgs, mode: RowMode) {
    val m = x.m
    val ui = m.ui
    val game = ui.game
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (game == null) {
            if (mode == RowMode.NORMAL) {
                Text("JUEGO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
                val last = ui.recentGames.firstOrNull()
                Text(
                    if (last != null) "Sin juegos · último ${last.name}" else "Sin juegos ahora mismo",
                    maxLines = 1, style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink3),
                )
            } else Text("JUEGO · ninguno abierto", maxLines = 1, style = label10.copy(color = Vega.Ink3))
        } else if (mode == RowMode.EXPANDED) {
            val seconds = liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)
            Row(
                Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenGame),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GameArt(m.gameArt, game.name, m.gameAccent, Modifier.size(50.dp), 10.dp, initialSize = 20.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveDot(m.gameAccent, 6.dp)
                        Spacer(Modifier.width(5.dp))
                        Text("JUGANDO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.gameAccent))
                    }
                    Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
                    Text(sessionClock(seconds), maxLines = 1, style = doto(20.sp, Vega.Ink))
                    ui.telemetry?.let { t ->
                        Text(
                            listOfNotNull(t.gpuUtil?.let { "GPU $it %" }, t.gpuTemp?.let { "$it°" }).joinToString(" · "),
                            maxLines = 1, style = label10.copy(color = Vega.Ink2),
                        )
                    }
                }
            }
        } else {
            if (mode == RowMode.NORMAL) Text("JUGANDO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.gameAccent))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(m.gameAccent, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun AgendaRow(x: LayoutArgs, mode: RowMode) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        if (mode == RowMode.NORMAL) Text("HOY", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
        AgendaText(x, bigSize = if (mode == RowMode.NORMAL) 20.sp else 14.sp, textSize = if (mode == RowMode.NORMAL) 12.sp else 11.sp)
    }
}

// ───────────────────────── 2 · Flancos ─────────────────────────

@Composable
internal fun ColumnScope.FlankLayout(x: LayoutArgs) {
    val m = x.m
    Row(Modifier.weight(1f).fillMaxWidth().card(x.aura), verticalAlignment = Alignment.CenterVertically) {
        MeterColumn(x, Modifier.width(134.dp).fillMaxHeight().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 10.dp))
        LedRule(Modifier.fillMaxHeight().padding(vertical = 14.dp), vertical = true)
        FaceCard(
            x.mood, x.color, m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.weight(1f).fillMaxHeight().attention(x), bare = true,
            headerRight = { HeaderTools(m, x.a, withTime = false) },
        )
        LedRule(Modifier.fillMaxHeight().padding(vertical = 14.dp), vertical = true)
        ContextColumn(x, Modifier.width(132.dp).fillMaxHeight().padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp))
    }
    MainKeys(x)
}

@Composable
private fun MeterColumn(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val t = m.ui.telemetry
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
    Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
        DotMeterV(t?.cpu?.div(100f), "CPU", t?.cpu?.toString() ?: "—", Modifier.fillMaxHeight().width(28.dp))
        DotMeterV(t?.gpuTemp?.let { (it - 30) / 60f }, "GPU", t?.gpuTemp?.let { "$it°" } ?: "—", Modifier.fillMaxHeight().width(28.dp))
        DotMeterV(vram, "VRAM", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", Modifier.fillMaxHeight().width(28.dp))
        DotMeterV(if (m.battery >= 0) m.battery / 100f else null, "BAT", if (m.battery >= 0) "${m.battery}" else "—", Modifier.fillMaxHeight().width(28.dp), invert = true)
    }
}

@Composable
private fun ContextColumn(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val (link, linkColor) = linkText(m)
    Column(modifier) {
        Text(m.clock, maxLines = 1, style = doto(30.sp, Vega.Ink))
        Spacer(Modifier.height(3.dp))
        Text(m.date, maxLines = 1, style = label10)
        if (lowBattery(m.battery, m.charging)) Text("Batería ${m.battery} %", maxLines = 1, style = label10.copy(color = Vega.Red))
        else Text(link, maxLines = 1, style = label10.copy(color = linkColor))
        Spacer(Modifier.height(6.dp))
        LedRule(Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        val media = ui.media
        val game = ui.game
        when {
            media != null -> Column(
                Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenMusic),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Equalizer(media.playing, m.mediaAccent, Modifier.size(width = 13.dp, height = 9.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(media.app.uppercase(), maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.mediaAccent))
                }
                Text(media.title, maxLines = 1, style = Vega.body.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold))
                Text(media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp))
                Spacer(Modifier.weight(1f))
                Progress(livePosition(media, ui.mediaAt, m.clockMs), media.duration, m.mediaAccent, Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clockTime(livePosition(media, ui.mediaAt, m.clockMs)), style = label10.copy(fontSize = 9.sp, color = Vega.Ink3))
                    Text(if (media.duration > 0) clockTime(media.duration) else "", style = label10.copy(fontSize = 9.sp, color = Vega.Ink3))
                }
            }
            game != null -> Column(
                Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenGame),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(m.gameAccent, 6.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("JUGANDO", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = m.gameAccent))
                }
                Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold))
                Text(sessionClock(liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)), maxLines = 1, style = doto(20.sp, Vega.Ink))
                ui.telemetry?.let { t ->
                    Text(
                        listOfNotNull(t.gpuUtil?.let { "GPU $it %" }, t.gpuTemp?.let { "$it°" }).joinToString(" · "),
                        maxLines = 1, style = label10.copy(color = Vega.Ink2),
                    )
                }
            }
            else -> Column {
                Text("HOY", maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
                val next = ui.telemetry?.upcoming?.firstOrNull()
                val local = timerLeft(m)
                when {
                    next != null -> {
                        Text(countdown(next.dueIn - x.elapsed), style = doto(24.sp, Vega.Amber))
                        Text(
                            if (next.kind == "timer") "temporizador" else next.text, maxLines = 2,
                            style = Vega.label.copy(fontSize = 11.sp, lineHeight = 13.sp, color = Vega.Ink),
                        )
                    }
                    local != null -> {
                        Text(countdownText(local), style = doto(24.sp, Vega.Amber))
                        Text("temporizador", maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink))
                    }
                    else -> Text("Sin recordatorios pendientes", maxLines = 2, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink3))
                }
            }
        }
    }
}

// ───────────────────────── 3 · Cara grande ─────────────────────────

@Composable
internal fun ColumnScope.GridLayout(x: LayoutArgs) {
    val m = x.m
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FaceCard(
            x.mood, x.color, m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.weight(1f).fillMaxHeight().attention(x),
            belowFace = { DataStrip(x) },
        )
        Column(Modifier.width(232.dp).fillMaxHeight().cached(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockModule(x, Modifier.fillMaxWidth())
            KeyGrid(x, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** La hora, la fecha y el estado a la izquierda; el micrófono y dormir como dos botones redondos de LED a la derecha. */
@Composable
private fun ClockModule(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val (link, linkColor) = linkText(m)
    val micOn = m.listening && m.micOn
    Row(
        modifier.card(x.aura).padding(start = 14.dp, end = 10.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.clock, maxLines = 1, style = doto(34.sp, Vega.Ink))
            Spacer(Modifier.height(3.dp))
            Text(m.date, maxLines = 1, style = label10)
            when {
                m.updateAvailable -> Text("Actualizar disponible", maxLines = 1, style = label10.copy(color = Vega.Amber))
                lowBattery(m.battery, m.charging) -> Text("Batería ${m.battery} %", maxLines = 1, style = label10.copy(color = Vega.Red))
                else -> Text(link, maxLines = 1, style = label10.copy(color = linkColor))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            RoundKey("mic", if (micOn) Vega.Coral else Vega.Ink2, x.a.toggleMic, filled = micOn)
            RoundKey("sleep", Vega.Ink2, x.a.sleep)
        }
    }
}

/** Un botón redondo con un icono de LED (apagado en reposo; encendido con el color si está activo). */
@Composable
internal fun RoundKey(icon: String, color: Color, onClick: () -> Unit, filled: Boolean = false, size: androidx.compose.ui.unit.Dp = 32.dp, modifier: Modifier = Modifier) {
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed.value) 0.9f else 1f, spring(dampingRatio = 0.5f, stiffness = 1500f), label = "pulsar")
    val glyph = remember(icon) { deckGlyph(icon) }
    Box(
        modifier.size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(if (filled) color.copy(alpha = 0.18f) else Vega.CardHi, CircleShape)
            .border(1.dp, if (filled) color.copy(alpha = 0.75f) else Vega.Line, CircleShape)
            .instantPress(enabled = true, pressed = pressed, onPress = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LedIcon(glyph, color, Modifier.size(20.dp), glow = if (filled) 0.7f else 0f)
    }
}

@Composable
private fun StripCell(name: String, value: String, fraction: Float?, modifier: Modifier = Modifier, invert: Boolean = false, charging: Boolean = false) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(name, maxLines = 1, style = label10.copy(fontSize = 9.sp, color = Vega.Ink3))
            Text(value, maxLines = 1, style = doto(12.sp, Vega.Ink))
        }
        Spacer(Modifier.height(3.dp))
        DotBar(fraction, invert, charging, Modifier.fillMaxWidth().height(6.dp))
    }
}

@Composable
private fun ColumnScope.DataStrip(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val t = ui.telemetry
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
    Column(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 6.dp)) {
        LedRule(Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StripCell("CPU", t?.cpu?.let { "$it %" } ?: "—", t?.cpu?.div(100f), Modifier.weight(1f))
            StripCell("GPU", t?.gpuTemp?.let { "$it°" } ?: "—", t?.gpuTemp?.let { (it - 30) / 60f }, Modifier.weight(1f))
            StripCell("VRAM", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", vram, Modifier.weight(1f))
            StripCell("BAT", if (m.battery >= 0) "${m.battery} %" else "—", if (m.battery >= 0) m.battery / 100f else null, Modifier.weight(1f), invert = true, charging = m.charging)
        }
        Spacer(Modifier.height(6.dp))
        val media = ui.media
        val game = ui.game
        when {
            media != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Equalizer(media.playing, m.mediaAccent, Modifier.size(width = 13.dp, height = 9.dp))
                Spacer(Modifier.width(6.dp))
                Text(media.title, maxLines = 1, style = Vega.body.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f, fill = false))
                Text("  " + media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp), modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Text(clockTime(livePosition(media, ui.mediaAt, m.clockMs)), style = label10.copy(color = Vega.Ink3))
            }
            game != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(m.gameAccent, 6.dp)
                Spacer(Modifier.width(6.dp))
                Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Text(sessionClock(liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)), style = doto(14.sp, Vega.Ink))
            }
            else -> AgendaText(x, bigSize = 14.sp, textSize = 11.sp)
        }
    }
}

@Composable
private fun KeyGrid(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val favs = ui.actions.filter { it.quick }.take(5)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in 0 until 3) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 2) {
                    val action = favs.getOrNull(r * 2 + c)
                    if (action != null) {
                        val s = keyState(action, ui.media)
                        Key(
                            label = s.label, live = s.live, icon = action.icon, color = deckColor(action.color), enabled = x.canAct,
                            image = m.icons[action.id], id = action.id, confirming = ui.pending?.id == action.id, secondsLeft = x.secondsLeft,
                            onClick = { x.press(action.id) }, modifier = Modifier.weight(1f).fillMaxHeight(), horizontal = true,
                        )
                    } else {
                        Key(
                            label = "Más", icon = "more", color = Vega.Ink2, enabled = true, confirming = false, secondsLeft = 0,
                            onClick = x.onMore, modifier = Modifier.weight(1f).fillMaxHeight(), horizontal = true,
                        )
                    }
                }
            }
        }
    }
}
