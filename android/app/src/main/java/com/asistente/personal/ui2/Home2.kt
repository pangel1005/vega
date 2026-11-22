package com.asistente.personal.ui2

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.asistente.personal.ui.agoLabel
import com.asistente.personal.ui.hoursLabel
import kotlin.math.abs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import kotlinx.coroutines.launch
import com.asistente.personal.ui.BrainLoad
import com.asistente.personal.ui.NOTE_PEEK_MS
import com.asistente.personal.ui.brainLoad
import com.asistente.personal.ui.timeAgo
import com.asistente.personal.ui.LayoutArgs
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.attention
import com.asistente.personal.ui.cached
import com.asistente.personal.ui.clockTime
import com.asistente.personal.ui.countdown
import com.asistente.personal.ui.es
import com.asistente.personal.ui.instantPress
import com.asistente.personal.ui.keyState
import com.asistente.personal.ui.lastLine
import com.asistente.personal.ui.liveSession
import com.asistente.personal.ui.livePosition
import com.asistente.personal.ui.lowBattery
import com.asistente.personal.ui.moodLabel
import com.asistente.personal.ui.sessionClock
import com.asistente.personal.ui.timerLeft
import com.asistente.personal.ui.led.countdownText

/*
 * Pantalla principal del nuevo diseño (800 × 360 dp):
 *
 *   ┌───────────────┬──────────┬──────────────┐
 *   │               │  hora    │  PC          │
 *   │   la cara     ├──────────┴──────────────┤
 *   │ (y lo que     │  ahora: aviso, música o  │
 *   │   dice)       │  juego                   │
 *   ├───────────────┴─────────────────────────┤
 *   │  teclas                                  │
 *   └──────────────────────────────────────────┘
 */

@Composable
internal fun ColumnScope.HomeLayout2(x: LayoutArgs) {
    val p = palFor(x.m.clock)
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FaceTile(x, p, Modifier.weight(0.42f).fillMaxHeight())
        Column(Modifier.weight(0.58f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(0.47f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ClockTile(x, p, Modifier.weight(0.46f).fillMaxHeight().cached())
                PcTile(x, p, Modifier.weight(0.54f).fillMaxHeight().cached())
            }
            NowTile(x, p, Modifier.weight(0.53f).fillMaxWidth().cached())
        }
    }
    Dock2(x, p)
}

private val TileShape = RoundedCornerShape(30.dp)

// ───────────────────────── la cara ─────────────────────────

private fun replyOf(m: ScreenModel): Triple<String, String, Boolean> {
    val ui = m.ui
    val pending = ui.pending
    val notice = ui.notice
    return when {
        ui.link == LinkStatus.DENIED || !m.hasToken -> Triple("", "Sin token válido: ábrela desde el PC con scripts/android-install.ps1", true)
        ui.link != LinkStatus.OPEN && !m.brainOnPhone -> {
            val retry = ui.retryInMs?.let { " · reintento en ${(it + 999) / 1000} s" } ?: ""
            // Con el cable puesto el problema no es el cable: el servidor de Vega está apagado o no responde.
            val what = if (m.usbPlugged) "El PC no responde. ¿Está Vega encendida en el PC?" else "Sin cable USB al PC. Conéctalo para seguir."
            Triple("", what + retry, true)
        }
        pending != null -> Triple("Confirma", "${pending.message} Toca la tecla otra vez o di «sí».", true)
        notice != null -> Triple(if (notice.ok) "Aviso" else "Error", notice.text, !notice.ok)
        else -> lastLine(ui.messages, ui.assistant).let { Triple(if (it.who.startsWith("Tú")) it.who else "", it.text, it.who == "Error") }
    }
}

