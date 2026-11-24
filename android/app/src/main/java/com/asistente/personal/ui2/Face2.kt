package com.asistente.personal.ui2

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.asistente.personal.ui.led.Glance
import com.asistente.personal.ui.led.Mood
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/*
 * La cara de Vega, dibujada con curvas (no con puntos): dos ojos de cápsula, una boca y poco más. Cada ánimo es un conjunto de
 * medidas hacia el que la cara se mueve con suavidad, así que pasa de uno a otro sin saltos: se agranda al escuchar, entorna un
 * ojo al pensar, abre la boca con la voz, se hace dos arcos al estar contenta. Los datos también se ven en ella: suda si la
 * gráfica se calienta, baja los párpados con poca batería y lleva un visor mientras juegas.
 * Las medidas van en «unidades de cara»: la cara mide 1,0 de ancho y 0,62 de alto.
 */

internal class FaceState {
    var ew = 0.17f
    var eh = 0.29f
    var gap = 0.23f
    var ey = 0.27f
    var tiltL = 0f
    var tiltR = 0f
    var ehL = 1f
    var ehR = 1f
    var smile = 0.35f
    var open = 0f
    var mw = 0.14f
    var arch = 0f
    var cross = 0f
    var listen = 0f
    var think = 0f
    var sleep = 0f
    var visor = 0f
    var sweat = 0f
    var lookX = 0f
    var lookY = 0f
    var blink = 0f
    var level = 0f

    private var nextBlink = 2f
    private var blinkT = -1f
    private var goalX = 0f
    private var goalY = 0f
    private var lookTimer = 1f

    fun glance(x: Float, y: Float) {
        goalX = x.coerceIn(-1f, 1f)
        goalY = y.coerceIn(-1f, 1f)
        lookTimer = 1.3f
    }

    /** Hay un cambio grande (un gesto que se está haciendo): conviene dibujar en cada cuadro de la pantalla. */
    var urgent = false

