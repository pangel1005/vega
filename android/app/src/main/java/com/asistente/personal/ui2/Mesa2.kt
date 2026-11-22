package com.asistente.personal.ui2

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.asistente.personal.net.DeskApp
import com.asistente.personal.net.DeskInfo
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.instantPress
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ───────────────────────── la «Mesa» ─────────────────────────

/**
 * La «Mesa» del PC al alcance del dedo: volumen de cada programa, fondo de pantalla (el de ahora, tres para elegir, «otros», «al azar» y pausa) y la cámara.
 * Es lo mismo que «Vega manda en tu PC» por voz, pero tocando. Se actualiza sola cada dos segundos mientras está abierta.
 */
@Composable
internal fun MesaSheet2(m: ScreenModel, a: ScreenActions, p: Pal, onClose: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) {
        while (true) {
            a.deskRefresh()
            delay(2000)
        }
    }
    val ui = m.ui
    val linkOpen = ui.link == LinkStatus.OPEN
    Column(modifier.clip(RoundedCornerShape(34.dp)).background(p.bg).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Mesa", maxLines = 1, style = Type2.body(20.sp, p.ink, FontWeight.Bold), modifier = Modifier.weight(1f))
            RoundBtn("cancel", 34.dp, p.tile2, p.ink, onClose, Modifier.testTag("mesa-close"))
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            VolumeCard(ui.desk, linkOpen, p, a, Modifier.weight(1.05f).fillMaxHeight())
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WallpaperCard(ui.desk, linkOpen, m, p, a, Modifier.weight(1f).fillMaxWidth())
                if (ui.camera.available) CameraCard(m, p, a, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Empty(text: String, p: Pal, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
        Text(text, maxLines = 3, style = Type2.body(13.sp, p.ink2).copy(textAlign = TextAlign.Center))
    }
}

@Composable
private fun VolumeCard(desk: DeskInfo?, linkOpen: Boolean, p: Pal, a: ScreenActions, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(p.tile).padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("VOLUMEN", maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.padding(start = 2.dp))
        when {
            desk == null -> Empty(if (linkOpen) "Leyendo el PC…" else "Sin conexión con el PC.", p)
            desk.apps.isEmpty() -> Empty("Nada suena en el PC ahora mismo. Pon algo y aparecerá aquí.", p)
            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                desk.apps.forEach { AppRow(it, p, a) }
            }
        }
    }
}

@Composable
private fun AppRow(app: DeskApp, p: Pal, a: ScreenActions) {
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            app.label, maxLines = 1, modifier = Modifier.width(84.dp).padding(start = 4.dp),
            style = Type2.body(13.sp, if (app.muted) p.ink3 else p.ink, FontWeight.SemiBold),
        )
        VolumeBar2(
            level = app.volume, muted = app.muted, p = p, description = "Volumen de ${app.label}",
            onChange = { level, final -> a.deskVolume(app.key, level, final) },
            modifier = Modifier.weight(1f).testTag("vol-${app.key}"),
        )
        Text(
            "${(app.volume * 100).roundToInt()}", maxLines = 1, modifier = Modifier.width(34.dp),
            style = Type2.num(14.sp, if (app.muted) p.ink3 else p.ink).copy(textAlign = TextAlign.End),
        )
        RoundBtn(
            if (app.muted) "mute" else "volume-up", 32.dp, if (app.muted) p.red else p.tile2, if (app.muted) Color.White else p.ink,
            { a.deskMute(app.key, !app.muted) }, Modifier.testTag("mute-${app.key}"),
        )
    }
}

/**
 * Barra de volumen que se mueve con el dedo: tocar en un punto salta ahí y arrastrar la sigue. La zona que responde mide 48 dp de alto
 * aunque la barra se vea fina. Avisa en cada movimiento (`final` falso) y una vez más al soltar (`final` verdadero).
 */
