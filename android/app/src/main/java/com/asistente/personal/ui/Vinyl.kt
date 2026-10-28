package com.asistente.personal.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Segundos de canción que avanza (o retrocede) una vuelta entera del disco al arrastrarlo. */
const val VINYL_SECONDS_PER_TURN = 30.0

/** Velocidad de giro de un disco de 33⅓ rpm, en grados por segundo. */
const val VINYL_DEGREES_PER_SECOND = 200f

/** Tiempo para dar el segundo toque de un doble toque. Un toque suelto espera esto antes de pausar/reanudar. */
const val VINYL_DOUBLE_TAP_MS = 260L

/** Un toque más largo que esto (sin girar) ya no cuenta como toque. */
private const val TAP_MAX_MS = 450L

/** Lo mínimo que hay que girar el dedo para que cuente como arrastre (y no como un toque), en grados. */
const val VINYL_DRAG_SLOP = 7f

/** Diferencia más corta entre dos ángulos en grados, con signo (positivo = en el sentido de las agujas del reloj). */
fun angleDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

/** Posición de la canción tras girar el disco `turnedDegrees` desde `start`: adelante al girar hacia la derecha. */
fun scrubTarget(start: Double, turnedDegrees: Float, duration: Double, secondsPerTurn: Double = VINYL_SECONDS_PER_TURN): Double {
    val target = start + turnedDegrees / 360.0 * secondsPerTurn
    return if (duration > 0) target.coerceIn(0.0, duration) else target.coerceAtLeast(0.0)
}

/** La velocidad de giro se acerca a la que toca (parada o 33 rpm) como un plato con inercia. `tau` = segundos que tarda en cambiar. */
fun spinStep(omega: Float, target: Float, dtSeconds: Float, tau: Float = 0.55f): Float =
    target + (omega - target) * exp(-dtSeconds / tau)

/** «+0:12» o «−0:05»: lo que has adelantado o retrasado la canción. */
fun scrubLabel(deltaSeconds: Double): String {
    val s = abs(deltaSeconds).roundToInt()
    return (if (deltaSeconds < 0) "−" else "+") + "%d:%02d".format(s / 60, s % 60)
}

/** La carátula recortada en círculo (para la etiqueta del disco); se hace una vez, no en cada cuadro. */
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

private fun angleOf(p: Offset, center: Offset): Float = (atan2(p.y - center.y, p.x - center.x) * 180f / PI.toFloat())

/**
 * Un vinilo girando (33 rpm) con la carátula de etiqueta y un brazo cuya aguja baja al disco al sonar.
 *
 * Es interactivo: con el dedo sobre el disco se detiene y gira con la mano. Girar hacia la derecha adelanta la
 * canción y hacia la izquierda la retrasa (una vuelta = 30 s); mientras se arrastra se ve cuánto, y al soltar se
 * salta a ese punto (`onSeek`). Un toque corto pausa o reanuda (`onToggle`). Al soltar en movimiento, el disco
 * sigue girando por inercia y vuelve poco a poco a su velocidad.
 */
@Composable
fun Vinyl(
    art: ImageBitmap?,
    accent: Color,
    playing: Boolean,
    position: Double,
    duration: Double,
    onScrub: (Double?) -> Unit,
    onSeek: (Double) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    cover: Boolean = false,
    onDoubleTap: () -> Unit = {},
) {
    val isCover by rememberUpdatedState(cover)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val isPlaying by rememberUpdatedState(playing)
    val livePosition by rememberUpdatedState(position)
    val liveDuration by rememberUpdatedState(duration)
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val toggle by rememberUpdatedState(onToggle)

    var angle by remember { mutableFloatStateOf(0f) }
    var omega by remember { mutableFloatStateOf(0f) }
    var held by remember { mutableStateOf(false) }
    var frame by remember { mutableLongStateOf(0L) }
    var delta by remember { mutableStateOf<Double?>(null) } // lo adelantado o retrasado mientras se arrastra
    val arm by animateFloatAsState(
        if ((playing || held) && !cover) 1f else 0f, spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = 90f), label = "brazo",
    )
    val label = remember(art) { art?.let { circularArt(it, 320) } }
    val sleeve = remember(art) { art?.let { roundedArt(it, 480, 0.045f) } }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            if (!held) {
                omega = spinStep(omega, if (isPlaying) VINYL_DEGREES_PER_SECOND else 0f, dt)
                angle = (angle + omega * dt) % 360f
            }
            frame = now
        }
    }

    Box(
        modifier.semantics { contentDescription = "Portada: toca para pausar, doble toque para ver el vinilo, y en el vinilo gíralo para adelantar o retroceder" },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val side = min(size.width, size.height).toFloat()
                    val center = Offset(side * DISC_X, side * DISC_Y)
                    val radius = side * DISC_R
                    val onRecord = (down.position - center).getDistance() <= radius * 1.04f
                    // En la portada vale tocar en cualquier parte del cuadrado; con el disco entero, solo sobre él.
                    if (!isCover && !onRecord) return@awaitEachGesture
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
                        if (!dragging && !isCover && abs(turned) > VINYL_DRAG_SLOP) {
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
                        // Un toque corto espera un instante por si llega un segundo: doble toque cambia entre la
                        // portada y el vinilo; un toque suelto pausa o reanuda.
                        val second = withTimeoutOrNull(VINYL_DOUBLE_TAP_MS) { awaitFirstDown(requireUnconsumed = false) }
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
            if (cover) drawSleeve(angle, accent, label, sleeve) else drawVinyl(angle, arm, accent, label)
        }
        delta?.let {
            Box(Modifier.padding(bottom = 6.dp)) {
                Text(scrubLabel(it), style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 26.sp, color = accent))
            }
        }
    }
}

