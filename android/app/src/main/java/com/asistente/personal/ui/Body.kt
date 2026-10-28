package com.asistente.personal.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.ui.led.ledOff
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Memoria usada del teléfono (0 a 1), leída del sistema; null si no la da. */
fun phoneMemoryUsed(context: Context): Float? {
    val am = context.getSystemService(ActivityManager::class.java) ?: return null
    val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    return if (info.totalMem > 0) (1f - info.availMem.toFloat() / info.totalMem).coerceIn(0f, 1f) else null
}

/** Las frases de hoy y las llamadas, en una línea corta para la tarjeta «Cuerpo». */
fun earsText(heard: Int, calls: Int): String =
    "$heard ${if (heard == 1) "frase" else "frases"} · $calls ${if (calls == 1) "llamada" else "llamadas"}"

const val SCOPE_BARS = 48
const val SCOPE_ROWS = 3 // puntos hacia arriba y hacia abajo de la línea central

/** Mete una lectura nueva por la derecha del osciloscopio y desplaza el resto a la izquierda. */
fun scopePush(bars: FloatArray, level: Float) {
    System.arraycopy(bars, 1, bars, 0, bars.size - 1)
    bars[bars.size - 1] = level.coerceIn(0f, 1f)
}

/** Cuántos puntos se encienden por encima (y por debajo) de la línea central para un volumen dado. */
fun scopeLit(level: Float): Int = (level.coerceIn(0f, 1f) * SCOPE_ROWS).roundToInt()

/** Osciloscopio de LED: la onda de tu voz mientras te escucha. Se mueve de derecha a izquierda. */
@Composable
fun VoiceScope(pulse: Int, amount: Float, color: Color, modifier: Modifier = Modifier) {
    val bars = remember { FloatArray(SCOPE_BARS) }
    var tick by remember { mutableIntStateOf(0) }
    var level by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(pulse) { level = maxOf(level, amount) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            scopePush(bars, level)
            level *= 0.72f
            tick++
        }
    }
    val off = ledOff
    Canvas(modifier) {
        tick // se repinta con cada lectura
        val step = size.width / SCOPE_BARS
        val rowStep = size.height / (SCOPE_ROWS * 2 + 1)
        val r = minOf(step, rowStep) * 0.38f
        for (i in 0 until SCOPE_BARS) {
            val lit = scopeLit(bars[i])
            for (row in -SCOPE_ROWS..SCOPE_ROWS) {
                val on = row == 0 || kotlin.math.abs(row) <= lit
                drawCircle(
                    if (on) color.copy(alpha = if (row == 0 && lit == 0) 0.45f else 1f) else off,
                    r, Offset(step * (i + 0.5f), rowStep * (row + SCOPE_ROWS + 0.5f)),
                )
            }
        }
    }
}

/** Lo que se enseña de la conversación al tocar la respuesta: las últimas frases, sin avisos internos. */
fun recentChat(messages: List<Message>, limit: Int = 6): List<Message> = messages.filter { it.role != Role.NOTE }.takeLast(limit)

const val CHAT_OPEN_MS = 10_000L

/** La conversación reciente en letra grande (se abre tocando la respuesta; se cierra sola o al tocarla). */
@Composable
fun ConversationOverlay(messages: List<Message>, assistant: String, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val shown = recentChat(messages)
    LaunchedEffect(shown.size, shown.lastOrNull()?.text?.length) {
        delay(CHAT_OPEN_MS)
        onClose()
    }
    Column(
        modifier.fillMaxSize().background(Color(0xF2050505))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .safeDrawingPadding().padding(horizontal = 36.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        for (m in shown) {
            val vega = m.role == Role.ASSISTANT
            val error = m.role == Role.ERROR
            Text(
                when (m.role) { Role.USER -> "Tú"; Role.ASSISTANT -> assistant.ifEmpty { "Vega" }; else -> "Error" },
                TextStyle(fontFamily = Vega.Sans, fontSize = 12.sp, color = if (error) Vega.Red else if (vega) Vega.Coral else Vega.Ink3),
            )
            Text(
                m.text.replace('\n', ' '),
                TextStyle(
                    fontFamily = Vega.Sans, fontWeight = if (vega) FontWeight.Medium else FontWeight.Normal, fontSize = if (vega) 24.sp else 18.sp,
                    lineHeight = if (vega) 28.sp else 22.sp, color = if (vega) Vega.Ink else Vega.Ink2,
                ),
                maxLines = 4,
            )
            Spacer(Modifier.height(10.dp))
        }
        if (shown.isEmpty()) Text("Todavía no hemos hablado.", TextStyle(fontFamily = Vega.Sans, fontSize = 20.sp, color = Vega.Ink2))
    }
}

/** En qué punto está la IA del teléfono, sacado de lo que dice la pastilla («Bajando IA 45 %», «Cargando IA…», «IA: error»). */
data class BrainLoad(val busy: Boolean, val fraction: Float?, val failed: Boolean) {
    companion object {
        val None = BrainLoad(busy = false, fraction = null, failed = false)
    }
}

fun brainLoad(label: String): BrainLoad {
    val percent = Regex("""Bajando IA (\d+) ?%""").find(label)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        percent != null -> BrainLoad(busy = true, fraction = (percent / 100f).coerceIn(0f, 1f), failed = false)
        label.startsWith("Cargando IA") -> BrainLoad(busy = true, fraction = null, failed = false) // sin porcentaje: barra que corre
        label == "IA: error" || label == "IA: sin modelos" -> BrainLoad(busy = false, fraction = null, failed = true)
        else -> BrainLoad.None
    }
}

const val LED_BAR_DOTS = 40

/** Cuántos puntos de la barra se encienden para una fracción (0 a 1). */
fun ledBarLit(fraction: Float, dots: Int = LED_BAR_DOTS): Int = (fraction.coerceIn(0f, 1f) * dots + 0.5f).toInt()

/** ¿Está encendido el punto `i` de la barra «que corre» (sin porcentaje) en el paso `step`? Una ventana de `width` puntos que da la vuelta. */
fun ledBarRuns(i: Int, step: Int, dots: Int = LED_BAR_DOTS, width: Int = 6): Boolean {
    val cycle = dots + width
    return (((step - i) % cycle) + cycle) % cycle < width
}

/** Barra de LED: con `fraction` se llena; sin ella (null) una luz corre de lado a lado. */
@Composable
fun LedBar(fraction: Float?, color: Color, modifier: Modifier = Modifier) {
    var step by remember { mutableIntStateOf(0) }
    if (fraction == null) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(55)
                step++
            }
        }
    }
    val off = ledOff
    Canvas(modifier) {
        val w = size.width / LED_BAR_DOTS
        val r = minOf(w, size.height) * 0.4f
        val lit = if (fraction != null) ledBarLit(fraction) else 0
        for (i in 0 until LED_BAR_DOTS) {
            val on = if (fraction != null) i < lit else ledBarRuns(i, step)
            drawCircle(if (on) color else off, r, Offset(w * (i + 0.5f), size.height / 2))
        }
    }
}

/** Marco rojo que late por todo el borde de la pantalla mientras hay que confirmar algo peligroso. */
@Composable
fun AlertFrame(modifier: Modifier = Modifier) {
    val alpha by rememberInfiniteTransition(label = "alerta").animateFloat(
        0.25f, 0.95f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "latido",
    )
    Box(modifier.fillMaxSize().border(4.dp, Vega.Red.copy(alpha = alpha), RoundedCornerShape(18.dp)))
}
