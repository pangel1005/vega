package com.asistente.personal.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.ui.led.FaceExtras
import com.asistente.personal.ui.led.LedFaceView
import com.asistente.personal.ui.led.countdownText

/*
 * Vega · Escenas. La pantalla principal es una composición distinta para cada momento, y pasa de una a otra sola:
 *   Reposo  — la cara enorme y casi nada más: la hora, cuatro cifras y lo que dice (una criatura, no un panel)
 *   Música  — el fondo toma el color de la carátula; carátula grande, título, progreso, mandos y el analizador de espectro
 *   Juego   — la portada del juego de fondo; el tiempo de partida enorme, dos medidores de arco y, si hay partida, el marcador
 * La cara no desaparece: en música y juego se queda en una ventanita con su gesto (visor, sudor, párpados).
 */

private enum class Scene(val label: String) { REST("Reposo"), MUSIC("Música"), GAME("Juego") }

@Composable
internal fun ColumnScope.ScenesLayout(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val available = buildList {
        add(Scene.REST)
        if (ui.media != null) add(Scene.MUSIC)
        if (ui.game != null) add(Scene.GAME)
    }
    var chosen by remember { mutableStateOf(if (ui.game != null) Scene.GAME else if (ui.media != null) Scene.MUSIC else Scene.REST) }
    // Al empezar algo, la pantalla pasa a su escena; al acabar, vuelve.
    LaunchedEffect(ui.media != null) { if (ui.media != null) chosen = Scene.MUSIC else if (chosen == Scene.MUSIC) chosen = Scene.REST }
    LaunchedEffect(ui.game?.key) { if (ui.game != null) chosen = Scene.GAME else if (chosen == Scene.GAME) chosen = Scene.REST }
    val scene = if (chosen in available) chosen else Scene.REST

    Box(Modifier.weight(1f).fillMaxWidth()) {
        AnimatedContent(
            targetState = scene,
            transitionSpec = { (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.97f)) togetherWith (fadeOut(tween(250)) + scaleOut(tween(250), targetScale = 1.02f)) },
            label = "escena",
        ) { s ->
            when (s) {
                Scene.REST -> RestScene(x)
                Scene.MUSIC -> MusicScene(x)
                Scene.GAME -> GameScene(x)
            }
        }
        // Los puntitos de arriba dicen en qué escena estás; tocar uno cambia a ella.
        if (available.size > 1) {
            Row(Modifier.align(Alignment.TopCenter).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                available.forEach { s ->
                    Box(
                        Modifier.size(width = if (s == scene) 22.dp else 8.dp, height = 8.dp)
                            .background(if (s == scene) Vega.Ink else Vega.Ink3, RoundedCornerShape(50))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { chosen = s },
                    )
                }
            }
        }
    }
    KeyRow(
        favorites = ui.actions.filter { it.quick }, pending = ui.pending, secondsLeft = x.secondsLeft, enabled = x.canAct,
        onPress = x.press, onMore = x.onMore, modifier = Modifier.fillMaxWidth().height(52.dp).cached(), icons = m.icons, media = ui.media,
        compact = true,
    )
}

// ───────────────────────── piezas ─────────────────────────

/** La cara en una ventana pequeña (sin su tarjeta): conserva sus gestos de datos. */
@Composable
private fun MiniFace(x: LayoutArgs, modifier: Modifier) {
    val m = x.m
    val ui = m.ui
    LedFaceView(
        mood = x.mood, pulse = m.pulse, pulseAmount = m.pulseAmount, color = x.color, notice = null, noticeKey = 0,
        countdown = timerLeft(m)?.let(::countdownText), glance = x.glance, modifier = modifier,
        extras = FaceExtras(hot = (ui.telemetry?.gpuTemp ?: 0) >= 78, low = lowBattery(m.battery, m.charging), game = ui.game != null),
        lens = true,
    )
}

private val tap get() = MutableInteractionSource()

/** Un dato suelto: nombre pequeño arriba y cifra de puntos debajo. */
@Composable
private fun Bit(name: String, value: String, color: Color = Vega.Ink) {
    Column {
        Text(name, maxLines = 1, style = label10.copy(fontSize = 9.sp, letterSpacing = 0.8.sp, color = Vega.Ink3))
        Text(value, maxLines = 1, style = doto(15.sp, color))
    }
}

