package com.asistente.personal.ui

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
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
