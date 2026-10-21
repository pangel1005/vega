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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.ui.ChatPanel
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

        controller = AssistantController(applicationContext)
        takeToken(intent)
        setContent { AssistantScreen(controller, ::startVoice) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (takeToken(intent)) controller.restart()
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

    private fun takeToken(intent: Intent?): Boolean {
        val token = intent?.getStringExtra("token")?.takeIf { it.isNotBlank() } ?: return false
        controller.saveToken(token)
        return true
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
private fun AssistantScreen(c: AssistantController, startVoice: () -> Unit) {
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
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            StatusBar(ui.assistant, ui.model, ui.link, ui.retryInMs, clock)
            // Horizontal: rostro y botón a la izquierda, conversación a la derecha.
            Row(
                Modifier.fillMaxSize().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    Modifier.fillMaxHeight().weight(0.42f).hudFrame(accent.copy(alpha = 0.24f)).padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    Face(
                        state = state,
                        pulse = if (listening) c.micPulse else ui.tokenPulse,
                        pulseAmount = if (listening) c.micAmount else 0.55f,
                        modifier = Modifier.weight(1f, fill = false).widthIn(max = 320.dp),
                    )
                    StateLabel(state)
                    MicButton(
                        status = c.voice,
                        enabled = canSend && !busy,
                        accent = accent,
                        onStart = startVoice,
                        onStop = c::stopVoice,
                        onCancel = c::cancelVoice,
                        modifier = Modifier.widthIn(max = 320.dp).padding(top = 8.dp),
                    )
                    if (ui.link == LinkStatus.DENIED || !c.hasToken) {
                        Text(
                            "SIN TOKEN VÁLIDO: INSTALA CON scripts/android-install.ps1",
                            style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, color = Nerv.Red),
                        )
                    }
                }
                ChatPanel(
                    assistant = ui.assistant,
                    messages = ui.messages,
                    canSend = canSend,
                    busy = busy,
                    accent = accent,
                    onSend = c::send,
                    onReset = c::reset,
                    modifier = Modifier.fillMaxHeight().weight(0.58f),
                )
            }
        }
        Box(Modifier.fillMaxSize().scanlines())
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
        Text(es, style = TextStyle(fontFamily = Nerv.Mono, fontSize = 11.sp, letterSpacing = 3.sp, color = Nerv.Mute))
    }
}