@Composable
private fun FaceTile(x: LayoutArgs, p: Pal, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val base = when {
        ui.game != null -> m.gameAccent
        ui.media != null -> m.mediaAccent
        else -> p.sun
    }
    val accent = faceAccent(p, x.mood, base)
    val bg by animateColorAsState(if (p.night) p.tile else accent, tween(450), label = "mosaico")
    val glow by animateColorAsState(accent, tween(450), label = "luz")
    // Sobre un mosaico de color, los ojos y el texto son tinta (o crema si el color es oscuro); de noche, los ojos son la luz.
    val onBg = if (bg.luminance() > 0.42f) p.ink else Color(0xFFFDF8EE)
    val ink = if (p.night) glow else onBg
    // El texto de un mosaico de color es el que más contraste da (tinta o crema); los ojos siguen siendo `onBg`, su sello.
    val text = if (p.night) p.ink else if (contrast(p.ink, bg) >= contrast(Color(0xFFFDF8EE), bg)) p.ink else Color(0xFFFDF8EE)
    val micOn = m.listening && m.micOn
    val linked = ui.link == LinkStatus.OPEN || m.brainOnPhone
    val (who, line, bad) = replyOf(m)
    var sleepHint by remember { mutableStateOf(false) }
    LaunchedEffect(sleepHint) { if (sleepHint) { delay(1_800); sleepHint = false } }

    Box(modifier.attention(x)) {
        // Lo que casi no cambia (el mosaico, la cabecera y lo que dice) se guarda como imagen: no se vuelve a dibujar cuando la
        // cara se mueve. La cara va encima, que es lo único que se anima.
        Box(Modifier.fillMaxSize().cached()) {
        Box(Modifier.fillMaxSize().clip(TileShape).background(bg))
        // Arriba: qué hace Vega y los botones del micro y de dormir.
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(text, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(if (sleepHint) "Mantén para dormir" else moodLabel(x.mood, ui.assistant), maxLines = 1, style = Type2.body(13.sp, text, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            val cam = ui.camera
            if (cam.available) {
                // La cámara del Redmi como cámara de Windows: verde encendida; apagada, discreta; atenuada mientras cambia.
                RoundBtn(
                    "camera", 34.dp,
                    if (cam.on) Color(0xFF3FC79A) else text.copy(alpha = 0.14f), if (cam.on) Color.White else text, x.a.toggleCamera,
                    Modifier.graphicsLayer { alpha = if (cam.busy) 0.45f else 1f }.semantics { contentDescription = if (cam.on) "Apagar cámara" else "Encender cámara" },
                )
            }
            RoundBtn(
                "mic", 34.dp, if (micOn) text else text.copy(alpha = 0.14f), if (micOn) bg else text, x.a.toggleMic,
                Modifier.graphicsLayer { alpha = if (linked) 1f else 0.4f }.semantics { contentDescription = if (micOn) "Apagar micrófono" else "Encender micrófono" },
            )
            // Dormir pide mantener el dedo (un fallo al pulsar el micro o la cámara ya no duerme a Vega).
            HoldBtn("sleep", 34.dp, text.copy(alpha = 0.14f), text, 450, x.a.sleep, { sleepHint = true }, Modifier.semantics { contentDescription = "Dormir" })
        }
        // Abajo: lo que dice (y, encima, lo que le pediste).
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 18.dp, end = 16.dp, bottom = 14.dp)) {
            val load = if (m.brainOnPhone) brainLoad(m.brainLabel) else BrainLoad.None
            when {
                x.confirmFraction != null -> Capsule(x.confirmFraction, text, text.copy(alpha = 0.25f), Modifier.fillMaxWidth().padding(bottom = 8.dp), h = 6.dp)
                load.busy -> Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Capsule(load.fraction ?: 0.35f, text, text.copy(alpha = 0.18f), Modifier.weight(1f), h = 6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(m.brainLabel.removePrefix("IA: "), maxLines = 1, style = Type2.body(12.sp, text.copy(alpha = 0.7f)))
                }
                load.failed -> Text(
                    "La IA del teléfono falló · Reintentar", maxLines = 1, style = Type2.body(12.sp, text, FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 8.dp).clip(RoundedCornerShape(50)).background(text.copy(alpha = 0.14f)).clickable { x.a.retryBrain() }.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
            Crossfade(targetState = Triple(who, line, bad), animationSpec = tween(300), label = "respuesta") { (w, l, b) ->
                Column {
                    if (w.isNotEmpty()) Text(w, maxLines = 1, style = Type2.body(12.sp, text.copy(alpha = 0.8f)))
                    Text(l, maxLines = 2, style = Type2.body(15.sp, if (b && !p.night) p.ink else text, FontWeight.Medium, 18.sp))
                }
            }
        }
        }
        Face2(
            mood = x.mood, pulse = m.pulse, pulseAmount = m.pulseAmount, glance = x.glance, ink = ink, night = p.night,
            hot = (ui.telemetry?.gpuTemp ?: 0) >= 78, low = lowBattery(m.battery, m.charging), game = ui.game != null,
            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, top = 38.dp, bottom = 78.dp),
        )
        // La racha del gimnasio vive en la cara: una llama con los días; al tocarla, Vega en el gimnasio.
        FaceFlame(ui.streak, text, x.onRacha, Modifier.align(Alignment.CenterEnd).padding(end = 6.dp, bottom = 22.dp))

    }
}

// ───────────────────────── hora y estado ─────────────────────────

@Composable
private fun ClockTile(x: LayoutArgs, p: Pal, modifier: Modifier) {
    val m = x.m
    val (link, linkColor) = when (m.ui.link) {
        LinkStatus.OPEN -> "En línea" to p.mint
        LinkStatus.CONNECTING -> "Conectando…" to p.sun
        LinkStatus.RECONNECTING -> "Reconectando…" to p.sun
        LinkStatus.DENIED -> "Acceso denegado" to p.red
    }
    Column(modifier.clip(TileShape).background(p.tile).padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Text(m.clock, maxLines = 1, style = Type2.num(if (m.updateAvailable) 46.sp else 52.sp, p.ink))
        Spacer(Modifier.height(3.dp))
        Text(m.date, maxLines = 1, style = Type2.body(13.sp, p.ink2))
        Spacer(Modifier.weight(1f))
        if (m.updateAvailable) {
            Text(
                "Actualizar", maxLines = 1, style = Type2.body(12.sp, Color.White, FontWeight.Bold),
                modifier = Modifier.clip(RoundedCornerShape(50)).background(p.coral).clickable { x.a.update() }.padding(horizontal = 14.dp, vertical = 4.dp),
            )
        } else Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(linkColor, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(link, maxLines = 1, style = Type2.body(12.sp, p.ink2))
        }
    }
}

/** Color de un medidor: el suyo mientras va bien, ámbar cerca del límite y rojo en él (al revés con la batería). */
internal fun heat(p: Pal, f: Float, base: Color, invert: Boolean = false): Color = when {
    !invert && f >= 0.9f || invert && f <= 0.15f -> p.red
    !invert && f >= 0.75f || invert && f <= 0.3f -> p.sun
    else -> base
}

@Composable
internal fun GaugeRow(p: Pal, name: String, value: String, f: Float?, base: Color, modifier: Modifier = Modifier, invert: Boolean = false) {
    val shown by animateFloatAsState((f ?: 0f).coerceIn(0f, 1f), tween(700), label = "medidor")
    val color = heat(p, f ?: 0f, base, invert)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(name, maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.width(50.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawRoundRect(p.tile2, size = size, cornerRadius = CornerRadius(size.height / 2))
            if (f != null) {
                val w = maxOf(size.height, size.width * shown)
                drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
            }
        }
        Text(value, maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.SemiBold).copy(textAlign = TextAlign.End), modifier = Modifier.width(44.dp).padding(start = 6.dp))
    }
}