// Posiciones relativas al lado del cuadrado: el disco a la izquierda y el pivote del brazo arriba a la derecha.
private const val DISC_X = 0.46f
private const val DISC_Y = 0.545f
private const val DISC_R = 0.43f
private val PIVOT = Offset(0.95f, 0.065f)
private const val ARM_OFF_DEGREES = -40f // cuánto se aparta el brazo del disco cuando no suena

private fun DrawScope.drawVinyl(angle: Float, arm: Float, accent: Color, label: ImageBitmap?) {
    val s = min(size.width, size.height)
    val c = Offset(s * DISC_X, s * DISC_Y)
    val rd = s * DISC_R
    drawRecord(c, rd, s / 300f, angle, accent, label)
    drawArm(s, arm, s / 300f, c, rd)
}

/** Un disco de vinilo (surcos, marcas del borde, etiqueta y reflejo) con su centro `c` y radio `rd`. */
private fun DrawScope.drawRecord(c: Offset, rd: Float, dp: Float, angle: Float, accent: Color, label: ImageBitmap?) {
    // Sombra bajo el disco.
    drawCircle(
        Brush.radialGradient(listOf(Color(0xAA000000), Color.Transparent), c + Offset(rd * 0.06f, rd * 0.1f), rd * 1.25f),
        rd * 1.25f, c + Offset(rd * 0.06f, rd * 0.1f),
    )

    // Todo lo que gira con el disco: surcos, marcas del borde, arañazos y etiqueta.
    rotate(angle, c) {
        drawCircle(Brush.radialGradient(listOf(Color(0xFF18181B), Color(0xFF0A0A0C), Color(0xFF050506)), c, rd), rd, c)
        var r = rd * 0.40f
        var k = 0
        while (r < rd * 0.975f) {
            drawCircle(Color.White.copy(alpha = if (k % 7 == 0) 0.085f else 0.04f), r, c, style = Stroke(0.8f * dp))
            r += rd * 0.0125f
            k++
        }
        for (gap in floatArrayOf(0.56f, 0.69f, 0.81f, 0.91f)) {
            drawCircle(Color.Black.copy(alpha = 0.55f), rd * gap, c, style = Stroke(rd * 0.02f)) // separación entre canciones
        }
        // Marcas del borde, como las de un plato con luz estroboscópica: así se nota el giro.
        for (i in 0 until 72) {
            val a = i * 5f * PI.toFloat() / 180f
            val inner = Offset(c.x + cos(a) * rd * 0.955f, c.y + sin(a) * rd * 0.955f)
            val outer = Offset(c.x + cos(a) * rd * 0.985f, c.y + sin(a) * rd * 0.985f)
            drawLine(accent.copy(alpha = if (i % 6 == 0) 0.75f else 0.28f), inner, outer, (if (i % 6 == 0) 2f else 1.2f) * dp, StrokeCap.Round)
        }
        // Unos arañazos finos que giran con el disco.
        for ((radius, start, sweep) in listOf(Triple(0.62f, 30f, 38f), Triple(0.78f, 200f, 26f), Triple(0.87f, 110f, 18f))) {
            drawArc(
                Color.White.copy(alpha = 0.16f), start, sweep, false, Offset(c.x - rd * radius, c.y - rd * radius),
                Size(rd * radius * 2, rd * radius * 2), style = Stroke(0.9f * dp, cap = StrokeCap.Round),
            )
        }
        // Etiqueta: la carátula en círculo (o el color de la canción) con un borde fino.
        val lr = rd * 0.34f
        if (label != null) {
            drawImage(label, dstOffset = IntOffset((c.x - lr).roundToInt(), (c.y - lr).roundToInt()), dstSize = IntSize((lr * 2).roundToInt(), (lr * 2).roundToInt()))
        } else {
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.55f)), c, lr), lr, c)
        }
        drawCircle(Color.Black.copy(alpha = 0.45f), lr, c, style = Stroke(1.6f * dp))
        drawCircle(Color.White.copy(alpha = 0.18f), lr * 0.995f, c, style = Stroke(0.8f * dp))
        drawCircle(Color(0xFF0A0A0C), rd * 0.045f, c) // agujero
        drawCircle(Color.White.copy(alpha = 0.35f), rd * 0.045f, c, style = Stroke(1f * dp))
    }

    // Reflejo de la luz: no gira con el disco (la luz está quieta), por eso se ve que gira.
    drawCircle(
        Brush.sweepGradient(
            0f to Color.Transparent, 0.06f to Color.White.copy(alpha = 0.17f), 0.15f to Color.Transparent,
            0.5f to Color.Transparent, 0.56f to Color.White.copy(alpha = 0.13f), 0.65f to Color.Transparent, 1f to Color.Transparent,
            center = c,
        ),
        rd, c,
    )
    drawCircle(Color.White.copy(alpha = 0.10f), rd, c, style = Stroke(1.2f * dp))
}

