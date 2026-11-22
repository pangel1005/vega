package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.ui.led.Mood
import com.asistente.personal.ui.led.countdownText
import com.asistente.personal.ui.led.ledOff
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Vega · Pulso. La pantalla principal vuelve a pensarse desde cero: en vez de tarjetas con cifras sueltas, el PC, tu voz,
 * la voz de Vega y la música corren como luces de LED hacia «ahora». Los datos tienen historia: un pico de la gráfica,
 * tu voz pidiendo algo y la respuesta quedan en la misma línea de tiempo.
 *
 *   arriba — el estado de Vega, la hora y los botones del micro y de dormir
 *   centro — la cara en su ventana, y el panel de pulso con cuatro pistas
 *   abajo  — una fila baja de teclas con relieve
 */

private const val N = 60

/** Las últimas 60 lecturas de cada magnitud (de la más vieja a la más nueva), de 0 a 1. */
private class PulseSeries {
    val cpu = FloatArray(N)
    val temp = FloatArray(N)
    val gpu = FloatArray(N)
    val vram = FloatArray(N)
    val voice = FloatArray(N)
    val music = FloatArray(N)

    fun push(a: FloatArray, v: Float) {
        System.arraycopy(a, 1, a, 0, N - 1)
        a[N - 1] = v.coerceIn(0f, 1f)
    }
}

private class Track(
    val name: String,
    val value: String,
    val data: FloatArray,
    val color: Color,
    /** Por encima de este valor, los puntos se pintan en rojo. */
    val hotAt: Float? = null,
    /** Onda simétrica (la voz) en vez de barras desde abajo. */
    val centered: Boolean = false,
    val weight: Float = 1f,
    /** Fila baja: el nombre y la cifra van en una sola línea. */
    val dense: Boolean = false,
)

@Composable
internal fun ColumnScope.PulseLayout(x: LayoutArgs) {
    val m = x.m
    val series = remember { PulseSeries() }
    var version by remember { mutableIntStateOf(0) }
    val latest = rememberUpdatedState(x)
    val voice = remember { FloatArray(1) }
    // Cada pulso de voz (la tuya al escucharte, la de Vega al hablar) sube el nivel; luego cae solo.
    LaunchedEffect(m.pulse) { if (m.pulse > 0) voice[0] = max(voice[0], m.pulseAmount) }
    LaunchedEffect(Unit) {
        // Al abrir, las pistas del PC arrancan con el valor de ahora en vez de vacías.
        latest.value.m.ui.telemetry?.let { t ->
            series.cpu.fill((t.cpu / 100f).coerceIn(0f, 1f))
            series.temp.fill((((t.gpuTemp ?: 30) - 30) / 60f).coerceIn(0f, 1f))
            series.gpu.fill(((t.gpuUtil ?: 0) / 100f).coerceIn(0f, 1f))
            series.vram.fill(vramFraction(t.vramUsed, t.vramTotal))
        }
        var tick = 0
        while (true) {
            delay(125)
            val cur = latest.value
            val t = cur.m.ui.telemetry
            // El PC se lee cada segundo; la voz y la música, ocho veces por segundo.
            if (tick % 8 == 0 && t != null) {
                series.push(series.cpu, t.cpu / 100f)
                series.push(series.temp, ((t.gpuTemp ?: 30) - 30) / 60f)
                series.push(series.gpu, (t.gpuUtil ?: 0) / 100f)
                series.push(series.vram, vramFraction(t.vramUsed, t.vramTotal))
            }
            series.push(series.voice, voice[0])
            voice[0] *= 0.6f
            series.push(series.music, if (cur.m.ui.media != null) cur.m.musicLevel() else 0f)
            tick++
            version++
        }
    }

    PulseHeader(x)
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FaceCard(
            x.mood, x.color, m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.width(262.dp).fillMaxHeight().attention(x), showHeader = false, compactReply = true,
        )
        PulsePanel(x, series, version, Modifier.weight(1f).fillMaxHeight())
    }
    val ui = m.ui
    KeyRow(
        favorites = ui.actions.filter { it.quick }, pending = ui.pending, secondsLeft = x.secondsLeft, enabled = x.canAct,
        onPress = x.press, onMore = x.onMore, modifier = Modifier.fillMaxWidth().height(50.dp).cached(), icons = m.icons, media = ui.media,
        compact = true,
    )
}

