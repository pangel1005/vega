package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Segundos de canción que avanza (o retrocede) una vuelta entera del disco al arrastrarlo. */
const val DISC_SECONDS_PER_TURN = 30.0

/** Velocidad de giro de la portada, en grados por segundo (una vuelta cada ~9 s: pausado, como un disco que flota). */
const val DISC_DEGREES_PER_SECOND = 40f

/** Tiempo para dar el segundo toque de un doble toque. Un toque suelto espera esto antes de pausar/reanudar. */
const val DISC_DOUBLE_TAP_MS = 260L

/** Un toque más largo que esto (sin girar) ya no cuenta como toque. */
private const val TAP_MAX_MS = 450L

/** Lo mínimo que hay que girar el dedo para que cuente como arrastre (y no como un toque), en grados. */
const val DISC_DRAG_SLOP = 7f

/** Diferencia más corta entre dos ángulos en grados, con signo (positivo = en el sentido de las agujas del reloj). */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Posición de la canción tras girar el disco `turnedDegrees` desde `start`: adelante al girar hacia la derecha. */
fun scrubTarget(start: Double, turnedDegrees: Float, duration: Double, secondsPerTurn: Double = DISC_SECONDS_PER_TURN): Double {
    val target = start + turnedDegrees / 360.0 * secondsPerTurn
    return if (duration > 0) target.coerceIn(0.0, duration) else target.coerceAtLeast(0.0)
}

/** La velocidad de giro se acerca a la que toca (parada o la de siempre) como un plato con inercia. `tau` = segundos que tarda en cambiar. */
fun spinStep(omega: Float, target: Float, dtSeconds: Float, tau: Float = 0.55f): Float =
    target + (omega - target) * exp(-dtSeconds / tau)

/** «+0:12» o «−0:05»: lo que has adelantado o retrasado la canción. */
fun scrubLabel(deltaSeconds: Double): String {
    val s = abs(deltaSeconds).roundToInt()
    return (if (deltaSeconds < 0) "−" else "+") + "%d:%02d".format(s / 60, s % 60)
}

/** Cuánto se ensancha el brillo y se agranda un poco la portada con el volumen (0 a 1): un latido suave. */
fun discPulse(level: Float): Float = level.coerceIn(0f, 1f)

/** La carátula recortada en círculo; se hace una vez, no en cada cuadro. */
private fun circularArt(art: ImageBitmap, size: Int): ImageBitmap {
    val source = art.asAndroidBitmap()
    val side = min(source.width, source.height)
    val square = android.graphics.Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(square, null, android.graphics.Rect(0, 0, size, size), paint)
    return out.asImageBitmap()
}

/** La carátula con las esquinas redondeadas (portada plana); se hace una vez, no en cada cuadro. */
private fun roundedArt(art: ImageBitmap, size: Int, radiusFraction: Float): ImageBitmap {
    val source = art.asAndroidBitmap()
    val side = min(source.width, source.height)
    val square = android.graphics.Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
    val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    val r = size * radiusFraction
    canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, paint)
    paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SRC_IN)
    canvas.drawBitmap(square, null, android.graphics.Rect(0, 0, size, size), paint)
    return out.asImageBitmap()
}

private fun angleOf(p: Offset, center: Offset): Float = (atan2(p.y - center.y, p.x - center.x) * 180f / PI.toFloat())

private fun lightenColor(c: Color, amount: Float) = Color(
    c.red + (1f - c.red) * amount, c.green + (1f - c.green) * amount, c.blue + (1f - c.blue) * amount, c.alpha,
)

// Posiciones relativas al lado del cuadrado donde se dibuja todo.
private const val DISC_R = 0.30f // radio de la portada redonda
private const val FLAT_SIDE = 0.64f // lado de la portada plana

/**
 * La portada de la canción hecha disco: la carátula, redonda, girando despacio sobre un brillo que late con la
 * música y con un anillo de barras de LED alrededor (el espectro real de Spotify). Como el «Familiar» de escritorio,
 * pero con el ritmo de verdad.
 *
 * Es interactivo:
 * - Con el dedo sobre el disco se detiene y gira con la mano: hacia la derecha adelanta la canción y hacia la
 *   izquierda la retrasa (una vuelta = 30 s). Mientras se arrastra se ve cuánto, y al soltar se salta (`onSeek`).
 * - Un toque corto pausa o reanuda (`onToggle`).
 * - Un doble toque cambia entre el disco y la portada plana, cuadrada y quieta (`onDoubleTap`).
 */
