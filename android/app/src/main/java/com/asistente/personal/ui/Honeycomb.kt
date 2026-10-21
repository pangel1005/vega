package com.asistente.personal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.PendingConfirm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Hexágono con vértices a izquierda y derecha (lados planos arriba y abajo). */
object HexShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = size.width / 2
        val cx = size.width / 2
        val cy = size.height / 2
        return Outline.Generic(
            Path().apply {
                for (i in 0 until 6) {
                    val a = (PI / 3 * i).toFloat()
                    val x = cx + r * cos(a)
                    val y = cy + r * sin(a)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            },
        )
    }
}

/**
 * Panal: un hexágono central «MÁS» rodeado de hasta 6 favoritos (los `quick = true` de
 * config/actions.toml). Se adapta al espacio disponible.
 */
@Composable
fun Honeycomb(
    favorites: List<DeckAction>,
    pending: PendingConfirm?,
    secondsLeft: Int,
    enabled: Boolean,
    onPress: (String) -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // Flor de 7 hexágonos (planos arriba/abajo): ancho = 5 r, alto = 3·√3 r. Un pequeño hueco entre ellos.
        val sqrt3 = sqrt(3f)
        val r: Dp = min(maxWidth / 5.3f, maxHeight / (3 * sqrt3 + 0.3f))
        val cell = r * 2
        val gap = 1.06f
        // Posiciones de los 6 vecinos alrededor del centro (en múltiplos de r).
        val ring = listOf(0f to -sqrt3, 1.5f to -sqrt3 / 2, 1.5f to sqrt3 / 2, 0f to sqrt3, -1.5f to sqrt3 / 2, -1.5f to -sqrt3 / 2)

        HexButton(
            label = "MÁS", jp = "全体", icon = "more", color = Nerv.Mute, size = cell,
            enabled = true, confirming = false, secondsLeft = 0, onClick = onMore,
        )
        favorites.take(6).forEachIndexed { i, a ->
            val (dx, dy) = ring[i]
            HexButton(
                label = a.label, jp = a.jp, icon = a.icon, color = deckColor(a.color), size = cell,
                enabled = enabled, confirming = pending?.id == a.id, secondsLeft = secondsLeft,
                onClick = { onPress(a.id) },
                modifier = Modifier.offset(r * dx * gap, r * dy * gap),
            )
        }
    }
}

@Composable
private fun HexButton(
    label: String,
    jp: String,
    icon: String,
    color: Color,
    size: Dp,
    enabled: Boolean,
    confirming: Boolean,
    secondsLeft: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "pulsar",
    )
    val c = if (confirming) Nerv.Red else color
    Box(
        modifier
            .size(size, size * 0.866f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .clip(HexShape)
            .background(if (confirming) Nerv.Red.copy(alpha = 0.85f) else c.copy(alpha = if (pressed) 0.3f else 0.1f))
            .border(1.5.dp, c.copy(alpha = 0.55f), HexShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (confirming) Color(0xFF0A0A0A) else c
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (confirming) {
                Text("¿SEGURO? $secondsLeft", style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, color = fg))
                Text(
                    "TOCA O DI «SÍ»",
                    style = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg, textAlign = TextAlign.Center),
                )
            } else {
                Canvas(Modifier.size(size * 0.2f)) { drawDeckIcon(icon, fg) }
                Text(
                    label.uppercase(),
                    maxLines = 1,
                    style = TextStyle(
                        fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                        letterSpacing = 1.sp, color = Nerv.Ink, textAlign = TextAlign.Center,
                    ),
                )
                if (jp.isNotEmpty()) {
                    Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 10.sp, color = fg.copy(alpha = 0.7f)))
                }
            }
        }
    }
}