@Composable
private fun PcTile(x: LayoutArgs, p: Pal, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val t = ui.telemetry
    // Las últimas lecturas (una cada pocos segundos) para dibujar cómo ha ido el PC en el último rato.
    val history = remember { PcHistory() }
    LaunchedEffect(ui.telemetryAt) { t?.let { history.add(it.cpu, it.gpuUtil ?: 0) } }
    if (pcCalm(t, ui.game != null)) {
        PcCalm(p, t!!, history, modifier)
        return
    }
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
    val bat = if (m.battery >= 0) m.battery / 100f else null
    val cpu = Triple("CPU", t?.cpu?.let { "$it %" } ?: "—", t?.cpu?.div(100f))
    val temp = Triple("GPU", t?.gpuTemp?.let { "$it°" } ?: "—", t?.gpuTemp?.let { ((it - 30) / 60f).coerceIn(0f, 1f) })
    val use = Triple("USO", t?.gpuUtil?.let { "$it %" } ?: "—", t?.gpuUtil?.div(100f))
    val video = Triple("VÍDEO", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", vram)
    Column(modifier.clip(TileShape).background(p.tile).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        if (ui.game != null) {
            GaugeRow(p, use.first, use.second, use.third, ui.game.let { m.gameAccent }, Modifier.fillMaxWidth())
            GaugeRow(p, temp.first, temp.second, temp.third, p.sun, Modifier.fillMaxWidth())
        } else {
            GaugeRow(p, cpu.first, cpu.second, cpu.third, p.sky, Modifier.fillMaxWidth())
            GaugeRow(p, temp.first, temp.second, temp.third, p.sun, Modifier.fillMaxWidth())
        }
        GaugeRow(p, video.first, video.second, video.third, p.mint, Modifier.fillMaxWidth())
        GaugeRow(p, "BAT", bat?.let { "${m.battery} %" } ?: "—", bat, if (m.charging) p.mint else p.lilac, Modifier.fillMaxWidth(), invert = true)
    }
}

/**
 * ¿El PC va tranquilo? Sin juego en marcha, con el procesador por debajo del 70 %, la gráfica por debajo de 75 grados y el vídeo
 * por debajo del 90 %. Entonces el mosaico no enseña cuatro barras: dice «PC bien» y dibuja cómo ha ido el último rato. En cuanto
 * algo sube (o hay un juego), vuelven las barras, que enseñan cuál.
 */
internal fun pcCalm(t: com.asistente.personal.net.Telemetry?, gaming: Boolean): Boolean {
    if (t == null || gaming) return false
    val vram = if (t.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) t.vramUsed / t.vramTotal else 0.0
    return t.cpu < 70 && (t.gpuTemp ?: 0) < 75 && vram < 0.9
}

/** Las últimas lecturas del PC (procesador y uso de la gráfica, de 0 a 100), las más nuevas al final. */
internal class PcHistory(private val max: Int = 60) {
    val cpu = mutableStateListOf<Int>()
    val gpu = mutableStateListOf<Int>()

    fun add(cpuNow: Int, gpuNow: Int) {
        cpu.add(cpuNow)
        gpu.add(gpuNow)
        while (cpu.size > max) cpu.removeAt(0)
        while (gpu.size > max) gpu.removeAt(0)
    }
}

@Composable
internal fun PcCalm(p: Pal, t: com.asistente.personal.net.Telemetry, history: PcHistory, modifier: Modifier) {
    val vramLine = if (t.vramUsed != null && t.vramTotal != null) " · vídeo ${"%.1f".format(es, t.vramUsed)} de ${"%.0f".format(es, t.vramTotal)} GB" else ""
    Column(modifier.clip(TileShape).background(p.tile).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).background(p.mint, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("PC bien", maxLines = 1, style = Type2.body(17.sp, p.ink, FontWeight.SemiBold), modifier = Modifier.weight(1f))
            Text("${t.cpu} %", maxLines = 1, style = Type2.num(24.sp, p.ink2))
        }
        Spacer(Modifier.weight(1f))
        Sparkline(history.cpu.toList(), history.gpu.toList(), p, Modifier.fillMaxWidth().height(32.dp))
        Spacer(Modifier.height(6.dp))
        Text("GPU ${t.gpuTemp ?: "—"}°$vramLine", maxLines = 1, style = Type2.body(12.sp, p.ink2))
    }
}

