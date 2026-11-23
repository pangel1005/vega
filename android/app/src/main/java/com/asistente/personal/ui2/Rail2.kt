package com.asistente.personal.ui2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text

/*
 * El riel de estado: una columna fina en la franja que el hueco de la cámara frontal deja a un lado de la pantalla (en el Redmi,
 * unos 47 dp a la derecha) y que antes estaba vacía. Lleva lo que importa saber siempre, sin abrir nada: si hay enlace con el PC,
 * si la cámara está encendida, si el micro está abierto (los indicadores de privacidad) y la batería del teléfono. Es solo
 * información: no se toca, para que nadie lo pulse sin querer junto al hueco de la cámara.
 */

internal enum class RailSide { START, END }

/** En qué lado y de qué ancho cabe el riel según los márgenes seguros; null si ninguno es lo bastante ancho. */
internal fun railSide(left: Dp, right: Dp, min: Dp = 30.dp): Pair<RailSide, Dp>? = when {
    right >= min && right >= left -> RailSide.END to right
    left >= min -> RailSide.START to left
    else -> null
}

/** Para las pruebas y las capturas: fuerza el ancho del riel (en el simulador no hay hueco de cámara). */
internal val LocalRailInset = compositionLocalOf<Dp?> { null }

/** Dibuja el riel en el lado que toque, o nada si no hay franja. */
@Composable
internal fun RailHost(m: ScreenModel) {
    val forced = LocalRailInset.current
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val found = if (forced != null) RailSide.END to forced
    else railSide(insets.calculateLeftPadding(LayoutDirection.Ltr), insets.calculateRightPadding(LayoutDirection.Ltr))
    if (found == null) return
    val (side, width) = found
    Box(Modifier.fillMaxSize()) {
        StatusRail2(m, palFor(m.clock), Modifier.align(if (side == RailSide.END) Alignment.CenterEnd else Alignment.CenterStart).width(width))
    }
}

@Composable
internal fun StatusRail2(m: ScreenModel, p: Pal, modifier: Modifier = Modifier) {
    val ui = m.ui
    val (linkColor, linkWord) = when (ui.link) {
        LinkStatus.OPEN -> p.mint to "en línea"
        LinkStatus.CONNECTING, LinkStatus.RECONNECTING -> p.sun to "reconectando"
        LinkStatus.DENIED -> p.red to "sin permiso"
    }
    val micOn = m.listening && m.micOn
    val cam = ui.camera
    // Arriba queda por debajo del punto verde de privacidad de Android (esquina) y se corta antes del hueco de la cámara (mitad).
    Column(modifier.fillMaxHeight().padding(top = 44.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(Modifier.semantics { contentDescription = "Conexión con el PC: $linkWord" }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(10.dp).background(linkColor, CircleShape))
                Text("PC", maxLines = 1, style = Type2.body(12.sp, p.ink2, FontWeight.SemiBold), modifier = Modifier.padding(top = 3.dp))
            }
            if (cam.available) {
                Box(Modifier.semantics { contentDescription = if (cam.on) "Cámara encendida" else "Cámara apagada" }) {
                    Icon2("camera", if (cam.on) p.mint else p.ink3, 20.dp)
                }
            }
            Box(Modifier.semantics { contentDescription = if (micOn) "Micrófono abierto" else "Micrófono apagado" }, contentAlignment = Alignment.Center) {
                Icon2("mic", if (micOn) p.ink else p.ink3, 20.dp)
                if (!micOn) Canvas(Modifier.size(20.dp)) {
                    drawLine(p.ink3, Offset(size.width * 0.15f, size.height * 0.85f), Offset(size.width * 0.85f, size.height * 0.15f), 2.dp.toPx(), StrokeCap.Round)
                }
            }
        }
        BatteryMark(m.battery, m.charging, p)
    }
}

/** La batería del teléfono: una pila que se vacía, un rayo si carga y el número. */
@Composable
private fun BatteryMark(level: Int, charging: Boolean, p: Pal) {
    val f = if (level >= 0) level / 100f else null
    val color = heat(p, f ?: 0f, if (charging) p.mint else p.lilac, invert = true)
    Column(
        Modifier.semantics { contentDescription = "Batería del teléfono " + (if (level >= 0) "$level %" else "sin datos") + if (charging) ", cargando" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(width = 16.dp, height = 26.dp)) {
            val corner = CornerRadius(3.dp.toPx())
            val stroke = 2.dp.toPx()
            drawRoundRect(p.ink3, Offset(size.width * 0.3f, 0f), Size(size.width * 0.4f, 3.dp.toPx()), CornerRadius(1.dp.toPx()))
            val body = Size(size.width, size.height - 3.dp.toPx())
            drawRoundRect(p.ink3, Offset(0f, 3.dp.toPx()), body, corner, style = Stroke(stroke))
            if (f != null) {
                val inner = Size(body.width - stroke * 2, (body.height - stroke * 2) * f)
                drawRoundRect(color, Offset(stroke, 3.dp.toPx() + stroke + (body.height - stroke * 2) - inner.height), inner, CornerRadius(1.5.dp.toPx()))
            }
            if (charging) {
                val bolt = Path().apply {
                    moveTo(size.width * 0.58f, 6.dp.toPx())
                    lineTo(size.width * 0.28f, size.height * 0.56f)
                    lineTo(size.width * 0.5f, size.height * 0.56f)
                    lineTo(size.width * 0.42f, size.height - 4.dp.toPx())
                    lineTo(size.width * 0.74f, size.height * 0.44f)
                    lineTo(size.width * 0.52f, size.height * 0.44f)
                    close()
                }
                drawPath(bolt, if (p.night) Color.White else p.ink)
            }
        }
        Text(if (level >= 0) "$level" else "–", maxLines = 1, style = Type2.body(12.sp, p.ink2, FontWeight.SemiBold).copy(textAlign = TextAlign.Center), modifier = Modifier.padding(top = 3.dp))
    }
}
