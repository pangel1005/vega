package com.asistente.personal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        if (granted) {
            if (!VegaService.running) VegaService.start(this)
            askNotifications()
        }
    }

    /** Android 13+: sin este permiso la notificación de «Vega activa» queda oculta (el servicio funciona igual). */
    private val askNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Android 12+: para ver el lector OBD-II emparejado. Se pregunta una sola vez. */
    private val askBluetooth = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun askBluetoothOnce() {
        val prefs = getSharedPreferences("asistente", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 31 && !prefs.getBoolean("pidio_bluetooth", false) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("pidio_bluetooth", true).apply()
            askBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

        controller = VegaRuntime.controller(applicationContext)
        takeToken(intent)
        setContent { AssistantScreen(controller, ::openAndroid, ::dimScreen) }
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
            // Con el micrófono permitido, Vega sigue escuchando aunque esta pantalla pase a segundo plano.
            if (!VegaService.running) VegaService.start(this)
            askNotifications()
            askBluetoothOnce()
        } else {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onStop() {
        // Sin el servicio, en segundo plano no se mantiene la conexión ni el micrófono.
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
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = fastest.modeId
            preferredRefreshRate = fastest.refreshRate
        }
        // Android 15+: además, cada cuadro de la app pide la categoría «alta». HyperOS deja
        // a las apps que no conoce a 60 Hz si no lo piden así (medido: estaba a 60 con 120 disponibles).
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.requestedFrameRate = View.REQUESTED_FRAME_RATE_CATEGORY_HIGH
        }
    }

    private var sleepDim = false

    /** Con Vega dormida (PC apagado) la pantalla baja casi al mínimo; al despertar, vuelve a la normal. */
    private fun dimScreen(dim: Boolean) {
        sleepDim = dim
        applyBrightness()
    }

    private fun applyBrightness() {
        window.attributes = window.attributes.apply {
            screenBrightness = when {
                sleepDim -> 0.02f
                else -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
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
private fun AssistantScreen(c: AssistantController, openAndroid: () -> Unit, sleepChanged: (Boolean) -> Unit) {
    val ui = c.ui
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val clock by produceState("" to "") {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault())
        val day = SimpleDateFormat("EEEE d MMM", Locale.forLanguageTag("es"))
        while (true) {
            val now = Date()
            value = time.format(now) to day.format(now).removeSuffix(".")
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
            nextFaceColor = c::nextFaceColor,
            sleepChanged = sleepChanged,
            mediaControl = c::mediaControl,
            mediaSeek = c::mediaSeek,
            toggleBrain = c::toggleBrain,
            dismissNote = c::dismissNote,
            nextTouchFeel = c::nextTouchFeel,
            nextWaveStyle = c::nextWaveStyle,
            toggleMusicFlat = c::toggleMusicFlat,
            toggleGlass = c::toggleGlass,
            nextStyle = c::nextStyle,
            retryBrain = c::retryBrain,
            sleep = c::sleep,
            wake = c::wake,
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
            icons = c.icons,
            faceColor = c.faceColor,
            mediaArt = c.mediaArt,
            mediaAccent = c.mediaAccent,
            gameArt = c.gameArt,
            gameAccent = c.gameAccent,
            recentArts = c.recentArts,
            gameSamples = c.gameSamples,
            lyrics = c.lyrics,
            brainLabel = c.brainLabel,
            brainOnPhone = c.brainOnPhone,
            noteIcons = c.noteIcons,
            localTimerEndsAt = c.localTimerEndsAt,
            touchFeel = c.touchFeel,
            glass = c.glass,
            terminal = c.terminal,
            orb = c.orb,
            localNext = c.localNext,
            musicLevel = c::musicLevel,
            musicSignal = c::musicSignal,
            musicSpectrum = c::musicSpectrum,
            waveStyle = c.waveStyle,
            musicFlat = c.musicFlat,
            ears = c::earsStats,
            phoneRam = { com.asistente.personal.ui.phoneMemoryUsed(appContext) },
            phoneTemp = { com.asistente.personal.ui.phoneTemperature(appContext) },
            sleeping = c.sleeping,
        ),
        actions,
    )
}