/** Dos líneas finas (procesador y gráfica) de lo ocurrido hasta ahora; la más nueva, a la derecha. */
@Composable
private fun Sparkline(cpu: List<Int>, gpu: List<Int>, p: Pal, modifier: Modifier) {
    Canvas(modifier) {
        val base = size.height - 1.dp.toPx()
        drawLine(p.tile2, Offset(0f, base), Offset(size.width, base), 1.dp.toPx())
        fun line(values: List<Int>, color: Color) {
            if (values.size < 2) return
            val path = androidx.compose.ui.graphics.Path()
            values.forEachIndexed { i, v ->
                val px = size.width * i / (values.size - 1)
                val py = base - (size.height - 3.dp.toPx()) * (v.coerceIn(0, 100) / 100f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
        line(gpu, p.sun)
        line(cpu, p.sky)
    }
}

// ───────────────────────── lo de ahora ─────────────────────────

@Composable
internal fun RoundBtn(icon: String, size: Dp, bg: Color, fg: Color, onClick: () -> Unit, modifier: Modifier = Modifier, hit: Dp = 48.dp) {
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed.value) 0.9f else 1f, spring(dampingRatio = 0.55f, stiffness = 1500f), label = "pulsar")
    // El círculo se dibuja del tamaño `size`, pero la zona que responde al dedo es de `hit` (48 dp, lo que pide Android).
    Box(modifier.size(maxOf(size, hit)).instantPress(enabled = true, pressed = pressed, onPress = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).graphicsLayer { scaleX = scale; scaleY = scale }.background(bg, CircleShape), contentAlignment = Alignment.Center) {
            Icon2(icon, fg, size * 0.5f)
        }
    }
}

/**
 * Un botón redondo que solo actúa si se mantiene el dedo `holdMs` milisegundos (se ve un aro que se llena). Para lo que no debe
 * pasar por un roce: dormir a Vega. Si se suelta antes, avisa con `onEarly`. Con TalkBack se activa con un toque normal.
 */
@Composable
internal fun HoldBtn(
    icon: String, size: Dp, bg: Color, fg: Color, holdMs: Int, onHold: () -> Unit, onEarly: () -> Unit,
    modifier: Modifier = Modifier, hit: Dp = 48.dp,
) {
    val progress = remember { Animatable(0f) }
    var held by remember { mutableStateOf(false) }
    var fired by remember { mutableStateOf(false) }
    val view = LocalView.current
    val hold = rememberUpdatedState(onHold)
    val early = rememberUpdatedState(onEarly)
    LaunchedEffect(held) {
        if (held) {
            progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
            fired = true
            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            hold.value()
        } else {
            progress.snapTo(0f)
        }
    }
    Box(
        modifier.size(maxOf(size, hit)).semantics(mergeDescendants = true) {
            role = androidx.compose.ui.semantics.Role.Button
            onClick { hold.value(); true }
        }.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false).consume()
                fired = false
                held = true
                waitForUpOrCancellation()
                held = false
                if (!fired) early.value()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).background(bg, CircleShape).drawWithContent {
                drawContent()
                if (progress.value > 0f) {
                    val w = 3.dp.toPx()
                    drawArc(fg, -90f, 360f * progress.value, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
                }
            },
            contentAlignment = Alignment.Center,
        ) { Icon2(icon, fg, size * 0.5f) }
    }
}

@Composable
internal fun Cover(art: ImageBitmap?, accent: Color, glyph: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.2f)), contentAlignment = Alignment.Center) {
        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.55f)))))
            Icon2(glyph, Color.White.copy(alpha = 0.9f), size * 0.42f)
        }
    }
}

@Composable
internal fun Capsule(f: Float, color: Color, track: Color, modifier: Modifier = Modifier, h: Dp = 6.dp) {
    Canvas(modifier.height(h)) {
        drawRoundRect(track, size = size, cornerRadius = CornerRadius(size.height / 2))
        val w = maxOf(size.height, size.width * f.coerceIn(0f, 1f))
        drawRoundRect(color, size = Size(w, size.height), cornerRadius = CornerRadius(size.height / 2))
    }
}

/** Lo que ocupan los puntos de página arriba a la derecha del mosaico: los títulos de cada página dejan ese hueco. */
private val LocalDotsInset = androidx.compose.runtime.staticCompositionLocalOf { 0.dp }

