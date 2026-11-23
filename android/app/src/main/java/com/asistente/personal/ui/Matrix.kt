package com.asistente.personal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.asistente.personal.ui.led.FaceExtras
import com.asistente.personal.ui.led.Frame
import com.asistente.personal.ui.led.LedFaceAnimator
import com.asistente.personal.ui.led.LedFont
import com.asistente.personal.ui.led.LedOff
import com.asistente.personal.ui.led.Mood
import com.asistente.personal.ui.led.countdownText
import com.asistente.personal.ui.led.framePacingMs
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/*
 * Vega · Matriz. Sin tarjetas: la pantalla entera es un único panel de LED (160 columnas) y todo se escribe con sus puntos.
 *
 *   arriba izq.  — qué hace Vega (o qué suena / qué juegas)        arriba der. — la hora y la fecha
 *   izquierda    — CPU, GPU, VRAM y batería: nombre, cifra y una barra de puntos
 *   centro       — la cara, en el doble de tamaño (cada punto suyo son 2 × 2 LED)
 *   derecha      — lo que importa ahora: el temporizador, la música (ecualizador) o la carga del juego
 *   debajo       — lo que dice Vega, en letra normal; y las teclas
 */

private const val GC = 160 // columnas del panel
private const val FACE_X = 30
private const val FACE_Y = 2

// Colores (índices de la paleta).
private const val LIGHT = 0
private const val DIM = 1
private const val AMBER = 2
private const val MINT = 3
private const val RED = 4
private const val CORAL = 5
private const val MUSIC = 6
private const val GAME = 7
private const val FACE = 8
private const val NCOL = 9

private class Dots(val cols: Int, val rows: Int) {
    val v = FloatArray(cols * rows)
    val c = ByteArray(cols * rows)

    fun clear() = v.fill(0f)

    fun set(x: Int, y: Int, a: Float, ci: Int) {
        if (x < 0 || y < 0 || x >= cols || y >= rows) return
        val i = y * cols + x
        if (a > v[i]) {
            v[i] = a
            c[i] = ci.toByte()
        }
    }

    fun rect(x: Int, y: Int, w: Int, h: Int, a: Float, ci: Int) {
        for (i in 0 until w) for (j in 0 until h) set(x + i, y + j, a, ci)
    }

    /** Escribe `text` (mayúsculas sin tildes, letras de 3 × 5 puntos; `scale` 2 = 6 × 10) desde (x, y). */
    fun text(text: String, x: Int, y: Int, ci: Int, a: Float = 1f, scale: Int = 1) {
        val n = LedFont.normalize(text)
        if (n.isEmpty()) return
        val w = n.length * 4 * scale
        val tmp = Frame(w, 5 * scale)
        LedFont.draw(tmp, n, 0, 0, 1f, scale)
        for (j in 0 until tmp.rows) for (i in 0 until tmp.cols) {
            val a0 = tmp.v[j * tmp.cols + i]
            if (a0 > 0.05f) set(x + i, y + j, a * a0, ci)
        }
    }

    /** Lo mismo, pegado al borde derecho `xEnd` (la columna siguiente a la última). */
    fun textRight(text: String, xEnd: Int, y: Int, ci: Int, a: Float = 1f, scale: Int = 1) {
        val n = LedFont.normalize(text)
        text(n, xEnd - (n.length * 4 * scale - scale), y, ci, a, scale)
    }

    /** Una barra de `w` puntos que se llena hasta `fraction`; las que sobran quedan tenues. */
    fun bar(x: Int, y: Int, w: Int, fraction: Float?, ci: Int, hot: Boolean) {
        val n = ((fraction ?: 0f).coerceIn(0f, 1f) * w + 0.5f).toInt()
        for (i in 0 until w) {
            val on = i < n
            rect(x + i, y, 1, 2, if (on) 1f else 0.16f, if (on) (if (hot) RED else ci) else DIM)
        }
    }
}

