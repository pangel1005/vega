package com.asistente.personal.ui2

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.Telemetry
import com.asistente.personal.ui.AlbumDisc
import com.asistente.personal.ui.GAME_ACTIONS
import com.asistente.personal.ui.GameSample
import com.asistente.personal.ui.GameSection
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.agoLabel
import com.asistente.personal.ui.clockTime
import com.asistente.personal.ui.currentLine
import com.asistente.personal.ui.es
import com.asistente.personal.ui.friendsLabel
import com.asistente.personal.ui.gameSections
import com.asistente.personal.ui.hoursLabel
import com.asistente.personal.ui.liveSession
import com.asistente.personal.ui.livePosition
import com.asistente.personal.ui.matchSeconds
import com.asistente.personal.ui.playersLabel
import com.asistente.personal.ui.sessionClock
import com.asistente.personal.ui.sessionStats
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** El texto que se lee sobre un color de acento: oscuro si el color es claro, blanco si es oscuro. */
internal fun onAccent(accent: Color): Color = if (accent.luminance() > 0.5f) Color(0xFF1B1812) else Color.White

@Composable
internal fun Chip2(label: String, selected: Boolean, p: Pal, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(if (selected) accent else p.tile2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, maxLines = 1, style = Type2.body(12.sp, if (selected) onAccent(accent) else p.ink2, FontWeight.SemiBold)) }
}

/**
 * La barra de progreso con tirador: un toque salta a ese punto y arrastrar busca (se ve a dónde se saltaría y, al soltar, se
 * salta). La zona que responde es más alta que la barra para acertar con el dedo, y el punto que se toca es exactamente el
 * que se busca (la posición se mide sobre toda la anchura, no sobre lo arrastrado).
 */
@Composable
internal fun SeekBar2(
    frac: Float, duration: Double, color: Color, track: Color,
    onScrub: (Float?) -> Unit, onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier, touchHeight: androidx.compose.ui.unit.Dp = 34.dp, barHeight: androidx.compose.ui.unit.Dp = 8.dp,
) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf(false) }
    val last = remember { floatArrayOf(0f) }
    val knob by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1f else 0f, tween(120), label = "tirador")
    Box(
        modifier.height(touchHeight).onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(duration) {
                detectTapGestures { o -> if (duration > 0) onSeek((o.x / width).coerceIn(0f, 1f) * duration) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o ->
                        dragging = true
                        last[0] = (o.x / width).coerceIn(0f, 1f)
                        onScrub(last[0])
                    },
                    onDragEnd = {
                        dragging = false
                        onScrub(null)
                        if (duration > 0) onSeek(last[0] * duration)
                    },
                    onDragCancel = {
                        dragging = false
                        onScrub(null)
                    },
                ) { change, _ ->
                    change.consume()
                    last[0] = (change.position.x / width).coerceIn(0f, 1f)
                    onScrub(last[0])
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Capsule(frac, color, track, Modifier.fillMaxWidth(), h = barHeight)
        Canvas(Modifier.fillMaxSize()) {
            val r = (barHeight.toPx() * 0.7f) + 5.dp.toPx() * knob
            val cx = (frac * size.width).coerceIn(r, size.width - r)
            drawCircle(Color.White, r + 1.5.dp.toPx(), Offset(cx, size.height / 2))
            drawCircle(color, r, Offset(cx, size.height / 2))
        }
    }
}

// ───────────────────────── música ─────────────────────────