private enum class NowPage(val label: String) { AVISO("Aviso"), TAREAS("Moodle"), NOTAS("PC"), JUEGOS("Juegos"), MUSICA("Música"), JUEGO("Juego") }

/**
 * El mosaico de «lo de ahora»: páginas (aviso, avisos del PC, tus últimos juegos, música, juego) que se deslizan con el dedo:
 * el contenido va pegado al dedo y, al soltar, encaja en la página más cercana (o en la siguiente, si fue un empujón rápido).
 * Pasa solo a lo que empieza y se asoma a los avisos nuevos; también se cambia tocando los puntos.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NowTile(x: LayoutArgs, p: Pal, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    val pages = buildList {
        add(NowPage.AVISO)
        if (!ui.tasks.isNullOrEmpty()) add(NowPage.TAREAS)
        if (ui.pcNotes.isNotEmpty()) add(NowPage.NOTAS)
        if (ui.game == null) add(NowPage.JUEGOS)
        if (ui.media != null) add(NowPage.MUSICA)
        if (ui.game != null) add(NowPage.JUEGO)
    }
    var chosen by remember { mutableStateOf(if (ui.game != null) NowPage.JUEGO else if (ui.media != null) NowPage.MUSICA else NowPage.AVISO) }
    LaunchedEffect(ui.media != null) { if (ui.media != null) chosen = NowPage.MUSICA else if (chosen == NowPage.MUSICA) chosen = NowPage.AVISO }
    LaunchedEffect(ui.game?.key) { if (ui.game != null) chosen = NowPage.JUEGO else if (chosen == NowPage.JUEGO) chosen = NowPage.AVISO }
    // Al llegar un aviso nuevo del PC, el mosaico se asoma a él unos segundos y vuelve a lo que estaba.
    val newest = ui.pcNotes.firstOrNull { it.id in ui.freshNotes }?.id
    var before by remember { mutableStateOf(NowPage.AVISO) }
    LaunchedEffect(newest) {
        if (newest == null) return@LaunchedEffect
        if (chosen != NowPage.NOTAS) before = chosen
        chosen = NowPage.NOTAS
        kotlinx.coroutines.delay(NOTE_PEEK_MS)
        if (chosen == NowPage.NOTAS) chosen = before
    }

    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = pages.indexOf(chosen).coerceAtLeast(0)) { pages.size }
    val latestPages = androidx.compose.runtime.rememberUpdatedState(pages)
    // Lo que se elige con el dedo pasa a ser lo elegido (cuando la página se asienta)…
    LaunchedEffect(pager) {
        androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { i -> latestPages.value.getOrNull(i)?.let { chosen = it } }
    }
    // …y lo que cambia por sí solo (empieza la música, llega un aviso) mueve el paginador.
    LaunchedEffect(chosen, pages) {
        val i = pages.indexOf(chosen)
        if (i >= 0 && i != pager.currentPage) pager.animateScrollToPage(i)
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val page = pages.getOrNull(pager.currentPage) ?: NowPage.AVISO

    Box(modifier.origin { Origins.nowTile = it }.clip(TileShape).background(p.tile)) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        var dotsWidth by remember { mutableStateOf(0.dp) }
        androidx.compose.foundation.pager.HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize(), key = { pages.getOrNull(it)?.name ?: it },
        ) { i ->
            androidx.compose.runtime.CompositionLocalProvider(LocalDotsInset provides if (pages.size > 1) dotsWidth + 8.dp else 0.dp) {
            when (pages.getOrNull(i) ?: NowPage.AVISO) {
                NowPage.MUSICA -> NowMusic(x, p)
                NowPage.JUEGO -> NowGame(x, p)
                NowPage.JUEGOS -> NowGames(x, p)
                NowPage.NOTAS -> NowNotes(x, p)
                NowPage.TAREAS -> NowTasks(x, p)
                NowPage.AVISO -> NowRest(x, p)
            }
            }
        }
        if (pages.size > 1) {
            Row(
                Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 12.dp)
                    .onSizeChanged { dotsWidth = with(density) { it.width.toDp() } + 12.dp },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.forEachIndexed { i, pg ->
                    if (pg == page) {
                        Text(
                            pg.label, maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.SemiBold),
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(p.tile2).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    } else {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape).semantics { contentDescription = pg.label }
                                .clickable { scope.launch { pager.animateScrollToPage(i) } },
                            contentAlignment = Alignment.Center,
                        ) { Box(Modifier.size(6.dp).background(p.ink3.copy(alpha = 0.6f), CircleShape)) }
                    }
                }
            }
        }
    }
}

/** Las entregas de Moodle: la próxima con su cuenta atrás, cuántas van vencidas y las que siguen. Tocarla abre el tablero. */
@Composable
private fun NowTasks(x: LayoutArgs, p: Pal) {
    val m = x.m
    val now = m.wallMs / 1000
    val items = m.ui.tasks.orEmpty()
    val overdue = items.count { it.due <= now }
    val upcoming = items.filter { it.due > now }.sortedBy { it.due }
    val next = upcoming.firstOrNull()
    Row(
        Modifier.fillMaxSize().testTag("now-tasks")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onTablero)
            .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("PRÓXIMA ENTREGA", maxLines = 1, style = Type2.label(p.ink3))
            Spacer(Modifier.weight(1f))
            if (next != null) {
                val soon = next.due - now < 24 * 3600
                Text(bigCountdown(next.due, now), maxLines = 1, style = Type2.num(40.sp, if (soon) p.readable(p.coral) else p.ink))
                Spacer(Modifier.height(4.dp))
                Text(next.title, maxLines = 1, style = Type2.body(14.sp, p.ink, FontWeight.SemiBold))
                if (next.course.isNotEmpty()) Text(next.course, maxLines = 1, style = Type2.body(12.sp, p.ink3))
            } else {
                Text("Al día", maxLines = 1, style = Type2.num(30.sp, p.ink))
                Spacer(Modifier.height(4.dp))
                Text(if (overdue > 0) "Solo quedan vencidas por revisar" else "Sin entregas pendientes", maxLines = 1, style = Type2.body(13.sp, p.ink2))
            }
        }
        Column(Modifier.weight(0.9f).fillMaxHeight().padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            if (overdue > 0) {
                Text(
                    "$overdue vencida${if (overdue == 1) "" else "s"}", maxLines = 1, style = Type2.body(12.sp, Color.White, FontWeight.SemiBold),
                    modifier = Modifier.background(p.red, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            upcoming.drop(1).take(if (overdue > 0) 1 else 2).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        dueLabel(t.due, now), maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.SemiBold),
                        modifier = Modifier.background(p.tile2, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(t.title, maxLines = 1, style = Type2.body(13.sp, p.ink2))
                }
            }
        }
    }
}

