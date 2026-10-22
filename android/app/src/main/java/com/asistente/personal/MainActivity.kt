package com.asistente.personal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
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
import com.asistente.personal.ui.Honeycomb
import com.asistente.personal.ui.Nerv
import com.asistente.personal.ui.NoticeBar
import com.asistente.personal.ui.StatusBar
import com.asistente.personal.ui.Text
import com.asistente.personal.ui.accentFor
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var controller: AssistantController
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        controller.micPermission(granted)
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
        // El fondo lo pinta la app (imagen opaca): sin el fondo de la ventana, una pasada menos por cuadro.
        window.setBackgroundDrawable(null)
        preferFastestRefreshRate()

        controller = AssistantController(applicationContext)
        takeToken(intent)
        setContent { AssistantScreen(controller, ::openAndroid) }
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
        // Escucha continua: hace falta el permiso de micrófono (se pide una sola vez).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            controller.micPermission(true)
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
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
}

/**
 * Pantalla principal (horizontal): el rostro es el protagonista, con una línea de
 * subtítulo debajo; a la derecha, el panal con los 6 favoritos y «MÁS».
 */
@Composable
private fun AssistantScreen(c: AssistantController, openAndroid: () -> Unit) {
    val ui = c.ui
    // Mientras suena su voz, el rostro está «hablando» aunque el servidor ya terminara de enviarla.
    val state = when {
        ui.link == LinkStatus.DENIED -> AssistantState.ERROR
        c.speaking -> AssistantState.SPEAKING
        else -> ui.state
    }
    val canAct = ui.link == LinkStatus.OPEN
    var showMore by remember { mutableStateOf(false) }
    BackHandler(enabled = showMore) { showMore = false }

    val clock by produceState("") {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            value = fmt.format(Date())
            delay(15_000)
        }
    }

    // Cuenta atrás de la confirmación (compartida por el panal y la hoja «MÁS»).
    var secondsLeft by remember { mutableIntStateOf(0) }
    LaunchedEffect(ui.pending?.nonce) {
        val p = ui.pending ?: return@LaunchedEffect
        for (s in p.expiresIn downTo 1) {
            secondsLeft = s
            delay(1000)
        }
        c.confirmExpired(p.nonce)
    }
    LaunchedEffect(ui.notice?.seq) {
        val n = ui.notice ?: return@LaunchedEffect
        delay(if (n.ok) 3500 else 6000)
        c.noticeExpired(n.seq)
    }
    // Al pulsar un botón desde «MÁS», la hoja se cierra cuando llega el resultado.
    LaunchedEffect(ui.notice?.seq) { if (ui.notice != null && ui.pending == null) showMore = false }

    Box(Modifier.fillMaxSize()) {
        // Color final del estado (no el animado): la imagen de fondo solo se repinta una vez por cambio.
        HexBackground(accentFor(state), fast = state == AssistantState.THINKING)
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            StatusBar(
                assistant = ui.assistant,
                link = ui.link,
                retryInMs = ui.retryInMs,
                clock = clock,
                listening = c.listenEnabled,
                micOn = c.micOn,
                onToggleMic = c::toggleListening,
                onAndroid = openAndroid,
                update = if (c.updateAvailable) c::update else null,
            )
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Rostro + subtítulo. Tocar el rostro = «te escucho» (no hace falta decir el nombre).
                Column(
                    Modifier.weight(0.55f).fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // La boca sigue: la voz de Vega al hablar, tu voz al escucharte, o las palabras al escribir.
                    Face(
                        state = state,
                        pulse = when {
                            c.speaking -> c.voicePulse
                            state == AssistantState.LISTENING -> c.micPulse
                            else -> ui.tokenPulse
                        },
                        pulseAmount = when {
                            c.speaking -> c.voiceAmount
                            state == AssistantState.LISTENING -> c.micAmount
                            else -> 0.55f
                        },
                        modifier = Modifier.weight(1f, fill = false)
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                c.attention()
                            },
                    )
                    StateLine(state, c.listenEnabled && c.micOn, ui.assistant)
                    Subtitle(ui.messages, ui.assistant)
                }
                Honeycomb(
                    favorites = ui.actions.filter { it.quick },
                    pending = ui.pending,
                    secondsLeft = secondsLeft,
                    enabled = canAct,
                    onPress = c::press,
                    onMore = { showMore = true },
                    modifier = Modifier.weight(0.45f).fillMaxHeight(),
                )
            }
        }
        AnimatedVisibility(
            visible = showMore,
            enter = fadeIn() + slideInHorizontally { it / 3 },
            exit = fadeOut() + slideOutHorizontally { it / 3 },
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.62f),
        ) {
            Deck(
                actions = ui.actions,
                pending = ui.pending,
                secondsLeft = secondsLeft,
                enabled = canAct,
                onPress = c::press,
                onClose = { showMore = false },
                voiceLabel = ui.voices.firstOrNull { it.id == ui.voice }?.label,
                onNextVoice = c::nextVoice,
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
        }
        NoticeBar(ui.notice, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).widthIn(max = 520.dp))
        if (ui.link == LinkStatus.DENIED || !c.hasToken) {
            Text(
                "SIN TOKEN VÁLIDO: ÁBRELA DESDE EL PC CON scripts/android-install.ps1",
                style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, color = Nerv.Red),
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
            )
        }
    }
}

/** Estado en una línea: «待機 · DI «VEGA»» cuando escucha en segundo plano. */
@Composable
private fun StateLine(state: AssistantState, listening: Boolean, assistant: String) {
    val (jp, es) = when (state) {
        AssistantState.IDLE -> "待機" to if (listening) "DI «${assistant.uppercase().ifEmpty { "SU NOMBRE" }}»" else "EN ESPERA"
        AssistantState.LISTENING -> "受信" to "TE ESCUCHO"
        AssistantState.THINKING -> "審議中" to "PENSANDO"
        AssistantState.SPEAKING -> "発声" to "RESPONDIENDO"
        AssistantState.ACTING -> "実行" to "EJECUTANDO"
        AssistantState.ERROR -> "非常事態" to "ERROR"
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
        Text(jp, style = TextStyle(fontFamily = Nerv.Jp, fontSize = 22.sp, color = accentFor(state)))
        Text(es, maxLines = 1, style = TextStyle(fontFamily = Nerv.Mono, fontSize = 11.sp, letterSpacing = 3.sp, color = Nerv.Mute))
    }
}

/** Una sola línea de conversación: lo último que dijo Vega (o lo que le dijiste, mientras piensa). */
@Composable
private fun Subtitle(messages: List<Message>, assistant: String) {
    val last = messages.lastOrNull() ?: return
    val (who, color) = when (last.role) {
        Role.USER -> "TÚ" to Nerv.Mute
        Role.ASSISTANT -> assistant.uppercase().ifEmpty { "ASISTENTE" } to Nerv.Orange
        Role.ERROR -> "ERROR" to Nerv.Red
        Role.NOTE -> "AVISO" to Nerv.Amber
    }
    Column(Modifier.fillMaxWidth().padding(top = 6.dp, start = 12.dp, end = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(who, style = TextStyle(fontFamily = Nerv.Mono, fontSize = 10.sp, letterSpacing = 3.sp, color = color))
        Text(
            last.text + if (last.streaming) " ▌" else "",
            maxLines = 2,
            style = TextStyle(
                fontFamily = Nerv.Cond, fontSize = 19.sp, lineHeight = 22.sp, textAlign = TextAlign.Center,
                color = if (last.role == Role.USER) Nerv.Mute else Nerv.Ink,
            ),
        )
    }
}
