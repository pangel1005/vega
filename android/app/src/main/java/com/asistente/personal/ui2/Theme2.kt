package com.asistente.personal.ui2

import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.asistente.personal.ui.led.Mood

/*
 * Vega · nuevo diseño. Nada que ver con el de puntos y tarjetas negras: aquí hay papel y tinta de día, y tinta y luz de noche;
 * mosaicos grandes de esquinas muy redondas, cifras finas y una cara dibujada con curvas que vive sobre un mosaico de color.
 * De día (de 7 a 20 h) la pantalla es clara; por la noche se oscurece y la cara pasa de «mosaico de color» a «ojos de luz».
 */

internal class Pal(
    val night: Boolean,
    val bg: Color,
    val tile: Color,
    val tile2: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val sun: Color,
    val coral: Color,
    val lilac: Color,
    val mint: Color,
    val sky: Color,
    val red: Color,
)

internal val DayPal = Pal(
    night = false,
    bg = Color(0xFFEEE8DB), tile = Color(0xFFFBF8F1), tile2 = Color(0xFFE0D8C7),
    ink = Color(0xFF211D17), ink2 = Color(0xFF554C40), ink3 = Color(0xFF645B4E),
    sun = Color(0xFFFFC23D), coral = Color(0xFFFF6A47), lilac = Color(0xFFA898FF),
    mint = Color(0xFF3FC79A), sky = Color(0xFF55B4FF), red = Color(0xFFE5484D),
)

internal val NightPal = Pal(
    night = true,
    bg = Color(0xFF0E0C13), tile = Color(0xFF1A1723), tile2 = Color(0xFF2A2538),
    ink = Color(0xFFF3EEFA), ink2 = Color(0xFFB3ACC8), ink3 = Color(0xFF9890B2),
    sun = Color(0xFFFFC95C), coral = Color(0xFFFF7D5C), lilac = Color(0xFFB9ABFF),
    mint = Color(0xFF55E0AE), sky = Color(0xFF6CC2FF), red = Color(0xFFFF6166),
)

/** Contraste entre dos colores según WCAG (de 1 a 21): para texto normal se pide 4,5 o más. */
internal fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return maxOf(la, lb) / minOf(la, lb)
}

/**
 * Un color de acento (coral, amarillo, azul…) usado como TEXTO. Los acentos están pensados para dibujar, no para escribir:
 * sobre el papel claro no llegan al contraste mínimo (el amarillo, a 1,5). Si no se lee con `min`, se oscurece sin cambiar de tono
 * (de noche se aclara); si ya se lee, se queda como está.
 */
internal fun Pal.readable(accent: Color, min: Float = 4.5f): Color {
    val grounds = listOf(tile, tile2, bg)
    fun ok(c: Color) = grounds.all { contrast(c, it) >= min }
    var c = accent
    repeat(40) {
        if (ok(c)) return c
        c = lerp(c, if (night) Color.White else Color.Black, 0.06f)
    }
    return c
}

/** Tema elegido en «Más»: 0 = automático (por la hora), 1 = siempre de día, 2 = siempre de noche. */
internal object ThemeMode {
    var mode by mutableIntStateOf(0)

    fun next() {
        mode = (mode + 1) % 3
    }
}

/** Día de 7 a 20 h; el resto, noche (salvo que se haya elegido el tema a mano). La hora llega como «21:48». */
internal fun palFor(clock: String): Pal {
    if (ThemeMode.mode == 1) return DayPal
    if (ThemeMode.mode == 2) return NightPal
    val hour = clock.substringBefore(':').trim().toIntOrNull() ?: 21
    return if (hour in 7..19) DayPal else NightPal
}

/** El color de la cara según su ánimo; en reposo, el del momento (música, juego o el sol). */
internal fun faceAccent(p: Pal, mood: Mood, base: Color): Color = when (mood) {
    Mood.LISTEN -> p.coral
    Mood.THINK -> p.lilac
    Mood.HAPPY -> p.mint
    Mood.ERROR, Mood.ALERT -> p.red
    Mood.SLEEP -> p.sky
    Mood.OFFLINE -> p.ink3
    else -> base
}

internal object Type2 {
    private val sans = FontFamily.SansSerif

    /** Cifras finas y anchas (la hora, los contadores). */
    fun num(size: TextUnit, color: Color) = TextStyle(
        fontFamily = sans, fontWeight = FontWeight.Light, fontSize = size, lineHeight = size, color = color, fontFeatureSettings = "tnum",
    )

    fun label(color: Color) = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.8.sp, color = color)

    fun body(size: TextUnit, color: Color, weight: FontWeight = FontWeight.Medium, line: TextUnit = TextUnit.Unspecified) = TextStyle(
        fontFamily = sans, fontWeight = weight, fontSize = size, lineHeight = line, color = color, fontFeatureSettings = "tnum",
    )
}
