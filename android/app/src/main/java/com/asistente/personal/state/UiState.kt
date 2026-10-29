package com.asistente.personal.state

import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.Voice
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.Telemetry
import java.util.Locale

/** Estado de la pantalla a partir de los eventos del servidor (igual que web/src/chat/reducer.ts). */

enum class Role { USER, ASSISTANT, ERROR, NOTE }

data class Message(
    val id: Int,
    val role: Role,
    val text: String,
    val streaming: Boolean = false,
    val meta: String? = null,
)

/** Acción sensible esperando la segunda pulsación. */
data class PendingConfirm(val id: String, val nonce: String, val message: String, val expiresIn: Int)

/**
 * Aviso corto: resultado de un botón («El PC se apagará en 15 s…»), un error o un
 * recordatorio (`reminder`: pasa por la cara en letras de LED).
 */
data class Notice(val seq: Int, val text: String, val ok: Boolean, val reminder: Boolean = false)

data class UiState(
    val actions: List<DeckAction> = emptyList(),
    val voices: List<Voice> = emptyList(),
    val voice: String = "",
    val pending: PendingConfirm? = null,
    val notice: Notice? = null,
    val link: LinkStatus = LinkStatus.CONNECTING,
    val retryInMs: Long? = null,
    val assistant: String = "",
    val model: String = "",
    val state: AssistantState = AssistantState.IDLE,
    val messages: List<Message> = emptyList(),
    val nextId: Int = 1,
    /** Cuenta de trozos recibidos: mueve la boca mientras llega la respuesta. */
    val tokenPulse: Int = 0,
    /** Última lectura del PC (null hasta la primera, o sin enlace). */
    val telemetry: Telemetry? = null,
    /** Cuándo llegó (reloj del teléfono, ms): con ella se cuentan atrás los temporizadores. */
    val telemetryAt: Long = 0,
    /**
     * Se mandó apagar, reiniciar o suspender el PC (shutdown/restart/sleep): al perder el
     * enlace, Vega se duerme en vez de mostrar «reconectando». Se borra al volver el enlace.
     */
    val pcOff: String? = null,
    /** Notificaciones del PC (la más nueva primero). */
    val pcNotes: List<com.asistente.personal.net.PcNote> = emptyList(),
    /** Ids de las que llegaron después de conectar (para resaltarlas): las de antes no son «nuevas». */
    val freshNotes: Set<Long> = emptySet(),
    /** Ya llegó la primera lista desde que se abrió el enlace. */
    val notesKnown: Boolean = false,
    /** Lo que suena en el PC (null = nada) y cuándo llegó (reloj del teléfono, ms). */
    val media: com.asistente.personal.net.MediaInfo? = null,
    val mediaAt: Long = 0,
)

sealed interface Action {
    data class Server(val event: ServerEvent) : Action
    data class Link(val status: LinkStatus, val retryInMs: Long? = null) : Action
    data class Sent(val text: String) : Action
    data class LocalError(val text: String) : Action
    data class ConfirmExpired(val nonce: String) : Action
    data class NoticeExpired(val seq: Int) : Action
    data class Telemetry(val data: com.asistente.personal.net.Telemetry, val at: Long) : Action
    data class Media(val media: com.asistente.personal.net.MediaInfo?, val at: Long) : Action
}

private fun UiState.notify(text: String, ok: Boolean, reminder: Boolean = false) =
    copy(notice = Notice((notice?.seq ?: 0) + 1, text, ok, reminder))

const val NOT_HEARD = "No te he oído. Prueba a hablar un poco más cerca del micrófono."

private fun seconds(ms: Int) = String.format(Locale.forLanguageTag("es"),"%.2f s", ms / 1000.0)

private fun UiState.push(role: Role, text: String, streaming: Boolean = false, meta: String? = null) =
    copy(messages = messages + Message(nextId, role, text, streaming, meta), nextId = nextId + 1)

private fun UiState.lastStreaming(): Message? = messages.lastOrNull()?.takeIf { it.streaming }

private fun UiState.replaceLast(m: Message) = copy(messages = messages.dropLast(1) + m)

