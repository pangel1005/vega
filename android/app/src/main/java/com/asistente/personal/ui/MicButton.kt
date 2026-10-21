package com.asistente.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class VoiceStatus { OFF, STARTING, RECORDING }

/** Pulsación más corta que esto = modo «toque» (toca para empezar, toca para terminar). */
private const val TAP_MS = 350L

/**
 * Botón para hablar. Mantener pulsado: graba mientras se sostiene.
 * Toque corto: empieza a grabar y un segundo toque termina.
 */
@Composable
fun MicButton(
    status: VoiceStatus,
    enabled: Boolean,
    accent: Color,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = status != VoiceStatus.OFF
    val currentActive by rememberUpdatedState(active)
    val currentEnabled by rememberUpdatedState(enabled)
    val tapMode = remember { booleanArrayOf(false) }
    val shape = remember { Chamfer(14.dp) }

    Row(
        modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.35f)
            .clip(shape)
            .background(if (active) accent else accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.3f), shape)
            .semantics {
                role = Role.Button
                contentDescription = if (active) "Terminar de hablar" else "Hablar"
                onClick { if (currentActive) onStop() else onStart(); true }
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    if (!currentEnabled) return@awaitEachGesture
                    val pressedAt = System.currentTimeMillis()
                    if (currentActive && tapMode[0]) {
                        // Segundo toque en modo toque: terminar.
                        tapMode[0] = false
                        onStop()
                        waitForUpOrCancellation()
                        return@awaitEachGesture
                    }
                    tapMode[0] = false
                    onStart()
                    val up = waitForUpOrCancellation()
                    when {
                        up == null -> onCancel()
                        System.currentTimeMillis() - pressedAt < TAP_MS -> tapMode[0] = true
                        else -> onStop()
                    }
                }
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (active) Color(0xFF0A0A0A) else accent
        Text("●", style = TextStyle(fontSize = 22.sp, color = fg))
        Column(Modifier.padding(start = 12.dp)) {
            Text(if (active) "受信中" else "通話", style = TextStyle(fontFamily = Nerv.Jp, fontSize = 20.sp, color = fg))
            Text(
                when (status) {
                    VoiceStatus.RECORDING -> "GRABANDO · SUELTA O TOCA"
                    VoiceStatus.STARTING -> "ABRIENDO MICRÓFONO"
                    VoiceStatus.OFF -> "HABLAR"
                },
                style = TextStyle(fontFamily = Nerv.Mono, fontSize = 11.sp, letterSpacing = 3.sp, color = fg),
            )
        }
    }
}
