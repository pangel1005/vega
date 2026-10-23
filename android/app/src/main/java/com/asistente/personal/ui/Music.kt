package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
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
 * suena (un tic por segundo, solo mientras suena).
 */
@Composable
fun livePosition(media: MediaInfo, receivedAt: Long, now: () -> Long): Double {
    var pos by remember(media, receivedAt) { mutableStateOf(media.position) }
    LaunchedEffect(media, receivedAt) {
        while (media.playing) {
            val elapsed = (now() - receivedAt) / 1000.0
            pos = (media.position + elapsed).let { if (media.duration > 0) it.coerceAtMost(media.duration) else it }
            delay(1000)
        }
    }
    return pos
}

/** Barra de progreso fina con los tiempos a los lados (o debajo, en pequeño). */
@Composable
private fun Progress(position: Double, duration: Double, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
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
private fun Equalizer(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
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
private fun Art(art: ImageBitmap?, accent: Color, modifier: Modifier, radius: Dp) {
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
 * fondo toma el color de la carátula.
 */
@Composable
fun MusicScreen(
    media: MediaInfo,
    position: Double,
    art: ImageBitmap?,
    accent: Color,
    clock: String,
    onControl: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint by animateColorAsState(accent, tween(600), label = "color de la carátula")
    Box(
        modifier.fillMaxSize().background(Vega.Bg)
            .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent))),
    ) {
        Row(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 28.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Art(art, tint, Modifier.fillMaxHeight().aspectRatio(1f), 22.dp)
            Spacer(Modifier.width(28.dp))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
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
                    Pill("Volver", Vega.Ink2, onClose)
                }
                Spacer(Modifier.height(14.dp))
                Text(media.title, maxLines = 2, style = Vega.body.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold))
                Spacer(Modifier.height(4.dp))
                Text(media.artist, maxLines = 1, style = Vega.body.copy(fontSize = 18.sp, color = Vega.Ink2))
                if (media.album.isNotEmpty() && media.album != media.title) {
                    Text(media.album, maxLines = 1, style = Vega.label.copy(fontSize = 13.sp, color = Vega.Ink3))
                }
                Spacer(Modifier.height(20.dp))
                Progress(position, media.duration, tint, Modifier.fillMaxWidth(), height = 6.dp)
                Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(clockTime(position), style = Vega.label.copy(color = Vega.Ink2))
                    Text(if (media.duration > 0) clockTime(media.duration) else "", style = Vega.label.copy(color = Vega.Ink2))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
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
