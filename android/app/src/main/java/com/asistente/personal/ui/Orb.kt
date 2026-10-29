package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Telemetry
import com.asistente.personal.state.Role
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Estilo «Orbe»: Vega como una esfera de luz que respira, sin cara ni tarjetas.
 *
 * - La esfera es de plasma: tres manchas de luz giran por dentro, con un borde de luz y un brillo.
 * - El color dice qué hace (verde escucha, ámbar piensa, crema habla, rojo confirma) o es el de la canción que suena.
 * - Su contorno vibra con tu voz y con la suya; motas de luz fluyen hacia ella al escuchar, un cometa gira al
 *   pensar y salen ondas al hablar.
 * - Cuatro anillos finos (CPU, RAM, GPU, batería del móvil); cada aviso del PC es un satélite.
 * - Al pedir confirmación, un anillo rojo se vacía en 8 segundos.
 */
fun orbColor(state: AssistantState, sleeping: Boolean, pending: Boolean, idle: Color): Color = when {
    pending -> Vega.Red
    sleeping -> Color(0xFF3A4660)
    state == AssistantState.LISTENING -> Vega.Mint
    state == AssistantState.THINKING || state == AssistantState.ACTING -> Vega.Amber
    state == AssistantState.SPEAKING -> Color(0xFFFFE2C2)
    state == AssistantState.ERROR -> Vega.Red
    else -> idle
}

/** Lo que llenan los cuatro anillos (0 a 1): procesador, memoria, gráfica (sobre 90 °C) y batería del teléfono. */
fun orbRings(t: Telemetry?, battery: Int): List<Float?> = listOf(
    t?.let { it.cpu / 100f },
    t?.takeIf { it.ramTotal > 0 }?.let { (it.ramUsed / it.ramTotal).toFloat() },
    t?.gpuTemp?.let { it / 90f },
    battery.takeIf { it >= 0 }?.let { it / 100f },
).map { it?.coerceIn(0f, 1f) }

/** Las cuatro etiquetas bajo la esfera, en el mismo orden (y color) que los anillos. */
fun orbLegend(t: Telemetry?, battery: Int): List<String> = listOf(
    t?.let { "CPU ${it.cpu}%" } ?: "CPU —",
    t?.takeIf { it.ramTotal > 0 }?.let { "RAM ${it.ramUsed.roundToInt()}/${it.ramTotal.roundToInt()} GB" } ?: "RAM —",
    t?.gpuTemp?.let { "GPU $it°" } ?: "GPU —",
    if (battery >= 0) "Móvil $battery%" else "Móvil —",
)

/** Cuánto tarda en repintarse la esfera: a toda velocidad cuando actúa, despacio en reposo (ahorra batería). */
fun orbFrameMs(state: AssistantState, sleeping: Boolean): Long = when {
    sleeping -> 120L
    state == AssistantState.IDLE -> 55L
    else -> 16L
}

/** Cómo se mueven las motas: `swirl` (remolino al pensar), `inward` (hacia la esfera al escuchar) y `outward` (hacia fuera al hablar). */
data class OrbMotion(val swirl: Float, val inward: Float, val outward: Float)

fun orbMotion(state: AssistantState, sleeping: Boolean): OrbMotion = when {
    sleeping -> OrbMotion(0f, 0f, 0f)
    state == AssistantState.LISTENING -> OrbMotion(0f, 1f, 0f)
    state == AssistantState.THINKING || state == AssistantState.ACTING -> OrbMotion(1f, 0f, 0f)
    state == AssistantState.SPEAKING -> OrbMotion(0f, 0f, 1f)
    else -> OrbMotion(0f, 0f, 0f)
}

private const val SURFACE = 1.15f // dónde nacen y mueren las motas, en radios de la esfera

/** Radio de una mota (en radios de la esfera): en reposo, el suyo; al escuchar baja hasta la esfera; al hablar sale de ella. `flow` va de 0 a 1 en cada ciclo. */
fun moteRadius(base: Float, flow: Float, inward: Float, outward: Float): Float {
    val toward = SURFACE + (base - SURFACE) * (1f - flow)
    val away = SURFACE + (base * 1.25f - SURFACE) * flow
    return base + (toward - base) * inward + (away - base) * outward
}

