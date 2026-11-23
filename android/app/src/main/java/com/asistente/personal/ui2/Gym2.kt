package com.asistente.personal.ui2

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.StreakInfo
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ───────────────────────── Vega en el gimnasio ─────────────────────────
//
// Una escena con luz, no un dibujo plano: dormida es de noche (azul, luz de luna fría por arriba, una plataforma con un borde
// tenue); al despertar, una onda sale de la plataforma, el fuego sube desde abajo y la luz pasa a ser cálida y de abajo-atrás.
// Vega tiene volumen (degradado, brillo, sombra, luz de borde) y la coreografía de despertar es un salto con aplastamiento y estirón.

// Paleta de la escena.
private val NightTop = Color(0xFF0B0A1A)
private val NightMid = Color(0xFF15122C)
private val NightLow = Color(0xFF07060E)
private val EmberDeep = Color(0xFF3A0610)
private val FireRed = Color(0xFFE0261B)
private val FireOrange = Color(0xFFFF7A1A)
private val FireGold = Color(0xFFFFC23A)
private val FireCore = Color(0xFFFFF1C4)
private val Moon = Color(0xFF8FA2FF)

// Vega.
private val SkinTop = Color(0xFFFFDE73)
private val SkinLow = Color(0xFFF2A02A)
private val SkinNightTop = Color(0xFFD9CB98) // a la luz de la luna: amarillo pálido arriba, sombra fría abajo
private val SkinNightLow = Color(0xFF6E6788)
private val Ink = Color(0xFF2A1A10)
private val BandRed = Color(0xFFFF3B30)
private val BandDark = Color(0xFFB81D1D)

/** Transición suave (0 antes de `a`, 1 después de `b`). */
internal fun phase(p: Float, a: Float, b: Float): Float {
    val x = ((p - a) / (b - a)).coerceIn(0f, 1f)
    return x * x * (3 - 2 * x)
}

/** Llega un poco más allá y vuelve: para lo que cae o salta (las gafas, los brazos). */
private fun backOut(p: Float, a: Float, b: Float): Float {
    val x = ((p - a) / (b - a)).coerceIn(0f, 1f)
    val c1 = 1.9f
    val c3 = c1 + 1f
    val y = x - 1f
    return 1f + c3 * y * y * y + c1 * y * y
}

private fun mix(a: Float, b: Float, f: Float) = a + (b - a) * f

/**
 * La racha en grande. Si hoy aún no fuiste, Vega duerme de noche; al tocarla salta, se baja las gafas, saca bíceps y todo arde. Si ya
 * ardía hoy, se abre despierta y tocarla la hace sacar más músculo. A la derecha: los días, la semana con sus llamas y los números.
 */
