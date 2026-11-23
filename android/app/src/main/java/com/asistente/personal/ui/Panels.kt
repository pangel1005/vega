package com.asistente.personal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(22.dp)

/**
 * Tarjeta: fondo oscuro, esquinas redondeadas y un borde muy fino; en estilo «Cristal», de cristal.
 * Con el rediseño, además, es un panel de LED apagado: una rejilla de puntos casi invisible y un bisel de luz arriba.
 */
fun Modifier.card(border: Color = Vega.Line, radius: Dp = 22.dp, fill: Color = Vega.Card, overlay: Boolean = false): Modifier = composed {
    val shape = if (radius == 22.dp) CardShape else RoundedCornerShape(radius)
    if (LocalAurora.current != null) glass(shape, border, smoke = if (overlay) 0.93f else 0.62f)
    else {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val tile = remember(density) { dotTile(density) }
        background(fill, shape).border(1.dp, border, shape).ledPanel(shape, tile)
    }
}

/** Un cuadradito de 6 dp con un punto en el centro: se repite para hacer la rejilla de LED apagados. */
private fun dotTile(density: androidx.compose.ui.unit.Density): androidx.compose.ui.graphics.ImageBitmap {
    val px = with(density) { 6.dp.toPx() }.toInt().coerceAtLeast(4)
    val bitmap = androidx.compose.ui.graphics.ImageBitmap(px, px)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap),
        androidx.compose.ui.geometry.Size(px.toFloat(), px.toFloat()),
    ) {
        drawCircle(Color.White.copy(alpha = 0.055f), radius = px * 0.13f, center = androidx.compose.ui.geometry.Offset(px / 2f, px / 2f))
    }
    return bitmap
}

private fun Modifier.ledPanel(shape: androidx.compose.ui.graphics.Shape, tile: androidx.compose.ui.graphics.ImageBitmap): Modifier =
    drawWithCache {
        val path = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val dots = androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(tile, androidx.compose.ui.graphics.TileMode.Repeated, androidx.compose.ui.graphics.TileMode.Repeated),
        )
        val sheen = androidx.compose.ui.graphics.Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.04f), Color.Transparent), endY = size.height * 0.55f,
        )
        val bevel = androidx.compose.ui.graphics.Brush.horizontalGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.18f), Color.Transparent),
        )
        onDrawBehind {
            clipPath(path) {
                drawRect(sheen)
                drawRect(dots)
                drawLine(bevel, androidx.compose.ui.geometry.Offset(size.width * 0.08f, 1.dp.toPx()), androidx.compose.ui.geometry.Offset(size.width * 0.92f, 1.dp.toPx()), 1.dp.toPx())
            }
        }
    }

/** Cuatro marcas en las esquinas, como las de la ventana de un instrumento. */
fun Modifier.cornerTicks(color: Color = Vega.Ink3.copy(alpha = 0.7f)): Modifier = drawBehind {
    val inset = 9.dp.toPx()
    val len = 7.dp.toPx()
    val w = 1.2.dp.toPx()
    fun o(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(x, y)
    fun tick(x: Float, y: Float, sx: Float, sy: Float) {
        drawLine(color, o(x, y), o(x + sx * len, y), w)
        drawLine(color, o(x, y), o(x, y + sy * len), w)
    }
    tick(inset, inset, 1f, 1f)
    tick(size.width - inset, inset, -1f, 1f)
    tick(inset, size.height - inset, 1f, -1f)
    tick(size.width - inset, size.height - inset, -1f, -1f)
}

/**
 * Botón pequeño en forma de píldora (micrófono, ANDROID, actualizar…). Responde al tocar,
 * no al soltar.
 */
@Composable
fun Pill(label: String, color: Color, onClick: () -> Unit, filled: Boolean = false, modifier: Modifier = Modifier) {
    val pressed = remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed.value) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 1400f), label = "pulsar")
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(if (filled) color else if (pressed.value) Vega.CardHi else Color.Transparent, shape)
            .border(1.dp, if (filled) color else color.copy(alpha = 0.35f), shape)
            .instantPress(enabled = true, pressed = pressed, onPress = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            maxLines = 1,
            style = TextStyle(
                fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp,
                color = if (filled) Color(0xFF140A05) else color,
            ),
        )
    }
}

/** Texto simple sin Material (más ligero). Recorta con «…» si no cabe. */
@Composable
fun Text(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE, overflow: TextOverflow = TextOverflow.Ellipsis) {
    androidx.compose.foundation.text.BasicText(text, modifier, style, overflow = overflow, maxLines = maxLines)
}