private fun vramFraction(used: Double?, total: Double?): Float =
    if (used != null && total != null && total > 0) (used / total).toFloat().coerceIn(0f, 1f) else 0f

/** Estado a la izquierda; hora, fecha y los dos botones redondos a la derecha. */
@Composable
private fun PulseHeader(x: LayoutArgs) {
    val m = x.m
    val (link, linkColor) = linkText(m)
    val micOn = m.listening && m.micOn
    Row(Modifier.fillMaxWidth().height(28.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Dot(x.color)
        Spacer(Modifier.width(7.dp))
        Text(moodLabel(x.mood, m.ui.assistant), maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = x.color))
        if (m.brainOnPhone) {
            Spacer(Modifier.width(10.dp))
            Text(m.brainLabel, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Mint))
        }
        Spacer(Modifier.weight(1f))
        Text(
            if (lowBattery(m.battery, m.charging)) "Batería ${m.battery} %" else link, maxLines = 1,
            style = label10.copy(color = if (lowBattery(m.battery, m.charging)) Vega.Red else linkColor),
        )
        Spacer(Modifier.width(12.dp))
        Text(m.date, maxLines = 1, style = label10)
        Spacer(Modifier.width(10.dp))
        Text(m.clock, maxLines = 1, style = doto(20.sp, Vega.Ink))
        Spacer(Modifier.width(12.dp))
        RoundKey("mic", if (micOn) Vega.Coral else Vega.Ink2, x.a.toggleMic, filled = micOn, size = 26.dp)
        Spacer(Modifier.width(6.dp))
        RoundKey("sleep", Vega.Ink2, x.a.sleep, size = 26.dp)
    }
}

@Composable
private fun PulsePanel(x: LayoutArgs, s: PulseSeries, version: Int, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val t = ui.telemetry
    val cpu = Track("CPU", t?.cpu?.let { "$it %" } ?: "—", s.cpu, Vega.Light, hotAt = 0.9f)
    val temp = Track("GRÁFICA", t?.gpuTemp?.let { "$it°" } ?: "—", s.temp, Vega.Amber, hotAt = 0.75f)
    val gpu = Track("GPU", t?.gpuUtil?.let { "$it %" } ?: "—", s.gpu, Vega.Amber, hotAt = 0.9f)
    val vram = Track("VÍDEO", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", s.vram, Vega.Mint, hotAt = 0.9f)
    val voiceText = when (x.mood) {
        Mood.LISTEN -> "te oigo"
        Mood.TALK -> "habla"
        Mood.THINK -> "piensa"
        else -> "calma"
    }
    val voice = Track("VOZ", voiceText, s.voice, Vega.Coral, centered = true)
    val music = Track("MÚSICA", ui.media?.let { clockTime(livePosition(it, ui.mediaAt, m.clockMs)) } ?: "—", s.music, m.mediaAccent, centered = true, weight = 2.1f)
    val tracks = when {
        ui.game != null -> listOf(gpu, temp, vram, cpu)
        ui.media != null -> listOf(cpu, temp, voice, music)
        else -> listOf(cpu, temp, vram, voice)
    }.let { list -> if (ui.media != null && ui.game == null) list.map { if (it.weight == 1f) Track(it.name, it.value, it.data, it.color, it.hotAt, it.centered, it.weight, dense = true) else it } else list }
    Column(modifier.card(x.aura).padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 6.dp)) {
        tracks.forEach { tr ->
            Row(Modifier.weight(tr.weight).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (tr.dense) Column(Modifier.width(56.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(tr.name.take(3), maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, color = Vega.Ink3))
                        Spacer(Modifier.width(4.dp))
                        Text(tr.value, maxLines = 1, style = doto(11.sp, Vega.Ink))
                    }
                } else Column(Modifier.width(56.dp)) {
                    Text(tr.name, maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, color = Vega.Ink3))
                    Text(tr.value, maxLines = 1, style = doto(13.sp, Vega.Ink))
                }
                TrackChart(tr, version, Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp))
            }
        }
        Spacer(Modifier.height(3.dp))
        LedRule(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        ContextLine(x)
    }
}

