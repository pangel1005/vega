package com.asistente.personal.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.Telemetry
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// La sección «Juego»: el juego que está abierto en el PC, con su carátula, la sesión de hoy, tus horas y su información.

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
    hours < 10 -> String.format(Locale.forLanguageTag("es"), "%.1f h", hours)
    else -> "${hours.roundToInt()} h"
}

/** El color de la nota (Metacritic): verde, ámbar o rojo. */
fun ratingColor(score: Int): Color = when {
    score >= 75 -> Vega.Mint
    score >= 50 -> Vega.Amber
    else -> Vega.Red
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
private fun GameArt(art: ImageBitmap?, name: String, accent: Color, modifier: Modifier, radius: Dp) {
    val shape = RoundedCornerShape(radius)
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text(
                name.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty(),
                style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 44.sp, color = accent),
            )
        }
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
        if (t.vramUsed != null && t.vramTotal != null) {
            Stat(String.format(Locale.forLanguageTag("es"), "%.1f", t.vramUsed), "VÍDEO GB", accent)
        }
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
            Text("JUGANDO", maxLines = 1, style = Vega.label.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = accent))
            Spacer(Modifier.weight(1f))
            Text("${game.source} · ${playedLabel(seconds)}", maxLines = 1, style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink3))
        }
        Text(game.name, maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
        PcStats(telemetry, accent, Modifier.fillMaxWidth().padding(top = 2.dp))
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

/**
 * Pantalla «Juego»: la carátula grande, el reloj de la sesión, tus horas, la información del juego (géneros, quién lo
 * hizo, cuándo salió, la nota y la descripción) y las cifras en vivo del PC. El fondo toma el color de la carátula.
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
) {
    val seconds = liveSession(game, receivedAt, now)
    Box(
        modifier.fillMaxSize().background(Vega.Bg)
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.28f), Color.Transparent))),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Jugando en ${game.source}", maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = accent))
                Spacer(Modifier.weight(1f))
                Text(clock, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Vega.Ink3))
                Spacer(Modifier.width(12.dp))
                Pill("Volver", Vega.Ink2, onClose)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(300.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    GameArt(art, game.name, accent, Modifier.fillMaxWidth().aspectRatio(2.14f), 16.dp)
                    Spacer(Modifier.height(14.dp))
                    Text("EN ESTA SESIÓN", style = Vega.label.copy(fontSize = 10.sp, letterSpacing = 1.5.sp, color = Vega.Ink3))
                    Text(
                        sessionClock(seconds),
                        style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 46.sp, color = accent),
                    )
                    val total = listOfNotNull(
                        game.hours?.let { "${hoursLabel(it)} jugadas en total" },
                        game.sizeGb?.let { String.format(Locale.forLanguageTag("es"), "%.1f GB", it) + " en disco" },
                    ).joinToString(" · ")
                    if (total.isNotEmpty()) Text(total, maxLines = 2, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2))
                }
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(game.name, maxLines = 2, style = Vega.body.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold))
                    val by = listOf(game.developer, game.released).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (by.isNotEmpty()) Text(by, maxLines = 1, style = Vega.body.copy(fontSize = 16.sp, color = Vega.Ink2))
                    val chips = game.genres + game.tags
                    if (chips.isNotEmpty() || game.rating != null) {
                        Spacer(Modifier.height(10.dp))
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
                    }
                    if (game.summary.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(game.summary, maxLines = 5, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 19.sp, color = Vega.Ink2))
                    }
                    if (telemetry != null) {
                        Spacer(Modifier.height(16.dp))
                        PcStats(telemetry, accent, Modifier.fillMaxWidth().card().padding(vertical = 8.dp))
                    }
                }
            }
        }
    }
}
