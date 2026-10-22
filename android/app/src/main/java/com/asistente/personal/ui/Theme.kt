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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.asistente.personal.R

/**
 * Estilo «Vega · noche»: negro, tarjetas oscuras con esquinas redondeadas, la luz cálida
 * de la cara de LED y un único color propio (coral). Los demás colores solo aparecen
 * cuando significan algo: rojo = cuidado, verde = hecho, ámbar = pensando.
 */
object Vega {
    val Bg = Color(0xFF000000)
    val Card = Color(0xFF0E0E0F)
    val CardHi = Color(0xFF17171A) // tecla pulsada, tarjeta resaltada
    val Line = Color(0xFF1F1F22)

    val Ink = Color(0xFFF5F2ED)
    val Ink2 = Color(0xFF9A968F)
    val Ink3 = Color(0xFF55524E)

    val Light = Color(0xFFFFF1DE) // luz de la cara en reposo: blanco cálido
    val Coral = Color(0xFFFF6A3D)
    val Amber = Color(0xFFFFB547)
    val Mint = Color(0xFF6CF0A8)
    val Red = Color(0xFFFF4D4D)
    val Sky = Color(0xFF7FD8FF)

    /** Números y titulares: tipografía de puntos (como la cara). */
    val Dots = FontFamily(Font(R.font.doto_black, FontWeight.Black))
    val Sans = FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )

    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp, color = Ink2)
    val body = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 20.sp, color = Ink)
}

/** Color de cada botón según config/actions.toml (más suaves que antes: es un acento, no un fondo). */
fun deckColor(name: String): Color = when (name) {
    "red" -> Vega.Red
    "amber" -> Vega.Amber
    "green" -> Vega.Mint
    "teal" -> Vega.Sky
    else -> Vega.Coral
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
    return pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!on.value) return@awaitEachGesture
            down.consume()
            pressed.value = true
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            action.value()
            waitForUpOrCancellation()
            pressed.value = false
        }
    }
}
