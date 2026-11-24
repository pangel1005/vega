package com.asistente.personal.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.ui.led.Glance
import com.asistente.personal.ui.led.Mood

/** Todo lo que necesita el mosaico de la portada (ui2/Home2.kt), tal como lo calcula VegaScreen. */
internal class LayoutArgs(
    val m: ScreenModel,
    val a: ScreenActions,
    val mood: Mood,
    val glance: Glance?,
    val elapsed: Long,
    val secondsLeft: Int,
    val canAct: Boolean,
    val press: (String) -> Unit,
    val onMore: () -> Unit,
    val onChat: () -> Unit,
    val onOpenMusic: () -> Unit,
    val onOpenGame: () -> Unit,
    val confirmFraction: Float?,
    /** Abre la «Mesa» (volumen por programa y fondo de pantalla del PC). */
    val onMesa: () -> Unit = {},
    /** Abre el tablero de Moodle (entregas por columnas). */
    val onTablero: () -> Unit = {},
    /** Abre la racha del gimnasio (la llama). */
    val onRacha: () -> Unit = {},
    /** Abre todos los avisos del PC (agrupados por app). */
    val onOpenNotes: () -> Unit = {},
)

internal val es = java.util.Locale("es", "ES")

/** Un toque en la cara: le pide atención a Vega o, sin enlace con el PC, reintenta la conexión ya. */
@Composable
internal fun Modifier.attention(x: LayoutArgs): Modifier =
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        // Sin enlace con el PC (y la IA no está en el teléfono), un toque en la cara reintenta ya.
        if (x.m.ui.link != LinkStatus.OPEN && !x.m.brainOnPhone) x.a.retryNow() else x.a.attention()
    }