@Composable
internal fun GymScreen2(m: ScreenModel, a: ScreenActions, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val s = m.ui.streak
    val lit = s?.today == true
    val canAct = m.ui.link == LinkStatus.OPEN
    val ignite = remember { Animatable(if (lit) 1f else 0f) }
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 260f)) }
    val pump = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var showCalendar by remember { mutableStateOf(false) }
    LaunchedEffect(lit) {
        if (lit && ignite.value < 1f) ignite.animateTo(1f, tween(2200, easing = LinearEasing))
        if (!lit && ignite.value > 0f) ignite.animateTo(0f, tween(1100))
    }
    val inf = rememberInfiniteTransition(label = "gym")
    // El reloj se lee solo al dibujar: así la pantalla se redibuja en cada cuadro, pero no se vuelve a componer (eso daba tirones).
    val clock = inf.animateFloat(0f, 1000f, infiniteRepeatable(tween(1_000_000, easing = LinearEasing), RepeatMode.Restart), label = "reloj")
    val p = ignite.value
    val fire = phase(p, 0.18f, 0.85f)
    val today = Instant.ofEpochMilli(m.wallMs).atZone(ZoneId.systemDefault()).toLocalDate()

    Box(modifier.testTag("gym-screen").drawBehind { drawScene(ignite.value, clock.value) }) {
        Row(Modifier.fillMaxSize()) {
            // El escenario: Vega, la plataforma y el fuego. Tocarla la despierta.
            Box(
                Modifier.weight(1.15f).fillMaxHeight().testTag("gym-vega")
                    .semantics { contentDescription = if (lit) "Vega encendida" else "Despertar a Vega y encender la racha" }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = canAct || lit) {
                        if (!lit) a.streakLight(null)
                        else scope.launch {
                            pump.snapTo(0f)
                            pump.animateTo(1f, tween(700, easing = LinearEasing))
                            pump.snapTo(0f)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // Entra de un salto: crece desde la plataforma con un pequeño rebote.
                    val k = intro.value
                    withTransform({ scale(k, k, Offset(size.width * 0.5f, size.height * 0.8f)) }) { drawStage(ignite.value, clock.value, pump.value) }
                }
                if (!lit) {
                    Text(
                        if (canAct) "Toca a Vega al volver del gimnasio" else "Sin conexión con el PC",
                        maxLines = 1,
                        style = Type2.body(13.sp, Color.White.copy(alpha = 0.9f), FontWeight.SemiBold),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                            .graphicsLayer { alpha = 0.7f + 0.3f * sin(clock.value * 2.6f) } // late despacio (solo se redibuja)
                            .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f))
                            .border(1.dp, Moon.copy(alpha = 0.3f), RoundedCornerShape(50))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
            }
            Panel(
                s, lit, fire, canAct, today, a, { showCalendar = true },
                Modifier.weight(1f).fillMaxHeight().graphicsLayer {
                    val k = intro.value.coerceIn(0f, 1f)
                    translationX = (1f - k) * 60.dp.toPx()
                    alpha = k
                },
            )
        }
        RoundBtn("cancel", 36.dp, Color.White.copy(alpha = 0.12f), Color.White, onClose, Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("gym-close"))
        AnimatedVisibility(showCalendar, enter = fadeIn(tween(150)), exit = fadeOut(tween(150)), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color(0xB3000000)).padding(10.dp)) {
                RachaSheet2(m, a, palFor(m.clock), onClose = { showCalendar = false }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// ───────────────────────── el panel ─────────────────────────

@Composable
private fun Panel(
    s: StreakInfo?, lit: Boolean, fire: Float, canAct: Boolean, today: java.time.LocalDate, a: ScreenActions,
    onCalendar: () -> Unit, modifier: Modifier,
) {
    val count = s?.count ?: 0
    val white = Color.White
    val soft = white.copy(alpha = mix(0.55f, 0.78f, fire))
    val glow = Shadow(lerp(Color(0x00000000), Color(0xCCFF6A00), fire), Offset(0f, 0f), 38f)
    Column(Modifier.then(modifier).padding(start = 6.dp, end = 26.dp, top = 22.dp, bottom = 18.dp), verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Flame(if (lit) 1f else (s?.heat ?: 0f), Modifier.size(width = 14.dp, height = 18.dp), still = true)
            Spacer(Modifier.width(6.dp))
            Text("RACHA DEL GIMNASIO", maxLines = 1, style = Type2.label(soft).copy(letterSpacing = 2.sp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "$count", maxLines = 1,
                style = TextStyle(
                    fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 112.sp, lineHeight = 112.sp,
                    letterSpacing = (-4).sp, color = lerp(Color(0xFFB9B6CC), white, fire), shadow = glow,
                ),
                modifier = Modifier.testTag("gym-count"),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 20.dp)) {
                Text(daysWord(count), maxLines = 1, style = Type2.body(22.sp, white, FontWeight.Bold))
                Text("seguidos", maxLines = 1, style = Type2.body(14.sp, soft))
            }
        }
        // Lo que toca hacer, en una pastilla con su color.
        val warn = !lit && s?.alive == true && s.restLeft == 0
        val chip = when {
            lit -> FireGold
            warn -> Color(0xFFFF5A4E)
            else -> Moon
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(chip.copy(alpha = 0.16f)).border(1.dp, chip.copy(alpha = 0.45f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(chip, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(if (lit) "¡Encendida hoy!" else rachaStatus(s), maxLines = 1, style = Type2.body(13.sp, white, FontWeight.SemiBold), modifier = Modifier.testTag("gym-status"))
        }
        if (lit && canAct) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Apagar hoy", maxLines = 1, style = Type2.body(12.sp, soft),
                modifier = Modifier.testTag("gym-undo").clip(RoundedCornerShape(50)).clickable { a.streakOut(null) }.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        }
        Spacer(Modifier.height(16.dp))
        // La semana: una llamita por cada día que fuiste. Tocarla abre el calendario entero.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ESTA SEMANA", maxLines = 1, style = Type2.label(soft).copy(letterSpacing = 1.5.sp), modifier = Modifier.weight(1f))
            Text(
                "Calendario  ›", maxLines = 1, style = Type2.body(12.sp, white, FontWeight.SemiBold),
                modifier = Modifier.testTag("gym-calendar").clip(RoundedCornerShape(50)).clickable(onClick = onCalendar).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        WeekStrip(s, today, fire, onCalendar)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mejor ", maxLines = 1, style = Type2.body(13.sp, soft))
            Text("${s?.best ?: 0} ${daysWord(s?.best ?: 0)}", maxLines = 1, style = Type2.body(13.sp, white, FontWeight.Bold))
            Box(Modifier.padding(horizontal = 10.dp).size(3.dp).background(soft, CircleShape))
            Text("${s?.total ?: 0} ", maxLines = 1, style = Type2.body(13.sp, white, FontWeight.Bold))
            Text(if ((s?.total ?: 0) == 1) "entreno" else "entrenos", maxLines = 1, style = Type2.body(13.sp, soft))
        }
    }
}

@Composable
private fun WeekStrip(s: StreakInfo?, today: java.time.LocalDate, fire: Float, onClick: () -> Unit) {
    val lit = s?.days.orEmpty().toSet()
    val monday = today.with(DayOfWeek.MONDAY)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf("L", "M", "X", "J", "V", "S", "D").forEachIndexed { i, letter ->
            val day = monday.plusDays(i.toLong())
            val on = day.toString() in lit
            val isToday = day == today
            val future = day.isAfter(today)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape)
                        .background(if (on) FireOrange.copy(alpha = 0.22f) else Color.White.copy(alpha = if (future) 0.02f else 0.05f))
                        .then(if (isToday) Modifier.border(2.dp, if (on) FireGold else Color.White.copy(alpha = 0.7f), CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    if (on) Flame(1f, Modifier.size(width = 16.dp, height = 21.dp), still = true)
                    else Box(Modifier.size(5.dp).background(Color.White.copy(alpha = if (future) 0.12f else 0.28f), CircleShape))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    letter, maxLines = 1,
                    style = Type2.body(11.sp, Color.White.copy(alpha = if (isToday) 0.95f else 0.55f), if (isToday) FontWeight.Bold else FontWeight.Medium)
                        .copy(textAlign = TextAlign.Center),
                )
            }
        }
    }
}

// ───────────────────────── la escena ─────────────────────────

/** El fondo: noche azul que se enciende desde abajo; brasas que suben; rayos de luz detrás de Vega cuando arde. */
private fun DrawScope.drawScene(p: Float, t: Float) {
    val fire = phase(p, 0.18f, 0.85f)
    val w = size.width
    val h = size.height
    // El cielo: de noche, azul profundo; encendido, rojo arriba y oro abajo. El calor sube desde el suelo.
    val rise = phase(p, 0.18f, 0.7f)
    drawRect(
        Brush.verticalGradient(
            0f to lerp(NightTop, Color(0xFF2A0710), fire),
            mix(0.95f, 0.45f, rise) to lerp(NightMid, Color(0xFF8E1712), fire),
            1f to lerp(NightLow, Color(0xFFE2541A), fire),
        ),
    )
    // Un halo detrás de Vega: luna fría dormida, sol de fuego encendida.
    val vx = w * 0.3f
    val vy = h * 0.5f
    drawCircle(
        Brush.radialGradient(
            listOf(lerp(Moon.copy(alpha = 0.16f), FireGold.copy(alpha = 0.55f), fire), Color.Transparent),
            center = Offset(vx, vy), radius = h * mix(0.55f, 0.85f, fire),
        ),
        radius = h * mix(0.55f, 0.85f, fire), center = Offset(vx, vy),
    )
    // Rayos que giran despacio detrás de Vega (solo encendida).
    if (fire > 0.02f) {
        val rays = 10
        for (i in 0 until rays) {
            val ang = (i.toFloat() / rays) * 2f * PI.toFloat() + t * 0.08f
            val spread = 0.09f
            val r = h * 1.1f
            val path = Path().apply {
                moveTo(vx, vy)
                lineTo(vx + cos(ang - spread) * r, vy + sin(ang - spread) * r)
                lineTo(vx + cos(ang + spread) * r, vy + sin(ang + spread) * r)
                close()
            }
            drawPath(path, Brush.radialGradient(listOf(FireCore.copy(alpha = 0.10f * fire), Color.Transparent), Offset(vx, vy), r))
        }
    }
    // Las llamas del suelo, de lado a lado.
    // (solo bajo el escenario: el panel de la derecha queda limpio para leerse)
    if (fire > 0.01f) fireRow(-w * 0.02f, w * 0.56f, h, h * 0.2f * fire, t, fire, n = 7)
    // Brasas que suben: unas motas azules y lentas dormida; chispas de fuego encendida.
    for (i in 0 until 34) {
        val seed = ((i * 7919) % 1000) / 1000f
        val seed2 = ((i * 104729) % 1000) / 1000f
        val asleepMote = i < 12
        val k = if (asleepMote) 1f - fire * 0.6f else fire
        if (k < 0.02f) continue
        val speed = if (asleepMote) 0.012f + 0.01f * seed else 0.07f + 0.09f * seed
        val y = 1.05f - ((t * speed + seed2) % 1.1f)
        val x = seed + sin(t * (0.6f + seed) + i) * 0.02f
        val life = (1f - y).coerceIn(0f, 1f)
        val color = if (asleepMote) Moon else lerp(FireGold, FireRed, life)
        val r = h * (if (asleepMote) 0.004f + 0.004f * seed else 0.003f + 0.006f * seed2)
        val a = k * (if (asleepMote) 0.35f else 0.85f) * (1f - life * 0.8f)
        drawCircle(color.copy(alpha = a), r, Offset(x * w, y * h))
        if (!asleepMote && r > h * 0.006f) drawCircle(color.copy(alpha = a * 0.25f), r * 3f, Offset(x * w, y * h))
    }
    // Viñeta: los bordes más oscuros centran la mirada.
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), Offset(w * 0.45f, h * 0.5f), w * 0.75f))
}

