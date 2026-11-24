package com.asistente.personal.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.asistente.personal.net.GameInfo
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

// Cuentas y textos del juego abierto en el PC (la pantalla y la tarjeta están en ui2/Media2.kt y ui2/Home2.kt).

private val ES = Locale.forLanguageTag("es")

/** Segundos que lleva abierto el juego: lo que dijo el PC al mandarlo más lo que pasó desde entonces. */
fun sessionSeconds(game: GameInfo, receivedAt: Long, now: Long): Long =
    (game.played + (now - receivedAt).coerceAtLeast(0) / 1000.0).toLong()

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
