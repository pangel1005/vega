package com.asistente.personal

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.asistente.personal.audio.Microphone
import com.asistente.personal.audio.Player
import com.asistente.personal.net.AssistantSocket
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min

/** Servidor del PC, al que se llega por el cable USB gracias a `adb reverse tcp:8000 tcp:8000`. */
const val SERVER_WS = "ws://127.0.0.1:8000/ws"

private const val PREFS = "asistente"
private const val KEY_TOKEN = "token"
private const val KEY_LISTEN = "escucha_continua"
private const val KEY_FACE_COLOR = "color_cara"
private const val ECHO_GUARD_MS = 500L

/**
 * Une la conexión, el estado de la pantalla y el micrófono. Todo su estado es
 * observable por Compose; los cambios se hacen siempre en el hilo principal.
 *
 * Escucha continua (como Alexa): con el enlace abierto y sin silenciar, el micrófono
 * se manda al PC todo el rato; el PC solo atiende las frases que empiezan por el nombre.
 */
class AssistantController(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var ui by mutableStateOf(UiState())
        private set
    /** El usuario quiere escucha continua (se recuerda entre aperturas). */
    var listenEnabled by mutableStateOf(prefs.getBoolean(KEY_LISTEN, true))
        private set
    /** El micrófono está realmente abierto y enviando. */
    var micOn by mutableStateOf(false)
        private set
    /** Pulsos del micrófono para mover la boca mientras Vega te atiende. */
    var micPulse by mutableIntStateOf(0)
        private set
    var micAmount by mutableFloatStateOf(0f)
        private set
    /** Vega está hablando (sonando en el altavoz). */
    var speaking by mutableStateOf(false)
        private set
    /** Pulsos del volumen de la voz para mover la boca al hablar. */
    var voicePulse by mutableIntStateOf(0)
        private set
    var voiceAmount by mutableFloatStateOf(0f)
        private set
    /** Hay una versión más nueva de la app en el PC. */
    var updateAvailable by mutableStateOf(false)
        private set

    /** Color de la cara elegido en «Más» (se recuerda entre reinicios). */
    var faceColor by mutableStateOf(com.asistente.personal.ui.FaceColor.from(prefs.getString(KEY_FACE_COLOR, null)))
        private set

    fun nextFaceColor() {
        faceColor = faceColor.next()
        prefs.edit().putString(KEY_FACE_COLOR, faceColor.name).apply()
    }

    private var micAllowed = false
    private val updater = Updater(context)
    private val scope = kotlinx.coroutines.MainScope()
    private val mainThread = android.os.Handler(android.os.Looper.getMainLooper())

    val hasToken: Boolean get() = !prefs.getString(KEY_TOKEN, null).isNullOrBlank()

    @Volatile private var audioRate = 22_050
    @Volatile private var voiceEndedAt = 0L

    private val player: Player = Player(
        onLevel = { level ->
            mainThread.post {
                voiceAmount = min(1f, level * 3.5f)
                voicePulse++
            }
        },
        onSpeaking = { now ->
            if (!now) {
                // Avisa al PC de que terminó de sonar: así sigue escuchando sin tener que decir «Vega».
                voiceEndedAt = android.os.SystemClock.elapsedRealtime()
                socket.send(Protocol.simple("playback_done"))
            }
            mainThread.post { speaking = now }
        },
    )

    private val socket: AssistantSocket = AssistantSocket(
        url = SERVER_WS,
        token = { prefs.getString(KEY_TOKEN, "") ?: "" },
        onEvent = { event ->
            if (event is ServerEvent.Reminder) vibrate()
            if (event is ServerEvent.Actions) loadIcons(event.items)
            if (event is ServerEvent.Media) {
                loadMediaArt(event.media?.art)
                loadLyrics(event.media)
            }
            if (event is ServerEvent.Stats) {
                dispatch(Action.Telemetry(event.telemetry, android.os.SystemClock.elapsedRealtime()))
            } else if (event is ServerEvent.Media) {
                dispatch(Action.Media(event.media, android.os.SystemClock.elapsedRealtime()))
            } else {
                dispatch(Action.Server(event))
            }
        },
        onAudio = { pcm -> player.enqueue(pcm, audioRate) },
        onAudioRate = { audioRate = it },
        onStatus = { status, retry ->
            dispatch(Action.Link(status, retry))
            syncMic()
            if (status == LinkStatus.OPEN) watchUpdates() else updateJob?.cancel()
        },
    )

    private val mic = Microphone { pcm, length, level ->
        // Mientras Vega habla no se manda el micrófono: así no se escucha a sí misma.
        // Medio segundo más: el altavoz aún suena un poco tras el último trozo y se oía a sí misma.
        if (player.speaking || android.os.SystemClock.elapsedRealtime() - voiceEndedAt < ECHO_GUARD_MS) return@Microphone
        socket.sendAudio(pcm, length)
        // La boca solo sigue tu voz mientras Vega te está atendiendo (no con el ruido de la sala:
        // así no se redibuja todo el rato).
        if (ui.state == AssistantState.LISTENING) {
            mainThread.post {
                micAmount = min(1f, level * 4f)
                micPulse++
            }
        }
    }

    private fun dispatch(action: Action) {
        ui = reduce(ui, action)
    }

    /** Guarda el token que entrega el script de instalación (`--es token …`). */
    fun saveToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token.trim()).apply()
    }

    private var batteryJob: kotlinx.coroutines.Job? = null

    fun start() {
        socket.start()
        batteryJob?.cancel()
        batteryJob = scope.launch {
            while (true) {
                readBattery()
                kotlinx.coroutines.delay(30_000)
            }
        }
    }

    fun stop() {
        batteryJob?.cancel()
        stopMic()
        player.stop()
        socket.stop()
    }

    private var updateJob: kotlinx.coroutines.Job? = null

    /** Carátula de lo que suena en el PC y su color principal (para la pantalla «Sonando»). */
    var mediaArt by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set
    var mediaAccent by mutableStateOf(com.asistente.personal.ui.Vega.Coral)
        private set
    private var mediaArtId: String? = null

    private fun loadMediaArt(id: String?) {
        if (id == mediaArtId) return
        mediaArtId = id
        if (id.isNullOrEmpty()) {
            mediaArt = null
            mediaAccent = com.asistente.personal.ui.Vega.Coral
            return
        }
        scope.launch {
            val art = updater.mediaArt(id)
            if (mediaArtId == id) {
                mediaArt = art
                mediaAccent = com.asistente.personal.ui.accentOf(art)
            }
        }
    }

    /** Letra de la canción que suena (null mientras no hay o no se encontró). */
    var lyrics by mutableStateOf<com.asistente.personal.net.Lyrics?>(null)
        private set
    private var lyricsFor: String? = null

    private fun loadLyrics(media: com.asistente.personal.net.MediaInfo?) {
        val key = media?.let { "${it.artist}|${it.title}" }
        if (key == lyricsFor) return
        lyricsFor = key
        lyrics = null
        if (media == null) return
        scope.launch {
            val found = updater.lyrics(media)
            if (lyricsFor == key) lyrics = found
        }
    }

    /** Pausa/continúa, siguiente o anterior en lo que suena en el PC. */
    fun mediaControl(command: String) {
        socket.send(Protocol.mediaControl(command))
    }

    /** Iconos reales de las apps (Spotify, Discord…), descargados del PC una vez. */
    val icons = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    private fun loadIcons(items: List<com.asistente.personal.net.DeckAction>) {
        val missing = items.filter { it.appIcon && it.id !in icons }.map { it.id }
        if (missing.isEmpty()) return
        scope.launch {
            for (id in missing) {
                updater.appIcon(id)?.let { icons[id] = it }
            }
        }
    }

    /**
     * Mira si el PC tiene una versión nueva al conectar y luego cada minuto (antes solo al
     * conectar: una versión compilada después no aparecía hasta reconectar el cable).
     */
    private fun watchUpdates() {
        updateJob?.cancel()
        updateJob = scope.launch {
            while (true) {
                updateAvailable = updater.newerVersion() != null
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    private val vibrator = context.getSystemService(android.os.Vibrator::class.java)
    private val batteryManager = context.getSystemService(android.os.BatteryManager::class.java)

    /** Batería del propio teléfono (−1 si no se sabe) y si está cargando. */
    var battery by mutableIntStateOf(-1)
        private set
    var charging by mutableStateOf(false)
        private set

    private fun readBattery() {
        val bm = batteryManager ?: return
        battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 } ?: -1
        charging = bm.isCharging
    }

    /** Tres pulsos cortos: se nota aunque no estés mirando el teléfono. */
    private fun vibrate() {
        vibrator?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180, 120, 180), -1))
    }

    /** Pasa a la siguiente voz disponible (el PC la recuerda y se presenta con ella). */
    fun nextVoice() {
        val voices = ui.voices
        if (voices.isEmpty()) return
        val i = voices.indexOfFirst { it.id == ui.voice }
        socket.send(Protocol.setVoice(voices[(i + 1) % voices.size].id))
    }

    /** Reintenta ya (p. ej. tras guardar un token nuevo). */
    fun restart() {
        socket.stop()
        socket.start()
    }

    /** La actividad avisa cuando hay (o no) permiso de micrófono. */
    fun micPermission(granted: Boolean) {
        micAllowed = granted
        syncMic()
        if (!granted && listenEnabled) {
            dispatch(Action.LocalError("Sin permiso para el micrófono. Actívalo en Ajustes → Apps → Asistente."))
        }
    }

    fun toggleListening() {
        listenEnabled = !listenEnabled
        prefs.edit().putBoolean(KEY_LISTEN, listenEnabled).apply()
        syncMic()
    }

    /** Abre o cierra el micrófono según el enlace, el permiso y el botón de silencio. */
    private fun syncMic() {
        val want = listenEnabled && micAllowed && ui.link == LinkStatus.OPEN
        if (want && !micOn) {
            if (mic.start()) {
                micOn = true
                socket.send(JSONObject().put("type", "wake_mode").put("on", true).toString())
            } else {
                dispatch(Action.LocalError("No pude abrir el micrófono. ¿Lo está usando otra app?"))
            }
        } else if (!want && micOn) {
            stopMic()
        }
    }

    private fun stopMic() {
        if (!micOn) return
        mic.stop()
        micOn = false
        socket.send(JSONObject().put("type", "wake_mode").put("on", false).toString())
    }

    /** Tocar el rostro: la próxima frase es una orden aunque no digas «Vega». */
    fun attention() {
        if (!micOn) syncMic()
        socket.send(Protocol.simple("attention"))
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

    /** Solo para pruebas desde el PC en la versión de depuración. */
    fun send(text: String) {
        if (socket.send(Protocol.chat(text))) dispatch(Action.Sent(text))
    }
}