@Composable
internal fun MusicScreen2(m: ScreenModel, a: ScreenActions, p: Pal, startWithLyrics: Boolean = false, onClose: () -> Unit) {
    val ui = m.ui
    val media = ui.media ?: return
    val accent = m.mediaAccent
    val tint by animateColorAsState(accent, tween(600), label = "tinte")
    val lyrics = m.lyrics
    val hasLyrics = lyrics != null && (lyrics.lines.isNotEmpty() || lyrics.instrumental)
    var showLyrics by remember { mutableStateOf(startWithLyrics) }
    val lyricsMode = showLyrics && hasLyrics
    val pos = livePosition(media, ui.mediaAt, m.clockMs, tickMs = if (lyricsMode) 200 else 1000)
    // Mientras se gira el disco o se arrastra la barra se ve a dónde se saltaría; al soltar se mantiene ese punto
    // hasta que el PC confirma el salto.
    var scrub by remember { mutableStateOf<Float?>(null) }
    var discScrub by remember { mutableStateOf<Double?>(null) }
    var held by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val shownPos = scrub?.let { it * media.duration } ?: discScrub
        ?: held?.takeIf { m.clockMs() - it.second < 2500 && abs(pos - it.first) > 3.0 }?.first ?: pos
    val frac = if (media.duration > 0) (shownPos / media.duration).toFloat().coerceIn(0f, 1f) else 0f

    Box(Modifier.fillMaxSize().background(p.bg).background(Brush.horizontalGradient(listOf(tint.copy(alpha = if (p.night) 0.28f else 0.4f), Color.Transparent)))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text((if (media.playing) "SONANDO EN " else "EN PAUSA · ") + media.app.uppercase(), maxLines = 1, style = Type2.label(p.ink2))
                Spacer(Modifier.weight(1f))
                Text(m.clock, maxLines = 1, style = Type2.num(22.sp, p.ink2))
                Spacer(Modifier.width(14.dp))
                if (hasLyrics) {
                    Chip2("Letra", lyricsMode, p, tint) { showLyrics = !showLyrics }
                    Spacer(Modifier.width(8.dp))
                }
                RoundBtn("cancel", 36.dp, p.tile2, p.ink, onClose)
            }
            Spacer(Modifier.height(12.dp))
            AnimatedContent(
                targetState = lyricsMode, label = "letra", modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            ) { withLyrics ->
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    if (!withLyrics) {
                        // La portada hecha disco (el de siempre): gira con inercia, late con la música, lleva el anillo de
                        // espectro y se gira con el dedo para adelantar o retroceder. Un toque pausa; doble toque, portada plana.
                        AlbumDisc(
                            m.mediaArt, tint, media.playing, pos, media.duration,
                            onScrub = { discScrub = it },
                            onSeek = { target ->
                                held = target to m.clockMs()
                                a.mediaSeek(target)
                            },
                            onToggle = { a.mediaControl("play_pause") },
                            modifier = Modifier.fillMaxHeight().aspectRatio(1f),
                            flat = m.musicFlat,
                            onDoubleTap = a.toggleMusicFlat,
                            level = m.musicLevel,
                            spectrum = m.musicSpectrum,
                            scrubStyle = Type2.num(30.sp, tint),
                        )
                    }
                    Column(Modifier.then(if (withLyrics) Modifier.width(310.dp) else Modifier.weight(1f)).fillMaxHeight()) {
                        if (withLyrics) {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Cover(m.mediaArt, tint, "note", 92.dp)
                                Column(Modifier.weight(1f)) {
                                    Text(media.title, maxLines = 3, style = Type2.body(19.sp, p.ink, FontWeight.Bold, 22.sp))
                                    Spacer(Modifier.height(3.dp))
                                    Text(media.artist, maxLines = 1, style = Type2.body(14.sp, p.ink2))
                                }
                            }
                        } else {
                            Text(media.title, maxLines = 3, style = Type2.body(30.sp, p.ink, FontWeight.Bold, 34.sp))
                            Spacer(Modifier.height(4.dp))
                            Text(media.artist, maxLines = 1, style = Type2.body(17.sp, p.ink2))
                            if (media.album.isNotBlank()) Text(media.album, maxLines = 1, style = Type2.body(13.sp, p.ink3))
                        }
                        Spacer(Modifier.weight(1f))
                        // La barra: se toca para saltar y se arrastra (con tirador) para buscar.
                        SeekBar2(
                            frac, media.duration, tint, p.tile2,
                            onScrub = { scrub = it },
                            onSeek = { target ->
                                held = target to m.clockMs()
                                a.mediaSeek(target)
                            },
                            modifier = Modifier.fillMaxWidth().testTag("seek"),
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(clockTime(shownPos), maxLines = 1, style = Type2.body(12.sp, p.ink2))
                            Spacer(Modifier.weight(1f))
                            Text(if (media.duration > 0) clockTime(media.duration) else "", maxLines = 1, style = Type2.body(12.sp, p.ink3))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            RoundBtn("prev", 46.dp, p.tile2, p.ink, { a.mediaControl("previous") })
                            RoundBtn(if (media.playing) "pause" else "play-solid", 58.dp, tint, onAccent(tint), { a.mediaControl("play_pause") })
                            RoundBtn("next", 46.dp, p.tile2, p.ink, { a.mediaControl("next") })
                        }
                    }
                    if (withLyrics && lyrics != null) Lyrics2(lyrics, pos, p, tint, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun Lyrics2(lyrics: com.asistente.personal.net.Lyrics, position: Double, p: Pal, tint: Color, modifier: Modifier) {
    if (lyrics.instrumental) {
        Box(modifier, contentAlignment = Alignment.Center) { Text("Instrumental ♪", style = Type2.num(26.sp, p.ink2)) }
        return
    }
    val current = if (lyrics.synced) currentLine(lyrics.lines, position) else -1
    val state = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0)) }
    LazyColumn(state = state, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 40.dp)) {
        itemsIndexed(lyrics.lines) { i, line ->
            val now = i == current
            val color by animateColorAsState(
                when { !lyrics.synced -> p.ink2; now -> p.ink; i < current -> p.ink3.copy(alpha = 0.6f); else -> p.ink3 }, tween(250), label = "línea",
            )
            Text(
                line.text.ifEmpty { "♪" }, style = Type2.body(if (now) 26.sp else 19.sp, if (line.text.isEmpty() && now) tint else color, if (now) FontWeight.Bold else FontWeight.Medium, if (now) 31.sp else 24.sp),
                modifier = Modifier.padding(vertical = 5.dp),
            )
        }
    }
}

