package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.PendingConfirm

private val KeyShape = RoundedCornerShape(18.dp)

/**
 * Fila de teclas grandes (como una botonera): los 6 favoritos de config/actions.toml y
 * «Más». Saltan al tocar, sin esperar a que levantes el dedo.
 */
@Composable
fun KeyRow(
    favorites: List<DeckAction>,
    pending: PendingConfirm?,
    secondsLeft: Int,
    enabled: Boolean,
    onPress: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    icons: Map<String, ImageBitmap> = emptyMap(),
    media: com.asistente.personal.net.MediaInfo? = null,
    compact: Boolean = false,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        favorites.take(6).forEach { a ->
            val state = keyState(a, media)
            Key(
                label = state.label, live = state.live, icon = a.icon, color = deckColor(a.color), enabled = enabled, image = icons[a.id], id = a.id,
                confirming = pending?.id == a.id, secondsLeft = secondsLeft, onClick = { onPress(a.id) },
                modifier = Modifier.weight(1f).fillMaxHeight(), compact = compact,
            )
        }
        Key(
            label = "Más", icon = "more", color = Vega.Ink2, enabled = true, confirming = false, secondsLeft = 0,
            onClick = onMore, modifier = Modifier.weight(0.75f).fillMaxHeight(), compact = compact,
        )
    }
}

/**
 * Una tecla: icono de LED en su color, nombre y una tira de luz abajo. En reposo la tira brilla
 * poco (dice de qué color es la tecla); al pulsar, la tecla se hunde y todo se enciende.
 */
@Composable
fun Key(
    label: String,
    icon: String,
    color: Color,
    enabled: Boolean,
    confirming: Boolean,
    secondsLeft: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    image: ImageBitmap? = null,
    id: String = label,
    live: Boolean = false,
    /** Icono a la izquierda y nombre a la derecha (para teclas bajas en cuadrícula). */
    horizontal: Boolean = false,
    /** Tecla baja: icono más pequeño y sin la tira de luz (para una fila estrecha). */
    compact: Boolean = false,
) {
    val pressed = remember { mutableStateOf(false) }
    val down = pressed.value
    // Se enciende al instante y se apaga despacio, como un LED.
    val glow by animateFloatAsState(if (down) 1f else 0f, tween(if (down) 0 else 420), label = "brillo")
    val glyph = remember(icon, image) { if (image != null) imageGlyph(id, image) else deckGlyph(icon) }
    val glassy = LocalAurora.current != null
    // Muelle rápido: se hunde en el mismo cuadro del toque y rebota al soltar.
    val scale by animateFloatAsState(if (down) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 1500f), label = "pulsar")
    val fill by animateColorAsState(
        when {
            confirming -> Vega.Red
            down -> color.copy(alpha = 0.22f)
            glassy -> Color.Transparent // en cristal, el fondo es el propio cristal
            else -> Vega.Card
        },
        tween(if (down) 0 else 220),
        label = "fondo",
    )
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled || confirming) 1f else 0.35f
            }
            .then(
                if (glassy) Modifier.glass(KeyShape, if (confirming) Vega.Red else if (down) color.copy(alpha = 0.6f) else Vega.Line)
                else Modifier,
            )
            .background(fill, KeyShape)
            .then(if (!glassy && !down && !confirming) Modifier.keycap(KeyShape) else Modifier)
            .then(
                if (glassy) Modifier
                else Modifier.border(1.dp, if (confirming) Vega.Red else if (down) color.copy(alpha = 0.6f) else Vega.Line, KeyShape),
            )
            .instantPress(enabled = enabled, pressed = pressed, onPress = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (confirming) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("¿Seguro?", style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color(0xFF1A0505)))
                Text(
                    "toca otra vez · $secondsLeft",
                    maxLines = 1,
                    style = TextStyle(fontFamily = Vega.Sans, fontSize = 10.sp, color = Color(0xFF1A0505), textAlign = TextAlign.Center),
                )
            }
        } else if (horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LedIcon(glyph, color, Modifier.size(22.dp), glow = glow)
                Text(
                    label,
                    maxLines = 1,
                    style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Vega.Ink),
                )
                if (live) Dot(color)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                // Las apps llevan su icono real (del .exe del PC) hecho de LED de colores; lo demás,
                // su dibujo en puntos del color de la tecla.
                LedIcon(glyph, color, Modifier.size(if (compact) 26.dp else 32.dp), glow = glow)
                Text(
                    label,
                    maxLines = 1,
                    style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = if (compact) 11.sp else 12.sp, color = Vega.Ink),
                )
                if (!compact) LightStrip(color, glow, live) else if (live) Dot(color)
            }
        }
    }
}

/** Relieve de tecla: la cara de arriba capta luz, el borde de abajo cae en sombra, y un filo claro arriba. */
private fun Modifier.keycap(shape: androidx.compose.ui.graphics.Shape): Modifier = drawWithCache {
    val path = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val body = androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(Color.White.copy(alpha = 0.075f), Color.Transparent, Color.Black.copy(alpha = 0.38f)),
    )
    onDrawBehind {
        clipPath(path) {
            drawRect(body)
            val w = 1.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.16f), androidx.compose.ui.geometry.Offset(size.width * 0.1f, w), androidx.compose.ui.geometry.Offset(size.width * 0.9f, w), w)
            drawLine(Color.Black.copy(alpha = 0.6f), androidx.compose.ui.geometry.Offset(0f, size.height - w), androidx.compose.ui.geometry.Offset(size.width, size.height - w), w * 2)
        }
    }
}

/** Cinco LED en fila bajo el nombre de la tecla: tenues en reposo, encendidos al pulsar (del centro afuera). */
@Composable
private fun LightStrip(color: Color, glow: Float, live: Boolean = false) {
    Canvas(Modifier.size(width = 34.dp, height = 4.dp)) {
        val pitch = size.width / 5
        for (i in 0 until 5) {
            val fromCenter = kotlin.math.abs(i - 2) / 2f
            val on = if (live) 1f else (glow * 1.6f - fromCenter * 0.6f).coerceIn(0f, 1f) // viva: toda encendida
            drawCircle(color.copy(alpha = 0.22f + 0.78f * on), size.height / 2, androidx.compose.ui.geometry.Offset(pitch * (i + 0.5f), size.height / 2))
        }
    }
}

/** Indicador redondo pequeño (enlace, micrófono). */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(color, RoundedCornerShape(50)))
}

/** Margen interior estándar de las tarjetas. */
val CardPadding = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
