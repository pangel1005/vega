package com.asistente.personal.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.Notice
import com.asistente.personal.state.PendingConfirm
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

fun deckColor(name: String): Color = when (name) {
    "red" -> Nerv.Red
    "amber" -> Nerv.Amber
    "green" -> Nerv.Green
    "teal" -> Nerv.Teal
    else -> Nerv.Orange
}

/** Hoja «MÁS»: todos los botones en cuadrícula, encima de la pantalla principal. */
@Composable
fun Deck(
    actions: List<DeckAction>,
    pending: PendingConfirm?,
    secondsLeft: Int,
    enabled: Boolean,
    onPress: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    voiceLabel: String? = null,
    onNextVoice: () -> Unit = {},
) {
    Column(modifier.background(Color(0xF20B0A09)).hudFrame(Nerv.Orange.copy(alpha = 0.24f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("PANEL", style = TextStyle(fontFamily = Nerv.Mono, fontSize = 11.sp, letterSpacing = 3.sp, color = Nerv.Mute))
            Box(Modifier.size(8.dp))
            Text("操作盤", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 13.sp, color = Nerv.Orange))
            Box(Modifier.weight(1f))
            // Cambiar de voz: pasa a la siguiente y Vega se presenta con ella.
            if (voiceLabel != null) {
                NervButton("VOZ: ${voiceLabel.uppercase()}", Nerv.Teal, enabled = enabled, onClick = onNextVoice, ghost = true, small = true)
                Box(Modifier.size(10.dp))
            }
            NervButton("CERRAR", Nerv.Mute, enabled = true, onClick = onClose, ghost = true, small = true)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(actions, key = { it.id }) { a ->
                DeckTile(
                    action = a,
                    confirming = pending?.id == a.id,
                    secondsLeft = secondsLeft,
                    enabled = enabled,
                    onPress = { onPress(a.id) },
                )
            }
        }
    }
}

/** Aviso corto del resultado de un botón (abajo, centrado). */
@Composable
fun NoticeBar(notice: Notice?, modifier: Modifier = Modifier) {
    androidx.compose.animation.AnimatedVisibility(
        visible = notice != null,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        val n = notice ?: return@AnimatedVisibility
        val c = if (n.ok) Nerv.Amber else Nerv.Red
        val shape = remember { Chamfer(10.dp) }
        Row(
            Modifier.background(Color(0xF20B0A09), shape).border(1.5.dp, c.copy(alpha = 0.8f), shape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.background(c).padding(horizontal = 6.dp, vertical = 1.dp)) {
                Text(if (n.ok) "通知" else "警報", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 15.sp, color = Color(0xFF0A0A0A)))
            }
            Box(Modifier.size(10.dp))
            Text(n.text, maxLines = 2, style = TextStyle(fontFamily = Nerv.Cond, fontSize = 18.sp, lineHeight = 20.sp, color = Nerv.Ink))
        }
    }
}

