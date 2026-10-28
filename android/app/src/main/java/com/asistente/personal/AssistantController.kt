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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.min

/** Servidor del PC, al que se llega por el cable USB gracias a `adb reverse tcp:8000 tcp:8000`. */
const val SERVER_WS = "ws://127.0.0.1:8000/ws"

private const val PREFS = "asistente"
private const val KEY_TOKEN = "token"
private const val KEY_LISTEN = "escucha_continua"
private const val KEY_FACE_COLOR = "color_cara"
private const val KEY_BRAIN = "cerebro_telefono"
private const val KEY_TOUCH = "vibracion_toque"
private const val KEY_GLASS = "estilo_cristal"
private const val KEY_TERMINAL = "estilo_terminal"
private const val KEY_ORB = "estilo_orbe"
private const val ECHO_GUARD_MS = 500L

/**
 * Une la conexión, el estado de la pantalla y el micrófono. Todo su estado es
 * observable por Compose; los cambios se hacen siempre en el hilo principal.
 *
 * Escucha continua (como Alexa): con el enlace abierto y sin silenciar, el micrófono
 * se manda al PC todo el rato; el PC solo atiende las frases que empiezan por el nombre.
 */
class AssistantController(context: Context) {
    private val appContext = context.applicationContext
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

    /** Vibración al tocar (fuerte, suave o ninguna); se recuerda entre reinicios. */
    var touchFeel by mutableStateOf(
        com.asistente.personal.ui.TouchFeel.from(prefs.getString(KEY_TOUCH, null)).also { com.asistente.personal.ui.TouchFeel.current = it },
    )
        private set

    /** Estilo «Cristal» (prototipo) o «Noche»; se recuerda entre reinicios. */
    var glass by mutableStateOf(prefs.getBoolean(KEY_GLASS, false))
        private set

    /** Estilo «Nostromo» (terminal de fósforo ámbar): excluyente con «Cristal». */
    var terminal by mutableStateOf(prefs.getBoolean(KEY_TERMINAL, false))
        private set

    /** Estilo «Orbe» (una esfera de luz que respira): excluyente con los demás. */
    var orb by mutableStateOf(prefs.getBoolean(KEY_ORB, false))
        private set

    /** Lo que oyó hoy (cifras), para la terminal. */
    fun earsStats(): com.asistente.personal.local.EarsSnapshot = local?.stats?.snapshot() ?: com.asistente.personal.local.EarsSnapshot()

    /** Noche → Cristal → Nostromo → Orbe → Noche. */
    fun nextStyle() = setStyle((styleIndex(glass, terminal, orb) + 1) % STYLE_COUNT)

    private fun setStyle(index: Int) {
        glass = index == 1
        terminal = index == 2
        orb = index == 3
        prefs.edit().putBoolean(KEY_GLASS, glass).putBoolean(KEY_TERMINAL, terminal).putBoolean(KEY_ORB, orb).apply()
    }

    fun toggleGlass() {
        glass = !glass
        if (glass) {
            terminal = false
            orb = false
        }
        prefs.edit().putBoolean(KEY_GLASS, glass).putBoolean(KEY_TERMINAL, terminal).putBoolean(KEY_ORB, orb).apply()
    }

    fun nextTouchFeel() {
        touchFeel = touchFeel.next()
        com.asistente.personal.ui.TouchFeel.current = touchFeel
        prefs.edit().putString(KEY_TOUCH, touchFeel.name).apply()
    }

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

    // --- IA del teléfono: Vega escucha, entiende y habla sin el PC (y el PC suelta la gráfica) ---

    /** El usuario eligió que Vega piense en el teléfono (se recuerda entre aperturas). */
    var brainOnPhone by mutableStateOf(prefs.getBoolean(KEY_BRAIN, false))
        private set
    /** Qué pasa con la IA del teléfono, para la pastilla de la tarjeta de la hora. */
    var brainStatus by mutableStateOf("")
        private set
    private val models = com.asistente.personal.local.ModelStore(
        context.getExternalFilesDir("modelos") ?: java.io.File(context.filesDir, "modelos"),
    )
    private val phoneMemory = com.asistente.personal.local.LocalMemory(java.io.File(context.filesDir, "memoria-telefono.json"))
    @Volatile private var local: com.asistente.personal.local.LocalAssistant? = null

