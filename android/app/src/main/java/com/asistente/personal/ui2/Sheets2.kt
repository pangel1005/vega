package com.asistente.personal.ui2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeckAction
import com.asistente.personal.state.Role
import com.asistente.personal.ui.CHAT_OPEN_MS
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.nextAlert
import com.asistente.personal.ui.nextAlertLabel
import com.asistente.personal.ui.recentChat
import com.asistente.personal.ui.led.Mood
import kotlinx.coroutines.delay

// ───────────────────────── hoja «Más» ─────────────────────────

/** Una fila de ajuste que se cambia tocándola: nombre a la izquierda, valor a la derecha. */
@Composable
private fun SettingRow(label: String, value: String, p: Pal, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.width(56.dp))
        Text(value, maxLines = 1, style = Type2.body(13.sp, p.readable(accent), FontWeight.SemiBold), modifier = Modifier.weight(1f))
        Text("›", maxLines = 1, style = Type2.body(15.sp, p.ink3))
    }
}

/** Interruptor de varias opciones a la vista (la elegida resalta): se ve qué hay y se elige de un toque. */
@Composable
internal fun Segmented(options: List<String>, selected: Int, p: Pal, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(p.tile2).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEachIndexed { i, text ->
            val on = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(13.dp)).background(if (on) p.tile else Color.Transparent)
                    .clickable(enabled = !on) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(text, maxLines = 1, style = Type2.body(12.sp, if (on) p.ink else p.ink3, if (on) FontWeight.Bold else FontWeight.Medium))
            }
        }
    }
}

/** Los nombres largos («Cancelar apagado») se acortan para que quepan en el botón: queda la primera palabra. */
internal fun deckLabel(label: String): String =
    if (label.length > 14 && ' ' in label) label.substringBefore(' ') else label

/** Los botones del PC por tipo de cosa: lo peligroso junto, el sonido junto, etc. Dentro de cada grupo, en un orden fijo. */
internal fun deckGroups(actions: List<DeckAction>): List<Pair<String, List<DeckAction>>> {
    val power = listOf("shutdown", "restart", "sleep", "lock", "cancel")
    val sound = listOf("mute", "volume-down", "volume-up", "play", "next", "prev")
    fun group(a: DeckAction): String = when {
        a.power.isNotEmpty() -> "Equipo"
        a.icon in sound -> "Sonido"
        a.appIcon || a.icon == "app" -> "Apps"
        else -> "Extras"
    }
    val order = listOf("Equipo", "Sonido", "Extras", "Apps")
    val byGroup = actions.groupBy(::group)
    return order.mapNotNull { name ->
        byGroup[name]?.let { list ->
            name to when (name) {
                "Equipo" -> list.sortedBy { power.indexOf(it.power).let { i -> if (i < 0) 99 else i } }
                "Sonido" -> list.sortedBy { sound.indexOf(it.icon).let { i -> if (i < 0) 99 else i } }
                else -> list
            }
        }
    }
}