/** Cuánto vibra el contorno de la esfera (en radios): un temblor mínimo en reposo, más con la voz, el remolino o al hablar. */
fun orbWaveAmp(level: Float, motion: OrbMotion): Float =
    0.012f + 0.075f * level.coerceIn(0f, 1f) + 0.014f * motion.swirl + 0.018f * motion.outward

/** Cuánto del anillo de confirmación queda (en grados): 360 al empezar, 0 al acabarse el tiempo. */
fun confirmSweep(secondsLeft: Int, total: Int): Float = if (total <= 0) 0f else 360f * (secondsLeft.coerceIn(0, total).toFloat() / total)

private const val MOTES = 36
private const val STARS = 44
private const val TAU = (2 * PI).toFloat()

/** Puntos que no cambian entre capturas ni entre pantallas: siempre las mismas posiciones. */
private class Sky {
    val motesAngle = FloatArray(MOTES)
    val motesRadius = FloatArray(MOTES)
    val motesSpeed = FloatArray(MOTES)
    val motesSize = FloatArray(MOTES)
    val starX = FloatArray(STARS)
    val starY = FloatArray(STARS)
    val starAlpha = FloatArray(STARS)

    init {
        val rnd = java.util.Random(7)
        for (i in 0 until MOTES) {
            motesAngle[i] = rnd.nextFloat() * TAU
            motesRadius[i] = 1.9f + rnd.nextFloat() * 0.5f
            motesSpeed[i] = (0.05f + rnd.nextFloat() * 0.12f) * if (rnd.nextBoolean()) 1f else -1f
            motesSize[i] = 0.9f + rnd.nextFloat() * 1.6f
        }
        for (i in 0 until STARS) {
            starX[i] = rnd.nextFloat()
            starY[i] = rnd.nextFloat()
            starAlpha[i] = 0.08f + rnd.nextFloat() * 0.22f
        }
    }
}

private val Ring = listOf(Color(0xFFFFD7B0), Vega.Amber, Color(0xFFFF8A5C), Vega.Mint)

