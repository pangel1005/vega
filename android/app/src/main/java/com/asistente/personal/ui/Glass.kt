package com.asistente.personal.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.cos
import kotlin.math.sin

/**
 * Estilo «Cristal» (PROTOTIPO): una aurora muy tenue detrás de todo y tarjetas y teclas de
 * cristal ahumado que la dejan ver, desenfocada y un poco aumentada (como una lente).
 *
 * No desenfoca la pantalla de verdad (caro en la gráfica del Redmi): como la aurora se dibuja
 * a partir de unas pocas manchas de luz conocidas, cada cristal dibuja esas mismas manchas
 * más grandes y suaves en su sitio. Se ve igual que un desenfoque y casi no cuesta.
 */
@Stable
class Aurora {
    /**
     * Posición de las manchas. Quieta a propósito: cada cambio obliga a repintar los cristales y
     * en el Redmi eso daba tirones (medido: moverla 4 veces por segundo → ~3 tirones por segundo).
     */
    var time by mutableFloatStateOf(0f)
    /** Colores de las tres manchas (los pone la pantalla según el ánimo y la música). */
    var colors by mutableStateOf(listOf(Vega.Coral, Color(0xFFB79BFF), Vega.Sky))
    /** Tamaño de la pantalla (para que cada cristal sepa dónde caen las manchas). */
    var screen by mutableStateOf(Size.Zero)
}

/** La aurora activa; null = estilo «Noche» de siempre (tarjetas lisas sobre negro). */
val LocalAurora = staticCompositionLocalOf<Aurora?> { null }

private class Blob(val x: Float, val y: Float, val r: Float, val color: Color)

/** Tres manchas que derivan despacio (posición en fracciones de la pantalla, radio por su alto). */
private fun blobs(t: Float, colors: List<Color>): List<Blob> = listOf(
    Blob(0.28f + 0.10f * sin(t * 0.13f), 0.30f + 0.14f * cos(t * 0.11f), 0.95f, colors[0]),
    Blob(0.74f + 0.12f * cos(t * 0.09f), 0.55f + 0.12f * sin(t * 0.12f), 1.05f, colors[1]),
    Blob(0.50f + 0.20f * sin(t * 0.07f + 1f), 1.02f + 0.06f * cos(t * 0.10f), 0.85f, colors[2]),
)

/**
 * Dibuja la aurora vista desde un trozo de pantalla que empieza en `origin`. `soft` agranda las
 * manchas (más desenfocadas, como tras un cristal) y `strength` es su intensidad.
 */
private fun DrawScope.aurora(a: Aurora, origin: Offset, soft: Float, strength: Float, area: Size) {
    val screen = a.screen
    if (screen == Size.Zero) return
    for (b in blobs(a.time, a.colors)) {
        val center = Offset(b.x * screen.width, b.y * screen.height) - origin
        val radius = b.r * screen.height * soft
        drawRect(
            Brush.radialGradient(
                listOf(b.color.copy(alpha = strength), b.color.copy(alpha = strength * 0.35f), Color.Transparent),
                center = center,
                radius = radius,
            ),
            size = area,
        )
    }
}

/** Lo de la aurora se pinta a un cuarto de resolución: va desenfocado, no se pierde nada. */
private const val DOWNSCALE = 4

/**
 * Pinta `block` (que dibuja como si midiera `full`) en una imagen pequeña para estirarla en cada
 * cuadro. Medido en el Redmi: pintar los degradados de todos los cristales en cada cuadro
 * llevaba cada cuadro de 21 a ~30 ms, porque la cara cambia 60 veces por segundo y Android
 * repinta la pantalla entera. Así solo se pintan cuando cambia el color de la aurora.
 */
private fun CacheDrawScope.lowRes(block: DrawScope.(full: Size) -> Unit): ImageBitmap {
    val full = size
    val w = (full.width / DOWNSCALE).toInt().coerceAtLeast(1)
    val h = (full.height / DOWNSCALE).toInt().coerceAtLeast(1)
    val bitmap = ImageBitmap(w, h)
    CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        scale(w / full.width, h / full.height, pivot = Offset.Zero) { block(full) }
    }
    return bitmap
}

private fun DrawScope.stretch(image: ImageBitmap) =
    scale(size.width / image.width, size.height / image.height, pivot = Offset.Zero) { drawImage(image) }

/** Fondo de la pantalla en estilo «Cristal»: negro con la aurora. */
@Composable
fun AuroraBackground(a: Aurora, modifier: Modifier = Modifier) {
    Spacer(
        modifier.fillMaxSize().onSizeChanged { a.screen = it.toSize() }.drawWithCache {
            val image = lowRes { full ->
                drawRect(Vega.Bg, size = full)
                aurora(a, Offset.Zero, soft = 1f, strength = 0.22f, area = full)
            }
            onDrawBehind { stretch(image) }
        },
    )
}

/**
 * Cristal ahumado: deja ver la aurora de detrás (más suave y aumentada un 10 %, como una
 * lente), con un brillo arriba y un borde de luz que se apaga hacia abajo. `tint` tiñe el
 * borde (el ánimo de Vega, una tecla pulsada…). `smoke` es lo oscuro del cristal: las hojas
 * que tapan otras cosas lo llevan casi opaco (este cristal no desenfoca lo de detrás).
 */
fun Modifier.glass(shape: Shape, tint: Color = Vega.Line, smoke: Float = 0.62f): Modifier = composed {
    val a = LocalAurora.current ?: return@composed this
    var origin by remember { mutableStateOf(Offset.Zero) }
    onGloballyPositioned { origin = it.positionInRoot() }.drawWithCache {
        val at = origin
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        val image = lowRes { full ->
            drawRect(Color(0xFF09090B).copy(alpha = smoke), size = full)
            withTransform({ scale(1.1f, 1.1f, Offset(full.width / 2, full.height / 2)) }) {
                aurora(a, at, soft = 1.25f, strength = 0.26f, area = full)
            }
            drawRect(
                Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.07f), Color.Transparent), endY = full.height * 0.5f),
                size = full,
            )
        }
        val edge = if (tint == Vega.Line) Color.White.copy(alpha = 0.05f) else tint
        val rim = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), edge, edge))
        val stroke = Stroke(1.dp.toPx())
        onDrawBehind {
            clipPath(path) { stretch(image) }
            drawOutline(outline, rim, style = stroke)
        }
    }
}
