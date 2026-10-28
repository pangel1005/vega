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
    val Ink3 = Color(0xFF7A766F) // era #55524E (2,5:1 sobre las tarjetas); así llega a ~4,6:1

    val Light = Color(0xFFFFF1DE) // luz de la cara en reposo: blanco cálido
    val Coral = Color(0xFFFF6A3D)
    val Amber = Color(0xFFFFB547)
    val Mint = Color(0xFF6CF0A8)
    val Red = Color(0xFFFF4D4D)
    val Sky = Color(0xFF7FD8FF)

    /** Números y titulares: tipografía de puntos (como la cara). */
    val Dots = FontFamily(Font(R.font.doto_black, FontWeight.Black))

    /** La fuente de la terminal del estilo «Nostromo» (VT323, licencia OFL). */
    val Terminal = FontFamily(Font(R.font.vt323, FontWeight.Normal))
    val Sans = FontFamily(
        Font(R.font.space_grotesk_regular, FontWeight.Normal),
        Font(R.font.space_grotesk_medium, FontWeight.Medium),
        Font(R.font.space_grotesk_bold, FontWeight.Bold),
    )

    val label = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.4.sp, color = Ink2)
    val body = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 20.sp, color = Ink)
}

/**
 * Color de la cara en reposo y al hablar (lo elige el usuario en «Más»). Los demás estados
 * conservan su color, porque significan algo: coral = te escucho, rojo = cuidado…
 */
enum class FaceColor(val label: String, val color: Color) {
    WARM("Cálido", Vega.Light),
    CORAL("Coral", Vega.Coral),
    SKY("Cielo", Vega.Sky),
    MINT("Menta", Vega.Mint),
    VIOLET("Violeta", Color(0xFFB79BFF)),
    PINK("Rosa", Color(0xFFFF8CC6)),
    RAINBOW("Arcoíris", Vega.Light), // el color real lo pone el pincel de arcoíris
    ;

    fun next(): FaceColor = entries[(ordinal + 1) % entries.size]

    companion object {
        fun from(name: String?): FaceColor = entries.firstOrNull { it.name == name } ?: WARM
    }
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