@Composable
internal fun ColumnScope.MatrixLayout(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val animator = remember { LedFaceAnimator() }
    val tick = remember { mutableIntStateOf(0) }
    val latest = rememberUpdatedState(x)
    val eq = remember { FloatArray(5) }
    val level = remember { FloatArray(1) }
    val clock = remember { floatArrayOf(0f) }

    val notice = ui.notice?.takeIf { it.reminder }
    // Lo que avanza solo (cuenta atrás, sesión de juego, posición de la canción) se calcula aquí y se pasa ya hecho al dibujo.
    val live = remember { Live() }
    live.timer = timerLeft(m)
    live.session = ui.game?.let { liveSession(it, ui.gameAt, m.clockMs, tickMs = 15_000) }
    live.position = ui.media?.let { livePosition(it, ui.mediaAt, m.clockMs) }
    animator.countdown = live.timer?.let(::countdownText)
    animator.extras = FaceExtras(
        hot = (ui.telemetry?.gpuTemp ?: 0) >= 78,
        low = lowBattery(m.battery, m.charging),
        game = ui.game != null,
    )
    LaunchedEffect(m.pulse) { if (m.pulse > 0) level[0] = max(level[0], m.pulseAmount) }
    LaunchedEffect(notice?.seq) { if (notice != null) animator.showText(notice.text) }
    LaunchedEffect(x.glance?.seq) { x.glance?.let { animator.glance(it.x, it.y) } }
    LaunchedEffect(Unit) {
        var last = 0L
        var eqAt = 0L
        while (true) {
            val cur = latest.value
            // En reposo, 15 cuadros por segundo bastan para la cara; el panel entero es mucho más grande que la cara sola.
            val pause = framePacingMs(cur.mood, false, animator.showingText).let { if (cur.mood == Mood.IDLE || cur.mood == Mood.SLEEP) max(it, 66L) else it }
            if (pause > 0) kotlinx.coroutines.delay(pause)
            withFrameMillis { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
                last = now
                level[0] *= exp(-dt * 5f)
                clock[0] = now / 1000f
                animator.step(dt, clock[0], cur.mood, level[0])
                if (now - eqAt > 110) {
                    eqAt = now
                    for (i in 0 until eq.size - 1) eq[i] = eq[i + 1]
                    eq[eq.size - 1] = if (cur.m.ui.media?.playing == true) cur.m.musicLevel().coerceIn(0f, 1f) else 0f
                }
                tick.intValue++
            }
        }
    }

    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val density = LocalDensity.current
        val pitchPx = constraints.maxWidth / GC.toFloat()
        val pitch = with(density) { pitchPx.toDp() }
        val rows = (constraints.maxHeight / pitchPx).toInt()

        Box(
            Modifier.fillMaxSize().drawWithCache {
                val dots = Dots(GC, rows)
                val buckets = Array(NCOL * 3) { ArrayList<Offset>(256) }
                val offPts = ArrayList<Offset>(GC * rows)
                for (y in 0 until rows) for (xx in 0 until GC) offPts.add(Offset((xx + 0.5f) * pitchPx, (y + 0.5f) * pitchPx))
                val base = ImageBitmap(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)).also { bmp ->
                    CanvasDrawScope().draw(this, layoutDirection, BitmapCanvas(bmp), Size(bmp.width.toFloat(), bmp.height.toFloat())) {
                        drawPoints(offPts, PointMode.Points, LedOff, strokeWidth = pitchPx * 0.62f, cap = StrokeCap.Round)
                    }
                }
                onDrawBehind {
                    drawImage(base)
                    tick.intValue // suscribe el dibujo: se repinta con cada cuadro nuevo
                    val cur = latest.value
                    draw(dots, cur, animator, clock[0], eq, live)
                    val pal = palette(cur)
                    buckets.forEach { it.clear() }
                    for (y in 0 until dots.rows) for (xx in 0 until GC) {
                        val i = y * GC + xx
                        val a = dots.v[i]
                        if (a < 0.05f) continue
                        val lvl = if (a < 0.4f) 0 else if (a < 0.8f) 1 else 2
                        buckets[dots.c[i] * 3 + lvl].add(Offset((xx + 0.5f) * pitchPx, (y + 0.5f) * pitchPx))
                    }
                    for (ci in 0 until NCOL) {
                        val glow = buckets[ci * 3 + 2]
                        if (glow.isNotEmpty()) drawPoints(glow, PointMode.Points, pal[ci].copy(alpha = 0.2f), strokeWidth = pitchPx * 1.9f, cap = StrokeCap.Round)
                        for (lvl in 0..2) {
                            val l = buckets[ci * 3 + lvl]
                            if (l.isNotEmpty()) drawPoints(l, PointMode.Points, pal[ci].copy(alpha = floatArrayOf(0.4f, 0.7f, 1f)[lvl]), strokeWidth = pitchPx * 0.68f, cap = StrokeCap.Round)
                        }
                    }
                }
            },
        )

        // Tocar la cara llama su atención; tocar la respuesta abre la conversación.
        Box(
            Modifier.offset(pitch * FACE_X, pitch * FACE_Y).size(pitch * 96, pitch * 40)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { x.a.attention() },
        )
        Box(Modifier.offset(pitch * 2, pitch * 45).size(pitch * 156, pitch * 10)) {
            Subtitle(m, Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onChat), compact = true)
        }
        val micOn = m.listening && m.micOn
        Row(Modifier.offset(pitch * 129, pitch * 37), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RoundKey("mic", if (micOn) Vega.Coral else Vega.Ink2, x.a.toggleMic, filled = micOn, size = 28.dp)
            RoundKey("sleep", Vega.Ink2, x.a.sleep, size = 28.dp)
        }
    }
    KeyRow(
        favorites = ui.actions.filter { it.quick }, pending = ui.pending, secondsLeft = x.secondsLeft, enabled = x.canAct,
        onPress = x.press, onMore = x.onMore, modifier = Modifier.fillMaxWidth().height(52.dp).cached(), icons = m.icons, media = ui.media,
        compact = true,
    )
}

