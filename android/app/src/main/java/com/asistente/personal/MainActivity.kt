package com.asistente.personal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.ui.Deck
import com.asistente.personal.ui.Face
import com.asistente.personal.ui.HexBackground
import com.asistente.personal.ui.MicButton
import com.asistente.personal.ui.Nerv
import com.asistente.personal.ui.StatusBar
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.VoiceStatus
import com.asistente.personal.ui.accentFor
import com.asistente.personal.ui.hudFrame
import com.asistente.personal.ui.scanlines
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var controller: AssistantController
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) controller.micDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // Pantalla completa: sin barras del sistema (aparecen deslizando desde el borde).
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Es el «cuerpo» del asistente conectado al PC: la pantalla no se apaga mientras está abierta.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferFastestRefreshRate()

        controller = AssistantController(applicationContext)
        takeToken(intent)
        setContent { AssistantScreen(controller, ::startVoice, ::openAndroid) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (takeToken(intent)) controller.restart()
        // Solo en la versión de depuración: permite probar desde el PC con
        // `adb shell am start … --es test_message "texto"` (Xiaomi sin SIM no deja simular toques).
        if (BuildConfig.DEBUG) intent.getStringExtra("test_message")?.let(controller::send)
    }

    override fun onStart() {
        super.onStart()
        controller.start()
    }

    override fun onStop() {
        // En segundo plano no se mantiene la conexión ni el micrófono.
        controller.stop()
        super.onStop()
    }

    /**
     * Pide el modo de pantalla más rápido (120 Hz en el Redmi 15C) para que las animaciones
     * vayan más suaves. El teléfono está enchufado al PC, así que el gasto extra no importa.
     */
    private fun preferFastestRefreshRate() {
        val display = display ?: return
        val current = display.mode
        val fastest = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = fastest.modeId }
    }

    private fun takeToken(intent: Intent?): Boolean {
        val token = intent?.getStringExtra("token")?.takeIf { it.isNotBlank() } ?: return false
        controller.saveToken(token)
        return true
    }

    /** Abre la pantalla de inicio normal del teléfono (HyperOS), aunque el asistente sea el inicio. */
    private fun openAndroid() {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val other = packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo }
            .filter { it.packageName != packageName && it.packageName != "com.android.settings" }
            .let { list -> list.firstOrNull { it.packageName == "com.miui.home" } ?: list.firstOrNull() }
        if (other != null) {
            startActivity(Intent(home).setClassName(other.packageName, other.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun startVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.startVoice()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
private fun AssistantScreen(c: AssistantController, startVoice: () -> Unit, openAndroid: () -> Unit) {
    val ui = c.ui
    val listening = c.voice != VoiceStatus.OFF
    val state = when {
        ui.link == LinkStatus.DENIED -> AssistantState.ERROR
        listening -> AssistantState.LISTENING
        else -> ui.state
    }
    val accent by animateColorAsState(accentFor(state), tween(400), label = "acento")
    val canSend = ui.link == LinkStatus.OPEN
    val busy = ui.state == AssistantState.THINKING || ui.state == AssistantState.SPEAKING
    val clock by produceState("") {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            value = fmt.format(Date())
            delay(15_000)
        }
    }

    Box(Modifier.fillMaxSize().background(Nerv.Bg)) {
        HexBackground(accent, fast = state == AssistantState.THINKING)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            StatusBar(
                ui.assistant, ui.model, ui.link, ui.retryInMs, clock, openAndroid,
                update = if (c.updateAvailable) c::update else null,
            )
            // Horizontal: voz a la izquierda (rostro, subtítulos, hablar), panel de botones a la derecha.
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.fillMaxHeight().weight(0.44f).hudFrame(accent.copy(alpha = 0.24f)).padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Face(
                            state = state,
                            pulse = if (listening) c.micPulse else ui.tokenPulse,
                            pulseAmount = if (listening) c.micAmount else 0.55f,
                            modifier = Modifier.fillMaxHeight(),
                        )
                        Column(Modifier.padding(start = 10.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            StateLabel(state)
                            Subtitles(ui.messages, ui.assistant, accent)
                        }
                    }
                    MicButton(
                        status = c.voice,
                        enabled = canSend && !busy,
                        accent = accent,
                        onStart = startVoice,
                        onStop = c::stopVoice,
                        onCancel = c::cancelVoice,
                    )
                    if (ui.link == LinkStatus.DENIED || !c.hasToken) {
                        Text(
                            "SIN TOKEN VÁLIDO: ÁBRELA DESDE EL PC CON scripts/android-install.ps1",
                            style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, color = Nerv.Red),
                        )
                    }
                }
                Deck(
                    actions = ui.actions,
                    pending = ui.pending,
                    notice = ui.notice,
                    enabled = canSend,
                    onPress = c::press,
                    onConfirmExpired = c::confirmExpired,
                    onNoticeExpired = c::noticeExpired,
                    modifier = Modifier.fillMaxHeight().weight(0.56f),
                )
            }
        }
        Box(Modifier.fillMaxSize().scanlines())
    }
}

/** Lo último que dijiste y lo último que respondió el asistente, como subtítulos. */
@Composable
private fun Subtitles(messages: List<Message>, assistant: String, accent: Color) {
    val lastUser = messages.lastOrNull { it.role == Role.USER }
    val lastReply = messages.lastOrNull { it.role == Role.ASSISTANT || it.role == Role.ERROR || it.role == Role.NOTE }
    val small = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, letterSpacing = 2.sp, color = Nerv.Mute)
    if (lastUser == null && lastReply == null) {
        Text("PULSA 通話 Y HABLA", style = small)
        return
    }
    lastUser?.let {
        Text("TÚ" + (it.meta?.let { m -> " · $m" } ?: ""), style = small)
        Text(it.text, maxLines = 2, style = TextStyle(fontFamily = Nerv.Cond, fontSize = 16.sp, color = Nerv.Mute))
    }
    lastReply?.let {
        val who = if (it.role == Role.ASSISTANT) assistant.uppercase().ifEmpty { "ASISTENTE" } else "AVISO"
        Text(who, style = small.copy(color = if (it.role == Role.ERROR) Nerv.Red else accent))
        Text(
            it.text + if (it.streaming) " ▌" else "",
            maxLines = 4,
            style = TextStyle(fontFamily = Nerv.Cond, fontSize = 18.sp, lineHeight = 21.sp, color = Nerv.Ink),
        )
    }
}

@Composable
private fun StateLabel(state: AssistantState) {
    val (jp, es) = when (state) {
        AssistantState.IDLE -> "待機" to "EN ESPERA"
        AssistantState.LISTENING -> "受信" to "ESCUCHANDO"
        AssistantState.THINKING -> "審議中" to "PENSANDO"
        AssistantState.SPEAKING -> "発声" to "RESPONDIENDO"
        AssistantState.ACTING -> "実行" to "EJECUTANDO"
        AssistantState.ERROR -> "非常事態" to "ERROR"
    }
    val color = accentFor(state)
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 22.sp, color = color))
        Text(es, maxLines = 1, style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, letterSpacing = 2.sp, color = Nerv.Mute))
    }
}