@Composable
private fun TopData(x: LayoutArgs, modifier: Modifier = Modifier, count: Int = 4) {
    val m = x.m
    val t = m.ui.telemetry
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Bit("CPU", t?.cpu?.let { "$it %" } ?: "—", if ((t?.cpu ?: 0) >= 90) Vega.Red else Vega.Ink)
        Bit("GPU", t?.gpuTemp?.let { "$it°" } ?: "—", if ((t?.gpuTemp ?: 0) >= 80) Vega.Red else Vega.Amber)
        Bit("VÍDEO", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", Vega.Mint)
        if (count > 3) Bit("BAT", if (m.battery >= 0) "${m.battery} %" else "—", if (lowBattery(m.battery, m.charging)) Vega.Red else if (m.charging) Vega.Mint else Vega.Ink)
    }
}

// ───────────────────────── reposo ─────────────────────────

@Composable
private fun RestScene(x: LayoutArgs) {
    val m = x.m
    val (link, linkColor) = linkText(m)
    Box(Modifier.fillMaxSize()) {
        FaceCard(
            x.mood, x.color, m, x.glance, x.aura, onChat = x.onChat, confirmFraction = x.confirmFraction, onRetry = x.a.retryBrain,
            modifier = Modifier.fillMaxSize().padding(horizontal = 96.dp).attention(x), bare = true, compactReply = true, showHeader = false,
        )
        Column(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 2.dp)) {
            Text(m.clock, maxLines = 1, style = doto(34.sp, Vega.Ink))
            Text(m.date, maxLines = 1, style = label10)
            Text(link, maxLines = 1, style = label10.copy(color = linkColor))
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(x.color)
                Spacer(Modifier.width(7.dp))
                Text(moodLabel(x.mood, m.ui.assistant), maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = x.color))
            }
        }
        TopData(x, Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 4.dp))
        val micOn = m.listening && m.micOn
        RoundKey("mic", if (micOn) Vega.Coral else Vega.Ink2, x.a.toggleMic, filled = micOn, size = 34.dp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp))
        RoundKey("sleep", Vega.Ink2, x.a.sleep, size = 34.dp, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp))
        // El próximo aviso, abajo a la derecha, junto al botón de dormir.
        val next = m.ui.telemetry?.upcoming?.firstOrNull()
        if (next != null) {
            Column(Modifier.align(Alignment.CenterEnd).padding(end = 8.dp), horizontalAlignment = Alignment.End) {
                Text(countdown(next.dueIn - x.elapsed), maxLines = 1, style = doto(24.sp, Vega.Amber))
                Text(if (next.kind == "timer") "temporizador" else next.text, maxLines = 2, style = Vega.label.copy(fontSize = 11.sp, color = Vega.Ink2))
            }
        }
    }
}

// ───────────────────────── música ─────────────────────────

