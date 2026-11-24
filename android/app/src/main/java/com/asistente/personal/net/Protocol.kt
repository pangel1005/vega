package com.asistente.personal.net

import org.json.JSONObject

/** Mensajes del WebSocket `/ws`. Deben coincidir con server/ws/protocol.py. */

enum class AssistantState(val wire: String) {
    IDLE("idle"), LISTENING("listening"), THINKING("thinking"), SPEAKING("speaking"), ACTING("acting"), ERROR("error");

    companion object {
        fun fromWire(s: String): AssistantState? = entries.firstOrNull { it.wire == s }
    }
}

data class HistoryItem(val role: String, val content: String)

/** Botón del panel tal como lo describe el servidor (config/actions.toml). */
data class DeckAction(
    val id: String,
    val label: String,
    val jp: String,
    val icon: String,
    val color: String,
    val confirm: Boolean,
    /** Aparece en la fila principal (los demás, en «Más»). */
    val quick: Boolean = false,
    /** Es una app con icono propio en /app/icons/<id>.png. */
    val appIcon: Boolean = false,
    /** Botones de energía: shutdown, restart, sleep, lock o cancel (vacío en los demás). */
    val power: String = "",
)

data class Voice(val id: String, val label: String)

/** Un programa del PC con sonido en la «Mesa»: `key` es lo que se manda de vuelta; `volume` va de 0 a 1. */
data class DeskApp(val key: String, val label: String, val volume: Float, val muted: Boolean)

/** La racha del gimnasio: días seguidos, si hoy ya ardió, cuánto puedes descansar y lo viva que está la llama (`heat`, 0–1). */
data class StreakInfo(
    val count: Int = 0,
    val best: Int = 0,
    val total: Int = 0,
    val today: Boolean = false,
    val rest: Int = 0,
    val restLeft: Int = 0,
    val alive: Boolean = false,
    val heat: Float = 0f,
    val week: Int = 0,
    val days: List<String> = emptyList(),
)

/** Una entrega de Moodle: `uid` es lo que se manda para marcarla hecha; `due` son segundos desde 1970. */
data class TaskItem(val uid: String, val title: String, val course: String, val due: Long)

/** Un fondo de Wallpaper Engine que se ofrece en la «Mesa» (la miniatura se pide aparte por su `id`). */
data class DeskChoice(val id: String, val title: String)

/** Lo que enseña la «Mesa»: volumen por programa y fondo de pantalla del PC. */
data class DeskInfo(
    val apps: List<DeskApp> = emptyList(),
    val wallpaperAvailable: Boolean = false,
    val wallpaper: String = "",
    val paused: Boolean = false,
    val suggestions: List<DeskChoice> = emptyList(),
)

/** Lo que suena en el PC (Spotify, el navegador…). `art` = id de la carátula en el PC. */
data class MediaInfo(
    val app: String,
    val title: String,
    val artist: String,
    val album: String,
    val playing: Boolean,
    val position: Double,
    val duration: Double,
    val art: String,
    val canPrevious: Boolean,
    val canNext: Boolean,
)

/** El juego abierto en el PC. `art` = id de la carátula en el PC; `played` = segundos de sesión al mandarlo. */
data class GameInfo(
    val key: String,
    val name: String,
    val source: String,
    val played: Double,
    val hours: Double?,
    val sizeGb: Double?,
    val art: String,
    val summary: String,
    val genres: List<String>,
    val developer: String,
    val released: String,
    val rating: Int?,
    val tags: List<String>,
    /** Logros que tiene el juego; `achUnlocked` = los tuyos (solo con la cuenta de Steam conectada). */
    val achievements: Int? = null,
    val achUnlocked: Int? = null,
    val achRecent: List<String> = emptyList(),
    val played2w: Double? = null,
    /** Gente jugando ahora, novedades y amigos que juegan a esto. */
    val players: Int? = null,
    val news: List<GameNews> = emptyList(),
    val friends: List<String> = emptyList(),
    val match: MatchInfo? = null,
)

