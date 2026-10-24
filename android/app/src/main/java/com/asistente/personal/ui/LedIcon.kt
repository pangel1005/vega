package com.asistente.personal.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/** Puntos por lado de un icono de LED (como la cara: todo está hecho de luz). */
const val ICON_DOTS = 11
private const val SUPER = 4 // se dibuja a 4× y se mide cuánto cubre cada punto

/**
 * Un icono convertido en rejilla de LED: para cada punto, su color y su brillo (0–1). Sale de
 * los mismos dibujos de siempre (`drawDeckIcon`) o del icono real de una app (Spotify en
 * puntos verdes, Discord en violeta…). Se calcula una vez por icono.
 */
class LedGlyph(val colors: Array<Color?>, val levels: FloatArray)

private val glyphCache = HashMap<String, LedGlyph>()

/** Dibujo de siempre → puntos: brillo según cuánto del punto cubre el trazo. */
fun deckGlyph(icon: String): LedGlyph = glyphCache.getOrPut("icon:$icon") {
    val px = ICON_DOTS * SUPER
    val bitmap = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(px.toFloat(), px.toFloat())) {
        // Con un margen de un punto, para que el icono no toque el borde de la rejilla.
        val inset = SUPER.toFloat()
        drawContext.transform.translate(inset, inset)
        drawContext.transform.scale((px - 2 * inset) / px, (px - 2 * inset) / px, Offset.Zero)
        drawDeckIcon(icon, Color.White)
    }
    val map = bitmap.toPixelMap()
    val levels = FloatArray(ICON_DOTS * ICON_DOTS) { i ->
        val cx = i % ICON_DOTS
        val cy = i / ICON_DOTS
        var sum = 0f
        for (y in 0 until SUPER) for (x in 0 until SUPER) sum += map[cx * SUPER + x, cy * SUPER + y].alpha
        val cover = sum / (SUPER * SUPER)
        // Un LED está encendido o apagado; a medias solo en los bordes del trazo.
        when {
            cover > 0.2f -> 1f
            cover > 0.06f -> 0.35f
            else -> 0f
        }
    }
    LedGlyph(arrayOfNulls(levels.size), levels)
}

/** Icono real de una app → puntos de colores (su color en cada punto; lo transparente, apagado). */
fun imageGlyph(key: String, image: ImageBitmap): LedGlyph = glyphCache.getOrPut("img:$key") {
    val map = image.toPixelMap()
    val cell = min(image.width, image.height) / ICON_DOTS.toFloat()
    val colors = arrayOfNulls<Color>(ICON_DOTS * ICON_DOTS)
    val levels = FloatArray(ICON_DOTS * ICON_DOTS)
    for (cy in 0 until ICON_DOTS) for (cx in 0 until ICON_DOTS) {
        var r = 0f
        var g = 0f
        var b = 0f
        var a = 0f
        var n = 0
        val x0 = (cx * cell).toInt()
        val y0 = (cy * cell).toInt()
        val x1 = ((cx + 1) * cell).toInt().coerceAtMost(image.width)
        val y1 = ((cy + 1) * cell).toInt().coerceAtMost(image.height)
        for (y in y0 until y1) for (x in x0 until x1) {
            val c = map[x, y]
            r += c.red * c.alpha
            g += c.green * c.alpha
            b += c.blue * c.alpha
            a += c.alpha
            n++
        }
        val i = cy * ICON_DOTS + cx
        if (n == 0 || a / n < 0.3f) continue
        // Color medio del trozo, un poco más vivo (en LED los colores apagados se pierden).
        val k = 1f / a
        val base = Color(r * k, g * k, b * k)
        val peak = maxOf(base.red, base.green, base.blue).coerceAtLeast(0.2f)
        colors[i] = Color((base.red / peak).coerceAtMost(1f), (base.green / peak).coerceAtMost(1f), (base.blue / peak).coerceAtMost(1f))
        // Lo casi negro del icono (fondos oscuros) se queda tenue, como un LED a medio gas.
        levels[i] = (0.35f + 0.65f * peak).coerceAtMost(1f)
    }
    LedGlyph(colors, levels)
}

/**
 * Pinta un icono de LED. Los puntos apagados se ven muy tenues (como en la cara); `glow`
 * (0–1) sube el brillo, p. ej. mientras se pulsa la tecla.
 */
@Composable
fun LedIcon(glyph: LedGlyph, color: Color, modifier: Modifier = Modifier, glow: Float = 0f) {
    val off = remember { Color.White.copy(alpha = 0.07f) }
    Canvas(modifier) {
        val pitch = size.minDimension / ICON_DOTS
        val r = pitch * 0.4f
        val ox = (size.width - pitch * ICON_DOTS) / 2 + pitch / 2
        val oy = (size.height - pitch * ICON_DOTS) / 2 + pitch / 2
        for (i in glyph.levels.indices) {
            val level = glyph.levels[i]
            val center = Offset(ox + (i % ICON_DOTS) * pitch, oy + (i / ICON_DOTS) * pitch)
            if (level <= 0f) {
                drawCircle(off, r * 0.8f, center)
                continue
            }
            val c = glyph.colors[i] ?: color
            val a = (level * (0.82f + 0.18f * glow)).coerceAtMost(1f)
            drawCircle(c.copy(alpha = a), r * (1f + 0.12f * glow), center)
        }
    }
}