/** Una fila de lenguas de fuego que bailan (tres capas: roja, naranja y oro). */
private fun DrawScope.fireRow(x0: Float, x1: Float, baseY: Float, height: Float, t: Float, k: Float, n: Int = 13, outer: Float = 0.85f) {
    val span = x1 - x0
    for (layer in 0..2) {
        val lk = 1f - layer * 0.3f
        val (top, bottom) = when (layer) {
            0 -> FireRed.copy(alpha = outer * k) to FireOrange.copy(alpha = 0.9f * k)
            1 -> FireOrange.copy(alpha = 0.9f * k) to FireGold.copy(alpha = 0.95f * k)
            else -> FireGold.copy(alpha = 0.9f * k) to FireCore.copy(alpha = k)
        }
        for (i in 0..n) {
            val x = x0 + span * (i + 0.5f * (layer % 2)) / n
            val noise = 0.55f + 0.45f * (sin(t * (4.2f + (i % 5) * 0.8f) + i * 2.3f + layer) * 0.5f + 0.5f)
            val hh = height * lk * noise * (0.75f + 0.25f * sin(i * 1.7f))
            val ww = span / n * 1.7f * lk
            val sway = sin(t * 2.7f + i * 1.3f) * ww * 0.18f
            drawPath(flamePath(x, baseY + 2f, ww, hh, sway, sway * 0.3f), Brush.verticalGradient(listOf(top, bottom), startY = baseY - hh, endY = baseY))
        }
    }
}

// ───────────────────────── Vega ─────────────────────────

/** Una extremidad con forma: dos círculos (de radios distintos) unidos por sus tangentes. */
private fun DrawScope.limb(a: Offset, b: Offset, ra: Float, rb: Float, brush: Brush) {
    val d = b - a
    val len = max(0.001f, d.getDistance())
    val u = d / len
    val n = Offset(-u.y, u.x)
    drawCircle(brush, ra, a)
    drawCircle(brush, rb, b)
    val path = Path().apply {
        moveTo((a + n * ra).x, (a + n * ra).y)
        lineTo((b + n * rb).x, (b + n * rb).y)
        lineTo((b - n * rb).x, (b - n * rb).y)
        lineTo((a - n * ra).x, (a - n * ra).y)
        close()
    }
    drawPath(path, brush)
}

/** El escenario: la plataforma, la onda al encenderse, la corona de fuego, el banco, las mancuernas y Vega Fit. */
private fun DrawScope.drawStage(p: Float, t: Float, pump: Float) {
    val fire = phase(p, 0.18f, 0.85f)
    val U = min(size.width / 2.2f, size.height * 0.8f / 1.95f) // el ancho de la cabeza de Vega
    val cx = size.width * 0.5f
    val floorY = size.height * 0.87f
    // La plataforma: un disco con borde de luz (fría dormida, de fuego encendida).
    val pw = U * 2.25f
    val ph = U * 0.36f
    drawOval(Brush.verticalGradient(listOf(Color(0xFF1C1830), Color(0xFF09080F)), startY = floorY - ph / 2, endY = floorY + ph / 2), Offset(cx - pw / 2, floorY - ph / 2), Size(pw, ph))
    drawOval(lerp(Moon.copy(alpha = 0.35f), FireGold.copy(alpha = 0.95f), fire), Offset(cx - pw / 2, floorY - ph / 2), Size(pw, ph), style = Stroke(width = U * 0.02f))
    drawOval(
        Brush.radialGradient(listOf(lerp(Moon.copy(alpha = 0.10f), FireOrange.copy(alpha = 0.55f), fire), Color.Transparent), Offset(cx, floorY), pw * 0.55f),
        Offset(cx - pw / 2, floorY - ph / 2), Size(pw, ph),
    )
    // La onda al encenderse: un aro que se abre desde la plataforma.
    val wave = phase(p, 0.22f, 0.62f)
    if (wave in 0.01f..0.99f) {
        val ww = pw * (0.6f + 1.6f * wave)
        val wh = ph * (0.6f + 1.6f * wave)
        drawOval(FireCore.copy(alpha = (1f - wave) * 0.9f), Offset(cx - ww / 2, floorY - wh / 2), Size(ww, wh), style = Stroke(width = U * 0.05f * (1f - wave)))
    }
    // La corona de fuego, detrás de todo.
    if (fire > 0.01f) {
        val crown = phase(p, 0.3f, 0.95f)
        fireRow(cx - U * 1.0f, cx + U * 1.0f, floorY - ph * 0.15f, U * 2.25f * crown * (1f + 0.06f * sin(pump * PI.toFloat())), t, crown, n = 7, outer = 0.55f)
    }
    bench(cx, floorY, U, fire)
    dumbbell(Offset(cx - U * 0.92f, floorY - U * 0.075f), U * 0.42f, fire)
    dumbbell(Offset(cx + U * 0.95f, floorY - U * 0.06f), U * 0.38f, fire)
    drawVegaFit(p, t, pump, cx, floorY, U, fire)
}