/**
 * La partida en curso (League of Legends, Rocket League…). `clock` son los segundos de partida al llegar y `clockStep`
 * hacia dónde va (+1 sube, −1 baja, 0 parado); con solo `hint`, lo que falta para poder verla.
 */
data class MatchInfo(
    val title: String,
    val score: String,
    val clock: Double?,
    val clockStep: Int,
    val stats: List<Pair<String, String>>,
    val feed: List<String>,
    val hint: String,
    /** Un color (#RRGGBB) por cada lado del marcador. */
    val colors: List<String> = emptyList(),
)

/** Una novedad del juego (parche, anuncio); `at` en segundos desde 1970. */
data class GameNews(val title: String, val at: Double)

/** Un juego de los que jugaste hace poco (cuando no hay ninguno abierto). */
data class RecentGame(val name: String, val art: String, val hours: Double?, val lastPlayed: Double)

/** Una línea de la letra: `t` = segundo en que empieza (-1 si la letra no está sincronizada). */
data class LyricLine(val t: Double, val text: String)

/** Letra de la canción que suena (de lrclib.net, a través del PC). */
data class Lyrics(val synced: Boolean, val lines: List<LyricLine>, val instrumental: Boolean = false) {
    companion object {
        fun parse(raw: String): Lyrics? = try {
            val o = JSONObject(raw)
            val arr = o.getJSONArray("lines")
            Lyrics(
                synced = o.optBoolean("synced"),
                instrumental = o.optBoolean("instrumental"),
                lines = List(arr.length()) { i -> arr.getJSONObject(i).let { LyricLine(it.getDouble("t"), it.getString("text")) } },
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Recordatorio o temporizador pendiente; `dueIn` en segundos desde que llegó la lectura. */
data class Upcoming(val kind: String, val text: String, val dueIn: Int)

/** Lectura del PC que manda el servidor cada 2 s (los campos de la gráfica faltan si no hay NVIDIA). */
data class Telemetry(
    val cpu: Int,
    val ramUsed: Double,
    val ramTotal: Double,
    val gpuTemp: Int? = null,
    val gpuUtil: Int? = null,
    val vramUsed: Double? = null,
    val vramTotal: Double? = null,
    val upcoming: List<Upcoming> = emptyList(),
)

/** Notificación del PC (Discord, WhatsApp…). `at` en segundos desde 1970; icono en /app/notifications/icon/<icon>.png. */
data class PcNote(val id: Long, val app: String, val title: String, val body: String, val at: Double, val icon: String)

sealed interface ServerEvent {
    data class Ready(val assistant: String, val model: String) : ServerEvent
    data class History(val messages: List<HistoryItem>) : ServerEvent
    data class State(val state: AssistantState) : ServerEvent
    data class Transcript(val text: String, val audioSeconds: Double, val elapsedMs: Int) : ServerEvent
    data class Token(val text: String) : ServerEvent
    data class Done(val text: String) : ServerEvent
    data class Error(val message: String) : ServerEvent
    data class Actions(val items: List<DeckAction>) : ServerEvent
    data class Confirm(val nonce: String, val id: String, val message: String, val expiresIn: Int) : ServerEvent
    data class ActionResult(val id: String, val ok: Boolean, val message: String) : ServerEvent
    data class Voices(val items: List<Voice>, val current: String) : ServerEvent
    /** Cabecera de una frase de voz: el audio llega justo después como mensaje binario. */
    data class Audio(val seq: Int, val rate: Int, val text: String) : ServerEvent
    data object AudioEnd : ServerEvent
    /** Ha vencido un recordatorio o un temporizador. */
    data class Reminder(val kind: String, val text: String) : ServerEvent
    data class Stats(val telemetry: Telemetry) : ServerEvent
    /** Lo que suena en el PC; null = nada. */
    data class Media(val media: MediaInfo?) : ServerEvent
    /** El juego al que se juega en el PC (null: ninguno). */
    data class Game(val game: GameInfo?, val recent: List<RecentGame> = emptyList(), val account: Boolean = false) : ServerEvent

    /** Cuánto suena el PC ahora (0 a 1), unas 20 veces por segundo mientras suena algo. */
    data class MusicLevel(val level: Float) : ServerEvent

    /** Cuánto suena cada banda de frecuencias de Spotify (de graves a agudos, 0 a 1), ~25 veces por segundo. */
    data class MusicSpectrum(val bands: List<Float>) : ServerEvent
    /** Las notificaciones del PC, de la más nueva a la más vieja. */
    data class Notifications(val items: List<PcNote>) : ServerEvent
    /** Una orden de la app del PC: sleep, wake, brain_pc, brain_phone, glass_on, glass_off, terminal_on, terminal_off, orb_on, orb_off, wave_next, mic_on, mic_off. */
    data class PhoneControl(val command: String) : ServerEvent
    /** Las casillas de la pantalla de Vega en Android Auto (vacía: usa las de serie). */
    data class CarLayout(val title: String, val items: List<com.asistente.personal.car.CarItem>) : ServerEvent
    /** «Vega, duérmete»: el teléfono pasa a dormir. */
    data object Sleep : ServerEvent
    /** Respuesta del PC a una orden que entendió el teléfono (IA en el teléfono). */
    /** `sleep`: después de decirlo, el teléfono se duerme («buenas noches» y luego «no» a apagar el PC). */
    data class PhoneReply(val handled: Boolean, val text: String, val sleep: Boolean = false) : ServerEvent
    /** La cámara del teléfono como cámara de Windows: si se puede (scrcpy y OBS instalados), si está encendida y cuál. */
    data class CameraState(val available: Boolean, val on: Boolean, val facing: String) : ServerEvent
    /** El estado de la «Mesa»: volumen por programa y fondos de pantalla. */
    data class DeskState(val desk: DeskInfo) : ServerEvent
    /** La racha del gimnasio (la llama). */
    data class Streak(val streak: StreakInfo) : ServerEvent
    /** Las entregas de Moodle del tablero: las pendientes y las vencidas hace poco sin marcar. */
    data class Tasks(val items: List<TaskItem>) : ServerEvent
    /** Respuesta del PC a una pregunta libre (`handled` falso: no puede ahora —juego abierto— y contesta el teléfono). */
    data class PhoneChatReply(val handled: Boolean, val text: String) : ServerEvent
    /** Lo que el PC entendió de una frase que le mandó el teléfono (`ok` falso: que lo intente el teléfono). */
    data class Heard(val id: Int, val ok: Boolean, val text: String) : ServerEvent
}

/** Como mucho cuántas bandas de frecuencia se aceptan del servidor (manda 24). */
private const val MAX_BANDS = 64

object Protocol {
    /** Convierte el texto recibido en un evento; null si no es válido o no se conoce. */
    fun parse(raw: String): ServerEvent? = try {
        val o = JSONObject(raw)
        when (o.optString("type")) {
            "ready" -> ServerEvent.Ready(o.getString("assistant"), o.getString("model"))
            "history" -> {
                val arr = o.getJSONArray("messages")
                ServerEvent.History(List(arr.length()) { i ->
                    val m = arr.getJSONObject(i)
                    HistoryItem(m.getString("role"), m.getString("content"))
                })
            }
            "state" -> AssistantState.fromWire(o.getString("state"))?.let { ServerEvent.State(it) }
            "transcript" -> ServerEvent.Transcript(
                o.getString("text"), o.getDouble("audio_seconds"), o.getInt("elapsed_ms"),
            )
            "token" -> ServerEvent.Token(o.getString("text"))
            "done" -> ServerEvent.Done(o.getString("text"))
            "error" -> ServerEvent.Error(o.getString("message"))
            "actions" -> {
                val arr = o.getJSONArray("items")
                ServerEvent.Actions(List(arr.length()) { i ->
                    val a = arr.getJSONObject(i)
                    DeckAction(
                        a.getString("id"), a.getString("label"), a.optString("jp"),
                        a.optString("icon", "app"), a.optString("color", "orange"), a.optBoolean("confirm"),
                        a.optBoolean("quick"), a.optBoolean("app_icon"), a.optString("power"),
                    )
                })
            }
            "confirm" -> ServerEvent.Confirm(
                o.getString("nonce"), o.getString("id"), o.getString("message"), o.getInt("expires_in"),
            )
            "action_result" -> ServerEvent.ActionResult(o.getString("id"), o.getBoolean("ok"), o.getString("message"))
            "voices" -> {
                val arr = o.getJSONArray("items")
                ServerEvent.Voices(
                    List(arr.length()) { i -> arr.getJSONObject(i).let { Voice(it.getString("id"), it.getString("label")) } },
                    o.getString("current"),
                )
            }
            "audio" -> ServerEvent.Audio(o.getInt("seq"), o.getInt("rate"), o.getString("text"))
            "audio_end" -> ServerEvent.AudioEnd
            "reminder" -> ServerEvent.Reminder(o.getString("kind"), o.getString("text"))
            "music_spectrum" -> ServerEvent.MusicSpectrum(
                o.optJSONArray("bands")?.let { arr -> List(minOf(arr.length(), MAX_BANDS)) { i -> arr.optDouble(i, 0.0).toFloat().coerceIn(0f, 1f) } }.orEmpty(),
            )
            "music_level" -> ServerEvent.MusicLevel(o.optDouble("level", 0.0).toFloat().coerceIn(0f, 1f))
            "media" -> ServerEvent.Media(
                if (!o.optBoolean("active")) null else MediaInfo(
                    app = o.optString("app"), title = o.optString("title"), artist = o.optString("artist"),
                    album = o.optString("album"), playing = o.optBoolean("playing"),
                    position = o.optDouble("position", 0.0), duration = o.optDouble("duration", 0.0),
                    art = o.optString("art"), canPrevious = o.optBoolean("can_previous"), canNext = o.optBoolean("can_next"),
                ),
            )
            "game" -> ServerEvent.Game(
                if (!o.optBoolean("active")) null else GameInfo(
                    key = o.optString("key"), name = o.optString("name"), source = o.optString("source"),
                    played = o.optDouble("played", 0.0),
                    hours = if (o.isNull("hours")) null else o.optDouble("hours"),
                    sizeGb = if (o.isNull("size_gb")) null else o.optDouble("size_gb"),
                    art = o.optString("art"), summary = o.optString("summary"),
                    genres = o.optJSONArray("genres")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                    developer = o.optString("developer"), released = o.optString("released"),
                    rating = if (o.isNull("rating")) null else o.optInt("rating"),
                    tags = o.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                    achievements = if (o.isNull("achievements")) null else o.optInt("achievements"),
                    achUnlocked = if (o.isNull("ach_unlocked")) null else o.optInt("ach_unlocked"),
                    achRecent = o.optJSONArray("ach_recent")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                    played2w = if (o.isNull("played_2w")) null else o.optDouble("played_2w"),
                    players = if (o.isNull("players")) null else o.optInt("players"),
                    news = o.optJSONArray("news")?.let { a ->
                        List(a.length()) { i -> a.getJSONObject(i).let { GameNews(it.optString("title"), it.optDouble("at", 0.0)) } }
                    }.orEmpty(),
                    friends = o.optJSONArray("friends")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                    match = o.optJSONObject("match")?.let { m ->
                        MatchInfo(
                            title = m.optString("title"), score = m.optString("score"),
                            clock = if (m.isNull("clock")) null else m.optDouble("clock"),
                            clockStep = m.optInt("clock_step"),
                            stats = m.optJSONArray("stats")?.let { a ->
                                List(a.length()) { i -> a.getJSONObject(i).let { it.optString("label") to it.optString("value") } }
                            }.orEmpty(),
                            feed = m.optJSONArray("feed")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                            hint = m.optString("hint"),
                            colors = m.optJSONArray("colors")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty(),
                        )
                    },
                ),
                recent = o.optJSONArray("recent")?.let { a ->
                    List(a.length()) { i ->
                        a.getJSONObject(i).let {
                            RecentGame(it.optString("name"), it.optString("art"), if (it.isNull("hours")) null else it.optDouble("hours"), it.optDouble("last_played", 0.0))
                        }
                    }
                }.orEmpty(),
                account = o.optBoolean("account"),
            )
            "notifications" -> {
                val arr = o.getJSONArray("items")
                ServerEvent.Notifications(List(arr.length()) { i ->
                    val n = arr.getJSONObject(i)
                    PcNote(n.getLong("id"), n.getString("app"), n.getString("title"), n.optString("body"), n.getDouble("at"), n.optString("icon"))
                })
            }
            "car_layout" -> {
                val arr = o.getJSONArray("items")
                ServerEvent.CarLayout(
                    o.optString("title", "Vega"),
                    List(arr.length()) { i ->
                        arr.getJSONObject(i).let { com.asistente.personal.car.CarItem(it.getString("id"), it.getString("label"), it.getString("kind"), it.getString("arg")) }
                    },
                )
            }
            "sleep" -> ServerEvent.Sleep
            "phone_control" -> ServerEvent.PhoneControl(o.getString("command"))
            "phone_reply" -> ServerEvent.PhoneReply(o.getBoolean("handled"), o.optString("text"), o.optBoolean("sleep"))
            "camera_state" -> ServerEvent.CameraState(o.optBoolean("available"), o.optBoolean("on"), o.optString("facing", "front"))
            "desk_state" -> {
                val apps = o.optJSONArray("apps")
                val w = o.optJSONObject("wallpaper")
                val sug = w?.optJSONArray("suggestions")
                ServerEvent.DeskState(
                    DeskInfo(
                        apps = List(apps?.length() ?: 0) { i ->
                            apps!!.getJSONObject(i).let { DeskApp(it.getString("key"), it.optString("label"), it.optDouble("volume", 0.0).toFloat().coerceIn(0f, 1f), it.optBoolean("muted")) }
                        },
                        wallpaperAvailable = w?.optBoolean("available") ?: false,
                        wallpaper = w?.optString("current").orEmpty(),
                        paused = w?.optBoolean("paused") ?: false,
                        suggestions = List(sug?.length() ?: 0) { i -> sug!!.getJSONObject(i).let { DeskChoice(it.getString("id"), it.optString("title")) } },
                    ),
                )
            }
            "streak" -> {
                val days = o.optJSONArray("days")
                ServerEvent.Streak(
                    StreakInfo(
                        count = o.optInt("count"), best = o.optInt("best"), total = o.optInt("total"), today = o.optBoolean("today"),
                        rest = o.optInt("rest"), restLeft = o.optInt("rest_left"), alive = o.optBoolean("alive"),
                        heat = o.optDouble("heat", 0.0).toFloat().coerceIn(0f, 1f), week = o.optInt("week"),
                        days = List(days?.length() ?: 0) { days!!.getString(it) },
                    ),
                )
            }
            "tasks" -> {
                val arr = o.optJSONArray("items")
                ServerEvent.Tasks(
                    List(arr?.length() ?: 0) { i ->
                        arr!!.getJSONObject(i).let { TaskItem(it.getString("uid"), it.optString("title"), it.optString("course"), it.getDouble("due").toLong()) }
                    },
                )
            }
            "phone_chat_reply" -> ServerEvent.PhoneChatReply(o.getBoolean("handled"), o.optString("text"))
            "heard" -> ServerEvent.Heard(o.getInt("id"), o.optBoolean("ok", true), o.optString("text"))
            "telemetry" -> {
                val arr = o.optJSONArray("upcoming")
                fun optInt(k: String) = if (o.isNull(k)) null else o.getInt(k)
                fun optDouble(k: String) = if (o.isNull(k)) null else o.getDouble(k)
                ServerEvent.Stats(
                    Telemetry(
                        cpu = o.getInt("cpu"), ramUsed = o.getDouble("ram_used"), ramTotal = o.getDouble("ram_total"),
                        gpuTemp = optInt("gpu_temp"), gpuUtil = optInt("gpu_util"),
                        vramUsed = optDouble("vram_used"), vramTotal = optDouble("vram_total"),
                        upcoming = List(arr?.length() ?: 0) { i ->
                            arr!!.getJSONObject(i).let { Upcoming(it.getString("kind"), it.getString("text"), it.getInt("due_in")) }
                        },
                    ),
                )
            }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun hello(token: String): String = JSONObject().put("type", "hello").put("token", token).toString()
    fun chat(text: String): String = JSONObject().put("type", "chat").put("text", text).toString()
    fun simple(type: String): String = JSONObject().put("type", type).toString()
    fun action(id: String): String = JSONObject().put("type", "action").put("id", id).toString()
    /** `seek` lleva además la posición (segundos) a la que saltar. */
    fun mediaControl(command: String, position: Double? = null): String =
        JSONObject().put("type", "media_control").put("command", command).apply { if (position != null) put("position", position) }.toString()
    fun setVoice(id: String): String = JSONObject().put("type", "set_voice").put("voice", id).toString()
    fun confirm(nonce: String): String = JSONObject().put("type", "action_confirm").put("nonce", nonce).toString()
    /** El estado del teléfono para la app del PC (ver `phone_state` en server/ws/protocol.py). */
    fun phoneState(
        brainOnPhone: Boolean, sleeping: Boolean, mic: Boolean, battery: Int, charging: Boolean,
        glass: Boolean, touch: String, face: String, version: Int,
    ): String = JSONObject().put("type", "phone_state").put("brain", if (brainOnPhone) "phone" else "pc")
        .put("sleeping", sleeping).put("mic", mic).put("battery", battery).put("charging", charging)
        .put("glass", glass).put("touch", touch.lowercase()).put("face", face).put("version", version).toString()
    fun sleepMode(on: Boolean): String = JSONObject().put("type", "sleep").put("on", on).toString()
    /** Una frase de voz (PCM16 a 16 kHz, en base64) para que el PC la transcriba. */
    fun hear(id: Int, pcm: ByteArray): String = JSONObject().put("type", "hear").put("id", id)
        .put("audio", android.util.Base64.encodeToString(pcm, android.util.Base64.NO_WRAP)).toString()
    fun phoneCommand(text: String): String = JSONObject().put("type", "phone_command").put("text", text).toString()
    /** Enciende o apaga la cámara del teléfono en el PC; `facing` (front/back) es opcional. */
    fun camera(on: Boolean, facing: String? = null): String = JSONObject().put("type", "camera").put("on", on)
        .apply { if (facing != null) put("facing", facing) }.toString()
    /** La llama del gimnasio: `action` es get, light u out; `day` (AAAA-MM-DD) es opcional (hoy si falta). */
    fun streak(action: String, day: String? = null): String =
        JSONObject().put("type", "streak").put("action", action).apply { if (day != null) put("day", day) }.toString()
    /** El tablero de Moodle: `action` es get o done (esta con el `uid` de la entrega). */
    fun tasks(action: String, uid: String? = null): String =
        JSONObject().put("type", "tasks").put("action", action).apply { if (uid != null) put("uid", uid) }.toString()
    /** Un gesto de la «Mesa»: `action` es get, volume, mute, wallpaper, random, more, pause o play. */
    fun desk(action: String, app: String? = null, level: Float? = null, muted: Boolean? = null, id: String? = null): String =
        JSONObject().put("type", "desk").put("action", action)
            .apply {
                if (app != null) put("app", app)
                if (level != null) put("level", Math.round(level.coerceIn(0f, 1f) * 100) / 100.0)
                if (muted != null) put("muted", muted)
                if (id != null) put("id", id)
            }.toString()
    /** Una pregunta libre para el modelo grande del PC, con los últimos intercambios (el PC no guarda nada). */
    fun phoneChat(text: String, history: List<Pair<String, String>>): String = JSONObject().put("type", "phone_chat").put("text", text)
        .put(
            "history",
            org.json.JSONArray().apply {
                history.forEach { (q, a) ->
                    put(JSONObject().put("role", "user").put("content", q.take(500)))
                    put(JSONObject().put("role", "assistant").put("content", a.take(500)))
                }
            },
        ).toString()
    /** Quita una notificación del PC; con null, todas. */
    fun dismissNotification(id: Long?): String =
        JSONObject().put("type", "notification_dismiss").apply { if (id != null) put("id", id) }.toString()
}
