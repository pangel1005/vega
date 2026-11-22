package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.MediaInfo
import com.asistente.personal.net.LyricLine
import com.asistente.personal.net.Lyrics
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.PaddingValues
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

/** «3:17»: minutos y segundos de una canción. */
fun clockTime(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

/**
 * Posición actual: el PC la manda al cambiar algo y el teléfono la avanza solo mientras
 * suena (un tic por segundo, o más a menudo con la letra a la vista).
 */
@Composable
fun livePosition(media: MediaInfo, receivedAt: Long, now: () -> Long, tickMs: Long = 1000): Double {
    var pos by remember(media, receivedAt) { mutableStateOf(media.position) }
    LaunchedEffect(media, receivedAt, tickMs) {
        while (media.playing) {
            val elapsed = (now() - receivedAt) / 1000.0
            pos = (media.position + elapsed).let { if (media.duration > 0) it.coerceAtMost(media.duration) else it }
            delay(tickMs)
        }
    }
    return pos
}

/** Barra de progreso fina con los tiempos a los lados (o debajo, en pequeño). */
@Composable
internal fun Progress(position: Double, duration: Double, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    val f = if (duration > 0) (position / duration).toFloat().coerceIn(0f, 1f) else 0f
    Canvas(modifier.height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color(0x33FFFFFF), cornerRadius = r)
        drawRoundRect(color, size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

/** Botón redondo de reproducción (anterior, pausa/play, siguiente). Salta al tocar. */
@Composable
private fun TransportButton(
    kind: String,
    size: Dp,
    color: Color,
    enabled: Boolean,
    onPress: () -> Unit,
    filled: Boolean = false,
) {
    val pressed = remember { mutableStateOf(false) }
    Box(
        Modifier.size(size)
            .graphicsLayer {
                val s = if (pressed.value) 0.88f else 1f
                scaleX = s
                scaleY = s
                alpha = if (enabled) 1f else 0.3f
            }
            .background(if (filled) color else if (pressed.value) Vega.CardHi else Color.Transparent, RoundedCornerShape(50))
            .instantPress(enabled = enabled, pressed = pressed, onPress = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) { drawTransport(kind, if (filled) Color(0xFF0B0B0B) else color) }
    }
}

private fun DrawScope.drawTransport(kind: String, c: Color) {
    val w = size.width
    fun tri(x0: Float, x1: Float) = Path().apply {
        moveTo(x0 * w, 0.1f * w); lineTo(x1 * w, 0.5f * w); lineTo(x0 * w, 0.9f * w); close()
    }
    when (kind) {
        "play" -> drawPath(tri(0.2f, 0.9f), c)
        "pause" -> {
            drawRoundRect(c, Offset(0.18f * w, 0.1f * w), Size(0.22f * w, 0.8f * w), CornerRadius(0.05f * w))
            drawRoundRect(c, Offset(0.6f * w, 0.1f * w), Size(0.22f * w, 0.8f * w), CornerRadius(0.05f * w))
        }
        "next" -> {
            drawPath(tri(0.05f, 0.7f), c)
            drawRoundRect(c, Offset(0.75f * w, 0.1f * w), Size(0.14f * w, 0.8f * w), CornerRadius(0.04f * w))
        }
        else -> { // previous
            drawPath(Path().apply { moveTo(0.95f * w, 0.1f * w); lineTo(0.3f * w, 0.5f * w); lineTo(0.95f * w, 0.9f * w); close() }, c)
            drawRoundRect(c, Offset(0.11f * w, 0.1f * w), Size(0.14f * w, 0.8f * w), CornerRadius(0.04f * w))
        }
    }
}

/** Ecualizador pequeño (5 barras) que se mueve mientras suena; quieto en pausa. */
@Composable
internal fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        while (playing) {
            withFrameMillis { t = it / 1000f }
            delay(60) // ~15 FPS: de sobra para unas barras
        }
    }
    Canvas(modifier) {
        val n = 5
        val bw = size.width / (n * 1.7f)
        for (i in 0 until n) {
            val level = if (playing) 0.25f + 0.75f * abs(sin(t * (2.1f + i * 0.7f) + i)) else 0.2f
            val h = size.height * level
            drawRoundRect(color, Offset(i * bw * 1.7f, size.height - h), Size(bw, h), CornerRadius(bw / 2))
        }
    }
}

@Composable
internal fun Art(art: ImageBitmap?, accent: Color, modifier: Modifier, radius: Dp) {
    val shape = RoundedCornerShape(radius)
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        Box(modifier.clip(shape).background(accent.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize(0.35f)) { drawDeckIcon("play", accent) }
        }
    }
}