    /** La IA que se está cargando ahora mismo (~400 MB nativos): si se cancela la carga, hay que cerrarla. */
    @Volatile private var loading: com.asistente.personal.local.LocalAssistant? = null
    /** Filtro barato de «Vega» (assets/wake_gate.bin): si falta o no se lee, Whisper mira todo, como antes. */
    private val wakeGate: com.asistente.personal.local.WakeGate? by lazy {
        try {
            appContext.assets.open("wake_gate.bin").use { com.asistente.personal.local.WakeGate(it.readBytes()) }
        } catch (e: Exception) {
            android.util.Log.w("Vega", "Sin filtro de «Vega»: ${e.message}")
            null
        }
    }
    private var localJob: kotlinx.coroutines.Job? = null
    private var tickJob: kotlinx.coroutines.Job? = null

    val brainLabel: String get() = when {
        !brainOnPhone -> "IA: PC"
        brainStatus.isNotEmpty() -> brainStatus
        else -> "IA: teléfono"
    }

    /** Cambia entre pensar en el PC y en el teléfono. */
    fun toggleBrain() {
        if (sleeping) return
        brainOnPhone = !brainOnPhone
        prefs.edit().putBoolean(KEY_BRAIN, brainOnPhone).apply()
        if (brainOnPhone) {
            socket.send(JSONObject().put("type", "wake_mode").put("on", false).toString())
            socket.send(brainMessage())
            startLocal()
        } else {
            stopLocal()
            socket.send(brainMessage())
            if (micOn) socket.send(JSONObject().put("type", "wake_mode").put("on", true).toString())
        }
        syncMic()
    }

    /** Si la IA del teléfono falló (error o sin modelos), la vuelve a cargar. */
    fun retryBrain() {
        if (!brainOnPhone || local != null || localJob?.isActive == true) return
        brainStatus = ""
        startLocal()
    }

    private fun brainMessage(): String =
        JSONObject().put("type", "brain").put("where", if (brainOnPhone) "phone" else "pc").toString()