fun reduce(s: UiState, action: Action): UiState = when (action) {
    is Action.Link -> {
        // Sin enlace, la lectura del PC deja de ser verdad: se quita en vez de mostrarla congelada.
        val next = s.copy(
            link = action.status, retryInMs = action.retryInMs,
            telemetry = if (action.status == LinkStatus.OPEN) s.telemetry else null,
            media = if (action.status == LinkStatus.OPEN) s.media else null,
            pcOff = if (action.status == LinkStatus.OPEN) null else s.pcOff,
            pcNotes = if (action.status == LinkStatus.OPEN) s.pcNotes else emptyList(),
            freshNotes = if (action.status == LinkStatus.OPEN) s.freshNotes else emptySet(),
            notesKnown = action.status == LinkStatus.OPEN && s.notesKnown,
        )
        val open = next.lastStreaming()
        // Si se corta a mitad de una respuesta, se cierra lo que había llegado.
        if (action.status == LinkStatus.OPEN || open == null) next else next.replaceLast(open.copy(streaming = false))
    }
    is Action.Sent -> s.push(Role.USER, action.text)
    is Action.LocalError -> s.push(Role.ERROR, action.text).notify(action.text, ok = false)
    is Action.ConfirmExpired -> if (s.pending?.nonce == action.nonce) s.copy(pending = null) else s
    is Action.NoticeExpired -> if (s.notice?.seq == action.seq) s.copy(notice = null) else s
    is Action.Telemetry -> s.copy(telemetry = action.data, telemetryAt = action.at)
    is Action.Media -> s.copy(media = action.media, mediaAt = action.at)
    is Action.Server -> when (val e = action.event) {
        is ServerEvent.Ready -> s.copy(assistant = e.assistant, model = e.model)
        is ServerEvent.History -> e.messages.fold(s.copy(messages = emptyList())) { acc, m ->
            when (m.role) {
                "user" -> acc.push(Role.USER, m.content)
                "assistant" -> acc.push(Role.ASSISTANT, m.content)
                else -> acc
            }
        }
        is ServerEvent.State -> s.copy(state = e.state)
        is ServerEvent.Transcript ->
            if (e.text.isNotEmpty()) s.push(Role.USER, e.text, meta = "voz · ${seconds(e.elapsedMs)}")
            else s.push(Role.NOTE, NOT_HEARD)
        is ServerEvent.Token -> {
            val open = s.lastStreaming()
            val next = if (open != null) s.replaceLast(open.copy(text = open.text + e.text))
            else s.push(Role.ASSISTANT, e.text, streaming = true)
            next.copy(tokenPulse = s.tokenPulse + 1)
        }
        is ServerEvent.Done -> {
            val open = s.lastStreaming()
            if (open != null) s.replaceLast(open.copy(text = e.text, streaming = false)) else s.push(Role.ASSISTANT, e.text)
        }
        is ServerEvent.Error -> s.push(Role.ERROR, e.message).notify(e.message, ok = false)
        is ServerEvent.Actions -> s.copy(actions = e.items)
        is ServerEvent.Confirm -> s.copy(pending = PendingConfirm(e.id, e.nonce, e.message, e.expiresIn))
        is ServerEvent.ActionResult -> {
            val power = s.actions.firstOrNull { it.id == e.id }?.power.orEmpty()
            val pcOff = when {
                !e.ok -> s.pcOff
                power in setOf("shutdown", "restart", "sleep") -> power
                power == "cancel" -> null
                else -> s.pcOff
            }
            s.copy(pending = null, pcOff = pcOff).notify(e.message, e.ok)
        }
        is ServerEvent.Voices -> s.copy(voices = e.items, voice = e.current)
        // La voz la gestiona el reproductor; el estado de la pantalla no cambia.
        is ServerEvent.Audio, ServerEvent.AudioEnd -> s
        is ServerEvent.Reminder -> s.push(Role.NOTE, e.text).notify(e.text, ok = true, reminder = true)
        // Llega sin hora local; AssistantController la reenvía como Action.Telemetry con la hora.
        is ServerEvent.Stats -> s.copy(telemetry = e.telemetry)
        is ServerEvent.Media -> s.copy(media = e.media)
        is ServerEvent.MusicSpectrum -> s // idem: lo lee la onda del controlador, sin recomponer
        is ServerEvent.MusicLevel -> s // el volumen no toca el estado (llega 20 veces por segundo): lo lee la onda del controlador
        is ServerEvent.Notifications -> {
            val old = s.pcNotes.map { it.id }.toSet()
            val arrived = if (s.notesKnown) e.items.map { it.id }.filter { it !in old } else emptyList()
            val alive = e.items.map { it.id }.toSet()
            s.copy(pcNotes = e.items, freshNotes = (s.freshNotes intersect alive) + arrived, notesKnown = true)
        }
        // La contesta el puente con el PC de la IA del teléfono; la pantalla no cambia.
        is ServerEvent.PhoneReply -> s
        is ServerEvent.Heard -> s
        // La atiende AssistantController (pasa a dormir); la pantalla no cambia por sí sola.
        is ServerEvent.Sleep -> s
        // La pantalla del coche la guarda AssistantController (la usa el servicio de Android Auto).
        is ServerEvent.CarLayout -> s
        // Las órdenes de la app del PC las ejecuta AssistantController.
        is ServerEvent.PhoneControl -> s
    }
}