/**
 * Mini reproductor (columna derecha, en lugar del estado del PC mientras suena algo).
 * Tocar la carátula o el título abre la pantalla «Sonando».
 */
@Composable
fun NowPlayingCard(
    media: MediaInfo,
    position: Double,
    art: ImageBitmap?,
    accent: Color,
    onControl: (String) -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.card().padding(horizontal = 12.dp, vertical = 10.dp)) {
        Row(
            Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(art, accent, Modifier.size(58.dp), 12.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Equalizer(media.playing, accent, Modifier.size(width = 14.dp, height = 10.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(media.app.uppercase(), maxLines = 1, style = Vega.label.copy(fontSize = 9.sp, letterSpacing = 1.sp, color = accent))
                }
                Text(media.title, maxLines = 1, style = Vega.body.copy(fontSize = 14.sp, lineHeight = 17.sp, fontWeight = FontWeight.Bold))
                Text(media.artist, maxLines = 1, style = Vega.label.copy(fontSize = 12.sp))
            }
        }
        Spacer(Modifier.weight(1f))
        Progress(position, media.duration, accent, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clockTime(position), style = Vega.label.copy(fontSize = 9.sp, color = Vega.Ink3))
            Text(if (media.duration > 0) clockTime(media.duration) else "", style = Vega.label.copy(fontSize = 9.sp, color = Vega.Ink3))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TransportButton("previous", 34.dp, Vega.Ink, media.canPrevious, { onControl("previous") })
            TransportButton(if (media.playing) "pause" else "play", 40.dp, accent, true, { onControl("play_pause") }, filled = true)
            TransportButton("next", 34.dp, Vega.Ink, media.canNext, { onControl("next") })
        }
    }
}

