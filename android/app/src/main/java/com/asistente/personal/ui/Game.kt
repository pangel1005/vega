package com.asistente.personal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.RecentGame
import com.asistente.personal.net.Telemetry
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// La sección «Juego»: el juego que está abierto en el PC, con su carátula, la sesión de hoy, tus horas y su información.
// Sin juego abierto, la pestaña sigue ahí y enseña «Sin juegos ahora mismo» con los últimos que jugaste.

private val ES = Locale.forLanguageTag("es")

/** Segundos que lleva abierto el juego: lo que dijo el PC al mandarlo más lo que pasó desde entonces. */
fun sessionSeconds(game: GameInfo, receivedAt: Long, now: Long): Long =
    (game.played + (now - receivedAt).coerceAtLeast(0) / 1000.0).toLong()

/** «menos de 1 min», «12 min», «1 h 05 min». */
fun playedLabel(seconds: Long): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "menos de 1 min"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')} min"
    }
}

/** El reloj grande de la sesión: «4:09» o «1:05:09». */
fun sessionClock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val two = { n: Long -> n.toString().padStart(2, '0') }
    return if (s >= 3600) "${s / 3600}:${two(s % 3600 / 60)}:${two(s % 60)}" else "${s / 60}:${two(s % 60)}"
}

/** Las horas jugadas en total: «45 min», «7,5 h», «268 h». */
fun hoursLabel(hours: Double): String = when {
    hours < 1 -> "${(hours * 60).roundToInt()} min"
    hours < 10 -> String.format(ES, "%.1f h", hours)
    else -> "${hours.roundToInt()} h"
}

/** El color de la nota (Metacritic): verde, ámbar o rojo. */
fun ratingColor(score: Int): Color = when {
    score >= 75 -> Vega.Mint
    score >= 50 -> Vega.Amber
    else -> Vega.Red
}

/** «54 309» (con espacio fino, como se escribe en español). */
fun playersLabel(count: Int): String = String.format(ES, "%,d", count).replace(',', ' ').replace('.', ' ')

/** «hoy», «ayer», «hace 3 días», «hace 2 semanas», «hace 5 meses». */
fun agoLabel(epochSeconds: Double, nowSeconds: Long): String {
    val days = ((nowSeconds - epochSeconds) / 86_400).toLong().coerceAtLeast(0)
    return when {
        days < 1 -> "hoy"
        days < 2 -> "ayer"
        days < 14 -> "hace $days días"
        days < 60 -> "hace ${days / 7} semanas"
        else -> "hace ${days / 30} meses"
    }
}

/** Qué amigos juegan ahora: «Ana está jugando», «Ana y Beto están jugando», «Ana, Beto y 2 más están jugando». */
fun friendsLabel(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "${names[0]} está jugando"
    2 -> "${names[0]} y ${names[1]} están jugando"
    else -> "${names[0]}, ${names[1]} y ${names.size - 2} más están jugando"
}

/** Una lectura del PC mientras juegas (cada ~2 s), para la gráfica de la sesión. */
data class GameSample(val gpu: Int?, val temp: Int?, val vram: Double?, val cpu: Int)

/** Lo más notable de la sesión: GPU media, temperatura máxima y vídeo máximo (null si no hubo lecturas). */
data class SessionStats(val gpuAvg: Int?, val tempMax: Int?, val vramMax: Double?)

fun sessionStats(samples: List<GameSample>): SessionStats {
    val gpu = samples.mapNotNull { it.gpu }
    return SessionStats(
        gpuAvg = if (gpu.isEmpty()) null else gpu.average().roundToInt(),
        tempMax = samples.mapNotNull { it.temp }.maxOrNull(),
        vramMax = samples.mapNotNull { it.vram }.maxOrNull(),
    )
}