/** Una pista: columnas de puntos que corren hacia la derecha; las más viejas, más tenues. */
@Composable
private fun TrackChart(t: Track, version: Int, modifier: Modifier) {
    val off = ledOff
    Canvas(modifier) {
        if (version < 0) return@Canvas // solo para que se redibuje con cada lectura nueva
        val rows = if (t.centered) 7 else max(3, (size.height / 5.dp.toPx()).toInt())
        val px = size.width / N
        val py = size.height / rows
        val r = min(px, py) * 0.4f
        val offPts = ArrayList<Offset>(N * rows)
        val lit = Array(4) { ArrayList<Offset>(N * rows / 3) }
        val hot = ArrayList<Offset>()
        val glow = ArrayList<Offset>()
        for (c in 0 until N) {
            val v = t.data[c]
            val cx = px * (c + 0.5f)
            val level = min(3, ((0.25f + 0.75f * c / (N - 1f)) * 4f).toInt())
            val k = if (t.centered) (v * (rows / 2f)).roundToInt() else (v * rows).roundToInt()
            for (row in 0 until rows) {
                val p = Offset(cx, py * (row + 0.5f))
                val on = if (t.centered) abs(row - rows / 2) < k || (row == rows / 2 && v > 0.03f) else row >= rows - k
                when {
                    !on -> offPts.add(p)
                    t.hotAt != null && v >= t.hotAt -> hot.add(p)
                    else -> lit[level].add(p)
                }
                if (on && c >= N - 3) glow.add(p)
            }
        }
        drawPoints(offPts, PointMode.Points, off, strokeWidth = r * 2f, cap = StrokeCap.Round)
        if (glow.isNotEmpty()) drawPoints(glow, PointMode.Points, t.color.copy(alpha = 0.22f), strokeWidth = r * 4.4f, cap = StrokeCap.Round)
        for (i in 0 until 4) {
            if (lit[i].isNotEmpty()) drawPoints(lit[i], PointMode.Points, t.color.copy(alpha = (i + 1) / 4f), strokeWidth = r * 2.1f, cap = StrokeCap.Round)
        }
        if (hot.isNotEmpty()) drawPoints(hot, PointMode.Points, Vega.Red, strokeWidth = r * 2.1f, cap = StrokeCap.Round)
        // «Ahora»: un filo de luz al borde derecho.
        drawLine(t.color.copy(alpha = 0.45f), Offset(size.width, 0f), Offset(size.width, size.height), 1.dp.toPx())
    }
}

/** Una línea debajo de las pistas: lo que suena, el juego abierto o lo siguiente que avisará. */
@Composable
private fun ContextLine(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val media = ui.media
    val game = ui.game
    when {
        media != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Equalizer(media.playing, m.mediaAccent, Modifier.size(width = 13.dp, height = 9.dp))
            Spacer(Modifier.width(6.dp))
            Text(media.title, maxLines = 1, style = Vega.body.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f, fill = false))
            Text("  " + media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp), modifier = Modifier.weight(1f, fill = false))
        }
        game != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(m.gameAccent, 6.dp)
            Spacer(Modifier.width(6.dp))
            Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 12.sp, lineHeight = 15.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            Text(sessionClock(liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)), style = doto(14.sp, Vega.Ink))
        }
        else -> {
            val next = ui.telemetry?.upcoming?.firstOrNull()
            val local = timerLeft(m)
            when {
                next != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(countdown(next.dueIn - x.elapsed), style = doto(14.sp, Vega.Amber))
                    Spacer(Modifier.width(7.dp))
                    Text(if (next.kind == "timer") "temporizador" else next.text, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink))
                    val more = (ui.telemetry?.upcoming?.size ?: 1) - 1
                    if (more > 0) Text("  +$more", maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink3))
                }
                local != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(countdownText(local), style = doto(14.sp, Vega.Amber))
                    Spacer(Modifier.width(7.dp))
                    Text("temporizador", maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink))
                }
                else -> Text("Sin recordatorios pendientes", maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink3))
            }
        }
    }
}
