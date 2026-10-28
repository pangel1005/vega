package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
 * Estilo «Orbe»: Vega como una esfera de luz que respira, sin cara ni tarjetas. El color dice qué hace
 * (escucha, piensa, habla, o el de la canción que suena); motas de luz fluyen hacia ella al escucharte,
 * remolinean al pensar y salen en ondas al hablar; la CPU, la memoria, la gráfica y la batería son cuatro
 * anillos finos; cada aviso del PC es un satélite; la respuesta va debajo y las teclas son cápsulas.
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
    state == AssistantState.IDLE -> 40L
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

private const val MOTES = 36
private const val TAU = (2 * PI).toFloat()

private class Motes {
    val angle = FloatArray(MOTES)
    val radius = FloatArray(MOTES)
    val speed = FloatArray(MOTES)
    val size = FloatArray(MOTES)

    init {
        val rnd = java.util.Random(7) // siempre las mismas, para que las capturas de pantalla no cambien
        for (i in 0 until MOTES) {
            angle[i] = rnd.nextFloat() * TAU
            radius[i] = 1.9f + rnd.nextFloat() * 0.5f
            speed[i] = (0.05f + rnd.nextFloat() * 0.12f) * if (rnd.nextBoolean()) 1f else -1f
            size[i] = 0.9f + rnd.nextFloat() * 1.6f
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
    val motes = remember { Motes() }
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
    val spoken = last?.text?.replace('\n', ' ') ?: "Di «${ui.assistant.ifEmpty { "Vega" }}» y lo que necesites."
    var shown by remember(last?.id) { mutableIntStateOf(if (last?.role == Role.ASSISTANT) 0 else Int.MAX_VALUE) }
    LaunchedEffect(last?.id, spoken.length) {
        while (shown < spoken.length) {
            delay(16)
            shown += 2
        }
    }

    Box(modifier.fillMaxSize().background(Color(0xFF030304))) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onAttention() } }
                .semantics { contentDescription = "Vega: $status" },
        ) {
            val t = phase
            val dim = if (m.sleeping) 0.45f else 1f
            val cx = size.width / 2
            val cy = size.height * 0.43f
            val rate = if (pending) 5f else 1.9f
            val r = size.height * 0.162f * (if (m.sleeping) 0.86f else 1f) * (1f + 0.035f * sin(t * rate) + level * 0.14f)
            val center = Offset(cx, cy)

            // Resplandor
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.34f * dim), color.copy(alpha = 0.08f * dim), Color.Transparent), center, r * 3.4f),
                radius = r * 3.4f, center = center,
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
                val a = motes.angle[i] + t * motes.speed[i] * (1f + swirl * 3.2f)
                val rad = moteRadius(motes.radius[i], f, inward, outward) * r
                val alpha = (0.22f * (1f - flow) + sin(PI.toFloat() * f) * 0.75f * flow) * dim
                drawCircle(color.copy(alpha = alpha.coerceIn(0f, 1f)), motes.size[i].dp.toPx(), Offset(cx + cos(a) * rad, cy + sin(a) * rad))
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
            // La esfera
            val glint = sin(t * 0.4f) * r * 0.06f
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.95f * dim), lerpColor(color, Color.White, 0.45f), color, lerpColor(color, Color.Black, 0.62f)),
                    Offset(cx - r * 0.3f + glint, cy - r * 0.34f), r * 1.55f,
                ),
                radius = r, center = center, alpha = dim,
            )
            drawArc(
                Color.White.copy(alpha = 0.16f * dim), 20f, 130f, false, Offset(cx - r, cy - r), Size(r * 2, r * 2),
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round),
            )
            // Cuatro anillos: PC (procesador, memoria, gráfica) y batería del teléfono
            val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
            rings.forEachIndexed { i, v ->
                val rr = r * (1.34f + i * 0.14f)
                val tl = Offset(cx - rr, cy - rr)
                val box = Size(rr * 2, rr * 2)
                drawArc(Color(0xFF17171A), 140f, 260f, false, tl, box, style = stroke)
                if (v != null && v > 0f) drawArc(Ring[i].copy(alpha = dim), 140f, 260f * v, false, tl, box, style = stroke)
            }
            // Un satélite por aviso del PC; los recién llegados brillan
            val notes = ui.pcNotes.take(6)
            notes.forEachIndexed { i, n ->
                val fresh = n.id in ui.freshNotes
                val a = TAU * i / notes.size + t * 0.18f
                val at = Offset(cx + cos(a) * r * 2.0f, cy + sin(a) * r * 2.0f)
                if (fresh) drawCircle(Vega.Amber.copy(alpha = 0.25f * (0.6f + 0.4f * sin(t * 4f))), 9.dp.toPx(), at)
                drawCircle(if (fresh) Vega.Amber else Color(0xFF8C8578), (if (fresh) 4 else 3).dp.toPx(), at)
            }
        }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(m.clock, TextStyle(fontFamily = Vega.Sans, fontSize = 15.sp, color = Color(0xFF6A665F)))
                timer?.let { Text("  ·  %d:%02d".format(it / 60, it % 60), TextStyle(fontFamily = Vega.Sans, fontSize = 15.sp, color = Vega.Amber)) }
                Text(
                    music?.let { "♪ $it" } ?: "",
                    TextStyle(fontFamily = Vega.Sans, fontSize = 13.sp, color = color.copy(alpha = 0.75f), textAlign = TextAlign.Center),
                    Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1,
                )
                Text(
                    status + if (ui.pcNotes.isNotEmpty()) "  ·  ${ui.pcNotes.size} avisos" else "",
                    TextStyle(fontFamily = Vega.Sans, fontSize = 14.sp, color = Color(0xFF8A857C)),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                legend.forEachIndexed { i, s ->
                    Text(s, TextStyle(fontFamily = Vega.Sans, fontSize = 12.sp, color = Ring[i].copy(alpha = if (s.endsWith("—")) 0.35f else 0.8f)), maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            val userSaid = last?.role == Role.USER
            Text(
                if (userSaid) "“${spoken.take(shown)}”" else spoken.take(shown),
                TextStyle(
                    fontFamily = Vega.Sans, fontSize = 21.sp, lineHeight = 25.sp, textAlign = TextAlign.Center,
                    color = if (userSaid) Color(0xFF8C8578) else Color(0xFFE8E0D2),
                ),
                Modifier.fillMaxWidth().padding(horizontal = 40.dp).height(52.dp),
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(36.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.actions.filter { it.quick }.take(6).forEach { action ->
                    val pressed = remember { mutableStateOf(false) }
                    val isPending = ui.pending?.id == action.id
                    val tint = if (isPending) Vega.Red else Color(0xFFA29C91)
                    Box(
                        Modifier.weight(1f).height(36.dp)
                            .background(if (pressed.value) Color(0x22FFFFFF) else Color(0x0DFFFFFF), RoundedCornerShape(18.dp))
                            .border(1.dp, if (isPending) Vega.Red else Color(0xFF2A2A2E), RoundedCornerShape(18.dp))
                            .instantPress(canAct, pressed) { onPress(action.id) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (isPending) "¿Seguro? $secondsLeft" else action.label.substringBefore(" /"),
                            TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = tint),
                            maxLines = 1,
                        )
                    }
                }
                val morePressed = remember { mutableStateOf(false) }
                Box(
                    Modifier.width(44.dp).height(36.dp)
                        .background(if (morePressed.value) Color(0x22FFFFFF) else Color.Transparent, RoundedCornerShape(18.dp))
                        .border(1.dp, Color(0xFF1F1F22), RoundedCornerShape(18.dp))
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