@Composable
private fun DeckTile(action: DeckAction, confirming: Boolean, secondsLeft: Int, enabled: Boolean, onPress: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Rebote suave al pulsar (muelle), como una tecla física.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 900f),
        label = "pulsar",
    )
    val color = if (confirming) Nerv.Red else deckColor(action.color)
    val shape = remember { Chamfer(10.dp) }

    Box(
        Modifier
            .height(66.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.35f
            }
            .clip(shape)
            .background(if (confirming) Nerv.Red.copy(alpha = 0.85f) else color.copy(alpha = if (pressed) 0.28f else 0.1f))
            .border(1.dp, color.copy(alpha = 0.45f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onPress()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (action.jp.isNotEmpty() && !confirming) {
            Text(
                action.jp,
                style = TextStyle(fontFamily = Nerv.Jp, fontSize = 11.sp, color = color.copy(alpha = 0.7f)),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
        val fg = if (confirming) Color(0xFF0A0A0A) else color
        Canvas(Modifier.size(20.dp).align(Alignment.TopStart)) { drawDeckIcon(action.icon, fg) }
        Column(Modifier.align(Alignment.BottomStart)) {
            if (confirming) {
                Text(
                    "¿SEGURO? · $secondsLeft",
                    style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, letterSpacing = 1.sp, color = fg),
                )
            }
            Text(
                if (confirming) "PULSA DE NUEVO" else action.label.uppercase(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontFamily = Nerv.Cond, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 1.sp,
                    color = if (confirming) fg else Nerv.Ink,
                ),
            )
        }
    }
}

/** Iconos dibujados con trazos (sin imágenes): ligeros y en el color del botón. */
fun DrawScope.drawDeckIcon(name: String, c: Color) {
    val w = size.width
    val s = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
    val mid = Offset(w / 2, w / 2)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(c, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), s.width, StrokeCap.Round)
    fun poly(vararg p: Float, fill: Boolean = true) {
        val path = Path().apply {
            moveTo(p[0] * w, p[1] * w)
            for (i in 2 until p.size step 2) lineTo(p[i] * w, p[i + 1] * w)
            close()
        }
        if (fill) drawPath(path, c) else drawPath(path, c, style = s)
    }
    fun speaker() = poly(0.1f, 0.38f, 0.28f, 0.38f, 0.5f, 0.18f, 0.5f, 0.82f, 0.28f, 0.62f, 0.1f, 0.62f)

    when (name) {
        "power" -> {
            drawArc(c, -60f, 300f, false, Offset(w * 0.15f, w * 0.18f), Size(w * 0.7f, w * 0.7f), style = s)
            line(0.5f, 0.08f, 0.5f, 0.48f)
        }
        "restart" -> {
            drawArc(c, -30f, 290f, false, Offset(w * 0.15f, w * 0.15f), Size(w * 0.7f, w * 0.7f), style = s)
            poly(0.62f, 0.05f, 0.92f, 0.22f, 0.62f, 0.38f)
        }
        "sleep" -> {
            drawCircle(c, w * 0.36f, mid)
            drawCircle(Color.Black.copy(alpha = 0.85f), w * 0.3f, Offset(w * 0.66f, w * 0.38f))
        }
        "lock" -> {
            drawArc(c, 180f, 180f, false, Offset(w * 0.28f, w * 0.1f), Size(w * 0.44f, w * 0.5f), style = s)
            drawRect(c, Offset(w * 0.18f, w * 0.42f), Size(w * 0.64f, w * 0.48f))
        }
        "cancel" -> { line(0.2f, 0.2f, 0.8f, 0.8f); line(0.8f, 0.2f, 0.2f, 0.8f) }
        "record" -> { drawCircle(c, w * 0.42f, mid, style = s); drawCircle(c, w * 0.24f, mid) }
        "screenshot" -> {
            drawRect(c, Offset(w * 0.1f, w * 0.22f), Size(w * 0.8f, w * 0.6f), style = s)
            drawCircle(c, w * 0.14f, Offset(w * 0.5f, w * 0.52f))
        }
        "mute" -> { speaker(); line(0.64f, 0.36f, 0.92f, 0.64f); line(0.92f, 0.36f, 0.64f, 0.64f) }
        "volume-up" -> { speaker(); line(0.62f, 0.5f, 0.94f, 0.5f); line(0.78f, 0.34f, 0.78f, 0.66f) }
        "volume-down" -> { speaker(); line(0.62f, 0.5f, 0.94f, 0.5f) }
        "play" -> { poly(0.12f, 0.15f, 0.5f, 0.5f, 0.12f, 0.85f); drawRect(c, Offset(w * 0.6f, w * 0.18f), Size(w * 0.1f, w * 0.64f)); drawRect(c, Offset(w * 0.8f, w * 0.18f), Size(w * 0.1f, w * 0.64f)) }
        "next" -> { poly(0.1f, 0.15f, 0.48f, 0.5f, 0.1f, 0.85f); poly(0.45f, 0.15f, 0.83f, 0.5f, 0.45f, 0.85f); drawRect(c, Offset(w * 0.82f, w * 0.15f), Size(w * 0.1f, w * 0.7f)) }
        "prev" -> { poly(0.9f, 0.15f, 0.52f, 0.5f, 0.9f, 0.85f); poly(0.55f, 0.15f, 0.17f, 0.5f, 0.55f, 0.85f); drawRect(c, Offset(w * 0.08f, w * 0.15f), Size(w * 0.1f, w * 0.7f)) }
        "mic" -> {
            drawRoundRect(c, Offset(w * 0.36f, w * 0.06f), Size(w * 0.28f, w * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.14f))
            drawArc(c, 0f, 180f, false, Offset(w * 0.2f, w * 0.26f), Size(w * 0.6f, w * 0.48f), style = s)
            line(0.5f, 0.74f, 0.5f, 0.92f)
        }
        "more" -> for (dx in listOf(0.2f, 0.5f, 0.8f)) drawCircle(c, w * 0.09f, Offset(dx * w, 0.5f * w))
        else -> { // app: hexágono NERV
            val p = Path()
            for (i in 0 until 6) {
                val a = (PI / 3 * i - PI / 2).toFloat()
                val x = mid.x + w * 0.42f * cos(a)
                val y = mid.y + w * 0.42f * sin(a)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            p.close()
            drawPath(p, c, style = s)
            drawCircle(c, w * 0.12f, mid)
        }
    }
}