/**
 * La portada de la canción, como una funda de disco, con el vinilo asomando por detrás (y girando si suena).
 * No es un vinilo literal: es la portada la que manda, y el disco da vida.
 */
private fun DrawScope.drawSleeve(angle: Float, accent: Color, label: ImageBitmap?, cover: ImageBitmap?) {
    val s = min(size.width, size.height)
    val dp = s / 300f
    val record = Offset(s * 0.695f, s * 0.5f)
    val rd = s * 0.30f
    drawRecord(record, rd, dp, angle, accent, label)

    val side = s * 0.68f
    val left = s * 0.03f
    val top = (s - side) / 2
    val radius = CornerRadius(side * 0.045f)
    // La funda proyecta su sombra sobre el disco.
    drawRoundRect(Brush.horizontalGradient(listOf(Color(0x99000000), Color.Transparent), left + side - 2 * dp, left + side + rd * 0.35f), Offset(left + side - 2 * dp, top + side * 0.04f), Size(rd * 0.4f, side * 0.92f))
    drawRoundRect(Color.Black.copy(alpha = 0.5f), Offset(left + 5 * dp, top + 9 * dp), Size(side, side), radius)
    if (cover != null) {
        drawImage(cover, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(side.roundToInt(), side.roundToInt()))
    } else {
        drawRoundRect(Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.45f)), Offset(left, top), Offset(left + side, top + side)), Offset(left, top), Size(side, side), radius)
    }
    // Un brillo en diagonal y el canto de la funda.
    drawRoundRect(
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.18f)), Offset(left, top), Offset(left + side, top + side)),
        Offset(left, top), Size(side, side), radius,
    )
    drawRoundRect(Color.White.copy(alpha = 0.14f), Offset(left, top), Size(side, side), radius, style = Stroke(1.2f * dp))
}

/** La carátula con las esquinas redondeadas (para la funda); se hace una vez, no en cada cuadro. */
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

/** El brazo del tocadiscos: gira alrededor de su pivote y baja la aguja al disco cuando suena. */
private fun DrawScope.drawArm(s: Float, arm: Float, dp: Float, c: Offset, rd: Float) {
    val pivot = Offset(s * PIVOT.x, s * PIVOT.y)
    val needle = Offset(c.x + rd * 0.62f * cos(-50f * PI.toFloat() / 180f), c.y + rd * 0.62f * sin(-50f * PI.toFloat() / 180f))
    val toNeedle = needle - pivot
    val length = toNeedle.getDistance()
    val baseAngle = atan2(toNeedle.y, toNeedle.x) * 180f / PI.toFloat()
    val swing = ARM_OFF_DEGREES * (1f - arm)

    rotate(swing, pivot) {
        val dir = Offset(toNeedle.x / length, toNeedle.y / length)
        val back = pivot - dir * (length * 0.2f)
        // Sombra del brazo.
        drawLine(Color.Black.copy(alpha = 0.35f), pivot + Offset(3 * dp, 5 * dp), needle + Offset(3 * dp, 5 * dp), 7 * dp, StrokeCap.Round)
        // Contrapeso y tubo.
        drawLine(Color(0xFF3A3A40), back, pivot, 17 * dp, StrokeCap.Round)
        drawLine(Color(0xFFBFC0C7), pivot, needle, 6 * dp, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.55f), pivot + Offset(0f, -1.5f * dp), needle + Offset(0f, -1.5f * dp), 1.6f * dp, StrokeCap.Round)
        // Cabezal (donde va la aguja), algo girado respecto al tubo.
        rotate(baseAngle + 14f, needle) {
            drawRoundRectCompat(Offset(needle.x - 6 * dp, needle.y - 9 * dp), Size(12 * dp, 22 * dp), Color(0xFF2B2B30))
        }
        drawCircle(needleColor(arm), 2.4f * dp, needle + dir * (8 * dp))
        // Base del pivote.
        drawCircle(Brush.radialGradient(listOf(Color(0xFF8E8F96), Color(0xFF3A3A40)), pivot, 15 * dp), 15 * dp, pivot)
        drawCircle(Color(0xFF17171A), 6 * dp, pivot)
    }
}

private fun needleColor(arm: Float) = Color(0xFFFF5A3A).copy(alpha = 0.4f + 0.6f * arm) // la aguja se enciende al bajar al disco

private fun DrawScope.drawRoundRectCompat(topLeft: Offset, size: Size, color: Color) {
    drawRoundRect(color, topLeft, size, androidx.compose.ui.geometry.CornerRadius(3f * (this.size.minDimension / 300f)))
}