/** Lo que dura la sesión, contado cada `tickMs` desde que el PC avisó (el teléfono sigue sumando solo). */
@Composable
fun liveSession(game: GameInfo, receivedAt: Long, now: () -> Long, tickMs: Long = 1000): Long {
    var seconds by remember(game, receivedAt) { mutableStateOf(sessionSeconds(game, receivedAt, now())) }
    LaunchedEffect(game, receivedAt, tickMs) {
        while (true) {
            seconds = sessionSeconds(game, receivedAt, now())
            delay(tickMs)
        }
    }
    return seconds
}

/** La carátula del juego, o (sin ella) su inicial grande sobre el color del juego. */
@Composable
private fun GameArt(art: ImageBitmap?, name: String, accent: Color, modifier: Modifier, radius: Dp, initialSize: androidx.compose.ui.unit.TextUnit = 44.sp) {
    val shape = RoundedCornerShape(radius)
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        val fill = Brush.linearGradient(listOf(accent.copy(alpha = 0.42f), accent.copy(alpha = 0.06f)))
        Box(modifier.clip(shape).background(fill), contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = initialSize, color = accent),
            )
        }
    }
}

/** Un punto que late: «en vivo». */
@Composable
private fun LiveDot(color: Color, size: Dp = 7.dp) {
    val transition = rememberInfiniteTransition(label = "latido")
    val alpha by transition.animateFloat(0.3f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alfa")
    Box(Modifier.size(size).graphicsLayer { this.alpha = alpha }.background(color, CircleShape))
}

private fun parseHex(hex: String?): Color? =
    try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { null }

/** El marcador con el color de cada equipo; al cambiar, los números dan un salto (gol, baja). */
@Composable
private fun ScoreBoard(score: String, colors: List<String>, accent: Color, size: TextUnit) {
    val parts = score.split(" – ")
    val pulse = remember { Animatable(1f) }
    var last by remember { mutableStateOf(score) }
    LaunchedEffect(score) {
        if (score != last) {
            last = score
            pulse.snapTo(1.3f)
            pulse.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 300f))
        }
    }
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = "marcador $score" }.graphicsLayer {
            scaleX = pulse.value
            scaleY = pulse.value
            transformOrigin = TransformOrigin(0f, 1f)
        },
        verticalAlignment = Alignment.Bottom,
    ) {
        parts.forEachIndexed { i, part ->
            if (i > 0) Text(" – ", style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = size, color = Vega.Ink3))
            Text(part, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = size, color = parseHex(colors.getOrNull(i)) ?: accent))
        }
    }
}

/** «5 / 2 / 7» con las bajas en verde, las muertes en rojo y las asistencias en azul. */
@Composable
private fun Kda(value: String) {
    val parts = value.split(" / ")
    if (parts.size != 3) {
        Text(value, maxLines = 1, style = Vega.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold))
        return
    }
    val bold = Vega.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Row {
        Text(parts[0], style = bold.copy(color = Vega.Mint))
        Text(" / ", style = bold.copy(color = Vega.Ink3))
        Text(parts[1], style = bold.copy(color = Vega.Red))
        Text(" / ", style = bold.copy(color = Vega.Ink3))
        Text(parts[2], style = bold.copy(color = Vega.Sky))
    }
}

/** El color de un suceso de la partida: bajas en rojo, objetivos en ámbar, goles y paradas en verde. */
fun feedColor(text: String): Color = when {
    "eliminó" in text -> Vega.Red
    listOf("Dragón", "Barón", "Heraldo", "Torre", "Inhibidor", "Ace").any { it in text } -> Vega.Amber
    "Gol" in text || "Save" in text || "Fin de" in text -> Vega.Mint
    else -> Vega.Ink3
}

/** Un medidor de arco (como un cuentarrevoluciones) con su cifra dentro; la aguja se mueve suave. */
@Composable
private fun ArcGauge(fraction: Float, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    val sweep by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700), label = "aguja")
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val stroke = 5.dp.toPx()
            val arc = Size(size.width - stroke, size.height - stroke)
            val at = Offset(stroke / 2, stroke / 2)
            drawArc(Vega.Line, 135f, 270f, false, at, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (sweep > 0.01f) drawArc(color, 135f, 270f * sweep, false, at, arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, maxLines = 1, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 15.sp, color = color))
            Text(label, maxLines = 1, style = Vega.label.copy(fontSize = 7.sp, letterSpacing = 0.6.sp, color = Vega.Ink3))
        }
    }
}