/**
 * Pantalla «Sonando»: carátula grande, canción, artista, progreso y botones grandes. El
 * fondo toma el color de la carátula. Con «Letra» se reorganiza: a la izquierda, compacto,
 * lo de la canción; a la derecha, la letra avanzando sola con la línea actual iluminada.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MusicScreen(
    media: MediaInfo,
    receivedAt: Long,
    now: () -> Long,
    art: ImageBitmap?,
    accent: Color,
    clock: String,
    lyrics: Lyrics?,
    onControl: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    startWithLyrics: Boolean = false,
    onSeek: (Double) -> Unit = {},
    flat: Boolean = false,
    onToggleFlat: () -> Unit = {},
    level: () -> Float = { 0f },
    spectrum: () -> FloatArray = { FloatArray(0) },
) {
    val artImage = art
    val tint by animateColorAsState(accent, tween(600), label = "color de la carátula")
    val hasLyrics = lyrics != null && (lyrics.lines.isNotEmpty() || lyrics.instrumental)
    var showLyrics by remember { mutableStateOf(startWithLyrics) }
    val lyricsMode = showLyrics && hasLyrics
    // Con letra, la posición se actualiza 5 veces por segundo para que las líneas vayan a tiempo.
    val position = livePosition(media, receivedAt, now, tickMs = if (lyricsMode) 200 else 1000)
    // Al girar el vinilo se ve a dónde se saltaría; al soltar se mantiene ese punto hasta que el PC confirma el salto.
    var scrub by remember { mutableStateOf<Double?>(null) }
    var held by remember { mutableStateOf<Pair<Double, Long>?>(null) }
    val shownPosition = scrub ?: held?.takeIf { now() - it.second < 2500 && kotlin.math.abs(position - it.first) > 3.0 }?.first ?: position
    Box(
        modifier.fillMaxSize().background(Vega.Bg)
            .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent))),
    ) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Cabecera común: qué suena, hora, Letra y Volver.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Equalizer(media.playing, tint, Modifier.size(width = 18.dp, height = 13.dp))
                Spacer(Modifier.width(7.dp))
                Text(
                    (if (media.playing) "Sonando en " else "En pausa · ") + media.app,
                    maxLines = 1,
                    style = Vega.label.copy(fontSize = 12.sp, color = tint),
                )
                Spacer(Modifier.weight(1f))
                Text(clock, style = TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 18.sp, color = Vega.Ink3))
                Spacer(Modifier.width(12.dp))
                if (hasLyrics) {
                    Pill("Letra", tint, { showLyrics = !showLyrics }, filled = lyricsMode)
                    Spacer(Modifier.width(8.dp))
                }
                Pill("Volver", Vega.Ink2, onClose)
            }
            Spacer(Modifier.height(12.dp))
            // Al cambiar entre normal y letra, las piezas viajan a su sitio nuevo: la carátula se
            // encoge y se va a la esquina, título y artista se achican a su lado, la barra y los
            // botones se mueven a la columna izquierda, y la letra entra deslizándose.
            SharedTransitionLayout(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = lyricsMode && lyrics != null,
                    transitionSpec = { fadeIn(tween(MORPH_MS)) togetherWith fadeOut(tween(MORPH_MS / 2)) },
                    label = "modo letra",
                ) { withLyrics ->
                    val scope = this
                    val bounds = BoundsTransform { _, _ -> tween(MORPH_MS, easing = FastOutSlowInEasing) }
                    // `staged`: primero se va lo viejo y después llega lo nuevo (botones y barra, que
                    // cambian de forma); sin él, se funden a la vez (título y artista: mismo texto).
                    @Composable
                    fun Modifier.shared(key: String, staged: Boolean = false): Modifier = sharedBounds(
                        rememberSharedContentState(key), scope,
                        boundsTransform = bounds,
                        enter = if (staged) fadeIn(tween(MORPH_MS / 2, delayMillis = MORPH_MS / 2)) else fadeIn(tween(MORPH_MS)),
                        exit = if (staged) fadeOut(tween(MORPH_MS / 2)) else fadeOut(tween(MORPH_MS)),
                        resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                    )
                    val art = Modifier.sharedElement(rememberSharedContentState("carátula"), scope, boundsTransform = bounds)
                    if (withLyrics && lyrics != null) {
                        Row(Modifier.fillMaxSize()) {
                            Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Art(artImage, tint, art.size(84.dp), 14.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        Text(media.title, maxLines = 2, modifier = Modifier.shared("título"), style = Vega.body.copy(fontSize = 17.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold))
                                        Text(media.artist, maxLines = 1, modifier = Modifier.shared("artista"), style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink2))
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                Column(Modifier.shared("progreso", staged = true)) {
                                    Progress(shownPosition, media.duration, tint, Modifier.fillMaxWidth(), height = 5.dp)
                                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(clockTime(shownPosition), style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink2))
                                        Text(if (media.duration > 0) clockTime(media.duration) else "", style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink2))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().shared("botones", staged = true),
                                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TransportButton("previous", 44.dp, Vega.Ink, media.canPrevious, { onControl("previous") })
                                    TransportButton(if (media.playing) "pause" else "play", 56.dp, tint, true, { onControl("play_pause") }, filled = true)
                                    TransportButton("next", 44.dp, Vega.Ink, media.canNext, { onControl("next") })
                                }
                            }
                            Spacer(Modifier.width(24.dp))
                            LyricsView(
                                lyrics, position, tint,
                                Modifier.weight(1f).fillMaxHeight().animateEnterExit(
                                    enter = slideInHorizontally(tween(MORPH_MS, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(MORPH_MS)),
                                    exit = slideOutHorizontally(tween(MORPH_MS)) { it / 3 } + fadeOut(tween(MORPH_MS / 2)),
                                ),
                            )
                        }
                    } else {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            // La portada hecha disco: gira, late con la música y se gira con el dedo para adelantar o retroceder.
                            AlbumDisc(
                                artImage, tint, media.playing, position, media.duration,
                                onScrub = { scrub = it },
                                onSeek = { target ->
                                    held = target to now()
                                    onSeek(target)
                                },
                                onToggle = { onControl("play_pause") },
                                modifier = art.fillMaxHeight().aspectRatio(1f),
                                flat = flat,
                                onDoubleTap = onToggleFlat,
                                level = level,
                                spectrum = spectrum,
                            )
                            Spacer(Modifier.width(28.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                                Text(media.title, maxLines = 2, modifier = Modifier.shared("título"), style = Vega.body.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold))
                                Spacer(Modifier.height(4.dp))
                                Text(media.artist, maxLines = 1, modifier = Modifier.shared("artista"), style = Vega.body.copy(fontSize = 18.sp, color = Vega.Ink2))
                                if (media.album.isNotEmpty() && media.album != media.title) {
                                    Text(media.album, maxLines = 1, style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink3))
                                }
                                Spacer(Modifier.height(18.dp))
                                Column(Modifier.shared("progreso", staged = true)) {
                                    Progress(shownPosition, media.duration, tint, Modifier.fillMaxWidth(), height = 6.dp)
                                    Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(clockTime(shownPosition), style = Vega.label.copy(color = Vega.Ink2))
                                        Text(if (media.duration > 0) clockTime(media.duration) else "", style = Vega.label.copy(color = Vega.Ink2))
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth().shared("botones", staged = true),
                                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    TransportButton("previous", 56.dp, Vega.Ink, media.canPrevious, { onControl("previous") })
                                    Spacer(Modifier.width(28.dp))
                                    TransportButton(if (media.playing) "pause" else "play", 72.dp, tint, true, { onControl("play_pause") }, filled = true)
                                    Spacer(Modifier.width(28.dp))
                                    TransportButton("next", 56.dp, Vega.Ink, media.canNext, { onControl("next") })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Duración de la transición entre el modo normal y el modo letra. */
