package com.asistente.personal.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.Telemetry
import com.asistente.personal.net.Upcoming
import java.util.Locale

/*
 * Piezas de la consola NERV: paneles con cabecera, barras segmentadas, la cinta de
 * datos que corre arriba, el riel de telemetría y el marco de peligro.
 * Todo lo que muestran es real (lo manda el PC o lo lee el teléfono): nada decorativo
 * que finja ser un dato.
 */

private val mono = TextStyle(fontFamily = Nerv.Mono, letterSpacing = 1.5.sp)
private val cond = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold)

/** «23,6»: decimales con coma, como se leen en español. */
fun gb(v: Double): String = String.format(Locale.forLanguageTag("es"), "%.1f", v)

/** 0–1 de lo lleno que va algo; los colores pasan a ámbar y rojo al acercarse al límite. */
fun loadColor(fraction: Float, base: Color = Nerv.Orange): Color = when {
    fraction >= 0.9f -> Nerv.Red
    fraction >= 0.75f -> Nerv.Amber
    else -> base
}

/**
 * Barra de segmentos (como los indicadores de sincronización de NERV). Los segmentos
 * apagados se ven tenues: así la escala completa siempre está a la vista.
 */
@Composable
fun SegmentBar(fraction: Float, color: Color, modifier: Modifier = Modifier, segments: Int = 20) {
    Canvas(modifier.height(7.dp)) {
        val gap = 1.5.dp.toPx()
        val w = (size.width - gap * (segments - 1)) / segments
        val lit = (fraction.coerceIn(0f, 1f) * segments + 0.001f).toInt()
        for (i in 0 until segments) {
            val on = i < lit
            drawRect(
                if (on) color else color.copy(alpha = 0.13f),
                topLeft = Offset(i * (w + gap), 0f),
                size = Size(w, size.height),
            )
        }
    }
}

/**
 * Panel con cabecera: número de unidad, título, kanji a la derecha y esquinas cortadas.
 * El contenido va debajo.
 */
@Composable
fun UnitPanel(
    no: String,
    title: String,
    jp: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = remember { Chamfer(8.dp) }
    Column(
        modifier
            .background(Color(0xCC0B0A09), shape)
            .border(1.dp, accent.copy(alpha = 0.32f), shape)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.background(accent).padding(horizontal = 3.dp)) {
                Text(no, style = mono.copy(fontSize = 8.sp, color = Color(0xFF0A0A0A), letterSpacing = 0.sp))
            }
            Spacer(Modifier.width(5.dp))
            Text(title, maxLines = 1, style = mono.copy(fontSize = 9.sp, color = Nerv.Mute))
            Spacer(Modifier.weight(1f))
            Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 9.sp, color = accent.copy(alpha = 0.75f)))
        }
        Spacer(Modifier.height(2.dp))
        content()
    }
}

/** Número grande + unidad pequeña («62 °C»). */
@Composable
private fun Reading(value: String, unit: String, color: Color, note: String? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = cond.copy(fontSize = 24.sp, lineHeight = 24.sp, color = color))
        Spacer(Modifier.width(2.dp))
        Text(unit, style = mono.copy(fontSize = 10.sp, color = color.copy(alpha = 0.8f)), modifier = Modifier.padding(bottom = 3.dp))
        if (note != null) {
            Spacer(Modifier.weight(1f))
            Text(note, maxLines = 1, style = mono.copy(fontSize = 9.sp, color = Nerv.Mute), modifier = Modifier.padding(bottom = 3.dp))
        }
    }
}

/**
 * Riel izquierdo: procesador, gráfica y teléfono. Sin enlace muestra guiones (no la
 * última lectura congelada).
 */
@Composable
fun TelemetryRail(t: Telemetry?, battery: Int, charging: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val cpu = t?.cpu
        val cpuF = (cpu ?: 0) / 100f
        UnitPanel("01", "PROCESADOR", "演算", loadColor(cpuF), Modifier.fillMaxWidth()) {
            Reading(cpu?.toString() ?: "--", "%", loadColor(cpuF), note = t?.let { "MEM ${gb(it.ramUsed)}/${gb(it.ramTotal)} GB" })
            SegmentBar(cpuF, loadColor(cpuF), Modifier.fillMaxWidth())
        }
        val temp = t?.gpuTemp
        val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
        val hot = (temp ?: 0) >= 80
        val gpuColor = if (hot) Nerv.Red else loadColor(vram ?: 0f, Nerv.Teal)
        UnitPanel("02", "GRÁFICA", "画像", gpuColor, Modifier.fillMaxWidth()) {
            Reading(temp?.toString() ?: "--", "°C", if (hot) Nerv.Red else Nerv.Ink, note = t?.gpuUtil?.let { "USO $it %" })
            SegmentBar(vram ?: 0f, gpuColor, Modifier.fillMaxWidth())
            Text(
                if (t?.vramUsed != null && t.vramTotal != null) "MEMORIA ${gb(t.vramUsed)}/${gb(t.vramTotal)} GB" else "SIN DATOS",
                style = mono.copy(fontSize = 8.sp, color = Nerv.Mute),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val batF = battery.coerceAtLeast(0) / 100f
        val batColor = when {
            battery in 0..15 && !charging -> Nerv.Red
            charging -> Nerv.Green
            else -> Nerv.Green.copy(alpha = 0.85f)
        }
        UnitPanel("03", "TELÉFONO", "端末", batColor, Modifier.fillMaxWidth()) {
            Reading(if (battery >= 0) "$battery" else "--", "%", batColor, note = if (charging) "CARGANDO" else "BATERÍA")
            SegmentBar(batF, batColor, Modifier.fillMaxWidth())
        }
    }
}

/** «04:59», «1:02:00»: lo que falta para un aviso. */
fun countdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val ss = s % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, ss) else String.format(Locale.ROOT, "%02d:%02d", m, ss)
}

