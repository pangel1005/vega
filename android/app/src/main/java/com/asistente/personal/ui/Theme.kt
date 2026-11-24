package com.asistente.personal.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Los colores con significado que comparten las piezas de `ui` (rojo = cuidado, verde = hecho,
 * ámbar = aviso, coral = Vega). Vega 2 tiene su propia paleta (ui2/Theme2.kt).
 */
object Vega {
    val Ink = Color(0xFFF5F2ED)
    val Ink2 = Color(0xFF9A968F)
    val Ink3 = Color(0xFF7A766F)

    val Coral = Color(0xFFFF6A3D)
    val Amber = Color(0xFFFFB547)
    val Mint = Color(0xFF6CF0A8)
    val Red = Color(0xFFFF4D4D)
}

/**
 * Vibración al tocar una tecla. En teléfonos baratos el motor «zumba» y el toque fuerte se
 * nota raro: la suave es un golpecito corto; también se puede quitar.
 */
enum class TouchFeel(val label: String, val haptic: Int?) {
    STRONG("Fuerte", HapticFeedbackConstants.KEYBOARD_TAP),
    SOFT("Suave", HapticFeedbackConstants.CLOCK_TICK),
    OFF("Sin vibrar", null),
    ;

    fun next(): TouchFeel = entries[(ordinal + 1) % entries.size]

    companion object {
        /** La elegida ahora (la guarda AssistantController); se lee en cada toque. */
        @Volatile var current: TouchFeel = SOFT

        fun from(name: String?): TouchFeel = entries.firstOrNull { it.name == name } ?: SOFT
    }
}

/**
 * Pulsación inmediata: la acción salta al TOCAR (no al levantar el dedo) con una vibración
 * corta y seca, como una tecla física. `pressed` se enciende mientras el dedo está encima,
 * para el efecto visual. Las acciones peligrosas siguen pidiendo un segundo toque.
 */
@Composable
fun Modifier.instantPress(enabled: Boolean, pressed: MutableState<Boolean>, onPress: () -> Unit): Modifier {
    val view = LocalView.current
    val action = rememberUpdatedState(onPress)
    val on = rememberUpdatedState(enabled)
    // Los gestos van con pointerInput (la acción salta al tocar, no al soltar), que TalkBack no ve: se declara
    // aquí que es un botón y qué hace, para que el lector de pantalla pueda activarlo.
    return semantics(mergeDescendants = true) {
        role = Role.Button
        if (enabled) {
            onClick {
                action.value()
                true
            }
        } else {
            disabled()
        }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!on.value) return@awaitEachGesture
            down.consume()
            pressed.value = true
            TouchFeel.current.haptic?.let(view::performHapticFeedback)
            action.value()
            waitForUpOrCancellation()
            pressed.value = false
        }
    }
}