@Composable
internal fun VolumeBar2(level: Float, muted: Boolean, p: Pal, description: String, onChange: (Float, Boolean) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    var width by remember { mutableIntStateOf(1) }
    val shown = (drag ?: level).coerceIn(0f, 1f)
    val fill = if (muted) p.ink3 else p.sky
    val knob = 22.dp
    val knobPx = with(LocalDensity.current) { knob.toPx() }
    // El pomo recorre el ancho menos su propio tamaño; el relleno llega hasta su centro.
    val travel = maxOf(1f, width - knobPx)
    val fillPx = travel * shown + knobPx / 2
    Box(
        modifier.height(48.dp).onSizeChanged { width = maxOf(1, it.width) }
            .semantics { contentDescription = "$description: ${(shown * 100).roundToInt()} por ciento" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun at(x: Float) = (((x - knobPx / 2) / travel).coerceIn(0f, 1f) * 100).roundToInt() / 100f
                    var f = at(down.position.x)
                    drag = f
                    onChange(f, false)
                    down.consume()
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull() ?: break
                        change.consume()
                        if (!change.pressed) break
                        f = at(change.position.x)
                        drag = f
                        onChange(f, false)
                    }
                    onChange(f, true)
                    drag = null
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(p.tile2))
        Box(Modifier.width(with(LocalDensity.current) { fillPx.toDp() }).height(10.dp).clip(CircleShape).background(fill))
        Box(
            Modifier.offset { IntOffset((travel * shown).roundToInt(), 0) }
                .size(knob).background(p.tile, CircleShape).padding(3.dp).background(fill, CircleShape),
        )
    }
}

@Composable
private fun Pill2(text: String, p: Pal, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val pressed = remember { mutableStateOf(false) }
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (selected) p.ink else if (pressed.value) p.tile else p.tile2)
            .instantPress(enabled = enabled, pressed = pressed, onPress = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, maxLines = 1, style = Type2.body(13.sp, if (selected) p.tile else p.ink, FontWeight.SemiBold))
    }
}

@Composable
private fun WallpaperCard(desk: DeskInfo?, linkOpen: Boolean, m: ScreenModel, p: Pal, a: ScreenActions, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(24.dp)).background(p.tile).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("FONDO DE PANTALLA", maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.padding(start = 2.dp))
        when {
            desk == null -> Empty(if (linkOpen) "Leyendo el PC…" else "Sin conexión con el PC.", p)
            !desk.wallpaperAvailable -> Empty("Wallpaper Engine no está en el PC.", p)
            else -> {
                Text(
                    desk.wallpaper.ifEmpty { "Toca uno para ponerlo" }, maxLines = 1, modifier = Modifier.padding(start = 2.dp).testTag("wall-current"),
                    style = Type2.body(13.sp, if (desk.wallpaper.isEmpty()) p.ink2 else p.ink, FontWeight.SemiBold),
                )
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val shown = desk.suggestions.take(3)
                    shown.forEach { w ->
                        val pressed = remember { mutableStateOf(false) }
                        Column(Modifier.weight(1f).testTag("wall-${w.id}").instantPress(enabled = true, pressed = pressed, onPress = { a.deskWallpaper(w.id) }), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(p.tile2), contentAlignment = Alignment.Center) {
                                val thumb = m.wallpaperThumbs[w.id]
                                if (thumb != null) Image(thumb, w.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                else Icon2("sliders", p.ink3, 18.dp)
                                if (pressed.value) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                            }
                            Text(w.title, maxLines = 1, style = Type2.body(12.sp, p.ink2))
                        }
                    }
                    repeat(3 - shown.size) { Spacer(Modifier.weight(1f)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill2("Otros", p, Modifier.weight(1f).testTag("wall-more")) { a.deskMore() }
                    Pill2("Al azar", p, Modifier.weight(1f).testTag("wall-random")) { a.deskRandom() }
                    Pill2(if (desk.paused) "Seguir" else "Pausa", p, Modifier.weight(1f).testTag("wall-pause"), selected = desk.paused) { a.deskPause(!desk.paused) }
                }
            }
        }
    }
}

/** La cámara del teléfono como cámara de Windows: encender o apagar y cuál (la misma que en «Más» y en la cabecera). */
@Composable
private fun CameraCard(m: ScreenModel, p: Pal, a: ScreenActions, modifier: Modifier) {
    val cam = m.ui.camera
    Row(
        modifier.height(56.dp).clip(RoundedCornerShape(24.dp)).background(p.tile).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("CÁMARA", maxLines = 1, style = Type2.label(p.ink3), modifier = Modifier.padding(start = 2.dp))
        Pill2(
            when {
                cam.busy -> "…"
                cam.on -> "Apagar"
                else -> "Encender"
            },
            p, Modifier.width(92.dp).testTag("mesa-camera"), selected = cam.on, enabled = !cam.busy,
        ) { a.toggleCamera() }
        Segmented(listOf("Frontal", "Trasera"), if (cam.facing == "back") 1 else 0, p, { a.cameraFacing(if (it == 1) "back" else "front") }, Modifier.weight(1f))
    }
}