/** GPU, temperatura, vídeo y procesador como cuatro medidores de arco. */
@Composable
private fun PcGauges(t: Telemetry?, accent: Color, modifier: Modifier = Modifier) {
    if (t == null) return
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        val size = Modifier.height(60.dp)
        t.gpuUtil?.let { ArcGauge(it / 100f, "$it", "GPU %", accent, size) }
        t.gpuTemp?.let { ArcGauge((it - 30) / 60f, "$it°", "TEMP", if (it >= 80) Vega.Red else if (it >= 70) Vega.Amber else accent, size) }
        if (t.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) {
            ArcGauge((t.vramUsed / t.vramTotal).toFloat(), String.format(ES, "%.1f", t.vramUsed), "VÍDEO GB", accent, size)
        }
        ArcGauge(t.cpu / 100f, "${t.cpu}", "CPU %", accent, size)
    }
}

/** Un mando de juego dibujado con líneas: cruceta a la izquierda, dos botones a la derecha. */
@Composable
fun GamepadIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(1.5f)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.045f, cap = StrokeCap.Round)
        // El cuerpo: una cápsula achatada con dos empuñaduras abajo.
        val body = Path().apply {
            moveTo(w * 0.22f, h * 0.22f)
            lineTo(w * 0.78f, h * 0.22f)
            cubicTo(w * 0.98f, h * 0.22f, w * 1.02f, h * 0.62f, w * 0.92f, h * 0.84f)
            cubicTo(w * 0.86f, h * 0.98f, w * 0.72f, h * 0.94f, w * 0.66f, h * 0.76f)
            lineTo(w * 0.60f, h * 0.66f)
            lineTo(w * 0.40f, h * 0.66f)
            lineTo(w * 0.34f, h * 0.76f)
            cubicTo(w * 0.28f, h * 0.94f, w * 0.14f, h * 0.98f, w * 0.08f, h * 0.84f)
            cubicTo(w * -0.02f, h * 0.62f, w * 0.02f, h * 0.22f, w * 0.22f, h * 0.22f)
            close()
        }
        drawPath(body, color.copy(alpha = 0.10f))
        drawPath(body, color, style = stroke)
        // Cruceta.
        val cx = w * 0.28f
        val cy = h * 0.44f
        val arm = w * 0.07f
        drawLine(color, Offset(cx - arm, cy), Offset(cx + arm, cy), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        drawLine(color, Offset(cx, cy - arm), Offset(cx, cy + arm), strokeWidth = w * 0.05f, cap = StrokeCap.Round)
        // Botones.
        drawCircle(color, w * 0.035f, Offset(w * 0.70f, h * 0.38f))
        drawCircle(color, w * 0.035f, Offset(w * 0.79f, h * 0.48f))
    }
}

/** Una cifra del PC con su nombre pequeño debajo: «63» / «GPU %» (la tipografía de puntos no tiene el signo %). */
@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, maxLines = 1, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 20.sp, color = color))
        Text(label, maxLines = 1, style = Vega.label.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = Vega.Ink3))
    }
}

/** Las cifras del PC mientras juegas: gráfica, temperatura, vídeo y procesador (lo que el PC ya manda). */
@Composable
private fun PcStats(t: Telemetry?, accent: Color, modifier: Modifier = Modifier) {
    if (t == null) return
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        t.gpuUtil?.let { Stat("$it", "GPU %", accent) }
        t.gpuTemp?.let { Stat("$it°", "TEMP", if (it >= 80) Vega.Red else if (it >= 70) Vega.Amber else accent) }
        if (t.vramUsed != null && t.vramTotal != null) Stat(String.format(ES, "%.1f", t.vramUsed), "VÍDEO GB", accent)
        Stat("${t.cpu}", "CPU %", accent)
    }
}