/** El banco de pesas: asiento acolchado y patas de metal (detrás de Vega). */
private fun DrawScope.bench(cx: Float, floorY: Float, U: Float, fire: Float) {
    val top = floorY - U * 0.42f
    val w = U * 1.42f
    val h = U * 0.11f
    val metal = lerp(Color(0xFF4A4D5C), Color(0xFF6B4A3A), fire)
    for (s in intArrayOf(-1, 1)) {
        val x = cx + s * w * 0.36f
        drawLine(metal, Offset(x, top + h), Offset(x, floorY - U * 0.02f), U * 0.045f, StrokeCap.Round)
        drawLine(metal, Offset(x - U * 0.1f, floorY - U * 0.02f), Offset(x + U * 0.1f, floorY - U * 0.02f), U * 0.04f, StrokeCap.Round)
    }
    drawRoundRect(
        Brush.verticalGradient(listOf(lerp(Color(0xFF3B3650), Color(0xFF5A2A22), fire), Color(0xFF16141F)), startY = top, endY = top + h),
        Offset(cx - w / 2, top), Size(w, h), CornerRadius(h * 0.45f),
    )
    drawRoundRect(Color.White.copy(alpha = 0.10f), Offset(cx - w / 2 + h * 0.4f, top + h * 0.12f), Size(w - h * 0.8f, h * 0.18f), CornerRadius(h * 0.1f))
}

/** Una mancuerna tirada en el suelo: barra y dos discos con brillo de metal. */
private fun DrawScope.dumbbell(c: Offset, len: Float, fire: Float) {
    val bar = lerp(Color(0xFF9AA0AE), Color(0xFFE8B07A), fire * 0.6f)
    drawLine(bar, c - Offset(len / 2, 0f), c + Offset(len / 2, 0f), len * 0.07f, StrokeCap.Round)
    for (s in intArrayOf(-1, 1)) {
        for ((k, hh) in listOf(0.34f to 0.62f, 0.2f to 0.46f)) {
            val x = c.x + s * len * k
            val w = len * 0.11f
            val h = len * hh
            drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3A3946), Color(0xFF15141B)), startY = c.y - h / 2, endY = c.y + h / 2), Offset(x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(w * 0.35f))
            drawRoundRect(lerp(Color.White.copy(alpha = 0.18f), FireGold.copy(alpha = 0.5f), fire), Offset(x - w / 2 + w * 0.15f, c.y - h / 2 + h * 0.06f), Size(w * 0.25f, h * 0.5f), CornerRadius(w * 0.1f))
        }
    }
}

// ── Estilo de dibujo: sombreado de dibujo animado ──
// Cada pieza es UNA forma suave con: color base, una sombra nítida abajo a la derecha (la misma forma desplazada), un brillo
// arriba a la izquierda y un contorno oscuro uniforme. Así se ve como una mascota ilustrada, no como círculos pegados.

private class Tone(val base: Color, val shade: Color)

private fun DrawScope.cel(path: Path, tone: Tone, outline: Color, ow: Float, d: Float, shine: Boolean = true, warm: Float = 0f) {
    drawPath(path, tone.shade)
    clipPath(path) {
        drawPath(Path().apply { addPath(path, Offset(-d, -d)) }, tone.base)
        if (shine) {
            val b = path.getBounds()
            drawOval(Color.White.copy(alpha = 0.32f), Offset(b.left + b.width * 0.16f, b.top + b.height * 0.1f), Size(b.width * 0.3f, b.height * 0.16f))
        }
        if (warm > 0.01f) {
            val b = path.getBounds()
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, FireOrange.copy(alpha = 0.32f * warm)), startY = b.top + b.height * 0.4f, endY = b.bottom), b.topLeft, b.size)
        }
    }
    drawPath(path, outline, style = Stroke(width = ow, join = StrokeJoin.Round, cap = StrokeCap.Round))
}

/**
 * Una «judía»: una extremidad de una sola forma suave, de `a` (radio `ra`) a `b` (radio `rb`), con un abultado `bulge` hacia el lado
 * `up` (el bíceps, la pantorrilla). Las puntas son medias lunas: no se ve ninguna junta.
 */
private fun bean(a: Offset, b: Offset, ra: Float, rb: Float, bulge: Float = 0f, toward: Offset = Offset(0f, -1f)): Path {
    val d = b - a
    val len = max(0.001f, d.getDistance())
    val u = d / len
    var n = Offset(-u.y, u.x)
    if (n.x * toward.x + n.y * toward.y < 0f) n = -n // `n` apunta al lado del abultado
    val ang = Math.toDegrees(kotlin.math.atan2(n.y.toDouble(), n.x.toDouble())).toFloat()
    // El lado de `n` va de `a` a `b`; el otro, de vuelta. Las puntas, medios círculos.
    val sweepB = if (cross(u, n) > 0) -180f else 180f
    return Path().apply {
        val a1 = a + n * ra
        val b1 = b + n * rb
        moveTo(a1.x, a1.y)
        cubicTo(
            a1.x + d.x * 0.3f + n.x * bulge, a1.y + d.y * 0.3f + n.y * bulge,
            b1.x - d.x * 0.3f + n.x * bulge * 0.7f, b1.y - d.y * 0.3f + n.y * bulge * 0.7f,
            b1.x, b1.y,
        )
        arcTo(Rect(b, rb), ang, sweepB, false)
        val a2 = a - n * ra
        val b2 = b - n * rb
        cubicTo(b2.x - d.x * 0.3f, b2.y - d.y * 0.3f, a2.x + d.x * 0.3f, a2.y + d.y * 0.3f, a2.x, a2.y)
        arcTo(Rect(a, ra), ang + 180f, sweepB, false)
        close()
    }
}

