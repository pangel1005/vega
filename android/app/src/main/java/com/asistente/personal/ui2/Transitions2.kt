package com.asistente.personal.ui2

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.max

/**
 * Dónde está en pantalla lo que abre cada pantalla (la tarjeta del mosaico, la llama de la cara): las transiciones nacen de ahí y
 * vuelven ahí al cerrar. Lo apuntan los propios elementos cada vez que se colocan.
 */
internal object Origins {
    var nowTile: Rect? = null
    var flame: Rect? = null
}

/** Apunta dónde está este elemento para que una pantalla pueda nacer de él. */
internal fun Modifier.origin(set: (Rect) -> Unit): Modifier = onGloballyPositioned { set(it.boundsInRoot()) }

private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f) // entra rápido y se posa suave
private val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f) // sale cogiendo velocidad

private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

private fun lerp(a: Rect, b: Rect, f: Float) =
    Rect(lerp(a.left, b.left, f), lerp(a.top, b.top, f), lerp(a.right, b.right, f), lerp(a.bottom, b.bottom, f))

internal enum class Grow { CARD, CIRCLE }

/**
 * Una pantalla que nace de un elemento y vuelve a él.
 * - [Grow.CARD]: la tarjeta se agranda hasta llenar la pantalla (las esquinas se enderezan) y el contenido aparece; el resto se oscurece.
 * - [Grow.CIRCLE]: un círculo se abre desde el elemento hasta cubrirlo todo (el fuego se extiende desde la llama).
 * Al cerrar hace lo mismo al revés, más deprisa. Sin origen conocido, nace del centro.
 */
@Composable
internal fun Expand(
    visible: Boolean,
    origin: Rect?,
    grow: Grow = Grow.CARD,
    content: @Composable () -> Unit,
) {
    val prog = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) prog.animateTo(1f, tween(if (grow == Grow.CIRCLE) 760 else 480, easing = Emphasized))
        else prog.animateTo(0f, tween(if (grow == Grow.CIRCLE) 380 else 300, easing = Accelerate))
    }
    // Solo cambia la composición al aparecer y al irse del todo; el resto del viaje se resuelve al dibujar.
    val shown by remember { derivedStateOf { prog.value > 0f } }
    if (!shown && !visible) return
    var self by remember { mutableStateOf(Rect.Zero) }
    fun originIn(size: Size): Rect {
        val full = Rect(Offset.Zero, size)
        return origin?.translate(-self.left, -self.top)?.takeIf { it.width > 0f } ?: full.deflate(size.minDimension * 0.3f)
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { self = it.boundsInRoot() }) {
        if (grow == Grow.CARD) Box(Modifier.fillMaxSize().graphicsLayer { alpha = prog.value }.background(Color(0x80000000)))
        // La capa de fuera recorta (forma que cambia en cada cuadro); la de dentro mueve y escala el contenido.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val p = prog.value
                val o = originIn(size)
                clip = true
                shape = GenericShape { sz, _ ->
                    if (grow == Grow.CIRCLE) {
                        val c = o.center
                        val far = max(max(hypot(c.x, c.y), hypot(sz.width - c.x, c.y)), max(hypot(c.x, sz.height - c.y), hypot(sz.width - c.x, sz.height - c.y)))
                        addOval(Rect(c, lerp(o.minDimension / 2f, far, p)))
                    } else {
                        addRoundRect(RoundRect(lerp(o, Rect(Offset.Zero, sz), p), CornerRadius(lerp(30.dp.toPx(), 0f, p))))
                    }
                }
            },
        ) {
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = prog.value
                    val o = originIn(size)
                    if (grow == Grow.CIRCLE) {
                        val k = lerp(1.12f, 1f, p)
                        scaleX = k
                        scaleY = k
                        transformOrigin = TransformOrigin((o.center.x / size.width).coerceIn(0f, 1f), (o.center.y / size.height).coerceIn(0f, 1f))
                    } else {
                        val r = lerp(o, Rect(Offset.Zero, size), p)
                        val k = lerp(o.height / size.height, 1f, p)
                        scaleX = k
                        scaleY = k
                        translationX = r.center.x - size.width / 2f
                        translationY = r.center.y - size.height / 2f
                        alpha = ((p - 0.08f) / 0.4f).coerceIn(0f, 1f)
                    }
                },
            ) { content() }
        }
    }
}