/**
 * La página «Juego» (columna derecha, mientras hay un juego abierto en el PC): carátula, nombre, cuánto llevas hoy
 * y la gráfica. Tocarla abre la pantalla grande.
 */
@Composable
fun GameCard(
    game: GameInfo,
    seconds: Long,
    art: ImageBitmap?,
    accent: Color,
    telemetry: Telemetry?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.card().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        GameArt(art, game.name, accent, Modifier.fillMaxWidth().weight(1f), 12.dp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            LiveDot(accent, 6.dp)
            Spacer(Modifier.width(5.dp))
            Text("JUGANDO", maxLines = 1, style = Vega.label.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = accent))
            Spacer(Modifier.weight(1f))
            Text("${game.source} · ${playedLabel(seconds)}", maxLines = 1, style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink3))
        }
        Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
        // Con partida en curso, su marcador en vivo sustituye a las cifras del PC (que están en la pantalla grande).
        val live = game.match?.takeIf { it.score.isNotEmpty() }
        if (live != null) {
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                ScoreBoard(live.score, live.colors, accent, 20.sp)
                Spacer(Modifier.weight(1f))
                Text(live.stats.firstOrNull { it.first.startsWith("K") }?.second ?: live.title, maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink2))
            }
        } else {
            PcStats(telemetry, accent, Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}

/**
 * La misma página cuando no hay ningún juego abierto: un mando, «Sin juegos ahora mismo» y, si Steam los tiene, los
 * últimos que jugaste (carátula, nombre y horas).
 */
@Composable
fun GameIdleCard(recent: List<RecentGame>, arts: Map<String, ImageBitmap>, wallSeconds: Long, modifier: Modifier = Modifier) {
    Column(modifier.card().padding(horizontal = 10.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            GamepadIcon(Vega.Ink3, Modifier.height(if (recent.isEmpty()) 52.dp else 30.dp))
            Spacer(Modifier.height(4.dp))
            Text("Sin juegos ahora mismo", maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
            // Con juegos recientes debajo no hay sitio para más texto; sin ellos, se invita a abrir uno.
            if (recent.isEmpty()) Text("Abre uno en Steam o Epic", maxLines = 1, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink3))
        }
        if (recent.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                recent.take(3).forEach { game ->
                    Column(Modifier.weight(1f)) {
                        GameArt(arts[game.art], game.name, Vega.Ink3, Modifier.fillMaxWidth().aspectRatio(2.0f), 8.dp, initialSize = 20.sp)
                        Text(game.name, maxLines = 1, style = Vega.label.copy(fontSize = 9.sp, color = Vega.Ink))
                        Text(
                            agoLabel(game.lastPlayed, wallSeconds), maxLines = 1,
                            style = Vega.label.copy(fontSize = 9.sp, color = Vega.Ink3),
                        )
                    }
                }
            }
        }
    }
}

/** Una etiqueta pequeña (género o modo de juego). */
@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text, maxLines = 1,
        modifier = Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
        style = Vega.label.copy(fontSize = 11.sp, color = color),
    )
}

/** Las secciones de la pantalla grande. */
enum class GameSection(val label: String) {
    MATCH("Partida"), SESSION("Sesión"), GAME("Juego"), COMMUNITY("Comunidad"), ACHIEVEMENTS("Logros")
}

/** Las secciones que tienen algo que enseñar: sesión y juego siempre; comunidad y logros solo si hay datos. */
fun gameSections(game: GameInfo): List<GameSection> = buildList {
    if (game.match != null) add(GameSection.MATCH) // la partida en curso va primera: es lo que importa ahora
    add(GameSection.SESSION)
    add(GameSection.GAME)
    if (game.players != null || game.news.isNotEmpty() || game.friends.isNotEmpty()) add(GameSection.COMMUNITY)
    if (game.achievements != null || game.achUnlocked != null) add(GameSection.ACHIEVEMENTS)
}