private fun cross(a: Offset, b: Offset) = a.x * b.y - a.y * b.x

/**
 * Vega Fit: chibi de cabeza grande, cuerpo pequeño y brazos y piernas cortos y redondos. Dormida está sentada en el banco con la
 * cabeza caída y las manos en las rodillas; al despertar se levanta de un salto, se pone las gafas y saca bíceps como el emoji 💪;
 * luego bombea un brazo y otro y rebota al ritmo.
 */
private fun DrawScope.drawVegaFit(p: Float, t: Float, pump: Float, cx: Float, floorY: Float, U: Float, fire: Float) {
    // ── la coreografía ──
    val wake = phase(p, 0.02f, 0.14f)
    val squat = sin(phase(p, 0.02f, 0.16f) * PI.toFloat())
    val stand = phase(p, 0.12f, 0.40f)
    val jump = sin(phase(p, 0.14f, 0.42f) * PI.toFloat())
    val land = sin(phase(p, 0.40f, 0.56f) * PI.toFloat())
    val wear = backOut(p, 0.26f, 0.48f).coerceIn(0f, 1.1f)
    val flex = backOut(p, 0.42f, 0.72f)
    val awake = phase(p, 0.5f, 0.8f)
    val pk = sin(pump * PI.toFloat())
    val sit = 1f - stand
    val breathe = sin(t * 1.4f) * (1f - wake)
    val beat = (sin(t * 5.6f) * 0.5f + 0.5f) * awake
    val pumpL = max(0f, sin(t * 2.8f)) * awake
    val pumpR = max(0f, sin(t * 2.8f + PI.toFloat())) * awake
    val sy = 1f + 0.02f * breathe - 0.1f * squat - 0.1f * land + 0.08f * jump - 0.03f * beat - 0.06f * pk
    val sx = 1f - 0.01f * breathe + 0.07f * squat + 0.08f * land - 0.05f * jump + 0.015f * beat + 0.04f * pk
    val lift = -U * (0.42f * jump + 0.02f * beat)
    val tilt = (pumpR - pumpL) * 2.5f * awake

    // Colores: de noche, tonos de luna; despierta, dorado. Contorno oscuro cálido (o frío dormida).
    val skin = Tone(lerp(Color(0xFFD6CDA8), Color(0xFFFFC93C), wake), lerp(Color(0xFF8C84A8), Color(0xFFE8902A), wake))
    val shirt = Tone(lerp(Color(0xFF34324A), Color(0xFF2F2E40), wake), Color(0xFF17161F))
    val shortsT = Tone(lerp(Color(0xFF8E4A5E), Color(0xFFF0403A), wake), lerp(Color(0xFF55304A), Color(0xFFB71F1C), wake))
    val shoe = Tone(Color(0xFFFBFBFD), Color(0xFFC9CEDB))
    val ink = lerp(Color(0xFF2B2742), Color(0xFF3E2210), wake)
    val ow = U * 0.032f
    val d = U * 0.045f

    // Sombra en el suelo (se encoge al saltar).
    val shadowK = 1f - 0.45f * jump
    drawOval(Color.Black.copy(alpha = 0.45f * shadowK), Offset(cx - U * 0.5f * shadowK, floorY - U * 0.05f), Size(U * 1.0f * shadowK, U * 0.1f))

    // ── el esqueleto ──
    val drop = U * 0.12f * sit // sentada, todo más abajo
    val waist = floorY - U * 0.5f + drop
    val shY = waist - U * (0.4f + 0.01f * breathe)
    val headC = Offset(cx + U * 0.05f * sit, shY - U * 0.4f + U * 0.05f * sit)
    val headTilt = 10f * sit * (1f - wake) + sin(t * 1.1f) * 2f * (1f - wake)

    withTransform({
        translate(0f, lift)
        scale(sx, sy, Offset(cx, floorY))
        rotate(tilt, Offset(cx, floorY))
    }) {
        // ── piernas y zapatillas ──
        for (s in intArrayOf(-1, 1)) {
            val hip = Offset(cx + s * U * 0.13f, waist + U * 0.06f)
            val ankle = Offset(cx + s * U * mix(0.15f, 0.24f, sit), floorY - U * 0.1f)
            cel(bean(hip, ankle, U * 0.11f, U * 0.085f, bulge = U * 0.03f, toward = Offset(s.toFloat(), 0f)), skin, ink, ow, d, shine = false, warm = fire)
            val sc = Offset(ankle.x + s * U * 0.02f, floorY - U * 0.065f)
            val shoeP = Path().apply { addRoundRect(RoundRect(sc.x - U * 0.15f, sc.y - U * 0.075f, sc.x + U * 0.15f, sc.y + U * 0.065f, CornerRadius(U * 0.07f))) }
            cel(shoeP, shoe, ink, ow, d * 0.6f, shine = false)
            drawRoundRect(Color(0xFF2A2633), Offset(sc.x - U * 0.14f, sc.y + U * 0.02f), Size(U * 0.28f, U * 0.035f), CornerRadius(U * 0.017f))
            drawArc(lerp(Color(0xFF9A6A7A), BandRed, wake), 200f, 120f, false, Offset(sc.x - U * 0.09f, sc.y - U * 0.04f), Size(U * 0.18f, U * 0.09f), style = Stroke(width = U * 0.022f, cap = StrokeCap.Round))
        }
        // ── pantalón corto ──
        val hem = waist + U * mix(0.2f, 0.14f, sit)
        val shorts = Path().apply {
            moveTo(cx - U * 0.24f, waist - U * 0.02f)
            lineTo(cx + U * 0.24f, waist - U * 0.02f)
            quadraticTo(cx + U * 0.29f, hem - U * 0.06f, cx + U * (0.26f + 0.06f * sit), hem)
            quadraticTo(cx + U * 0.13f, hem + U * 0.025f, cx + U * 0.02f, hem)
            quadraticTo(cx, hem - U * 0.06f, cx - U * 0.02f, hem)
            quadraticTo(cx - U * 0.13f, hem + U * 0.025f, cx - U * (0.26f + 0.06f * sit), hem)
            quadraticTo(cx - U * 0.29f, hem - U * 0.06f, cx - U * 0.24f, waist - U * 0.02f)
            close()
        }
        cel(shorts, shortsT, ink, ow, d, shine = false)
        clipPath(shorts) {
            for (s in intArrayOf(-1, 1)) drawLine(Color.White.copy(alpha = 0.92f), Offset(cx + s * U * 0.235f, waist), Offset(cx + s * U * (0.265f + 0.06f * sit), hem), U * 0.026f)
        }

        // ── torso: una forma de pera con la camiseta de tirantes ──
        val torso = Path().apply {
            moveTo(cx - U * 0.3f, shY + U * 0.04f)
            cubicTo(cx - U * 0.2f, shY - U * 0.04f, cx + U * 0.2f, shY - U * 0.04f, cx + U * 0.3f, shY + U * 0.04f)
            cubicTo(cx + U * 0.36f, shY + U * 0.2f, cx + U * 0.3f, waist - U * 0.02f, cx + U * 0.22f, waist + U * 0.01f)
            quadraticTo(cx, waist + U * 0.04f, cx - U * 0.22f, waist + U * 0.01f)
            cubicTo(cx - U * 0.3f, waist - U * 0.02f, cx - U * 0.36f, shY + U * 0.2f, cx - U * 0.3f, shY + U * 0.04f)
            close()
        }
        cel(torso, skin, ink, ow, d, shine = false)
        val tank = Path().apply {
            moveTo(cx - U * 0.17f, shY - U * 0.005f)
            quadraticTo(cx, shY + U * 0.16f, cx + U * 0.17f, shY - U * 0.005f)
            quadraticTo(cx + U * 0.21f, shY + U * 0.16f, cx + U * 0.32f, shY + U * 0.17f)
            cubicTo(cx + U * 0.33f, shY + U * 0.28f, cx + U * 0.28f, waist - U * 0.01f, cx + U * 0.225f, waist + U * 0.015f)
            quadraticTo(cx, waist + U * 0.045f, cx - U * 0.225f, waist + U * 0.015f)
            cubicTo(cx - U * 0.28f, waist - U * 0.01f, cx - U * 0.33f, shY + U * 0.28f, cx - U * 0.32f, shY + U * 0.17f)
            quadraticTo(cx - U * 0.21f, shY + U * 0.16f, cx - U * 0.17f, shY - U * 0.005f)
            close()
        }
        cel(tank, shirt, ink, ow * 0.8f, d, shine = false, warm = fire)
        // La llama de Vega en el pecho.
        val logo = flamePath(cx, shY + U * 0.32f, U * 0.1f, U * 0.13f, sin(t * 3f) * U * 0.006f * awake, 0f)
        drawPath(logo, Brush.verticalGradient(listOf(lerp(Color(0xFF6E6A80), FireRed, wake), lerp(Color(0xFF9A96AA), FireGold, wake)), startY = shY + U * 0.19f, endY = shY + U * 0.32f))

        // ── brazos: una judía para el brazo (con el bíceps en su contorno) y otra para el antebrazo; manos redondas ──
        for (s in intArrayOf(-1, 1)) {
            val mine = if (s < 0) pumpL else pumpR
            val f = flex
            val fl = f.coerceIn(0f, 1f)
            val upDeg = mix(106f, -4f, f) - 8f * mine - 6f * pk
            val foreDeg = mix(108f, 262f, f) - 12f * mine - 10f * pk
            fun dir(deg: Float): Offset {
                val r = Math.toRadians(deg.toDouble())
                return Offset((cos(r) * s).toFloat(), sin(r).toFloat())
            }
            val shoulder = Offset(cx + s * U * 0.28f, shY + U * 0.1f)
            val elbow = shoulder + dir(upDeg) * (U * mix(0.26f, 0.4f, fl))
            val hand = elbow + dir(foreDeg) * (U * mix(0.22f, 0.27f, fl))
            val biceps = U * (0.02f + 0.07f * fl.coerceIn(0f, 1f)) * (1f + 0.25f * mine + 0.3f * pk)
            cel(bean(shoulder, elbow, U * 0.1f, U * 0.08f, bulge = biceps), skin, ink, ow, d, warm = fire)
            cel(bean(elbow, hand, U * 0.075f, U * 0.09f, bulge = U * 0.015f), skin, ink, ow, d, shine = false, warm = fire)
            // Muñequera.
            val fd = (hand - elbow) / max(0.001f, (hand - elbow).getDistance())
            val fn = Offset(-fd.y, fd.x)
            val wrist = elbow + (hand - elbow) * 0.78f
            drawLine(ink, wrist - fn * U * 0.1f, wrist + fn * U * 0.1f, U * 0.07f, StrokeCap.Round)
            drawLine(lerp(Color(0xFF8A3A4A), BandRed, wake), wrist - fn * U * 0.1f, wrist + fn * U * 0.1f, U * 0.045f, StrokeCap.Round)
            // La mano: un óvalo con el pulgar (puño cerrado al flexionar).
            val hc = hand + fd * U * 0.05f
            val handP = Path().apply { addOval(Rect(hc, U * 0.1f)) }
            cel(handP, skin, ink, ow, d * 0.7f, shine = false)
            val thumb = hc + fn * (s * U * 0.07f) - fd * U * 0.02f
            drawArc(ink, 200f, 140f, false, Offset(thumb.x - U * 0.05f, thumb.y - U * 0.03f), Size(U * 0.1f, U * 0.06f), style = Stroke(width = ow * 0.8f, cap = StrokeCap.Round))
            if (mine > 0.6f || pk > 0.4f) sparkle(elbow + Offset(s * U * 0.1f, -U * 0.16f), U * 0.06f * max(mine, pk), FireCore)
        }

        // ── la cabeza ──
        rotate(headTilt, Offset(headC.x, headC.y + U * 0.4f)) {
            vegaHead(headC, U, t, wake, wear, awake, pk, pump, beat, breathe, fire, skin, ink, ow, d)
        }
    }

    // Zetas de dormir, suaves y azuladas.
    if (wake < 0.95f) {
        val a = 1f - wake
        for (k in 0..2) {
            val ph = (t * 0.28f + k / 3f) % 1f
            val x = headC.x + U * (0.45f + 0.11f * k) + sin(t + k) * U * 0.03f
            val y = headC.y - U * (0.45f + 0.5f * ph)
            val z = U * (0.07f + 0.035f * k) * (0.7f + 0.3f * ph)
            val zz = Path().apply { moveTo(x, y); lineTo(x + z, y); lineTo(x, y + z); lineTo(x + z, y + z) }
            drawPath(zz, Color(0xFFC9D3FF).copy(alpha = sin(ph * PI.toFloat()) * 0.85f * a), style = Stroke(width = U * 0.022f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** La cabeza de Vega con el mismo sombreado: el cuadrado de siempre, la cinta, las gafas, las cejas y la boca. `B` es su ancho. */
private fun DrawScope.vegaHead(
    c: Offset, B: Float, t: Float, wake: Float, wear: Float, awake: Float, pk: Float, pump: Float, beat: Float, breathe: Float, fire: Float,
    skin: Tone, ink: Color, ow: Float, d: Float,
) {
    val bw = B
    val bh = B * 0.84f
    val cx = c.x
    val cy = c.y
    val top = cy - bh / 2
    val bottom = cy + bh / 2
    val head = Path().apply { addRoundRect(RoundRect(cx - bw / 2, top, cx + bw / 2, bottom, CornerRadius(B * 0.32f))) }
    drawPath(head, skin.shade)
    clipPath(head) {
        drawPath(Path().apply { addPath(head, Offset(-d * 1.4f, -d * 1.4f)) }, skin.base)
        drawOval(Color.White.copy(alpha = 0.35f), Offset(cx - bw * 0.38f, top + bh * 0.05f), Size(bw * 0.3f, bh * 0.12f))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, FireOrange.copy(alpha = 0.3f * fire)), startY = cy, endY = bottom), Offset(cx - bw, cy), Size(bw * 2, bh))
        // La cinta del pelo, con su raya blanca.
        val by = top + bh * 0.12f
        drawRect(lerp(Color(0xFF9A4A5A), BandRed, wake), Offset(cx - bw, by), Size(bw * 2, bh * 0.14f))
        drawRect(lerp(Color(0xFF6A3044), BandDark, wake), Offset(cx - bw, by + bh * 0.1f), Size(bw * 2, bh * 0.04f))
        drawRect(Color.White.copy(alpha = 0.9f), Offset(cx - bw, by + bh * 0.055f), Size(bw * 2, bh * 0.022f))
        drawLine(ink, Offset(cx - bw, by), Offset(cx + bw, by), ow * 0.7f)
        drawLine(ink, Offset(cx - bw, by + bh * 0.14f), Offset(cx + bw, by + bh * 0.14f), ow * 0.7f)
    }
    drawPath(head, ink, style = Stroke(width = ow * 1.1f, join = StrokeJoin.Round))
    // Las puntas de la cinta, que ondean.
    val kx = cx + bw * 0.49f
    val ky = top + bh * 0.19f
    for (k in 0..1) {
        val speed = 2.2f + 5f * awake
        val wv = sin(t * speed + k * 1.4f) * B * 0.05f
        val len = B * (0.28f - k * 0.06f)
        val tail = Path().apply {
            moveTo(kx, ky - B * 0.035f)
            cubicTo(kx + len * 0.4f, ky - B * 0.06f + wv, kx + len * 0.7f, ky + B * 0.02f * k + wv, kx + len, ky + B * (0.02f + 0.08f * k) + wv * 1.4f)
            lineTo(kx + len * 0.9f, ky + B * (0.09f + 0.08f * k) + wv * 1.4f)
            cubicTo(kx + len * 0.6f, ky + B * 0.06f + wv, kx + len * 0.3f, ky + B * 0.05f, kx, ky + B * 0.04f)
            close()
        }
        drawPath(tail, lerp(Color(0xFF9A4A5A), BandRed, wake))
        drawPath(tail, ink, style = Stroke(width = ow * 0.8f, join = StrokeJoin.Round))
    }
    // Los ojos: los de Vega (píldoras) dormidos como rayitas curvas; despierta, tapados por las gafas.
    val ey = cy - bh * 0.02f
    val gap = B * 0.28f
    if (wear < 0.55f) {
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * gap / 2f
            val w = B * 0.14f
            drawArc(ink, 15f, 150f, false, Offset(ex - w / 2, ey - w * 0.45f), Size(w, w * 0.6f), style = Stroke(width = B * 0.032f, cap = StrokeCap.Round))
        }
    }
    if (awake > 0.05f) {
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * gap / 2f
            val y0 = ey - B * 0.15f
            drawLine(ink.copy(alpha = awake), Offset(ex - side * B * 0.03f, y0 + B * 0.03f), Offset(ex + side * B * 0.09f, y0 - B * 0.01f), B * 0.042f, StrokeCap.Round)
        }
    }
    // Las gafas: en la frente dormida; caen con rebote a los ojos al despertar.
    val restY = top + bh * 0.06f
    val gy = restY + (ey - restY) * wear
    val gScale = 0.82f + 0.18f * wear.coerceIn(0f, 1f)
    rotate(-9f * (1f - wear.coerceIn(0f, 1f)), Offset(cx, gy)) {
        glasses(cx, gy, B * gScale, fire, t, wear.coerceIn(0f, 1f))
    }
    // La boca.
    val my = cy + bh * 0.24f
    if (awake < 0.5f) {
        val o = B * (0.026f + 0.007f * breathe)
        drawOval(ink, Offset(cx - o, my - o * 0.8f), Size(o * 2, o * 1.6f))
        if (wake < 0.5f) {
            val cyc = (t * 0.42f) % 1f
            val r = B * (0.025f + 0.1f * cyc)
            val bc = Offset(cx + B * 0.08f + r * 0.6f, my - r * 0.4f)
            val k = (1f - wake * 2f).coerceIn(0f, 1f)
            drawCircle(Color(0xFFCFE3FF).copy(alpha = 0.25f * k), r, bc)
            drawCircle(Color.White.copy(alpha = 0.6f * k), r, bc, style = Stroke(width = B * 0.008f))
            drawCircle(Color.White.copy(alpha = 0.85f * k), r * 0.22f, bc + Offset(-r * 0.35f, -r * 0.35f))
        }
    } else {
        val mw = B * 0.3f * (0.92f + 0.08f * beat)
        val mh = B * (0.14f + 0.03f * pk)
        val mouth = Path().apply {
            moveTo(cx - mw / 2, my - mh * 0.25f)
            quadraticTo(cx, my - mh * 0.38f, cx + mw / 2, my - mh * 0.25f)
            cubicTo(cx + mw * 0.46f, my + mh * 0.55f, cx + mw * 0.18f, my + mh * 0.8f, cx, my + mh * 0.8f)
            cubicTo(cx - mw * 0.18f, my + mh * 0.8f, cx - mw * 0.46f, my + mh * 0.55f, cx - mw / 2, my - mh * 0.25f)
            close()
        }
        drawPath(mouth, Color(0xFF3B1710))
        clipPath(mouth) {
            drawRect(Color.White, Offset(cx - mw / 2, my - mh * 0.45f), Size(mw, mh * 0.34f))
            drawOval(Color(0xFFFF6F7F), Offset(cx - mw * 0.25f, my + mh * 0.35f), Size(mw * 0.5f, mh * 0.6f))
        }
        drawPath(mouth, ink, style = Stroke(width = ow * 0.8f, join = StrokeJoin.Round))
        for (side in intArrayOf(-1, 1)) {
            drawOval(Color(0xFFFF6B8A).copy(alpha = 0.4f * awake), Offset(cx + side * B * 0.31f - B * 0.06f, my - B * 0.08f), Size(B * 0.12f, B * 0.065f))
        }
        if (pk > 0.1f) {
            val dd = Offset(cx + bw * 0.38f, top + bh * (0.34f + 0.12f * pump))
            drawCircle(Color(0xFF8FD3FF).copy(alpha = pk), B * 0.025f, dd)
            drawPath(Path().apply { moveTo(dd.x, dd.y - B * 0.06f); lineTo(dd.x - B * 0.022f, dd.y - B * 0.01f); lineTo(dd.x + B * 0.022f, dd.y - B * 0.01f); close() }, Color(0xFF8FD3FF).copy(alpha = pk))
        }
    }
}