    /** Baja los modelos del PC si faltan (una vez, ~230 MB) y carga Whisper y Piper. */
    private fun startLocal() {
        if (local != null || localJob?.isActive == true) return
        localJob = scope.launch {
            val error = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                models.download { f -> mainThread.post { brainStatus = "Bajando IA ${(f * 100).toInt()} %" } }
            }
            if (error != null && !models.present()) {
                brainStatus = "IA: sin modelos"
                dispatch(Action.LocalError(error))
                return@launch
            }
            brainStatus = "Cargando IA…"
            val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val created = com.asistente.personal.local.LocalAssistant(
                        name = ui.assistant.ifEmpty { "Vega" },
                        skills = com.asistente.personal.local.Skills(phoneMemory, battery = { battery.takeIf { it >= 0 } }),
                        memory = phoneMemory,
                        ears = com.asistente.personal.local.WhisperEars(models.whisper),
                        mouth = com.asistente.personal.local.PiperMouth(models.piper),
                        events = { event ->
                            mainThread.post {
                                if (event is ServerEvent.Reminder) vibrate()
                                dispatch(Action.Server(event))
                            }
                        },
                        audio = { pcm, rate -> player.enqueue(pcm, rate) },
                        clock = { android.os.SystemClock.elapsedRealtime() },
                        pc = ::askPc,
                        onSleep = { mainThread.post { sleep() } },
                        pcVoice = ::speakWithPc,
                        log = { android.util.Log.i("Vega", it) },
                        // Charla sin PC: el modelo se carga al primer uso (~4 s) y se suelta tras 3 min sin usarlo.
                        talker = models.llm.takeIf { it.isFile }?.let { com.asistente.personal.local.PhoneLlm(it, log = { m -> android.util.Log.i("Vega", m) }) },
                        gate = wakeGate,
                    )
                    loading = created
                    // Cancelada mientras cargaba (cambio de IA, «Dormir»…): withContext tiraría el resultado
                    // y esos modelos se quedarían en memoria sin dueño. Aquí se cierran.
                    if (!isActive) {
                        loading = null
                        created.close()
                        null
                    } else {
                        created
                    }
                } catch (e: Throwable) {
                    android.util.Log.e("Vega", "No arranca la IA del teléfono", e)
                    null
                }
            }
            if (!brainOnPhone) { // se cambió de idea mientras cargaba
                loading = null
                loaded?.close()
                return@launch
            }
            if (loaded == null) {
                brainStatus = "IA: error"
                dispatch(Action.LocalError("No pude arrancar la IA del teléfono."))
                return@launch
            }
            local = loaded
            loading = null
            brainStatus = ""
            tickJob?.cancel()
            tickJob = scope.launch {
                while (true) {
                    local?.tick()
                    localTimerEndsAt = phoneMemory.pending().firstOrNull { it.kind == "timer" }?.let {
                        val left = java.time.Duration.between(java.time.LocalDateTime.now(), it.dueAt).toMillis()
                        // Redondeado al segundo: así no cambia (ni redibuja) en cada vuelta.
                        (android.os.SystemClock.elapsedRealtime() + left) / 1000 * 1000
                    }
                    localNext = phoneMemory.pending().firstOrNull { it.kind != "timer" }?.let {
                        com.asistente.personal.ui.NextAlert(it.text, it.dueAt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())
                    }
                    kotlinx.coroutines.delay(1_000)
                }
            }
            syncMic()
        }
    }

    /** Fin del temporizador del teléfono (reloj `elapsedRealtime`), para la cuenta atrás en la cara. */
    var localTimerEndsAt by mutableStateOf<Long?>(null)
        private set

    /** Próxima alarma o recordatorio del teléfono (hora real), para las pantallas de lejos y de dormida. */
    var localNext by mutableStateOf<com.asistente.personal.ui.NextAlert?>(null)
        private set

    @Volatile private var pcAnswer: java.util.concurrent.CompletableFuture<ServerEvent.PhoneReply>? = null

    /**
     * Pasa al PC una orden que ya entendió el teléfono (sus habilidades y botones, sin modelo).
     * Se llama desde el hilo de la IA local y espera la respuesta como mucho 4 s.
     */
    private fun askPc(text: String): String? {
        if (ui.link != LinkStatus.OPEN) return null
        val answer = java.util.concurrent.CompletableFuture<ServerEvent.PhoneReply>()
        pcAnswer = answer
        try {
            if (!socket.send(Protocol.phoneCommand(text))) return null
            val reply = answer.get(4, java.util.concurrent.TimeUnit.SECONDS)
            return if (reply.handled) reply.text.ifEmpty { "Hecho." } else null
        } catch (_: Exception) {
            return null
        } finally {
            pcAnswer = null
        }
    }

    /** La voz del PC llegó tarde y ya habló el teléfono: se ignora hasta que termine. */
    @Volatile private var dropPcAudio = false

    @Volatile private var pcVoiceStarted: java.util.concurrent.CompletableFuture<Boolean>? = null

    /**
     * Pide al PC que diga el texto con su voz (Kokoro, en su procesador). Espera a que empiece
     * a llegar el audio (como mucho 3 s); si no llega, habla el teléfono con la suya.
     */
    private fun speakWithPc(text: String): Boolean {
        if (ui.link != LinkStatus.OPEN) return false
        val started = java.util.concurrent.CompletableFuture<Boolean>()
        pcVoiceStarted = started
        dropPcAudio = false
        try {
            if (!socket.send(JSONObject().put("type", "say").put("text", text).toString())) return false
            return started.get(3, java.util.concurrent.TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // Habla el teléfono; si la voz del PC llega después, no debe sonar encima.
            dropPcAudio = true
            android.util.Log.i("Vega", "la voz del PC no llegó en 3 s: habla el teléfono")
            return false
        } catch (_: Exception) {
            return false
        } finally {
            pcVoiceStarted = null
        }
    }

    /** Lo que el teléfono guardó sin PC pasa al PC en cuanto hay enlace. */
    private fun handOverMemory() {
        scope.launch(kotlinx.coroutines.Dispatchers.IO) { phoneMemory.exportForPc { socket.send(it) } }
    }

    /**
     * Dormida a mano (botón o «duérmete»): sin micrófono y sin los modelos en memoria (~600 MB
     * menos). Se despierta tocando la pantalla; los modelos tardan unos segundos en volver.
     */
    var sleeping by mutableStateOf(false)
        private set

    fun sleep() {
        if (sleeping) return
        sleeping = true
        stopMic()
        socket.send(Protocol.sleepMode(true))
        stopLocal()
    }

    /** La pantalla de Vega en Android Auto: título y casillas que manda el PC (vacío: las de serie). */
    var carTitle by mutableStateOf("Vega")
        private set
    var carItems by mutableStateOf<List<com.asistente.personal.car.CarItem>>(emptyList())
        private set

    /** Lo que dice el lector OBD-II del coche, si hay uno conectado (null: ninguno). */
    var vehicle by mutableStateOf<com.asistente.personal.car.VehicleReading?>(null)

    private var obd: com.asistente.personal.car.ObdMonitor? = null

    /** El coche está conectado: se lee el lector OBD-II (solo lectura) mientras dure. */
    fun startVehicle() {
        if (obd != null) return
        obd = com.asistente.personal.car.ObdMonitor(appContext) { reading -> mainThread.post { vehicle = reading } }.also { it.start() }
    }

    fun stopVehicle() {
        obd?.stop()
        obd = null
    }

    /**
     * Pulsa un botón del PC que pide confirmación y la acepta: el coche ya preguntó «¿Seguro?».
     * El servidor sigue exigiendo su propia confirmación: se pulsa, se espera a que la pida
     * y se confirma (como los dos toques del teléfono).
     */
    fun pressAndConfirm(id: String) {
        press(id)
        var tries = 0
        val check = object : Runnable {
            override fun run() {
                if (ui.pending?.id == id) press(id) else if (++tries < 15) mainThread.postDelayed(this, 200)
            }
        }
        mainThread.postDelayed(check, 200)
    }

    /** Lo que manda la app del PC (ver `phone_control` en server/ws/protocol.py). */
    private fun obey(command: String) {
        when (command) {
            "sleep" -> sleep()
            "wake" -> wake()
            "brain_phone" -> if (!brainOnPhone) toggleBrain()
            "brain_pc" -> if (brainOnPhone) toggleBrain()
            "glass_on" -> if (!glass) toggleGlass()
            "terminal_on" -> setStyle(2)
            "terminal_off" -> if (terminal) setStyle(0)
            "orb_on" -> setStyle(3)
            "orb_off" -> if (orb) setStyle(0)
            "glass_off" -> if (glass) toggleGlass()
            "mic_on" -> if (!listenEnabled) toggleListening()
            "mic_off" -> if (listenEnabled) toggleListening()
        }
    }

    private var lastPublished = ""

    /** Cada 2 s, si algo cambió (o acaba de conectar), cuenta cómo está el teléfono al PC. */
    private fun publishState() {
        if (ui.link != LinkStatus.OPEN) {
            lastPublished = ""
            return
        }
        val now = Protocol.phoneState(
            brainOnPhone, sleeping, micOn, battery, charging, glass, touchFeel.name, faceColor.name, BuildConfig.VERSION_CODE,
        )
        if (now != lastPublished && socket.send(now)) lastPublished = now
    }

    fun wake() {
        if (!sleeping) return
        sleeping = false
        socket.send(Protocol.sleepMode(false))
        if (brainOnPhone) startLocal()
        syncMic()
    }

    private fun stopLocal() {
        local?.close()
        localTimerEndsAt = null
        localNext = null
        localJob?.cancel()
        loading?.close() // la que estaba a medio cargar
        loading = null
        tickJob?.cancel()
        local = null
        brainStatus = ""
    }

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
                val brain = local
                if (brainOnPhone && brain != null) brain.playbackDone() else socket.send(Protocol.simple("playback_done"))
            }
            mainThread.post { speaking = now }
        },
    )

    private val socket: AssistantSocket = AssistantSocket(
        url = SERVER_WS,
        token = { prefs.getString(KEY_TOKEN, "") ?: "" },
        onEvent = { event ->
            if (event is ServerEvent.Sleep) mainThread.post { sleep() }
            if (event is ServerEvent.CarLayout) mainThread.post {
                carTitle = event.title
                carItems = event.items
            }
            if (event is ServerEvent.PhoneControl) mainThread.post { obey(event.command) }
            if (event is ServerEvent.PhoneReply) pcAnswer?.complete(event)
            if (event is ServerEvent.Audio) pcVoiceStarted?.complete(true)
            if (event is ServerEvent.AudioEnd) {
                pcVoiceStarted?.complete(false) // el PC no tiene voz
                dropPcAudio = false
            }
            if (event is ServerEvent.Notifications) loadNoteIcons(event.items)
            if (event is ServerEvent.Reminder) {
                vibrate()
                // Con la IA en el teléfono, el PC no habla: el aviso lo dice el teléfono.
                if (brainOnPhone) local?.say(event.text)
            }
            if (event is ServerEvent.Actions) loadIcons(event.items)
            if (event is ServerEvent.Media) {
                loadMediaArt(event.media?.art)
                loadLyrics(event.media)
            }
            if (event is ServerEvent.Stats) {
                dispatch(Action.Telemetry(event.telemetry, android.os.SystemClock.elapsedRealtime()))
            } else if (event is ServerEvent.Media) {
                dispatch(Action.Media(event.media, android.os.SystemClock.elapsedRealtime()))
            } else if (event is ServerEvent.Notifications) {
                val before = ui.freshNotes
                dispatch(Action.Server(event))
                if ((ui.freshNotes - before).isNotEmpty()) tick()
            } else {
                dispatch(Action.Server(event))
            }
        },
        onAudio = { pcm -> if (!dropPcAudio) player.enqueue(pcm, audioRate) },
        onAudioRate = { audioRate = it },
        onStatus = { status, retry ->
            dispatch(Action.Link(status, retry))
            syncMic()
            // El PC debe saber dónde piensa Vega cada vez que se conecta (p. ej. tras reiniciarse).
            if (status == LinkStatus.OPEN && brainOnPhone) socket.send(brainMessage())
            if (status == LinkStatus.OPEN) handOverMemory()
            if (status == LinkStatus.OPEN) watchUpdates() else updateJob?.cancel()
        },
    )

    private val mic = Microphone(onDied = { mainThread.post { micDied() } }) { pcm, length, level ->
        // Mientras Vega habla no se manda el micrófono: así no se escucha a sí misma.
        // Medio segundo más: el altavoz aún suena un poco tras el último trozo y se oía a sí misma.
        if (player.speaking || android.os.SystemClock.elapsedRealtime() - voiceEndedAt < ECHO_GUARD_MS) return@Microphone
        val brain = local
        if (brainOnPhone) brain?.hear(pcm, length) else socket.sendAudio(pcm, length)
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

    private val owners = Owners()

    /** Alguien (la pantalla, el servicio, el coche…) necesita a Vega: arranca con el primero. */
    fun start(owner: String = "pantalla") {
        if (owners.add(owner)) begin()
    }

    /** Ese alguien ya no la necesita: para cuando se va el último. */
    fun stop(owner: String = "pantalla") {
        if (owners.remove(owner)) end()
    }

    private fun begin() {
        socket.start()
        if (brainOnPhone) startLocal()
        batteryJob?.cancel()
        batteryJob = scope.launch {
            var round = 0
            while (true) {
                if (round++ % 15 == 0) readBattery() // cada 30 s
                publishState()
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    private fun end() {
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

    /** Iconos de las apps que mandan notificaciones en el PC (por id de icono). */
    val noteIcons = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()
    private val noteIconsAsked = mutableSetOf<String>()

    private fun loadNoteIcons(items: List<com.asistente.personal.net.PcNote>) {
        val missing = items.map { it.icon }.filter { it.isNotEmpty() && noteIconsAsked.add(it) }.distinct()
        if (missing.isEmpty()) return
        scope.launch {
            for (id in missing) updater.noteIcon(id)?.let { noteIcons[id] = it } ?: noteIconsAsked.remove(id)
        }
    }

    /** Quita una notificación (o todas, con null) también del PC. */
    fun dismissNote(id: Long?) {
        socket.send(Protocol.dismissNotification(id))
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

    /** Un toque muy corto: ha llegado una notificación del PC. */
    private fun tick() {
        vibrator?.vibrate(android.os.VibrationEffect.createOneShot(35, 120))
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

    private var micRetries = 0

    /** El micrófono se paró solo: se quita «Oyendo» y se reintenta con espera creciente (2, 4, 8… hasta 30 s). */
    private fun micDied() {
        if (!micOn) return
        android.util.Log.w("Vega", "El micrófono se paró solo; se reintenta")
        stopMic()
        val wait = minOf(30_000L, 2_000L shl minOf(micRetries++, 4))
        mainThread.postDelayed({ syncMic() }, wait)
    }

    /** Abre o cierra el micrófono según el enlace, el permiso y el botón de silencio. */
    private fun syncMic() {
        // Con la IA en el teléfono se escucha aunque el PC no esté.
        val ready = if (brainOnPhone) local != null else ui.link == LinkStatus.OPEN
        val want = !sleeping && listenEnabled && micAllowed && ready
        if (want && !micOn) {
            if (mic.start()) {
                micOn = true
                micRetries = 0
                if (!brainOnPhone) socket.send(JSONObject().put("type", "wake_mode").put("on", true).toString())
            } else {
                if (micRetries == 0) dispatch(Action.LocalError("No pude abrir el micrófono. ¿Lo está usando otra app?"))
                mainThread.postDelayed({ syncMic() }, minOf(30_000L, 2_000L shl minOf(micRetries++, 4))) // y se vuelve a intentar
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
        val brain = local
        if (brainOnPhone) brain?.attention() else socket.send(Protocol.simple("attention"))
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

const val STYLE_COUNT = 4

/** 0 = Noche, 1 = Cristal, 2 = Nostromo, 3 = Orbe. */
fun styleIndex(glass: Boolean, terminal: Boolean, orb: Boolean = false): Int = if (orb) 3 else if (terminal) 2 else if (glass) 1 else 0

fun styleName(index: Int): String = when (index) {
    1 -> "Cristal"
    2 -> "Nostromo"
    3 -> "Orbe"
    else -> "Noche"
}