// ───────────────────────── juego ─────────────────────────

private fun hexColor(s: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(s)) }.getOrDefault(fallback)

@Composable
internal fun GameScreen2(
    m: ScreenModel, a: ScreenActions, p: Pal, onClose: () -> Unit, samples: List<GameSample>, canAct: Boolean,
) {
    val ui = m.ui
    val game = ui.game ?: return
    val accent = m.gameAccent
    val seconds = liveSession(game, ui.gameAt, m.clockMs)
    val sections = gameSections(game)
    var chosen by remember { mutableStateOf<GameSection?>(null) }
    val shown = chosen?.takeIf { it in sections } ?: sections.first()
    Box(Modifier.fillMaxSize().background(p.bg).background(Brush.horizontalGradient(listOf(accent.copy(alpha = if (p.night) 0.22f else 0.32f), Color.Transparent)))) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(p.red, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text("JUGANDO EN " + game.source.uppercase(), maxLines = 1, style = Type2.label(p.ink2))
                Spacer(Modifier.weight(1f))
                Text(m.clock, maxLines = 1, style = Type2.num(22.sp, p.ink2))
                Spacer(Modifier.width(14.dp))
                RoundBtn("cancel", 36.dp, p.tile2, p.ink, onClose)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.width(268.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2.14f).clip(RoundedCornerShape(26.dp))) {
                        if (m.gameArt != null) androidx.compose.foundation.Image(m.gameArt, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Box(Modifier.fillMaxSize().background(accent))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("EN ESTA SESIÓN", maxLines = 1, style = Type2.label(p.ink3))
                    Text(sessionClock(seconds), maxLines = 1, style = Type2.num(54.sp, accent))
                    val total = listOfNotNull(
                        game.hours?.let { "${hoursLabel(it)} jugadas en total" },
                        game.sizeGb?.let { "%.1f GB en disco".format(es, it) },
                    ).joinToString(" · ")
                    if (total.isNotEmpty()) Text(total, maxLines = 2, style = Type2.body(12.sp, p.ink2))
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Text(game.name, maxLines = 2, style = Type2.body(30.sp, p.ink, FontWeight.Bold, 33.sp))
                    val by = listOf(game.developer, game.released).filter { it.isNotEmpty() }.joinToString(" · ")
                    if (by.isNotEmpty()) Text(by, maxLines = 1, style = Type2.body(14.sp, p.ink2))
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        sections.forEach { s -> Chip2(s.label, s == shown, p, accent) { chosen = s } }
                    }
                    Spacer(Modifier.height(12.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            targetState = shown, label = "sección",
                            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 12 }) togetherWith fadeOut(tween(120)) },
                        ) { s ->
                            when (s) {
                                GameSection.MATCH -> game.match?.let { MatchPart(it, ui.gameAt, m, p, accent) }
                                GameSection.SESSION -> SessionPart(game, samples, ui.telemetry, p, accent)
                                GameSection.GAME -> InfoPart(game, p, accent)
                                GameSection.COMMUNITY -> CommunityPart(game, ui.steamAccount, p, accent)
                                GameSection.ACHIEVEMENTS -> AchievementsPart(game, ui.steamAccount, p, accent)
                            }
                        }
                    }
                }
            }
            val handy = GAME_ACTIONS.mapNotNull { id -> ui.actions.firstOrNull { it.id == id } }
            if (handy.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    handy.forEach { act ->
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).background(p.tile).clickable { if (canAct) a.press(act.id) }.padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(24.dp).background(accent.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) { Icon2(act.icon, accent, 14.dp) }
                            Spacer(Modifier.width(7.dp))
                            Text(act.label, maxLines = 1, style = Type2.body(12.sp, p.ink, FontWeight.Medium))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchPart(match: com.asistente.personal.net.MatchInfo, receivedAt: Long, m: ScreenModel, p: Pal, accent: Color) {
    val seconds = matchSeconds(match, receivedAt, m.clockMs())
    Column(Modifier.fillMaxWidth()) {
        if (match.score.isEmpty() && match.stats.isEmpty()) {
            Text(match.hint.ifEmpty { "Sin datos de la partida todavía." }, style = Type2.body(14.sp, p.ink2, FontWeight.Normal, 19.sp))
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            val parts = match.score.split(" – ")
            parts.forEachIndexed { i, s ->
                if (i > 0) Text("–", style = Type2.num(40.sp, p.ink3), modifier = Modifier.padding(horizontal = 8.dp))
                Text(s, style = Type2.num(40.sp, match.colors.getOrNull(i)?.let { hexColor(it, p.ink) } ?: p.ink))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.padding(bottom = 6.dp)) {
                Text(match.title, maxLines = 1, style = Type2.body(13.sp, p.ink2))
                seconds?.let { Text(sessionClock(it), maxLines = 1, style = Type2.body(12.sp, p.ink3)) }
            }
        }
        if (match.stats.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(p.tile).padding(vertical = 7.dp, horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                match.stats.take(6).forEach { (label, value) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(value, maxLines = 1, style = Type2.body(16.sp, p.ink, FontWeight.Bold))
                        Text(label.uppercase(), maxLines = 1, style = Type2.label(p.ink3).copy(fontSize = 9.sp))
                    }
                }
            }
        }
        match.feed.take(2).forEach {
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(it, maxLines = 1, style = Type2.body(13.sp, p.ink2))
            }
        }
    }
}