private const val MORPH_MS = 450

/** Línea de la letra que suena ahora (la última que ya empezó), o -1 antes de la primera. */
fun currentLine(lines: List<LyricLine>, position: Double): Int {
    var current = -1
    for (i in lines.indices) {
        if (lines[i].t <= position + 0.25) current = i else break // un pelín antes: se lee a tiempo
    }
    return current
}

/**
 * La letra: sincronizada, la línea actual va grande y clara, y la lista se desplaza sola
 * para dejarla en el primer tercio. Sin sincronizar, se puede leer deslizando el dedo.
 */
@Composable
private fun LyricsView(lyrics: Lyrics, position: Double, accent: Color, modifier: Modifier = Modifier) {
    if (lyrics.instrumental) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Instrumental ♪", style = Vega.body.copy(fontSize = 22.sp, color = Vega.Ink2))
        }
        return
    }
    val lines = lyrics.lines
    val current = if (lyrics.synced) currentLine(lines, position) else -1
    val state = rememberLazyListState()
    LaunchedEffect(current) {
        if (current >= 0) state.animateScrollToItem((current - 2).coerceAtLeast(0))
    }
    Box(modifier) {
        LazyColumn(
            state = state,
            // La letra entra y sale difuminada arriba y abajo, respetando el fondo de color (una
            // máscara, no una franja negra encima).
            modifier = Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.12f to Color.Black, 0.88f to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
            contentPadding = PaddingValues(vertical = 40.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                val now = i == current
                val color by animateColorAsState(
                    when {
                        !lyrics.synced -> Vega.Ink2
                        now -> Vega.Ink
                        i < current -> Vega.Ink3
                        else -> Vega.Ink3.copy(alpha = 0.75f)
                    },
                    tween(250),
                    label = "línea",
                )
                Text(
                    line.text.ifEmpty { "♪" },
                    style = Vega.body.copy(
                        fontSize = if (now) 24.sp else 19.sp,
                        lineHeight = if (now) 29.sp else 24.sp,
                        fontWeight = if (now) FontWeight.Bold else FontWeight.Normal,
                        color = if (line.text.isEmpty() && now) accent else color,
                    ),
                    modifier = Modifier.padding(vertical = 5.dp),
                )
            }
        }
        if (!lyrics.synced) {
            Text("Letra sin sincronizar", style = Vega.label.copy(fontSize = 10.sp, color = Vega.Ink3), modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

/**
 * Color principal de una carátula (promedio, subido de brillo para que se lea sobre negro).
 * Se calcula una vez por carátula.
 */
fun accentOf(art: ImageBitmap?): Color {
    if (art == null) return Vega.Coral
    val small = android.graphics.Bitmap.createScaledBitmap(art.asAndroidBitmap(), 8, 8, true)
    var r = 0f
    var g = 0f
    var b = 0f
    for (x in 0 until 8) for (y in 0 until 8) {
        val p = small.getPixel(x, y)
        r += android.graphics.Color.red(p)
        g += android.graphics.Color.green(p)
        b += android.graphics.Color.blue(p)
    }
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV((r / 64).toInt(), (g / 64).toInt(), (b / 64).toInt(), hsv)
    // Muy apagado o gris → coral de Vega; si no, el tono de la carátula, vivo y claro.
    if (hsv[1] < 0.15f) return Vega.Coral
    hsv[1] = hsv[1].coerceIn(0.45f, 0.85f)
    hsv[2] = 0.95f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
