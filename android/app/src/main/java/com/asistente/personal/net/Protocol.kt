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

sealed interface ServerEvent {
    data class Ready(val assistant: String, val model: String) : ServerEvent
    data class History(val messages: List<HistoryItem>) : ServerEvent
    data class State(val state: AssistantState) : ServerEvent
    data class Transcript(val text: String, val audioSeconds: Double, val elapsedMs: Int) : ServerEvent
    data class Token(val text: String) : ServerEvent
    data class Done(val text: String) : ServerEvent
    data class Error(val message: String) : ServerEvent
}

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
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    fun hello(token: String): String = JSONObject().put("type", "hello").put("token", token).toString()
    fun chat(text: String): String = JSONObject().put("type", "chat").put("text", text).toString()
    fun simple(type: String): String = JSONObject().put("type", type).toString()
}