@Composable
private fun SessionPart(game: GameInfo, samples: List<GameSample>, t: Telemetry?, p: Pal, accent: Color) {
    val stats = sessionStats(samples)
    val vram = if (t?.vramUsed != null && t.vramTotal != null && t.vramTotal > 0) (t.vramUsed / t.vramTotal).toFloat() else null
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent, CircleShape)); Spacer(Modifier.width(5.dp)); Text("GPU", style = Type2.label(p.ink2))
            Spacer(Modifier.width(12.dp))
            Box(Modifier.size(8.dp).background(p.sun, CircleShape)); Spacer(Modifier.width(5.dp)); Text("TEMPERATURA", style = Type2.label(p.ink2))
            Spacer(Modifier.weight(1f)); Text("esta sesión", style = Type2.label(p.ink3))
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(70.dp).clip(RoundedCornerShape(18.dp)).background(p.tile)) {
            if (samples.size < 2) return@Canvas
            val step = max(1, samples.size / 120)
            val pts = samples.filterIndexed { i, _ -> i % step == 0 }
            fun path(value: (GameSample) -> Float?): Path = Path().apply {
                var started = false
                pts.forEachIndexed { i, s ->
                    val v = value(s) ?: return@forEachIndexed
                    val x = size.width * i / (pts.size - 1)
                    val y = size.height * (1f - v.coerceIn(0f, 1f)) * 0.86f + size.height * 0.07f
                    if (!started) { moveTo(x, y); started = true } else lineTo(x, y)
                }
            }
            val gpu = path { it.gpu?.div(100f) }
            val area = Path().apply { addPath(gpu); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)))
            drawPath(gpu, accent, style = Stroke(width = 2.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawPath(path { it.temp?.let { t2 -> (t2 - 30) / 60f } }, p.sun, style = Stroke(width = 2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Spacer(Modifier.height(8.dp))
        GaugeRow(p, "GPU", t?.gpuUtil?.let { "$it %" } ?: "—", t?.gpuUtil?.div(100f), accent, Modifier.fillMaxWidth().height(22.dp))
        GaugeRow(p, "TEMP", t?.gpuTemp?.let { "$it°" } ?: "—", t?.gpuTemp?.let { ((it - 30) / 60f).coerceIn(0f, 1f) }, p.sun, Modifier.fillMaxWidth().height(22.dp))
        GaugeRow(p, "VÍDEO", t?.vramUsed?.let { "%.1f".format(es, it) } ?: "—", vram, p.mint, Modifier.fillMaxWidth().height(22.dp))
        val notes = listOfNotNull(
            stats.gpuAvg?.let { "GPU media $it %" }, stats.tempMax?.let { "máx. $it°" },
            game.played2w?.let { "últimas 2 semanas: ${hoursLabel(it)}" },
        )
        if (notes.isNotEmpty()) Text(notes.joinToString(" · "), maxLines = 2, style = Type2.body(12.sp, p.ink2), modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun InfoPart(game: GameInfo, p: Pal, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        val chips = game.genres + game.tags
        if (chips.isNotEmpty() || game.rating != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                game.rating?.let { score ->
                    val c = if (score >= 75) p.mint else if (score >= 50) p.sun else p.red
                    Text("$score", style = Type2.body(14.sp, onAccent(c), FontWeight.Bold), modifier = Modifier.background(c, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 3.dp))
                }
                chips.take(5).forEach { Text(it, maxLines = 1, style = Type2.body(12.sp, p.ink2), modifier = Modifier.background(p.tile2, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 5.dp)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (game.summary.isNotEmpty()) Text(game.summary, maxLines = 6, style = Type2.body(14.sp, p.ink2, FontWeight.Normal, 19.sp))
        else Text("Sin descripción (la tienda de Steam no está activada o no contestó).", style = Type2.body(12.sp, p.ink3))
    }
}

@Composable
private fun CommunityPart(game: GameInfo, account: Boolean, p: Pal, accent: Color) {
    val wall = System.currentTimeMillis() / 1000
    Column(Modifier.fillMaxWidth()) {
        game.players?.let {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(playersLabel(it), style = Type2.num(38.sp, accent))
                Spacer(Modifier.width(8.dp))
                Text("jugando ahora mismo", style = Type2.body(13.sp, p.ink2), modifier = Modifier.padding(bottom = 4.dp))
            }
        }
        if (game.friends.isNotEmpty()) Text(friendsLabel(game.friends), maxLines = 2, style = Type2.body(14.sp, p.readable(p.mint)))
        else if (!account) Text("Conecta tu cuenta de Steam para ver a tus amigos aquí.", style = Type2.body(12.sp, p.ink3))
        if (game.news.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("NOVEDADES", style = Type2.label(p.ink3))
            game.news.take(3).forEach { item ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.title, maxLines = 1, modifier = Modifier.weight(1f), style = Type2.body(13.sp, p.ink))
                    Spacer(Modifier.width(8.dp))
                    Text(agoLabel(item.at, wall), maxLines = 1, style = Type2.body(12.sp, p.ink3))
                }
            }
        }
    }
}

@Composable
private fun AchievementsPart(game: GameInfo, account: Boolean, p: Pal, accent: Color) {
    Column(Modifier.fillMaxWidth()) {
        val total = game.achievements
        val mine = game.achUnlocked
        if (mine != null && total != null && total > 0) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$mine", style = Type2.num(38.sp, accent))
                Text(" / $total logros", style = Type2.body(14.sp, p.ink2), modifier = Modifier.padding(bottom = 4.dp))
            }
            Spacer(Modifier.height(6.dp))
            Capsule(mine.toFloat() / total, accent, p.tile2, Modifier.fillMaxWidth(), h = 8.dp)
            if (game.achRecent.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("ÚLTIMOS DESBLOQUEADOS", style = Type2.label(p.ink3))
                game.achRecent.take(3).forEach { Text("★ $it", maxLines = 1, style = Type2.body(13.sp, p.ink), modifier = Modifier.padding(top = 4.dp)) }
            }
        } else {
            total?.let { Text("$it logros en total", style = Type2.body(17.sp, p.ink, FontWeight.Bold)) }
            Spacer(Modifier.height(4.dp))
            Text(
                if (account) "No pude leer tus logros: en Steam, tu perfil debe tener «Detalles del juego» en público."
                else "Conecta tu cuenta de Steam para ver cuántos llevas y cuáles te faltan.",
                style = Type2.body(12.sp, p.ink3),
            )
        }
    }
}
