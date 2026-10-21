package com.asistente.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.asistente.personal.audio.Microphone
import com.asistente.personal.net.AssistantSocket
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.VoiceStatus
import kotlinx.coroutines.launch
import kotlin.math.min

/** Servidor del PC, al que se llega por el cable USB gracias a `adb reverse tcp:8000 tcp:8000`. */
const val SERVER_WS = "ws://127.0.0.1:8000/ws"

private const val PREFS = "asistente"
private const val KEY_TOKEN = "token"

/**
 * Une la conexión, el estado de la pantalla y el micrófono. Todo su estado es
 * observable por Compose; los cambios se hacen siempre en el hilo principal.
 */
class AssistantController(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var ui by mutableStateOf(UiState())
        private set
    var voice by mutableStateOf(VoiceStatus.OFF)
        private set
    /** Pulsos del micrófono para mover la boca mientras se escucha. */
    var micPulse by mutableIntStateOf(0)
        private set
    var micAmount by mutableFloatStateOf(0f)
        private set
    /** Hay una versión más nueva de la app en el PC. */
    var updateAvailable by mutableStateOf(false)
        private set

    private val updater = Updater(context)
    private val scope = kotlinx.coroutines.MainScope()

    val hasToken: Boolean get() = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()

    private val socket = AssistantSocket(
        url = SERVER_WS,
        token = { prefs.getString(KEY_TOKEN, "") ?: "" },
        onEvent = { dispatch(Action.Server(it)) },
        onStatus = { status, retry ->
            dispatch(Action.Link(status, retry))
            if (status != LinkStatus.OPEN) cancelVoice()
            if (status == LinkStatus.OPEN) scope.launch { updateAvailable = updater.newerVersion() != null }
        },
    )

    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())

    private val mic = Microphone { pcm, length, level ->
        socket.sendAudio(pcm, length)
        mainThread.post {
            micAmount = min(1f, level * 4f)
            micPulse++
        }
    }

    private fun dispatch(action: Action) {
        ui = reduce(ui, action)
    }

    /** Guarda el token que entrega el script de instalación (`--es token …`). */
    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    fun start() = socket.start()

    fun stop() {
        cancelVoice()
        socket.stop()
    }

    /** Reintenta ya (p. ej. tras guardar un token nuevo). */
    fun restart() {
        socket.stop()
        socket.start()
    }

    fun send(text: String) {
        if (socket.send(Protocol.chat(text))) dispatch(Action.Sent(text))
    }

    fun reset() {
        socket.send(Protocol.simple("reset"))
    }

    /**
     * Pulsa un botón del panel. Si ese botón está esperando confirmación, esta
     * pulsación es la confirmación (el servidor exige el código de un solo uso).
     */
    fun press(id: String) {
        val pending = ui.pending
        if (pending != null && pending.id == id) socket.send(Protocol.confirm(pending.nonce))
        else socket.send(Protocol.action(id))
    }

    fun update() {
        scope.launch { updater.downloadAndInstall()?.let { dispatch(Action.LocalError(it)) } }
    }

    fun confirmExpired(nonce: String) = dispatch(Action.ConfirmExpired(nonce))

    fun noticeExpired(seq: Int) = dispatch(Action.NoticeExpired(seq))

    /** Requiere el permiso de micrófono ya concedido. */
    fun startVoice() {
        if (voice != VoiceStatus.OFF || !socket.send(Protocol.simple("listen_start"))) return
        voice = VoiceStatus.STARTING
        if (mic.start()) {
            voice = VoiceStatus.RECORDING
        } else {
            socket.send(Protocol.simple("listen_cancel"))
            voice = VoiceStatus.OFF
            dispatch(Action.LocalError("No pude abrir el micrófono. ¿Lo está usando otra app?"))
        }
    }

    fun stopVoice() = finishVoice("listen_stop")

    fun cancelVoice() = finishVoice("listen_cancel")

    private fun finishVoice(kind: String) {
        if (voice == VoiceStatus.OFF) return
        mic.stop()
        socket.send(Protocol.simple(kind))
        voice = VoiceStatus.OFF
    }

    fun micDenied() = dispatch(Action.LocalError("Sin permiso para el micrófono. Actívalo en Ajustes → Apps → Asistente."))
}