@Composable
private fun MusicScene(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val media = ui.media ?: return
    val accent = m.mediaAccent
    val position = livePosition(media, ui.mediaAt, m.clockMs)
    Box(
        Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))
            .background(Brush.horizontalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.12f), Color.Transparent)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)))),
    ) {
        // El espectro, al fondo, a todo lo ancho.
        MusicWave(m.musicLevel, m.musicSpectrum, accent.copy(alpha = 0.85f), m.waveStyle, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(58.dp))
        Row(Modifier.fillMaxSize().padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Art(m.mediaArt, accent, Modifier.size(236.dp).clickable(interactionSource = tap, indication = null, onClick = x.onOpenMusic), 20.dp)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Text((if (media.playing) "SONANDO EN " else "EN PAUSA · ") + media.app.uppercase(), maxLines = 1, style = label10.copy(fontSize = 10.sp, letterSpacing = 1.sp, color = accent))
                Spacer(Modifier.height(4.dp))
                Text(media.title, maxLines = 2, style = Vega.body.copy(fontSize = 28.sp, lineHeight = 31.sp, fontWeight = FontWeight.Bold))
                Text(media.artist, maxLines = 1, style = Vega.body.copy(fontSize = 15.sp, color = Vega.Ink2))
                if (media.album.isNotBlank()) Text(media.album, maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink3))
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clockTime(position), maxLines = 1, style = doto(15.sp, Vega.Ink), modifier = Modifier.width(52.dp))
                    Progress(position, media.duration, accent, Modifier.weight(1f), 5.dp)
                    Text(if (media.duration > 0) clockTime(media.duration) else "", maxLines = 1, style = doto(15.sp, Vega.Ink3), modifier = Modifier.width(52.dp).padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 46.dp), horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    TransportButton("previous", 44.dp, Vega.Ink, media.canPrevious, { x.a.mediaControl("previous") })
                    TransportButton(if (media.playing) "pause" else "play", 58.dp, accent, true, { x.a.mediaControl("play_pause") }, filled = true)
                    TransportButton("next", 44.dp, Vega.Ink, media.canNext, { x.a.mediaControl("next") })
                }
            }
            Column(Modifier.width(150.dp).fillMaxHeight(), horizontalAlignment = Alignment.End) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.size(150.dp, 66.dp).card(x.aura, radius = 16.dp).clickable(interactionSource = tap, indication = null, onClick = x.onChat).padding(4.dp)) {
                    MiniFace(x, Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(10.dp))
                Text(m.clock, maxLines = 1, style = doto(26.sp, Vega.Ink))
                TopData(x, Modifier.padding(top = 6.dp).width(150.dp), count = 3)
            }
        }
    }
}

// ───────────────────────── juego ─────────────────────────

@Composable
private fun GameScene(x: LayoutArgs) {
    val m = x.m
    val ui = m.ui
    val game = ui.game ?: return
    val accent = m.gameAccent
    val t = ui.telemetry
    val session = liveSession(game, ui.gameAt, m.clockMs, tickMs = 15_000)
    val match = game.match
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp))) {
        // La portada de fondo, desvaneciéndose hacia la derecha.
        if (m.gameArt != null) Image(m.gameArt, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.5f)
        else Box(Modifier.fillMaxSize().background(accent.copy(alpha = 0.2f)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.7f), Color.Black.copy(alpha = 0.95f)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.6f)))))
        Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 12.dp, top = 16.dp, bottom = 8.dp)) {
            Column(Modifier.weight(1f).fillMaxHeight().clickable(interactionSource = tap, indication = null, onClick = x.onOpenGame)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(accent, 7.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("JUGANDO · " + game.source.uppercase(), maxLines = 1, style = label10.copy(fontSize = 10.sp, letterSpacing = 1.sp, color = accent))
                }
                Spacer(Modifier.height(4.dp))
                Text(game.name, maxLines = 2, style = Vega.body.copy(fontSize = 30.sp, lineHeight = 33.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.weight(1f))
                if (match != null && match.score.isNotEmpty()) {
                    ScoreBoard(match.score, match.colors, accent, 40.sp)
                    Text(match.title, maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2))
                    match.stats.take(3).let { st ->
                        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            st.forEach { (k, v) -> Bit(k.uppercase(), v) }
                        }
                    }
                } else {
                    Text(sessionClock(session), maxLines = 1, style = doto(58.sp, Vega.Ink))
                    Text("de partida hoy", maxLines = 1, style = Vega.label.copy(fontSize = 12.sp, color = Vega.Ink2))
                }
                Spacer(Modifier.height(24.dp))
            }
            Column(Modifier.width(230.dp).fillMaxHeight(), horizontalAlignment = Alignment.End) {
                Box(Modifier.size(150.dp, 62.dp).card(x.aura, radius = 16.dp).clickable(interactionSource = tap, indication = null, onClick = x.onChat).padding(4.dp)) {
                    MiniFace(x, Modifier.fillMaxSize())
                }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    ArcGauge((t?.gpuUtil ?: 0) / 100f, t?.gpuUtil?.let { "$it" } ?: "—", "GPU %", if ((t?.gpuUtil ?: 0) >= 95) Vega.Red else accent, Modifier.size(106.dp))
                    ArcGauge((((t?.gpuTemp ?: 30) - 30) / 60f), t?.gpuTemp?.let { "$it°" } ?: "—", "TEMP", if ((t?.gpuTemp ?: 0) >= 80) Vega.Red else Vega.Amber, Modifier.size(106.dp))
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