@Composable
fun AlbumDisc(
    art: ImageBitmap?,
    accent: Color,
    playing: Boolean,
    position: Double,
    duration: Double,
    onScrub: (Double?) -> Unit,
    onSeek: (Double) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    flat: Boolean = false,
    onDoubleTap: () -> Unit = {},
    level: () -> Float = { 0f },
    spectrum: () -> FloatArray = { FloatArray(0) },
    scrubStyle: TextStyle? = null,
) {
    val isFlat by rememberUpdatedState(flat)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val isPlaying by rememberUpdatedState(playing)
    val livePosition by rememberUpdatedState(position)
    val liveDuration by rememberUpdatedState(duration)
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val toggle by rememberUpdatedState(onToggle)
    val readLevel by rememberUpdatedState(level)
    val readSpectrum by rememberUpdatedState(spectrum)

    var angle by remember { mutableFloatStateOf(0f) }
    var omega by remember { mutableFloatStateOf(0f) }
    var pulse by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    var frame by remember { mutableLongStateOf(0L) }
    var delta by remember { mutableStateOf<Double?>(null) } // lo adelantado o retrasado mientras se arrastra
    val bars = remember { SpectrumState() }
    val disc = remember(art) { art?.let { circularArt(it, 480) } }
    val square = remember(art) { art?.let { roundedArt(it, 560, 0.06f) } }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        var pending = 0f
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            if (!held) {
                omega = spinStep(omega, if (isPlaying) DISC_DEGREES_PER_SECOND else 0f, dt)
                angle = (angle + omega * dt) % 360f
            }
            // El espectro se actualiza cada 40 ms (como cuando llega del servidor), no en cada cuadro.
            pending += dt
            while (pending >= 0.04f) {
                pending -= 0.04f
                bars.push(if (isPlaying) readSpectrum() else FloatArray(0))
            }
            val target = if (isPlaying) discPulse(readLevel()) else 0f
            pulse += (target - pulse) * (1f - exp(-dt / 0.09f)) // el latido sube y baja con suavidad
            frame = now
        }
    }

    Box(
        modifier.semantics {
            contentDescription = "Portada: toca para pausar, doble toque para cambiar entre disco y portada plana, y en el disco gíralo para adelantar o retroceder"
        },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val side = min(size.width, size.height).toFloat()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = side * DISC_R
                    val onDisc = (down.position - center).getDistance() <= radius * 1.04f
                    // En la portada plana vale tocar en cualquier parte; con el disco, solo sobre él.
                    if (!isFlat && !onDisc) return@awaitEachGesture
                    var previous = angleOf(down.position, center)
                    var turned = 0f
                    var dragging = false
                    val start = livePosition
                    val pressedAt = down.uptimeMillis
                    var lastTime = pressedAt
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        val now = angleOf(change.position, center)
                        val step = angleDelta(previous, now)
                        previous = now
                        turned += step
                        if (!dragging && !isFlat && abs(turned) > DISC_DRAG_SLOP) {
                            dragging = true
                            held = true
                        }
                        if (dragging) {
                            angle = (angle + step) % 360f
                            val dt = ((change.uptimeMillis - lastTime) / 1000f).coerceAtLeast(0.008f)
                            omega = (omega + (step / dt - omega) * 0.5f).coerceIn(-1500f, 1500f)
                            val target = scrubTarget(start, turned, liveDuration)
                            delta = target - start
                            scrub(target)
                            change.consume()
                        }
                        lastTime = change.uptimeMillis
                    }
                    if (dragging) {
                        val target = scrubTarget(start, turned, liveDuration)
                        held = false
                        delta = null
                        scrub(null)
                        seek(target)
                    } else if (lastTime - pressedAt < TAP_MAX_MS) {
                        // Un toque corto espera un instante por si llega un segundo: doble toque cambia entre el
                        // disco y la portada plana; un toque suelto pausa o reanuda.
                        val second = withTimeoutOrNull(DISC_DOUBLE_TAP_MS) { awaitFirstDown(requireUnconsumed = false) }
                        if (second != null) {
                            waitForUpOrCancellation()
                            doubleTap()
                        } else {
                            toggle()
                        }
                    }
                }
            },
        ) {
            frame // se repinta en cada cuadro
            if (flat) drawFlat(accent, square, pulse, bars) else drawDisc(angle, accent, disc, pulse, bars)
        }
        delta?.let {
            Box(Modifier.padding(bottom = 6.dp)) {
                Text(scrubLabel(it), style = scrubStyle ?: TextStyle(fontWeight = FontWeight.Black, fontSize = 26.sp, color = accent))
            }
        }
    }
}

/** El brillo de color que late detrás de la portada. */
private fun DrawScope.drawHalo(center: Offset, radius: Float, accent: Color, pulse: Float) {
    val r = radius * (1.55f + 0.75f * pulse)
    drawCircle(
        Brush.radialGradient(listOf(accent.copy(alpha = 0.20f + 0.40f * pulse), accent.copy(alpha = 0.06f + 0.06f * pulse), Color.Transparent), center, r),
        r, center,
    )
}

/**
 * El disco: la carátula redonda girando, con el anillo de barras (el espectro) alrededor. Las 24 bandas se repiten
 * en espejo a los dos lados (graves abajo, agudos arriba), como un ecualizador radial.
 */