private fun palette(x: LayoutArgs): Array<Color> = arrayOf(
    Vega.Light, Vega.Ink3, Vega.Amber, Vega.Mint, Vega.Red, Vega.Coral, x.m.mediaAccent, x.m.gameAccent,
    if (x.m.ui.notice?.reminder == true) Vega.Amber else x.color,
)

private fun hm(date: String): String {
    val p = date.split(" ")
    return if (p.size >= 3) "${p[0].take(3)} ${p[1]} ${p[2].take(3)}" else date
}

private class Live(var timer: Long? = null, var session: Long? = null, var position: Double? = null)

private fun draw(d: Dots, x: LayoutArgs, animator: LedFaceAnimator, t: Float, eq: FloatArray, live: Live) {
    d.clear()
    val m = x.m
    val ui = m.ui
    val tel = ui.telemetry
    val media = ui.media
    val game = ui.game

    // ── la cara: cada punto suyo, un bloque de 2 × 2 LED (con la esquina de abajo a la derecha más tenue) ──
    val sh = animator.shown
    for (fy in 0 until 20) for (fx in 0 until 48) {
        val a = sh[fy * 48 + fx]
        if (a < 0.04f) continue
        val gx = FACE_X + fx * 2
        val gy = FACE_Y + fy * 2
        d.set(gx, gy, a, FACE)
        d.set(gx + 1, gy + 1, a, FACE)
        if (a > 0.9f) {
            d.set(gx + 1, gy, 0.3f, FACE)
            d.set(gx, gy + 1, 0.3f, FACE)
        }
    }
    if (x.confirmFraction != null) d.bar(FACE_X, 43, 96, x.confirmFraction, RED, hot = false)

    // ── arriba a la izquierda: qué pasa ──
    val status = when {
        x.mood == Mood.IDLE && media != null -> media.title + " - " + media.artist
        x.mood == Mood.IDLE && game != null -> game.name
        else -> moodLabel(x.mood, ui.assistant)
    }
    val statusColor = when {
        x.mood == Mood.IDLE && media != null -> MUSIC
        x.mood == Mood.IDLE && game != null -> GAME
        else -> FACE
    }
    d.rect(2, 2, 3, 3, 1f, statusColor)
    d.text(status.take(23), 7, 2, statusColor)

    // ── a la izquierda: el PC y la batería ──
    val vram = if (tel?.vramUsed != null && tel.vramTotal != null && tel.vramTotal > 0) (tel.vramUsed / tel.vramTotal).toFloat() else null
    val bat = if (m.battery >= 0) m.battery / 100f else null
    class Stat(val name: String, val value: String, val f: Float?, val color: Int, val hot: Boolean)
    val stats = listOf(
        Stat("CPU", tel?.cpu?.let { "$it%" } ?: "-", tel?.cpu?.div(100f), LIGHT, (tel?.cpu ?: 0) >= 90),
        Stat("GPU", tel?.gpuTemp?.let { "${it}C" } ?: "-", tel?.gpuTemp?.let { (it - 30) / 60f }, AMBER, (tel?.gpuTemp ?: 0) >= 80),
        Stat("VID", tel?.vramUsed?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-", vram, MINT, (vram ?: 0f) >= 0.9f),
        Stat("BAT", bat?.let { "${m.battery}" } ?: "-", bat, if (m.charging) MINT else LIGHT, lowBattery(m.battery, m.charging)),
    )
    stats.forEachIndexed { i, s ->
        val y = 9 + i * 9
        d.text(s.name, 2, y, DIM)
        d.textRight(s.value, 28, y, if (s.hot) RED else s.color)
        d.bar(2, y + 6, 26, s.f, s.color, s.hot)
    }

    // ── arriba a la derecha: la hora y la fecha ──
    d.textRight(m.clock, 158, 2, LIGHT, scale = 2)
    d.textRight(hm(m.date), 158, 13, DIM)

    // ── a la derecha: lo que importa ahora ──
    val rx = 158
    when {
        game != null -> {
            d.textRight(tel?.gpuUtil?.let { "$it%" } ?: "-", rx, 19, if ((tel?.gpuUtil ?: 0) >= 90) RED else GAME, scale = 2)
            d.textRight(sessionClock(live.session ?: 0L), rx, 30, LIGHT)
        }
        media != null -> {
            // Un ecualizador de cinco columnas con el volumen real de los últimos instantes.
            for (i in 0 until 5) {
                val h = (eq[i] * 10).toInt().coerceIn(if (media.playing) 1 else 0, 10)
                for (k in 0 until h) d.rect(128 + i * 3, 29 - k, 2, 1, if (k >= 8) 1f else 0.85f, MUSIC)
            }
            d.textRight(clockTime(live.position ?: 0.0), rx, 19, LIGHT)
            if (media.duration > 0) d.textRight(clockTime(media.duration), rx, 25, DIM)
        }
        else -> {
            val next = tel?.upcoming?.firstOrNull()
            val local = live.timer
            val txt = if (next != null) countdown(next.dueIn - x.elapsed) else local?.let(::countdownText)
            if (txt != null) {
                d.textRight(txt.take(5), rx, 19, AMBER, scale = 2)
                d.textRight(if (next == null || next.kind == "timer") "TEMPORIZ" else "AVISO", rx, 30, DIM)
            } else {
                d.textRight("SIN AVISOS", rx, 19, DIM)
            }
        }
    }
    // El filo de «ahora»: unas marcas en el borde para que se lea como un instrumento.
    for (k in 0 until 4) d.set(0, 2 + k * 10, 0.5f, DIM)
    if (abs(t) < 0f) d.set(0, 0, 0f, DIM) // (solo para usar `t` si más adelante se anima algo aquí)
}