/** Segundos de partida ahora: lo que dijo el juego al mandarlo más lo que pasó desde entonces (sube o baja según el juego). */
fun matchSeconds(match: com.asistente.personal.net.MatchInfo, receivedAt: Long, now: Long): Long? {
    val base = match.clock ?: return null
    val moved = match.clockStep * (now - receivedAt).coerceAtLeast(0) / 1000.0
    return (base + moved).coerceAtLeast(0.0).toLong()
}

/** Los botones de la hoja «Más» que sirven jugando, en este orden (solo los que existan). */
val GAME_ACTIONS = listOf("captura", "grabar", "discord", "silenciar")

/** La gráfica de la sesión: la GPU (relleno) y la temperatura (línea ámbar) desde que empezó. */
@Composable
private fun SessionGraph(samples: List<GameSample>, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (i in 1..3) drawLine(Vega.Line, Offset(0f, h * i / 4), Offset(w, h * i / 4), strokeWidth = 1.dp.toPx())
        if (samples.size < 2) return@Canvas
        val n = samples.size
        fun x(i: Int) = w * i / (n - 1)
        val area = Path().apply {
            moveTo(0f, h)
            samples.forEachIndexed { i, s -> lineTo(x(i), h - h * (s.gpu ?: 0).coerceIn(0, 100) / 100f) }
            lineTo(w, h)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.45f), accent.copy(alpha = 0.02f))))
        val temp = Path()
        var started = false
        samples.forEachIndexed { i, s ->
            val t = s.temp ?: return@forEachIndexed
            val y = h - h * ((t - 30f) / 60f).coerceIn(0f, 1f) // 30 a 90 °C
            if (!started) temp.moveTo(x(i), y) else temp.lineTo(x(i), y)
            started = true
        }
        drawPath(temp, Vega.Amber, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun MatchSection(match: com.asistente.personal.net.MatchInfo, receivedAt: Long, now: () -> Long, accent: Color) {
    // El reloj de la partida lo sigue contando el teléfono entre aviso y aviso.
    var seconds by remember(match, receivedAt) { mutableStateOf(matchSeconds(match, receivedAt, now())) }
    LaunchedEffect(match, receivedAt) {
        while (match.clockStep != 0) {
            seconds = matchSeconds(match, receivedAt, now())
            delay(1000)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if (match.score.isEmpty() && match.stats.isEmpty()) {
            Text(match.hint.ifEmpty { "Sin datos de la partida todavía." }, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 19.sp, color = Vega.Ink2))
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            ScoreBoard(match.score, match.colors, accent, 34.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.padding(bottom = 5.dp)) {
                Text(match.title, maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2))
                seconds?.let { Text(sessionClock(it), style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink3)) }
            }
        }
        if (match.stats.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().card().padding(vertical = 8.dp, horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                match.stats.take(6).forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (label.startsWith("K")) Kda(value)
                        else Text(value, maxLines = 1, style = Vega.body.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Vega.Ink))
                        Text(label.uppercase(), maxLines = 1, style = Vega.label.copy(fontSize = 8.sp, letterSpacing = 0.8.sp, color = Vega.Ink3))
                    }
                }
            }
        }
        if (match.feed.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            match.feed.take(3).forEach {
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(feedColor(it), CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(it, maxLines = 1, style = Vega.body.copy(fontSize = 13.sp, color = Vega.Ink2))
                }
            }
        }
    }
}

@Composable
private fun SessionSection(game: GameInfo, samples: List<GameSample>, telemetry: Telemetry?, accent: Color) {
    val stats = sessionStats(samples)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("GPU", style = Vega.label.copy(fontSize = 10.sp, letterSpacing = 1.sp, color = accent))
            Spacer(Modifier.width(10.dp))
            Text("TEMPERATURA", style = Vega.label.copy(fontSize = 10.sp, letterSpacing = 1.sp, color = Vega.Amber))
            Spacer(Modifier.weight(1f))
            Text("esta sesión", style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink3))
        }
        Spacer(Modifier.height(4.dp))
        SessionGraph(samples, accent, Modifier.fillMaxWidth().height(52.dp).background(Vega.Card, RoundedCornerShape(10.dp)))
        Spacer(Modifier.height(6.dp))
        PcGauges(telemetry, accent, Modifier.fillMaxWidth())
        val notes = listOfNotNull(
            stats.gpuAvg?.let { "GPU media $it %" },
            stats.tempMax?.let { "máx. $it°" },
            stats.vramMax?.let { String.format(ES, "vídeo máx. %.1f GB", it) },
            game.played2w?.let { "últimas 2 semanas: ${hoursLabel(it)}" },
        )
        if (notes.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(notes.joinToString(" · "), maxLines = 2, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink2))
        }
    }
}