@Composable
fun OrbScreen(
    m: ScreenModel,
    secondsLeft: Int,
    canAct: Boolean,
    onPress: (String) -> Unit,
    onMore: () -> Unit,
    onAttention: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = m.ui
    val pending = ui.pending != null
    val playing = ui.media?.playing == true && ui.link == LinkStatus.OPEN
    val color by animateColorAsState(
        orbColor(m.state, m.sleeping, pending, if (playing) m.mediaAccent else m.faceColor.color), tween(500), label = "color del orbe",
    )
    val motion = orbMotion(m.state, m.sleeping)
    val swirl by animateFloatAsState(motion.swirl, tween(700), label = "remolino")
    val inward by animateFloatAsState(motion.inward, tween(700), label = "hacia dentro")
    val outward by animateFloatAsState(motion.outward, tween(700), label = "hacia fuera")
    val sky = remember { Sky() }
    val wave = remember { Path() }
    val ripples = remember { FloatArray(5) { -100f } }
    var phase by remember { mutableFloatStateOf(0f) }
    var level by remember { mutableFloatStateOf(0f) }
    var seen by remember { mutableIntStateOf(m.pulse) }
    var lastRipple by remember { mutableFloatStateOf(-1f) }
    var nextRipple by remember { mutableIntStateOf(0) }
    LaunchedEffect(m.pulse) { // un trozo de voz (o de micrófono): la esfera da un latido y sale una onda
        if (seen != m.pulse) {
            seen = m.pulse
            level = max(level, m.pulseAmount)
            if (phase - lastRipple > 0.22f) {
                lastRipple = phase
                ripples[nextRipple++ % ripples.size] = phase
            }
        }
    }
    LaunchedEffect(m.state, m.sleeping) {
        var last = System.nanoTime()
        while (true) {
            delay(orbFrameMs(m.state, m.sleeping))
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.2f)
            last = now
            phase += dt
            level *= exp(-dt * 5f)
        }
    }
    val link = ui.telemetry.takeIf { ui.link == LinkStatus.OPEN }
    val rings = orbRings(link, m.battery)
    val legend = orbLegend(link, m.battery)
    val timer = (m.localTimerEndsAt ?: ui.telemetry?.upcoming?.firstOrNull { it.kind == "timer" }?.let { ui.telemetryAt + it.dueIn * 1000L })
        ?.let { ((it - m.clockMs() + 999) / 1000).toInt() }?.takeIf { it > 0 }
    val status = terminalStatus(m.state, m.sleeping, m.micOn).lowercase().replaceFirstChar { it.uppercase() }
    val music = ui.media?.takeIf { playing }?.let { (it.artist + " — " + it.title).trim(' ', '—') }?.takeIf { it.isNotEmpty() }

    val last = ui.messages.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.USER }
    val asked = if (last?.role == Role.ASSISTANT) ui.messages.getOrNull(ui.messages.indexOf(last) - 1)?.takeIf { it.role == Role.USER }?.text else null
    val spoken = last?.text?.replace('\n', ' ') ?: "Di «${ui.assistant.ifEmpty { "Vega" }}» y lo que necesites."
    var shown by remember(last?.id) { mutableIntStateOf(if (last?.role == Role.ASSISTANT) 0 else Int.MAX_VALUE) }
    LaunchedEffect(last?.id, spoken.length) {
        while (shown < spoken.length) {
            delay(16)
            shown += 2
        }
    }

    Box(modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF07080C), Color(0xFF020203))))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onAttention() } }
                .semantics { contentDescription = "Vega: ${status.ifEmpty { "lista" }}" },
        ) {
            val t = phase
            val dim = if (m.sleeping) 0.45f else 1f
            val cx = size.width / 2
            val cy = size.height * 0.40f
            val rate = if (pending) 5f else 1.9f
            val r = size.height * 0.155f * (if (m.sleeping) 0.86f else 1f) * (1f + 0.035f * sin(t * rate) + level * 0.14f)
            val center = Offset(cx, cy)
            val soft = lerpColor(color, Color.White, 0.35f)

            // Estrellas lejanas que titilan
            for (i in 0 until STARS) {
                val a = sky.starAlpha[i] * (0.65f + 0.35f * sin(t * 0.6f + i * 1.7f)) * dim
                drawCircle(Color.White.copy(alpha = a), 0.9.dp.toPx(), Offset(sky.starX[i] * size.width, sky.starY[i] * size.height))
            }
            // Resplandor
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.36f * dim), color.copy(alpha = 0.09f * dim), Color.Transparent), center, r * 3.0f),
                radius = r * 3.0f, center = center,
            )
            // Ondas que salen con cada trozo de voz
            for (born in ripples) {
                val age = t - born
                if (age in 0f..1.5f) {
                    drawCircle(color.copy(alpha = 0.30f * (1f - age / 1.5f) * dim), r * (1.05f + age), center, style = Stroke(1.5.dp.toPx()))
                }
            }
            // Motas de luz
            val flow = max(inward, outward)
            for (i in 0 until MOTES) {
                val f = (t * 0.32f + i * 0.61803f) % 1f
                val a = sky.motesAngle[i] + t * sky.motesSpeed[i] * (1f + swirl * 3.2f)
                val rad = moteRadius(sky.motesRadius[i], f, inward, outward) * r
                val alpha = (0.22f * (1f - flow) + sin(PI.toFloat() * f) * 0.75f * flow) * dim
                drawCircle(color.copy(alpha = alpha.coerceIn(0f, 1f)), sky.motesSize[i].dp.toPx(), Offset(cx + cos(a) * rad, cy + sin(a) * rad))
            }
            // Cometa que gira mientras piensa
            if (swirl > 0.01f) {
                val rr = r * 1.22f
                val head = (t * 230f) % 360f
                for (k in 0 until 12) {
                    drawArc(
                        color.copy(alpha = swirl * (1f - k / 12f) * 0.85f), head - k * 9f, 9.5f, false,
                        Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2), style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }

            // La esfera de plasma: base, tres manchas de luz que giran por dentro, sombra, borde de luz
            run {
                drawCircle(
                    Brush.radialGradient(
                        listOf(lerpColor(color, Color.White, 0.22f), color, lerpColor(color, Color.Black, 0.72f)),
                        Offset(cx - r * 0.25f, cy - r * 0.3f), r * 1.6f,
                    ),
                    radius = r, center = center,
                )
                val energy = 1f + swirl * 1.6f + inward * 0.6f + outward * 0.9f + level * 2f
                val blobs = listOf(lerpColor(color, Color.White, 0.6f), hueShift(color, 28f), hueShift(color, -28f))
                blobs.forEachIndexed { i, c ->
                    val a = t * (0.35f + i * 0.17f) * energy + i * 2.1f
                    val b = t * (0.27f + i * 0.13f) * energy + i * 1.3f
                    // Sin recortar con un círculo (cuesta mucho en la GPU): las manchas nunca salen de la esfera.
                    val at = Offset(cx + cos(a) * r * 0.36f, cy + sin(b) * r * 0.36f)
                    drawCircle(
                        Brush.radialGradient(listOf(c.copy(alpha = 0.6f), Color.Transparent), at, r * 0.6f),
                        radius = r * 0.6f, center = at, blendMode = BlendMode.Plus,
                    )
                }
                drawCircle(
                    Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent), Offset(cx + r * 0.5f, cy + r * 0.55f), r * 1.15f),
                    radius = r, center = center,
                )
                drawCircle(
                    Brush.radialGradient(
                        colorStops = arrayOf(0f to Color.Transparent, 0.72f to Color.Transparent, 1f to soft.copy(alpha = 0.65f)),
                        center = center, radius = r,
                    ),
                    radius = r, center = center,
                )
            }
            // Brillo
            val spec = Offset(cx - r * 0.38f, cy - r * 0.42f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f), Color.Transparent), spec, r * 0.34f), radius = r * 0.34f, center = spec)
            drawArc(
                Color.White.copy(alpha = 0.18f * dim), 200f, 100f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
            // Contorno que vibra con la voz
            val amp = orbWaveAmp(level, motion.copy(swirl = swirl, outward = outward))
            for (layer in 0..1) {
                wave.reset()
                val n = 96
                for (k in 0..n) {
                    val th = TAU * k / n
                    val d = amp * (sin(3 * th + t * 2.2f + layer) + 0.6f * sin(5 * th - t * 3.1f) + 0.4f * sin(8 * th + t * 4.3f + layer * 2f)) / 2f
                    val rr = r * (1.07f + layer * 0.05f + d)
                    val x = cx + cos(th) * rr
                    val y = cy + sin(th) * rr
                    if (k == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                }
                wave.close()
                drawPath(wave, color.copy(alpha = (0.6f - layer * 0.35f) * dim), style = Stroke(1.4.dp.toPx()))
            }
            // Cuenta atrás de la confirmación
            ui.pending?.let { p ->
                val rr = r * 1.17f
                drawArc(
                    Vega.Red, -90f, confirmSweep(secondsLeft, p.expiresIn), false, Offset(cx - rr, cy - rr), Size(rr * 2, rr * 2),
                    style = Stroke(3.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // Cuatro anillos: PC (procesador, memoria, gráfica) y batería del teléfono
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            rings.forEachIndexed { i, v ->
                val rr = r * (1.38f + i * 0.14f)
                val tl = Offset(cx - rr, cy - rr)
                val box = Size(rr * 2, rr * 2)
                drawArc(Color(0xFF17171A), 140f, 260f, false, tl, box, style = stroke)
                if (v != null && v > 0f) {
                    drawArc(Ring[i].copy(alpha = dim), 140f, 260f * v, false, tl, box, style = stroke)
                    val end = (140f + 260f * v) * PI.toFloat() / 180f
                    drawCircle(Color.White.copy(alpha = 0.9f * dim), 2.dp.toPx(), Offset(cx + cos(end) * rr, cy + sin(end) * rr))
                }
            }
            // Un satélite por aviso del PC; los recién llegados brillan
            val notes = ui.pcNotes.take(6)
            notes.forEachIndexed { i, n ->
                val fresh = n.id in ui.freshNotes
                val a = TAU * i / notes.size + t * 0.18f
                val at = Offset(cx + cos(a) * r * 2.05f, cy + sin(a) * r * 2.05f)
                if (fresh) drawCircle(Vega.Amber.copy(alpha = 0.25f * (0.6f + 0.4f * sin(t * 4f))), 9.dp.toPx(), at)
                drawCircle(if (fresh) Vega.Amber else Color(0xFF8C8578), (if (fresh) 4 else 3).dp.toPx(), at)
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.clock, TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Light, fontSize = 20.sp, color = Color(0xFFB9B4AA)))
                timer?.let { Text("  ·  %d:%02d".format(it / 60, it % 60), TextStyle(fontFamily = Vega.Sans, fontSize = 15.sp, color = Vega.Amber)) }
                Text(
                    music?.let { "♪ $it" } ?: "",
                    TextStyle(fontFamily = Vega.Sans, fontSize = 13.sp, color = color.copy(alpha = 0.8f), textAlign = TextAlign.Center),
                    Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1,
                )
                Box(Modifier.size(7.dp).background(color, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(
                    listOfNotNull(status.ifEmpty { null }, if (ui.pcNotes.isNotEmpty()) "${ui.pcNotes.size} avisos" else null)
                        .joinToString("  ·  "),
                    TextStyle(fontFamily = Vega.Sans, fontSize = 14.sp, color = Color(0xFFA29D93)),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                legend.forEachIndexed { i, s ->
                    val live = !s.endsWith("—")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(Ring[i].copy(alpha = if (live) 0.9f else 0.3f), CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(s, TextStyle(fontFamily = Vega.Sans, fontSize = 12.sp, color = Color(0xFFA29D93).copy(alpha = if (live) 1f else 0.5f)), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val userSaid = last?.role == Role.USER
            Text(
                asked?.let { "Tú: $it" } ?: "",
                TextStyle(fontFamily = Vega.Sans, fontSize = 12.sp, color = Color(0xFF6F6B64), textAlign = TextAlign.Center),
                Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(15.dp), maxLines = 1,
            )
            Text(
                if (userSaid) "“${spoken.take(shown)}”" else spoken.take(shown),
                TextStyle(
                    fontFamily = Vega.Sans, fontSize = 21.sp, lineHeight = 25.sp, textAlign = TextAlign.Center,
                    color = if (userSaid) Color(0xFF8C8578) else Color(0xFFEFE8DA),
                ),
                Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(52.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(38.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.actions.filter { it.quick }.take(6).forEach { action ->
                    val pressed = remember { mutableStateOf(false) }
                    val isPending = ui.pending?.id == action.id
                    val tint = if (isPending) Vega.Red else Color(0xFFB4AEA3)
                    val icon = m.icons[action.id]
                    Row(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(19.dp))
                            .background(if (pressed.value) Color(0x26FFFFFF) else Color(0x0FFFFFFF))
                            .border(1.dp, if (isPending) Vega.Red else Color(0xFF2A2A2E), RoundedCornerShape(19.dp))
                            .instantPress(canAct, pressed) { onPress(action.id) },
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon != null && !isPending) {
                            Image(icon, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (isPending) "¿Seguro? $secondsLeft" else action.label.substringBefore(" /"),
                            TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = tint),
                            maxLines = 1,
                        )
                    }
                }
                val morePressed = remember { mutableStateOf(false) }
                Box(
                    Modifier.width(44.dp).height(38.dp).clip(RoundedCornerShape(19.dp))
                        .background(if (morePressed.value) Color(0x26FFFFFF) else Color.Transparent)
                        .border(1.dp, Color(0xFF1F1F22), RoundedCornerShape(19.dp))
                        .instantPress(true, morePressed, onMore),
                    contentAlignment = Alignment.Center,
                ) { Text("···", TextStyle(fontFamily = Vega.Sans, fontSize = 14.sp, color = Color(0xFF8A857C))) }
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha,
)

/** El mismo color con el tono girado `degrees` grados: las manchas de la esfera son variaciones del color, no colores nuevos. */
private fun hueShift(c: Color, degrees: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), hsv)
    return Color.hsv((hsv[0] + degrees + 360f) % 360f, hsv[1], hsv[2])
}