/** Gafas deportivas envolventes: cristal oscuro que refleja el fuego, con un brillo que cruza. */
private fun DrawScope.glasses(cx: Float, cy: Float, B: Float, fire: Float, t: Float, wear: Float) {
    fun px(x: Float) = cx + x * B
    fun py(y: Float) = cy + y * B
    val shape = Path().apply {
        moveTo(px(-0.34f), py(-0.09f))
        quadraticTo(px(0f), py(-0.12f), px(0.34f), py(-0.09f))
        cubicTo(px(0.36f), py(0.03f), px(0.25f), py(0.11f), px(0.14f), py(0.1f))
        cubicTo(px(0.06f), py(0.095f), px(0.045f), py(0.035f), px(0f), py(0.035f))
        cubicTo(px(-0.045f), py(0.035f), px(-0.06f), py(0.095f), px(-0.14f), py(0.1f))
        cubicTo(px(-0.25f), py(0.11f), px(-0.36f), py(0.03f), px(-0.34f), py(-0.09f))
        close()
    }
    drawPath(shape, Color(0xFF0E0D16), style = Stroke(width = B * 0.03f, join = StrokeJoin.Round))
    drawPath(
        shape,
        Brush.verticalGradient(
            listOf(Color(0xFF1A1D3A), Color(0xFF2A1440), lerp(Color(0xFF3A2C6A), Color(0xFFFF8A2A), fire)),
            startY = py(-0.11f), endY = py(0.11f),
        ),
    )
    clipPath(shape) {
        // El brillo: una franja que cruza el cristal cada pocos segundos (y otra fina, fija).
        val sweep = ((t * 0.35f) % 1.4f) - 0.2f
        val x = px(-0.45f + 0.9f * sweep)
        drawPath(
            Path().apply { moveTo(x, py(-0.14f)); lineTo(x + B * 0.08f, py(-0.14f)); lineTo(x - B * 0.02f, py(0.14f)); lineTo(x - B * 0.1f, py(0.14f)); close() },
            Color.White.copy(alpha = 0.35f * wear),
        )
        drawLine(Color.White.copy(alpha = 0.5f), Offset(px(-0.27f), py(-0.05f)), Offset(px(-0.2f), py(-0.07f)), B * 0.02f, StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.5f), Offset(px(0.1f), py(-0.05f)), Offset(px(0.17f), py(-0.07f)), B * 0.02f, StrokeCap.Round)
    }
}

/** Un destello de cuatro puntas. */
private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    if (r < 0.5f) return
    val path = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(path, color)
}

/** La llama pequeña de la cara de Vega con el número de días: siempre a la vista; al tocarla, el gimnasio. */
@Composable
internal fun FaceFlame(s: StreakInfo?, text: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val count = s?.count ?: 0
    val warn = s != null && s.alive && !s.today && s.restLeft == 0
    Column(
        modifier.origin { Origins.flame = it }.testTag("face-racha").semantics { contentDescription = "Racha del gimnasio: $count ${daysWord(count)}" }
            .clip(RoundedCornerShape(18.dp))
            .background(text.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Flame(s?.heat ?: 0f, Modifier.size(width = 34.dp, height = 44.dp), still = s?.today != true)
        Text("$count", maxLines = 1, style = Type2.num(22.sp, text).copy(fontWeight = FontWeight.Bold))
        if (warn) Text("¡hoy!", maxLines = 1, style = Type2.body(11.sp, text, FontWeight.Bold))
    }
}