@Composable
private fun InfoSection(game: GameInfo, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        val chips = game.genres + game.tags
        if (chips.isNotEmpty() || game.rating != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                game.rating?.let { score ->
                    Text(
                        "$score",
                        modifier = Modifier.background(ratingColor(score), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                        style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.Black),
                    )
                }
                chips.take(5).forEach { Chip(it, accent) }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (game.summary.isNotEmpty()) {
            Text(game.summary, maxLines = 6, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 19.sp, color = Vega.Ink2))
        } else {
            Text("Sin descripción (la tienda de Steam no está activada o no contestó).", style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink3))
        }
    }
}

@Composable
private fun CommunitySection(game: GameInfo, account: Boolean, wallSeconds: Long, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        game.players?.let {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(playersLabel(it), style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 30.sp, color = accent))
                Spacer(Modifier.width(8.dp))
                Text("jugando ahora mismo", style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2), modifier = Modifier.padding(bottom = 5.dp))
            }
        }
        if (game.friends.isNotEmpty()) {
            Text(friendsLabel(game.friends), maxLines = 2, style = Vega.body.copy(fontSize = 14.sp, color = Vega.Mint))
        } else if (!account) {
            Text("Conecta tu cuenta de Steam para ver a tus amigos aquí.", style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink3))
        }
        if (game.news.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("NOVEDADES", style = Vega.label.copy(fontSize = 10.sp, letterSpacing = 1.5.sp, color = Vega.Ink3))
            game.news.take(3).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, maxLines = 1, modifier = Modifier.weight(1f), style = Vega.body.copy(fontSize = 13.sp, color = Vega.Ink))
                    Spacer(Modifier.width(8.dp))
                    Text(agoLabel(item.at, wallSeconds), maxLines = 1, style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink3))
                }
            }
        }
    }
}

@Composable
private fun AchievementsSection(game: GameInfo, account: Boolean, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        val total = game.achievements
        val mine = game.achUnlocked
        if (mine != null && total != null && total > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$mine", style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 30.sp, color = accent))
                Text(" / $total logros", style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink2), modifier = Modifier.padding(bottom = 5.dp))
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(Vega.Card, RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth((mine.toFloat() / total).coerceIn(0f, 1f)).fillMaxHeight().background(accent, RoundedCornerShape(50)))
            }
            if (game.achRecent.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("ÚLTIMOS DESBLOQUEADOS", style = Vega.label.copy(fontSize = 10.sp, letterSpacing = 1.5.sp, color = Vega.Ink3))
                game.achRecent.take(3).forEach { Text("★ $it", maxLines = 1, modifier = Modifier.padding(top = 3.dp), style = Vega.body.copy(fontSize = 13.sp)) }
            }
        } else {
            total?.let { Text("$it logros en total", style = Vega.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)) }
            Spacer(Modifier.height(4.dp))
            Text(
                if (account) "No pude leer tus logros: en Steam, tu perfil debe tener «Detalles del juego» en público."
                else "Conecta tu cuenta de Steam para ver cuántos llevas y cuáles te faltan.",
                style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink3),
            )
        }
    }
}

/**
 * Pantalla «Juego»: a la izquierda la carátula, el reloj de la sesión y tus horas; a la derecha el nombre y, debajo,
 * secciones que se eligen arriba: la sesión (gráfica de GPU y temperatura, cifras en vivo), la información del juego,
 * la comunidad (cuánta gente juega, tus amigos, novedades) y los logros. Abajo, botones útiles jugando. El fondo toma
 * el color de la carátula.
 */