private fun DrawScope.drawDisc(angle: Float, accent: Color, art: ImageBitmap?, pulse: Float, bars: SpectrumState) {
    val s = min(size.width, size.height)
    val c = Offset(size.width / 2f, size.height / 2f)
    val r = s * DISC_R * (1f + 0.018f * pulse)
    val dp = s / 300f
    drawHalo(c, r, accent, pulse)

    // Anillo de barras alrededor.
    val n = bars.bands
    val count = n * 2
    val gap = r * 0.07f
    for (k in 0 until count) {
        val band = if (k < n) k else count - 1 - k // espejo: graves abajo en las dos mitades
        val a = (PI.toFloat() / 2f) + (k + 0.5f) * (2f * PI.toFloat() / count) // empieza abajo y rodea
        val value = bars.level[band]
        val peak = bars.peak[band]
        val inner = r + gap
        val length = r * (0.05f + 0.34f * value)
        val dir = Offset(cos(a), sin(a))
        val tint = if (band > n * 0.6f) lightenColor(accent, 0.5f) else accent
        drawLine(tint.copy(alpha = 0.35f + 0.65f * value), c + dir * inner, c + dir * (inner + length), 3.2f * dp, StrokeCap.Round)
        if (peak > value + 0.04f) { // el pico flota un poco más arriba
            val p = inner + r * (0.05f + 0.34f * peak) + 3f * dp
            drawLine(lightenColor(tint, 0.5f).copy(alpha = 0.9f), c + dir * p, c + dir * (p + 2.4f * dp), 3.2f * dp, StrokeCap.Round)
        }
    }

    // Sombra y portada giratoria.
    drawCircle(Brush.radialGradient(listOf(Color(0xB0000000), Color.Transparent), c + Offset(0f, r * 0.08f), r * 1.18f), r * 1.18f, c + Offset(0f, r * 0.08f))
    rotate(angle, c) {
        if (art != null) {
            drawImage(art, dstOffset = IntOffset((c.x - r).roundToInt(), (c.y - r).roundToInt()), dstSize = IntSize((r * 2).roundToInt(), (r * 2).roundToInt()))
        } else {
            drawCircle(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.45f)), c - Offset(r, r), c + Offset(r, r)), r, c)
        }
        // Una marca en el borde que gira con el disco: así se nota el giro aunque la portada sea lisa.
        drawArc(Color.White.copy(alpha = 0.22f), -78f, 22f, false, Offset(c.x - r * 0.965f, c.y - r * 0.965f), Size(r * 1.93f, r * 1.93f), style = Stroke(2.2f * dp, cap = StrokeCap.Round))
    }
    // Lo que no gira (la luz y el borde): el reflejo, el canto y el agujero central.
    drawCircle(
        Brush.sweepGradient(
            0f to Color.Transparent, 0.07f to Color.White.copy(alpha = 0.13f), 0.16f to Color.Transparent,
            0.5f to Color.Transparent, 0.57f to Color.White.copy(alpha = 0.10f), 0.66f to Color.Transparent, 1f to Color.Transparent,
            center = c,
        ),
        r, c,
    )
    drawCircle(Color.White.copy(alpha = 0.20f), r, c, style = Stroke(1.4f * dp))
    drawCircle(Color.Black.copy(alpha = 0.30f), r * 0.985f, c, style = Stroke(1f * dp))
    drawCircle(Color(0xFF0A0A0C).copy(alpha = 0.88f), r * 0.05f, c)
    drawCircle(Color.White.copy(alpha = 0.38f), r * 0.05f, c, style = Stroke(1.1f * dp))
}

/** La portada plana: cuadrada, quieta, con sombra, brillo que late y unas barras de espectro en el suelo. */
private fun DrawScope.drawFlat(accent: Color, art: ImageBitmap?, pulse: Float, bars: SpectrumState) {
    val s = min(size.width, size.height)
    val c = Offset(size.width / 2f, size.height * 0.45f)
    val dp = s / 300f
    val side = s * FLAT_SIDE * (1f + 0.02f * pulse)
    val left = c.x - side / 2
    val top = c.y - side / 2
    val radius = CornerRadius(side * 0.06f)
    drawHalo(c, side * 0.55f, accent, pulse)
    drawRoundRect(Color.Black.copy(alpha = 0.55f), Offset(left + 4 * dp, top + 10 * dp), Size(side, side), radius)
    if (art != null) {
        drawImage(art, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(side.roundToInt(), side.roundToInt()))
    } else {
        drawRoundRect(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.45f)), Offset(left, top), Offset(left + side, top + side)), Offset(left, top), Size(side, side), radius)
    }
    drawRoundRect(
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.18f)), Offset(left, top), Offset(left + side, top + side)),
        Offset(left, top), Size(side, side), radius,
    )
    drawRoundRect(Color.White.copy(alpha = 0.16f), Offset(left, top), Size(side, side), radius, style = Stroke(1.2f * dp))
    // Barras de espectro debajo, a lo ancho de la portada.
    val n = bars.bands
    val barPitch = side / n
    val base = top + side + 12 * dp
    for (i in 0 until n) {
        val x = left + (i + 0.5f) * barPitch
        val h = 3 * dp + side * 0.10f * bars.level[i]
        val tint = if (i > n * 0.6f) lightenColor(accent, 0.5f) else accent
        drawLine(tint.copy(alpha = 0.35f + 0.65f * bars.level[i]), Offset(x, base), Offset(x, base + h), barPitch * 0.55f, StrokeCap.Round)
    }
}