/**
 * Próximo recordatorio o temporizador, con cuenta atrás. `elapsed` = segundos desde que
 * llegó la lectura (el PC manda una nueva cada 2 s; entre medias cuenta el teléfono).
 */
@Composable
fun NextUp(upcoming: List<Upcoming>, elapsed: Long, linked: Boolean, modifier: Modifier = Modifier) {
    val next = upcoming.firstOrNull()
    val accent = if (next == null) Nerv.Dim else Nerv.Amber
    UnitPanel("04", "PRÓXIMO AVISO", "予定", if (next == null) Nerv.Mute else Nerv.Amber, modifier) {
        if (next == null) {
            Text(
                if (linked) "NADA PENDIENTE" else "SIN ENLACE",
                style = mono.copy(fontSize = 10.sp, color = Nerv.Dim),
                modifier = Modifier.padding(vertical = 4.dp),
            )
            Text("«VEGA, RECUÉRDAME…»", style = mono.copy(fontSize = 8.sp, color = Nerv.Dim))
        } else {
            val left = next.dueIn - elapsed
            Row(verticalAlignment = Alignment.Bottom) {
                Text(countdown(left), style = cond.copy(fontSize = 24.sp, lineHeight = 24.sp, color = accent))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (next.kind == "timer") "TEMPORIZADOR" else "RECORDATORIO",
                    style = mono.copy(fontSize = 8.sp, color = Nerv.Mute),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Text(next.text, maxLines = 1, style = cond.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp, color = Nerv.Ink))
            if (upcoming.size > 1) {
                Text("+${upcoming.size - 1} MÁS", style = mono.copy(fontSize = 8.sp, color = Nerv.Mute))
            }
        }
    }
}

/**
 * Cinta de datos que corre despacio (como los teletipos de NERV). Se mide una vez y
 * solo se desplaza una capa: no recompone ni vuelve a medir el texto en cada cuadro.
 */
@Composable
fun Ticker(text: String, color: Color, modifier: Modifier = Modifier) {
    val shape = remember { Chamfer(6.dp) }
    BoxWithConstraints(
        modifier
            .height(20.dp)
            .border(1.dp, color.copy(alpha = 0.25f), shape)
            .background(Color(0x99000000), shape)
            .clipToBounds(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val loop = "$text   ◆   "
        val transition = rememberInfiniteTransition(label = "cinta")
        // ~28 dp/s: legible sin cansar. La duración depende del largo del texto.
        val shift by transition.animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween((loop.length * 220).coerceAtLeast(6000), easing = LinearEasing), RepeatMode.Restart),
            label = "desplazamiento",
        )
        val width = remember { floatArrayOf(0f) }
        Row(
            Modifier
                .wrapContentWidth(Alignment.Start, unbounded = true)
                .graphicsLayer { translationX = -shift * width[0] },
        ) {
            val style = mono.copy(fontSize = 10.sp, color = color)
            Text(loop, maxLines = 1, style = style, modifier = Modifier.onSizeChanged { width[0] = it.width.toFloat() })
            Text(loop, maxLines = 1, style = style)
            Text(loop, maxLines = 1, style = style)
        }
    }
}

/**
 * Marco de peligro: franjas amarillas y negras en los bordes de la pantalla (cuando hay
 * que confirmar algo serio, como apagar el PC).
 */
fun Modifier.hazardFrame(color: Color, thickness: Dp = 10.dp): Modifier = drawBehind {
    val t = thickness.toPx()
    val step = t * 1.6f
    fun stripes(x: Float, y: Float, w: Float, h: Float) {
        // Rayas a 45° recortadas al borde.
        val path = Path()
        var o = -h
        while (o < w + h) {
            path.moveTo(x + o, y + h)
            path.lineTo(x + o + h, y)
            path.lineTo(x + o + h + step / 2, y)
            path.lineTo(x + o + step / 2, y + h)
            path.close()
            o += step
        }
        drawRect(Color(0xFF0A0A0A), Offset(x, y), Size(w, h))
        clipRect(x, y, x + w, y + h) { drawPath(path, color) }
    }
    stripes(0f, 0f, size.width, t)
    stripes(0f, size.height - t, size.width, t)
}

/** Etiqueta con fondo de color y texto oscuro («待機 EN ESPERA»). */
@Composable
fun Tag(jp: String, es: String, color: Color, modifier: Modifier = Modifier) {
    val shape = remember { Chamfer(6.dp) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.background(color, shape).padding(horizontal = 7.dp, vertical = 1.dp)) {
            Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 13.sp, color = Color(0xFF0A0A0A)))
        }
        Spacer(Modifier.width(7.dp))
        Text(es, maxLines = 1, style = mono.copy(fontSize = 10.sp, letterSpacing = 2.5.sp, color = color))
    }
}

/** Pequeña escala de marcas (decoración de bordes de panel: solo líneas, sin falsos datos). */
@Composable
fun Ruler(color: Color, modifier: Modifier = Modifier, marks: Int = 24) {
    Canvas(modifier.fillMaxSize()) {
        val step = size.width / marks
        for (i in 0..marks) {
            val h = if (i % 6 == 0) size.height else size.height * 0.45f
            drawLine(color, Offset(i * step, size.height), Offset(i * step, size.height - h), 1f)
        }
    }
}