    /**
     * Avanza `dt` segundos. Devuelve true si la cara se ve distinta de antes (y hay que redibujarla): en reposo, con los ojos
     * quietos, no cambia nada y no se dibuja nada hasta el próximo parpadeo o mirada.
     */
    fun step(dt: Float, t: Float, mood: Mood, hot: Boolean, low: Boolean, game: Boolean): Boolean {
        val oldLookX = lookX
        val oldLookY = lookY
        val oldBlink = blink
        var delta = 0f
        var tew = 0.17f; var teh = 0.29f; var tgap = 0.23f; var tey = 0.27f
        var ttL = 0f; var ttR = 0f; var tehL = 1f; var tehR = 1f
        var tsm = 0.35f; var top = 0f; var tmw = 0.14f; var tar = 0f; var tcr = 0f
        var tl = 0f; var tth = 0f; var tsl = 0f; var tvi = 0f; var tsw = 0f
        when (mood) {
            Mood.IDLE -> {}
            Mood.LISTEN -> { tew = 0.19f; teh = 0.35f; tey = 0.25f; tsm = 0f; top = 0.2f; tmw = 0.05f; tl = 1f }
            Mood.THINK -> { tehL = 0.45f; tey = 0.26f; tsm = -0.05f; tmw = 0.08f; ttL = -10f; ttR = 6f; tth = 1f }
            Mood.TALK -> { tsm = 0.25f; tmw = 0.16f; top = (level * 0.9f + 0.1f * (0.5f + 0.5f * sin(t * 12f))).coerceIn(0f, 1f) }
            Mood.HAPPY -> { tar = 1f; tsm = 0.9f; tmw = 0.2f; teh = 0.18f }
            Mood.SLEEP -> { teh = 0.025f; tsm = 0f; tmw = 0.05f; tsl = 1f; tey = 0.3f }
            Mood.ERROR -> { tcr = 1f; tsm = -0.7f; tmw = 0.16f }
            Mood.ALERT -> { tew = 0.19f; teh = 0.34f; top = 0.18f; tmw = 0.05f; tsm = 0f }
            Mood.OFFLINE -> { teh = 0.04f; tsm = 0f; tmw = 0.1f }
        }
        if (mood == Mood.IDLE || mood == Mood.TALK) {
            if (low) { teh *= 0.6f; tey += 0.03f; tsm = -0.25f; tmw = 0.12f }
            if (hot) { teh *= 0.85f; tsw = 1f }
            if (game) { teh *= 0.45f; tvi = 1f }
        }
        val k = min(1f, dt * 9f)
        fun go(cur: Float, tgt: Float): Float {
            val next = cur + (tgt - cur) * k
            delta += abs(next - cur)
            return next
        }
        ew = go(ew, tew); eh = go(eh, teh); gap = go(gap, tgap); ey = go(ey, tey)
        tiltL = go(tiltL, ttL); tiltR = go(tiltR, ttR); ehL = go(ehL, tehL); ehR = go(ehR, tehR)
        smile = go(smile, tsm); open = go(open, top); mw = go(mw, tmw); arch = go(arch, tar); cross = go(cross, tcr)
        listen = go(listen, tl); think = go(think, tth); sleep = go(sleep, tsl); visor = go(visor, tvi); sweat = go(sweat, tsw)

        // Mirada: pasea la vista; al pensar mira arriba a un lado.
        lookTimer -= dt
        if (lookTimer <= 0f) {
            lookTimer = 0.8f + Random.nextFloat() * 2.6f
            val wander = mood == Mood.IDLE || mood == Mood.HAPPY || mood == Mood.TALK || mood == Mood.ALERT
            goalX = if (wander && Random.nextFloat() < 0.7f) Random.nextFloat() * 2f - 1f else 0f
            goalY = if (wander && Random.nextFloat() < 0.5f) Random.nextFloat() - 0.5f else 0f
        }
        if (mood == Mood.THINK) { goalX = 0.7f; goalY = -0.7f }
        lookX += (goalX - lookX) * min(1f, dt * 10f)
        lookY += (goalY - lookY) * min(1f, dt * 10f)

        // Parpadeo.
        nextBlink -= dt
        if (nextBlink <= 0f && mood != Mood.SLEEP && mood != Mood.OFFLINE && mood != Mood.ERROR) {
            nextBlink = 2.2f + Random.nextFloat() * 3.4f
            blinkT = 0f
        }
        blink = if (blinkT >= 0f) {
            blinkT += dt
            if (blinkT > 0.17f) { blinkT = -1f; 0f } else sin(PI.toFloat() * blinkT / 0.17f)
        } else 0f

        // La mirada va despacio y se ve bien a unos 30 cuadros por segundo; un cambio de forma (un gesto) pide cada cuadro.
        val lookDelta = abs(lookX - oldLookX) + abs(lookY - oldLookY)
        urgent = delta > 0.004f || lookDelta > 0.03f || mood == Mood.TALK
        delta += lookDelta
        // Hay cosas que se mueven solas con el tiempo (aros, puntos, zetas, gota, visor) y el parpadeo.
        val ambient = listen > 0.02f || think > 0.02f || sleep > 0.02f || sweat > 0.02f || visor > 0.02f || blink > 0f || oldBlink > 0f
        return delta > 0.0008f || ambient || mood == Mood.TALK
    }
}

private class FaceIn(val mood: Mood, val hot: Boolean, val low: Boolean, val game: Boolean)