/** Ajustes a la izquierda (interruptores claros) y los botones del PC a la derecha, agrupados por tipo. */
@Composable
internal fun DeckSheet2(m: ScreenModel, a: ScreenActions, p: Pal, secondsLeft: Int, canAct: Boolean, onClose: () -> Unit) {
    val ui = m.ui
    val groups = deckGroups(ui.actions)
    Row(Modifier.fillMaxSize().clip(RoundedCornerShape(34.dp)).background(p.bg).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.width(240.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ajustes", maxLines = 1, style = Type2.body(20.sp, p.ink, FontWeight.Bold), modifier = Modifier.weight(1f))
                RoundBtn("cancel", 34.dp, p.tile2, p.ink, onClose)
            }
            Column(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(24.dp)).background(p.tile).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("DÓNDE PIENSA VEGA", maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.padding(start = 2.dp))
                Segmented(listOf("En el PC", "En el teléfono"), if (m.brainOnPhone) 1 else 0, p, { a.toggleBrain() }, Modifier.fillMaxWidth())
                val status = m.brainLabel.removePrefix("IA: ").takeIf { it != "PC" && it != "teléfono" }
                if (status != null) Text(status, maxLines = 1, style = Type2.body(12.sp, p.readable(p.sun), FontWeight.SemiBold), modifier = Modifier.padding(start = 2.dp))
                Spacer(Modifier.height(4.dp))
                Text("TEMA", maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.padding(start = 2.dp))
                Segmented(listOf("Auto", "Día", "Noche"), ThemeMode.mode, p, { ThemeMode.mode = it }, Modifier.fillMaxWidth())
                if (ui.camera.available) {
                    Spacer(Modifier.height(4.dp))
                    Text("CÁMARA", maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.padding(start = 2.dp))
                    Segmented(listOf("Frontal", "Trasera"), if (ui.camera.facing == "back") 1 else 0, p, { a.cameraFacing(if (it == 1) "back" else "front") }, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.weight(1f))
                ui.voices.firstOrNull { it.id == ui.voice }?.label?.let { SettingRow("Voz", it, p, p.sky, a.nextVoice) }
                SettingRow("Toque", m.touchFeel.label, p, p.ink, a.nextTouchFeel)
                SettingRow("Onda", m.waveStyle.label, p, p.sky, a.nextWaveStyle)
                SettingRow("Android", "Abrir ajustes", p, p.ink2, a.openAndroid)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { (name, list) ->
                list.chunked(5).forEachIndexed { rowIndex, row ->
                    Row(
                        Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(26.dp)).background(p.tile2.copy(alpha = if (p.night) 0.55f else 0.6f)).padding(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (rowIndex == 0) name.uppercase() else "", maxLines = 1,
                            style = Type2.label(if (name == "Equipo") p.red else p.ink3), modifier = Modifier.width(60.dp).padding(start = 4.dp),
                        )
                        row.forEach { act ->
                            val accent = when (act.color) { "red" -> p.red; "amber" -> p.sun; "green" -> p.mint; "teal" -> p.sky; else -> p.coral }
                            Key2(
                                label = deckLabel(act.label), icon = act.icon, bitmap = m.icons[act.id], accent = accent, p = p, live = false, enabled = canAct,
                                confirming = ui.pending?.id == act.id, secondsLeft = secondsLeft, onClick = { a.press(act.id) },
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// ───────────────────────── conversación ─────────────────────────

/** Lo reciente que habéis dicho, en letra grande: tú a la derecha, Vega a la izquierda. Se cierra sola o al tocar. */
@Composable
internal fun ChatOverlay2(m: ScreenModel, p: Pal, onClose: () -> Unit) {
    val ui = m.ui
    val shown = recentChat(ui.messages, 5)
    LaunchedEffect(shown.size, shown.lastOrNull()?.text?.length) {
        delay(CHAT_OPEN_MS)
        onClose()
    }
    Column(
        Modifier.fillMaxSize().background(p.bg.copy(alpha = 0.97f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose)
            .safeDrawingPadding().padding(horizontal = 30.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        for (msg in shown) {
            val vega = msg.role == Role.ASSISTANT
            val error = msg.role == Role.ERROR
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = if (vega || error) Arrangement.Start else Arrangement.End) {
                Column(
                    Modifier.fillMaxWidth(if (vega) 0.8f else 0.6f).clip(RoundedCornerShape(26.dp))
                        .background(if (error) p.red else if (vega) (if (p.night) p.tile else p.sun) else p.tile2).padding(horizontal = 18.dp, vertical = 11.dp),
                ) {
                    val on = if (error) Color.White else if (vega && !p.night) p.ink else p.ink
                    Text(
                        if (error) "Error" else if (vega) ui.assistant.ifEmpty { "Vega" } else "Tú", maxLines = 1,
                        style = Type2.label(on.copy(alpha = 0.6f)),
                    )
                    Text(
                        msg.text.replace('\n', ' '), maxLines = 4,
                        style = Type2.body(if (vega) 21.sp else 17.sp, on, if (vega) FontWeight.Medium else FontWeight.Normal, if (vega) 25.sp else 21.sp),
                    )
                }
            }
        }
        if (shown.isEmpty()) Text("Todavía no hemos hablado.", style = Type2.num(26.sp, p.ink2))
    }
}

// ───────────────────────── dormida ─────────────────────────

/** De noche siempre, sea la hora que sea: la cara con los ojos cerrados, la hora tenue y lo que despertará a Vega. */
@Composable
internal fun SleepScreen2(m: ScreenModel, modifier: Modifier = Modifier) {
    val p = NightPal
    val message = if (m.sleeping) "Dormida · toca la pantalla para despertarme" else when (m.ui.pcOff) {
        "restart" -> "El PC se está reiniciando · vuelvo enseguida"
        "sleep" -> "El PC está en suspensión · me despierto cuando lo despiertes"
        else -> "El PC está apagado · me despierto cuando lo enciendas"
    }
    Row(modifier.fillMaxSize().background(p.bg).safeDrawingPadding().padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Face2(
            mood = Mood.SLEEP, pulse = 0, pulseAmount = 0f, glance = null, ink = p.sky.copy(alpha = 0.7f), night = true,
            hot = false, low = false, game = false, modifier = Modifier.weight(1f).fillMaxHeight(0.8f),
        )
        Spacer(Modifier.width(28.dp))
        Column(Modifier.width(260.dp)) {
            Text(m.clock, maxLines = 1, style = Type2.num(72.sp, p.ink2.copy(alpha = 0.75f)))
            Text(m.date, maxLines = 1, style = Type2.body(14.sp, p.ink3))
            Spacer(Modifier.height(16.dp))
            Text(message, style = Type2.body(15.sp, p.ink2, FontWeight.Normal, 20.sp))
            nextAlert(m.ui.telemetry?.upcoming.orEmpty(), m.ui.telemetryAt, m.nowMs, m.wallMs, m.localNext)?.let {
                Spacer(Modifier.height(10.dp))
                Text(nextAlertLabel(it, m.wallMs), style = Type2.body(14.sp, p.readable(p.sun), FontWeight.SemiBold))
            }
            if (m.battery >= 0) {
                Spacer(Modifier.height(10.dp))
                val low = m.battery <= 15 && !m.charging
                Text("Teléfono ${m.battery} %" + if (m.charging) " · cargando" else "", style = Type2.body(12.sp, if (low) p.red else p.ink3))
            }
        }
    }
}