/**
 * Las notificaciones del PC: las dos últimas, cada una en su fila con aire (quién, qué, hace cuánto y su «×»). Arriba, cuántas hay;
 * tocar la tarjeta abre todas (agrupadas por app, con «Borrar todos»).
 */
@Composable
private fun NowNotes(x: LayoutArgs, p: Pal) {
    val m = x.m
    val notes = m.ui.pcNotes
    Column(
        Modifier.fillMaxSize().testTag("now-notes")
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenNotes)
            .padding(start = 18.dp, end = 10.dp, top = 13.dp, bottom = 8.dp),
    ) {
        Row(Modifier.padding(end = LocalDotsInset.current), verticalAlignment = Alignment.CenterVertically) {
            Text("AVISOS DEL PC", maxLines = 1, style = Type2.label(p.ink3))
            Spacer(Modifier.width(8.dp))
            Text(
                "${notes.size}", maxLines = 1, style = Type2.body(11.sp, Color.White, FontWeight.Bold),
                modifier = Modifier.background(if (notes.any { it.id in m.ui.freshNotes }) p.coral else p.ink3, RoundedCornerShape(50)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
            if (notes.size > 2) {
                Spacer(Modifier.width(8.dp))
                Text("Ver todos ›", maxLines = 1, style = Type2.body(12.sp, p.ink2, FontWeight.SemiBold))
            }
        }
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
            notes.take(2).forEach { n ->
                val fresh = n.id in m.ui.freshNotes
                Row(
                    Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(if (fresh) p.coral.copy(alpha = 0.12f) else Color.Transparent),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val icon = m.noteIcons[n.icon]
                    Box(Modifier.padding(start = 4.dp).size(30.dp).clip(RoundedCornerShape(9.dp)).background(if (fresh) p.coral.copy(alpha = 0.25f) else p.tile2), contentAlignment = Alignment.Center) {
                        if (icon != null) Image(icon, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Text(n.app.take(1).uppercase(), maxLines = 1, style = Type2.body(13.sp, p.ink2, FontWeight.Bold))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(n.title.ifEmpty { n.app }, maxLines = 1, style = Type2.body(13.sp, p.ink, FontWeight.SemiBold), modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(6.dp))
                            Text(timeAgo(n.at, m.wallMs), maxLines = 1, style = Type2.body(11.sp, p.ink3))
                        }
                        if (n.body.isNotEmpty()) Text(n.body.replace('\n', ' '), maxLines = 1, style = Type2.body(12.sp, p.ink2))
                    }
                    RoundBtn("cancel", 24.dp, Color.Transparent, p.ink2, { x.a.dismissNote(n.id) }, hit = 40.dp)
                }
            }
        }
    }
}

