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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.PendingConfirm
import com.asistente.personal.ui.led.LedOff

/**
 * Fila de teclas (como una botonera física): los 6 favoritos de config/actions.toml y
 * «MÁS». Cada tecla lleva una tira de LED de su color encima.
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
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        favorites.take(6).forEach { a ->
            Key(
                label = a.label, icon = a.icon, color = deckColor(a.color), enabled = enabled,
                confirming = pending?.id == a.id, secondsLeft = secondsLeft, onClick = { onPress(a.id) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Key(
            label = "Más", icon = "more", color = Nerv.Mute, enabled = true, confirming = false, secondsLeft = 0,
            onClick = onMore, modifier = Modifier.weight(0.8f).fillMaxHeight(),
        )
    }
}

@Composable
private fun Key(
    label: String,
    icon: String,
    color: Color,
    enabled: Boolean,
    confirming: Boolean,
    secondsLeft: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, spring(dampingRatio = 0.45f, stiffness = 900f), label = "pulsar")
    val c = if (confirming) Nerv.Red else color
    val shape = remember { Chamfer(9.dp) }
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .background(if (confirming) Nerv.Red.copy(alpha = 0.9f) else if (pressed) c.copy(alpha = 0.22f) else Color(0xFF0D0A08), shape)
            .border(1.dp, c.copy(alpha = if (confirming) 1f else 0.38f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .padding(horizontal = 6.dp, vertical = 5.dp),
    ) {
        val fg = if (confirming) Color(0xFF0A0A0A) else c
        // Tira de LED: encendida en el color de la tecla.
        Canvas(Modifier.fillMaxWidth().height(4.dp).align(Alignment.TopCenter)) {
            val n = 5
            val step = size.width / (n + 1)
            for (i in 1..n) {
                val p = Offset(step * i, size.height / 2)
                if (!confirming) drawCircle(c.copy(alpha = 0.18f), size.height * 1.1f, p)
                drawCircle(if (enabled || confirming) fg else LedOff, size.height / 2, p)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            if (confirming) {
                Text("¿SEGURO? $secondsLeft", style = TextStyle(fontFamily = Nerv.Mono, fontSize = 9.sp, color = fg))
                Text("TOCA OTRA VEZ", maxLines = 1, style = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = fg))
            } else {
                Canvas(Modifier.size(17.dp)) { drawDeckIcon(icon, fg) }
                Text(
                    label.uppercase(),
                    maxLines = 1,
                    style = TextStyle(fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, color = Nerv.Ink),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
