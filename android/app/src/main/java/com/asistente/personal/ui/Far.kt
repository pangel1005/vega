package com.asistente.personal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Role

/** Lo que dice la pantalla de lejos de la última respuesta de Vega (vacío si aún no ha hablado). */
fun farAnswer(messages: List<com.asistente.personal.state.Message>): String =
    messages.lastOrNull { it.role == Role.ASSISTANT }?.text?.replace('\n', ' ').orEmpty()

/**
 * Modo de lejos: tras 10 minutos sin nadie, la pantalla baja a muy poco brillo y deja solo lo que se lee desde
 * la cama o el sofá: la hora enorme, el estado en una palabra y la última respuesta en letra grande.
 * Cualquier toque la devuelve a la pantalla normal.
 */
@Composable
fun FarScreen(m: ScreenModel, modifier: Modifier = Modifier) {
    val ui = m.ui
    val word = terminalStatus(m.state, m.sleeping, m.micOn).lowercase().replaceFirstChar { it.uppercase() }
    val timer = (m.localTimerEndsAt ?: ui.telemetry?.upcoming?.firstOrNull { it.kind == "timer" }?.let { ui.telemetryAt + it.dueIn * 1000L })
        ?.let { ((it - m.clockMs() + 999) / 1000).toInt() }?.takeIf { it > 0 }
    val answer = farAnswer(ui.messages)
    Row(
        modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(horizontal = 32.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(m.clock, TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 140.sp, lineHeight = 140.sp, color = Color(0xFFC2BBAF)))
        Spacer(Modifier.width(28.dp))
        Column {
            Text(m.date, TextStyle(fontFamily = Vega.Sans, fontSize = 16.sp, color = Color(0xFF7A766F)), maxLines = 1)
            Text(
                word + if (ui.link != LinkStatus.OPEN && !m.brainOnPhone) " · sin PC" else "",
                TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Medium, fontSize = 28.sp, color = Color(0xFFB9B2A6)), maxLines = 1,
            )
            timer?.let {
                Text("%d:%02d".format(it / 60, it % 60), TextStyle(fontFamily = Vega.Dots, fontWeight = FontWeight.Black, fontSize = 30.sp, color = Vega.Amber))
            }
            if (answer.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(answer, TextStyle(fontFamily = Vega.Sans, fontSize = 22.sp, lineHeight = 27.sp, color = Color(0xFF8C867C)), maxLines = 3)
            }
        }
    }
}