/** Sin ningún juego abierto: los últimos que jugaste, con su carátula, las horas y cuándo fue. */
@Composable
private fun NowGames(x: LayoutArgs, p: Pal) {
    val m = x.m
    val recent = m.ui.recentGames
    if (recent.isEmpty()) {
        Row(Modifier.fillMaxSize().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(64.dp).background(p.tile2, CircleShape), contentAlignment = Alignment.Center) { Icon2("pad", p.ink2, 32.dp) }
            Column {
                Text("Sin juegos ahora mismo", maxLines = 1, style = Type2.num(24.sp, p.ink))
                Spacer(Modifier.height(4.dp))
                Text("Abre uno en Steam o Epic", maxLines = 1, style = Type2.body(13.sp, p.ink2))
            }
        }
        return
    }
    val wall = m.wallMs / 1000
    Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 12.dp)) {
        Text("TUS ÚLTIMOS JUEGOS", maxLines = 1, style = Type2.label(p.ink3))
        Spacer(Modifier.height(7.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            recent.take(3).forEach { g ->
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        val art = m.recentArts[g.art]
                        if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else {
                            Box(Modifier.fillMaxSize().background(p.tile2))
                            Text(g.name.take(1).uppercase(), maxLines = 1, style = Type2.num(30.sp, p.ink3))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(g.name, maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.SemiBold))
                    Text(
                        listOfNotNull(g.hours?.let { hoursLabel(it) }, agoLabel(g.lastPlayed, wall)).joinToString(" · "),
                        maxLines = 1, style = Type2.body(12.sp, p.ink3),
                    )
                }
            }
        }
    }
}

@Composable
private fun NowRest(x: LayoutArgs, p: Pal) {
    val m = x.m
    val ui = m.ui
    val list = ui.telemetry?.upcoming.orEmpty()
    val next = list.firstOrNull()
    val local = timerLeft(m)
    Row(Modifier.fillMaxSize().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text("PRÓXIMO AVISO", maxLines = 1, style = Type2.label(p.ink3))
            Spacer(Modifier.weight(1f))
            when {
                next != null || local != null -> {
                    Text(if (next != null) countdown(next.dueIn - x.elapsed) else countdownText(local ?: 0), maxLines = 1, style = Type2.num(54.sp, p.readable(p.coral)))
                    Text(if (next == null || next.kind == "timer") "temporizador" else next.text, maxLines = 1, style = Type2.body(14.sp, p.ink2))
                }
                m.ui.link != LinkStatus.OPEN -> {
                    Text("Sin datos del PC", maxLines = 1, style = Type2.num(30.sp, p.ink))
                    Spacer(Modifier.height(4.dp))
                    Text("Aquí verás los avisos cuando vuelva la conexión", maxLines = 1, style = Type2.body(14.sp, p.ink2))
                }
                else -> {
                    Text("Todo tranquilo", maxLines = 1, style = Type2.num(30.sp, p.ink))
                    Spacer(Modifier.height(4.dp))
                    Text("Sin avisos pendientes", maxLines = 1, style = Type2.body(14.sp, p.ink2))
                }
            }
        }
        if (list.size > 1) {
            Column(Modifier.weight(0.8f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                list.drop(1).take(2).forEach { u ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            countdown(u.dueIn - x.elapsed), maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.SemiBold),
                            modifier = Modifier.background(p.tile2, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 4.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (u.kind == "timer") "temporizador" else u.text, maxLines = 1, style = Type2.body(13.sp, p.ink2))
                    }
                }
            }
        }
    }
}

