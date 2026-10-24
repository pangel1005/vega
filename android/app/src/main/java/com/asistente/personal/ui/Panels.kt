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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(22.dp)

/** Tarjeta: fondo oscuro, esquinas redondeadas y un borde muy fino; en estilo «Cristal», de cristal. */
fun Modifier.card(border: Color = Vega.Line, radius: Dp = 22.dp, fill: Color = Vega.Card, overlay: Boolean = false): Modifier = composed {
    val shape = if (radius == 22.dp) CardShape else RoundedCornerShape(radius)
    if (LocalAurora.current != null) glass(shape, border, smoke = if (overlay) 0.93f else 0.62f)
    else background(fill, shape).border(1.dp, border, shape)
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