@Composable
internal fun Face2(
    mood: Mood, pulse: Int, pulseAmount: Float, glance: Glance?, ink: Color, night: Boolean,
    hot: Boolean, low: Boolean, game: Boolean, modifier: Modifier = Modifier,
) {
    val st = remember { FaceState() }
    val tick = remember { mutableIntStateOf(0) }
    val input = rememberUpdatedState(FaceIn(mood, hot, low, game))
    LaunchedEffect(pulse) { if (pulse > 0) st.level = max(st.level, pulseAmount) }
    LaunchedEffect(glance?.seq) { glance?.let { st.glance(it.x, it.y) } }
    val clock = remember { floatArrayOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            var changed = false
            withFrameMillis { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1000f).coerceAtMost(0.1f)
                last = now
                clock[0] = now / 1000f
                st.level *= exp(-dt * 5f)
                val cur = input.value
                changed = st.step(dt, clock[0], cur.mood, cur.hot, cur.low, cur.game)
                // Si algo se mueve, se dibuja en cada cuadro de la pantalla: a 30 por segundo la cara se veía a tirones.
                if (changed) tick.intValue++
            }
            // Con la cara quieta no hace falta mirar en cada cuadro: se vuelve a mirar enseguida.
            if (!changed) delay(40)
        }
    }
    Canvas(modifier) {
        tick.intValue // se vuelve a dibujar con cada cuadro nuevo
        drawFace(st, clock[0], ink, night)
    }
}