@Composable
private fun NowMusic(x: LayoutArgs, p: Pal) {
    val m = x.m
    val ui = m.ui
    val media = ui.media ?: return
    val accent = m.mediaAccent
    val pos = livePosition(media, ui.mediaAt, m.clockMs)
    // Mientras se arrastra la barra se ve a dónde se saltaría; al soltar se mantiene ese punto hasta que el PC confirma el salto.
    var scrub by remember { mutableStateOf<Float?>(null) }
    var held by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val shown = scrub?.let { it * media.duration }
        ?: held?.takeIf { m.clockMs() - it.second < 2500 && abs(pos - it.first) > 3.0 }?.first ?: pos
    val frac = if (media.duration > 0) (shown / media.duration).toFloat().coerceIn(0f, 1f) else 0f
    Row(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenMusic).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(m.mediaArt, accent, "note", 108.dp, Modifier.align(Alignment.CenterVertically))
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
            Row(Modifier.padding(end = (LocalDotsInset.current - 14.dp).coerceAtLeast(0.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(accent, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text((if (media.playing) "SONANDO · " else "EN PAUSA · ") + media.app.uppercase(), maxLines = 1, style = Type2.label(p.ink3))
            }
            Spacer(Modifier.height(3.dp))
            Text(media.title, maxLines = 1, style = Type2.body(18.sp, p.ink, FontWeight.Bold))
            Text(media.artist, maxLines = 1, style = Type2.body(13.sp, p.ink2))
            Spacer(Modifier.weight(1f))
            SeekBar2(
                frac, media.duration, accent, p.tile2,
                onScrub = { scrub = it },
                onSeek = { target ->
                    held = target to m.clockMs()
                    x.a.mediaSeek(target)
                },
                modifier = Modifier.fillMaxWidth().testTag("seek"), touchHeight = 26.dp, barHeight = 6.dp,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundBtn("prev", 30.dp, p.tile2, p.ink, { x.a.mediaControl("previous") })
                RoundBtn(if (media.playing) "pause" else "play-solid", 36.dp, accent, if (accent.luminance() > 0.5f) p.ink else Color.White, { x.a.mediaControl("play_pause") })
                RoundBtn("next", 30.dp, p.tile2, p.ink, { x.a.mediaControl("next") })
                Spacer(Modifier.weight(1f))
                Text(clockTime(shown), maxLines = 1, style = Type2.body(13.sp, if (scrub != null) accent else p.ink2, if (scrub != null) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
}

@Composable
private fun NowGame(x: LayoutArgs, p: Pal) {
    val m = x.m
    val ui = m.ui
    val game = ui.game ?: return
    val accent = m.gameAccent
    val seconds = liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)
    val match = game.match
    Row(
        Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = x.onOpenGame).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Cover(m.gameArt, accent, "pad", 112.dp, Modifier.align(Alignment.CenterVertically))
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
            Row(Modifier.padding(end = (LocalDotsInset.current - 14.dp).coerceAtLeast(0.dp)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(p.red, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text("JUGANDO · " + game.source.uppercase(), maxLines = 1, style = Type2.label(p.ink3))
            }
            Spacer(Modifier.height(3.dp))
            Text(game.name, maxLines = 1, style = Type2.body(19.sp, p.ink, FontWeight.Bold))
            Spacer(Modifier.weight(1f))
            if (match != null && match.score.isNotEmpty()) {
                Text(match.score, maxLines = 1, style = Type2.num(40.sp, p.ink))
                Text(match.title, maxLines = 1, style = Type2.body(13.sp, p.ink2))
            } else {
                Text(sessionClock(seconds), maxLines = 1, style = Type2.num(44.sp, p.ink))
                Text("de partida hoy", maxLines = 1, style = Type2.body(13.sp, p.ink2))
            }
        }
    }
}

// ───────────────────────── teclas ─────────────────────────

@Composable
private fun Dock2(x: LayoutArgs, p: Pal) {
    val m = x.m
    val ui = m.ui
    Row(Modifier.fillMaxWidth().height(62.dp).cached(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ui.actions.filter { it.quick }.take(6).forEach { a ->
            val s = keyState(a, ui.media)
            val accent = when (a.color) {
                "red" -> p.red
                "amber" -> p.sun
                "green" -> p.mint
                "teal" -> p.sky
                else -> p.coral
            }
            Key2(
                label = s.label, icon = a.icon, bitmap = m.icons[a.id], accent = accent, p = p, live = s.live, enabled = x.canAct,
                confirming = ui.pending?.id == a.id, secondsLeft = x.secondsLeft, onClick = { x.press(a.id) }, modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Key2(label = "Mesa", icon = "sliders", bitmap = null, accent = p.sky, p = p, live = false, enabled = true, confirming = false, secondsLeft = 0, onClick = x.onMesa, modifier = Modifier.weight(0.7f).fillMaxHeight().testTag("dock-mesa"))
        Key2(label = "Más", icon = "more", bitmap = null, accent = p.ink2, p = p, live = false, enabled = true, confirming = false, secondsLeft = 0, onClick = x.onMore, modifier = Modifier.weight(0.7f).fillMaxHeight())
    }
}

/** Una tecla: una pastilla con relieve (un labio más oscuro debajo), su icono en un círculo de color y su nombre. */
@Composable
internal fun Key2(
    label: String, icon: String, bitmap: ImageBitmap?, accent: Color, p: Pal, live: Boolean, enabled: Boolean,
    confirming: Boolean, secondsLeft: Int, onClick: () -> Unit, modifier: Modifier,
) {
    val pressed = remember { mutableStateOf(false) }
    val down = pressed.value
    val dip by animateFloatAsState(if (down) 3f else 0f, spring(dampingRatio = 0.6f, stiffness = 1800f), label = "hundir")
    val shape = RoundedCornerShape(24.dp)
    val lip = if (confirming) p.red.copy(alpha = 0.6f) else p.tile2
    val face = when {
        confirming -> p.red
        down -> p.tile2
        else -> p.tile
    }
    Box(modifier.graphicsLayer { alpha = if (enabled || confirming) 1f else 0.4f }) {
        Box(
            Modifier.fillMaxSize().padding(bottom = 4.dp)
                .drawBehind { drawRoundRect(lip, Offset(0f, 4.dp.toPx()), size, CornerRadius(24.dp.toPx())) }
                .graphicsLayer { translationY = dip.dp.toPx() }
                .clip(shape).background(face)
                .instantPress(enabled = enabled, pressed = pressed, onPress = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (confirming) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("¿Seguro?", maxLines = 1, style = Type2.body(14.sp, Color.White, FontWeight.Bold))
                    Text("toca otra vez · $secondsLeft", maxLines = 1, style = Type2.body(12.sp, Color.White.copy(alpha = 0.85f)))
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (bitmap != null) {
                        Image(bitmap, null, Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(26.dp).background(accent.copy(alpha = if (p.night) 0.22f else 0.2f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon2(icon, if (accent.luminance() > 0.6f && !p.night) p.ink else accent, 15.dp)
                        }
                    }
                    Text(label, maxLines = 1, style = Type2.body(12.sp, p.ink))
                }
                if (live) Box(Modifier.align(Alignment.TopEnd).padding(top = 9.dp, end = 12.dp).size(7.dp).background(accent, CircleShape))
            }
        }
    }
}
