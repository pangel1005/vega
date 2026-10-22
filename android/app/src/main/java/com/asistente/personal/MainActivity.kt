package com.asistente.personal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.ui.ScreenActions
import com.asistente.personal.ui.ScreenModel
import com.asistente.personal.ui.VegaScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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

/** Une el controlador (estado vivo) con la pantalla, que no sabe nada de él. */
@Composable
private fun AssistantScreen(c: AssistantController, openAndroid: () -> Unit) {
    val ui = c.ui
    val clock by produceState("" to "") {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val day = SimpleDateFormat("EEE dd.MM.yyyy", Locale.forLanguageTag("es"))
        while (true) {
            val now = Date()
            value = time.format(now) to day.format(now).uppercase()
            delay(15_000)
        }
    }
    // Mientras suena su voz, el rostro está «hablando» aunque el servidor ya terminara de enviarla.
    val state = when {
        ui.link == LinkStatus.DENIED -> AssistantState.ERROR
        c.speaking -> AssistantState.SPEAKING
        else -> ui.state
    }
    val actions = remember(c) {
        ScreenActions(
            toggleMic = c::toggleListening,
            openAndroid = openAndroid,
            update = c::update,
            attention = c::attention,
            press = c::press,
            nextVoice = c::nextVoice,
            confirmExpired = c::confirmExpired,
            noticeExpired = c::noticeExpired,
        )
    }
    VegaScreen(
        ScreenModel(
            ui = ui,
            clock = clock.first,
            date = clock.second,
            state = state,
            listening = c.listenEnabled,
            micOn = c.micOn,
            battery = c.battery,
            charging = c.charging,
            updateAvailable = c.updateAvailable,
            hasToken = c.hasToken,
            // La boca sigue: la voz de Vega al hablar, tu voz al escucharte, o las palabras al escribir.
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
            nowMs = SystemClock.elapsedRealtime(),
            appVersion = BuildConfig.VERSION_CODE,
        ),
        actions,
    )
}
