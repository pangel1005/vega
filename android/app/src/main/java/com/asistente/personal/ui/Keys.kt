package com.asistente.personal.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
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
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        favorites.take(6).forEach { a ->
            Key(
                label = a.label, icon = a.icon, color = deckColor(a.color), enabled = enabled,
                confirming = pending?.id == a.id, secondsLeft = secondsLeft, onClick = { onPress(a.id) },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        Key(
            label = "Más", icon = "more", color = Vega.Ink2, enabled = true, confirming = false, secondsLeft = 0,
            onClick = onMore, modifier = Modifier.weight(0.75f).fillMaxHeight(),
        )
    }
}

/** Una tecla: icono en su color, nombre debajo. Al pulsar se hunde y se ilumina. */
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
) {
    val pressed = remember { mutableStateOf(false) }
    val down = pressed.value
    // Muelle rápido: se hunde en el mismo cuadro del toque y rebota al soltar.
    val scale by animateFloatAsState(if (down) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 1500f), label = "pulsar")
    val fill by animateColorAsState(
        when {
            confirming -> Vega.Red
            down -> color.copy(alpha = 0.22f)
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
            .background(fill, KeyShape)
            .border(1.dp, if (confirming) Vega.Red else if (down) color.copy(alpha = 0.6f) else Vega.Line, KeyShape)
            .instantPress(enabled = enabled, pressed = pressed, onPress = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
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
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(20.dp)) { drawDeckIcon(icon, color) }
                Text(
                    label,
                    maxLines = 1,
                    style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, color = Vega.Ink),
                )
            }
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
