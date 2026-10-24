package com.asistente.personal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.PendingConfirm

/** Hoja «Más»: todos los botones en cuadrícula y el cambio de voz. */
@Composable
fun Deck(
    actions: List<DeckAction>,
    pending: PendingConfirm?,
    secondsLeft: Int,
    enabled: Boolean,
    onPress: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    voiceLabel: String? = null,
    onNextVoice: () -> Unit = {},
    icons: Map<String, ImageBitmap> = emptyMap(),
    faceColor: FaceColor = FaceColor.WARM,
    onNextFaceColor: () -> Unit = {},
    brainLabel: String = "IA: PC",
    brainOnPhone: Boolean = false,
    onToggleBrain: () -> Unit = {},
    touchLabel: String = TouchFeel.SOFT.label,
    onNextTouch: () -> Unit = {},
    glass: Boolean = false,
    onToggleGlass: () -> Unit = {},
) {
    Column(modifier.card(fill = Vega.Card.copy(alpha = 0.98f), overlay = true).padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Todos los botones", style = TextStyle(fontFamily = Vega.Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Vega.Ink))
            Spacer(Modifier.weight(1f))
            // Dónde piensa Vega: en el teléfono, el PC suelta la gráfica (para jugar sin estorbos).
            Pill(brainLabel, if (brainOnPhone) Vega.Mint else Vega.Ink2, onToggleBrain, filled = brainOnPhone)
            Spacer(Modifier.width(8.dp))
            // Estilo: «Noche» (tarjetas lisas) o «Cristal» (aurora y cristal, prototipo).
            Pill(if (glass) "Estilo: Cristal" else "Estilo: Noche", if (glass) Vega.Sky else Vega.Ink2, onToggleGlass)
            Spacer(Modifier.width(8.dp))
            // Vibración al tocar: fuerte, suave o sin vibrar.
            Pill("Toque: $touchLabel", Vega.Ink2, onNextTouch)
            Spacer(Modifier.width(8.dp))
            // Color de la cara: cada toque pasa al siguiente (el último es el arcoíris).
            Pill("Cara: ${faceColor.label}", if (faceColor == FaceColor.RAINBOW) Vega.Amber else faceColor.color, onNextFaceColor)
            Spacer(Modifier.width(8.dp))
            // Cambiar de voz: pasa a la siguiente y Vega se presenta con ella.
            if (voiceLabel != null) {
                Pill("Voz: $voiceLabel", Vega.Sky, onNextVoice)
                Spacer(Modifier.width(8.dp))
            }
            Pill("Cerrar", Vega.Ink2, onClose)
        }
        // Cuadrícula sencilla (no perezosa): con ~16 botones, construirla de una vez es más barato
        // que la lista perezosa, que daba tirones al abrir la hoja (medido: hasta 81 ms).
        val perRow = 6
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { a ->
                        Key(
                            label = a.label, icon = a.icon, color = deckColor(a.color), enabled = enabled, image = icons[a.id], id = a.id,
                            confirming = pending?.id == a.id, secondsLeft = secondsLeft, onClick = { onPress(a.id) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