internal fun DrawScope.drawFace(st: FaceState, t: Float, ink: Color, night: Boolean) {
    val s = min(size.width * 1.08f, size.height / 0.58f)
    val ox = (size.width - s) / 2f
    val oy = (size.height - s * 0.58f) / 2f
    fun px(v: Float) = ox + v * s
    fun py(v: Float) = oy + v * s
    val sweatBlue = Color(0xFF6CC2FF)

    // Al escuchar, unos aros se abren desde la cara.
    if (st.listen > 0.02f) {
        for (k in 0..2) {
            val ph = (t * 0.7f + k / 3f) % 1f
            drawCircle(ink.copy(alpha = (1f - ph) * 0.22f * st.listen), (0.14f + ph * 0.3f) * s, Offset(px(0.5f), py(0.29f)), style = Stroke(width = 0.012f * s))
        }
    }
    // El visor del juego: una banda sobre los ojos y una luz que va y viene.
    if (st.visor > 0.02f) {
        drawRoundRect(ink.copy(alpha = 0.14f * st.visor), Offset(px(0.2f), py(st.ey - 0.08f)), Size(0.6f * s, 0.16f * s), CornerRadius(0.08f * s))
        val vx = 0.24f + 0.52f * (sin(t * 1.8f) * 0.5f + 0.5f)
        drawRoundRect(ink.copy(alpha = 0.5f * st.visor), Offset(px(vx), py(st.ey - 0.075f)), Size(0.012f * s, 0.15f * s), CornerRadius(0.006f * s))
    }

    // Ojos.
    for (sg in intArrayOf(-1, 1)) {
        val hMul = if (sg < 0) st.ehL else st.ehR
        val tilt = if (sg < 0) st.tiltL else st.tiltR
        val cx = 0.5f + sg * st.gap / 2f + st.lookX * 0.028f
        val cy = st.ey + st.lookY * 0.02f
        val w = st.ew
        val h = st.eh * hMul * (1f - st.blink * 0.93f)
        val fill = (1f - st.arch) * (1f - st.cross)
        if (fill > 0.02f) {
            rotate(tilt, Offset(px(cx), py(cy))) {
                if (night) {
                    // De noche los ojos son luz: un halo ancho y suave alrededor.
                    drawRoundRect(ink.copy(alpha = 0.16f * fill), Offset(px(cx - w / 2 - 0.014f), py(cy - h / 2 - 0.014f)), Size((w + 0.028f) * s, (h + 0.028f) * s), CornerRadius(min(w, h) * s / 2f + 0.014f * s))
                }
                drawRoundRect(ink.copy(alpha = fill), Offset(px(cx - w / 2), py(cy - h / 2)), Size(w * s, h * s), CornerRadius(min(w, h) * s / 2f))
                if (h > 0.08f) {
                    drawCircle(Color.White.copy(alpha = 0.85f * fill), w * s * 0.15f, Offset(px(cx + w * 0.16f + st.lookX * 0.01f), py(cy - h * 0.27f)))
                }
            }
        }
        if (st.arch > 0.02f) {
            drawArc(
                ink.copy(alpha = st.arch), 200f, 140f, false, Offset(px(cx - w * 0.62f), py(cy - w * 0.1f)), Size(w * 1.24f * s, w * 1.1f * s),
                style = Stroke(width = w * s * 0.42f, cap = StrokeCap.Round),
            )
        }
        if (st.cross > 0.02f) {
            val l = w * 0.7f
            val sk = Stroke(width = w * s * 0.28f, cap = StrokeCap.Round)
            val a = ink.copy(alpha = st.cross)
            drawLine(a, Offset(px(cx - l / 2), py(cy - l / 2)), Offset(px(cx + l / 2), py(cy + l / 2)), sk.width, StrokeCap.Round)
            drawLine(a, Offset(px(cx + l / 2), py(cy - l / 2)), Offset(px(cx - l / 2), py(cy + l / 2)), sk.width, StrokeCap.Round)
        }
        if (st.arch > 0.3f) {
            drawCircle(Color(0xFFFF6B8A).copy(alpha = 0.32f * st.arch), 0.045f * s, Offset(px(0.5f + sg * 0.31f), py(0.42f)))
        }
    }

    // Boca: una sonrisa que se abre con la voz.
    val my = 0.52f + st.lookY * 0.01f
    val mx = 0.5f + st.lookX * 0.015f
    val openA = (st.open * 7f).coerceIn(0f, 1f)
    if (openA > 0.02f) {
        val mh = 0.02f + st.open * 0.13f
        val mwid = st.mw * (0.8f + 0.4f * st.open)
        drawRoundRect(ink.copy(alpha = openA), Offset(px(mx - mwid / 2), py(my - mh / 2)), Size(mwid * s, mh * s), CornerRadius(min(mwid, mh) * s / 2f))
    }
    if (openA < 0.98f) {
        val path = Path().apply {
            moveTo(px(mx - st.mw / 2), py(my))
            quadraticBezierTo(px(mx), py(my + st.smile * 0.16f), px(mx + st.mw / 2), py(my))
        }
        drawPath(path, ink.copy(alpha = 1f - openA), style = Stroke(width = 0.026f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Pensando: tres puntos que rebotan sobre la cabeza.
    if (st.think > 0.02f) {
        for (i in 0..2) {
            val ph = t * 2.4f - i * 0.4f
            val b = max(0f, sin(ph * PI.toFloat() / 1.6f))
            drawCircle(ink.copy(alpha = st.think * (0.35f + 0.65f * b)), 0.014f * s, Offset(px(0.8f + i * 0.055f), py(0.07f - 0.025f * b)))
        }
    }
    // Dormida: unas zetas que suben y se apagan.
    if (st.sleep > 0.02f) {
        for (k in 0..1) {
            val ph = (t * 0.35f + k * 0.5f) % 1f
            val x = px(0.8f + k * 0.07f)
            val y = py(0.2f - ph * 0.18f)
            val z = (0.04f + k * 0.02f) * s
            val path = Path().apply { moveTo(x, y); lineTo(x + z, y); lineTo(x, y + z); lineTo(x + z, y + z) }
            drawPath(path, ink.copy(alpha = (1f - ph) * st.sleep), style = Stroke(width = 0.012f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
    // Con la gráfica caliente: una gota de sudor resbala por el lado del ojo derecho.
    if (st.sweat > 0.02f) {
        val ph = (t * 0.4f) % 1f
        val x = px(0.5f + st.gap / 2f + st.ew / 2f + 0.04f)
        val y = py(0.1f + ph * 0.28f)
        val a = st.sweat * (1f - ph * 0.6f)
        drawCircle(sweatBlue.copy(alpha = a), 0.018f * s, Offset(x, y))
        val drop = Path().apply { moveTo(x, y - 0.045f * s); lineTo(x - 0.015f * s, y - 0.008f * s); lineTo(x + 0.015f * s, y - 0.008f * s); close() }
        drawPath(drop, sweatBlue.copy(alpha = a))
    }
}