@Composable
fun GameScreen(
    game: GameInfo,
    receivedAt: Long,
    now: () -> Long,
    art: ImageBitmap?,
    accent: Color,
    clock: String,
    telemetry: Telemetry?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    samples: List<GameSample> = emptyList(),
    actions: List<DeckAction> = emptyList(),
    onPress: (String) -> Unit = {},
    account: Boolean = false,
    wallSeconds: Long = System.currentTimeMillis() / 1000,
    startSection: GameSection? = null,
) {
    val seconds = liveSession(game, receivedAt, now)
    val sections = gameSections(game)
    var section by remember { mutableStateOf(startSection) }
    // Sin elegir otra, se ve la partida si la hay (aparece cuando empieza) y si no la sesión.
    val shown = section?.takeIf { it in sections } ?: sections.first()
    Box(modifier.fillMaxSize().background(Vega.Bg)) {
        // De fondo, la carátula grande, desenfocada y oscurecida; encima, un velo del color del juego.
        if (art != null) Image(art, null, contentScale = ContentScale.Crop, alpha = 0.34f, modifier = Modifier.fillMaxSize().blur(26.dp))
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(listOf(accent.copy(alpha = 0.20f), Vega.Bg.copy(alpha = 0.55f), Vega.Bg.copy(alpha = 0.92f))),
            ),
        )
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LiveDot(accent)
                Spacer(Modifier.width(7.dp))
                Text("Jugando en ${game.source}", maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = accent))
                Spacer(Modifier.weight(1f))
                Text(clock, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Vega.Ink3))
                Spacer(Modifier.width(12.dp))
                Pill("Volver", Vega.Ink2, onClose)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(280.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    GameArt(art, game.name, accent, Modifier.fillMaxWidth().aspectRatio(2.14f), 16.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("EN ESTA SESIÓN", style = Vega.label.copy(fontSize = 10.sp, letterSpacing = 1.5.sp, color = Vega.Ink3))
                    Text(
                        sessionClock(seconds),
                        style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 46.sp, color = accent),
                    )
                    val total = listOfNotNull(
                        game.hours?.let { "${hoursLabel(it)} jugadas en total" },
                        game.sizeGb?.let { String.format(ES, "%.1f GB", it) + " en disco" },
                    ).joinToString(" · ")
                    if (total.isNotEmpty()) Text(total, maxLines = 2, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2))
                }
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Text(game.name, maxLines = 2, style = Vega.body.copy(fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold))
                    val by = listOf(game.developer, game.released).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (by.isNotEmpty()) Text(by, maxLines = 1, style = Vega.body.copy(fontSize = 15.sp, color = Vega.Ink2))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sections.forEach { s -> Pill(s.label, if (s == shown) accent else Vega.Ink3, { section = s }, filled = s == shown) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        AnimatedContent(
                            targetState = shown,
                            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 }) togetherWith fadeOut(tween(120)) },
                            label = "sección",
                        ) { current ->
                            when (current) {
                                GameSection.MATCH -> game.match?.let { MatchSection(it, receivedAt, now, accent) }
                                GameSection.SESSION -> SessionSection(game, samples, telemetry, accent)
                                GameSection.GAME -> InfoSection(game, accent)
                                GameSection.COMMUNITY -> CommunitySection(game, account, wallSeconds, accent)
                                GameSection.ACHIEVEMENTS -> AchievementsSection(game, account, accent)
                            }
                        }
                    }
                }
            }
            // Botones que sirven jugando (los de «Más»: captura, grabar, Discord, silenciar), si existen.
            val handy = GAME_ACTIONS.mapNotNull { id -> actions.firstOrNull { it.id == id } }
            if (handy.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    handy.forEach { a -> Pill(a.label, Vega.Ink2, { onPress(a.id) }) }
                }
            }
        }
    }
}
