package com.asistente.personal.net

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

enum class LinkStatus { CONNECTING, OPEN, RECONNECTING, DENIED }

const val BASE_DELAY_MS = 500L
const val MAX_DELAY_MS = 10_000L
private const val CLOSE_UNAUTHORIZED = 4401

/** Espera antes del reintento `attempt` (0, 1, 2…): 0,5 s, 1 s, 2 s… hasta 10 s, con ±20 % de azar. */
fun backoffDelay(attempt: Int, random: Double = Random.nextDouble()): Long {
    val exp = min(MAX_DELAY_MS.toDouble(), BASE_DELAY_MS * 2.0.pow(attempt))
    return (exp * (0.8 + random * 0.4)).roundToLong()
}

/**
 * Conexión con el servidor del PC que se recupera sola (mismo comportamiento que
 * ui/src/net/socket.ts). Los avisos llegan siempre en el hilo principal.
 */
class AssistantSocket(
    private val url: String,
    private val token: () -> String,
    private val onEvent: (ServerEvent) -> Unit,
    private val onStatus: (LinkStatus, Long?) -> Unit,
    /** Voz que llega del PC (PCM16). Se llama desde el hilo de red, no desde el principal. */
    private val onAudio: (ByteArray) -> Unit = {},
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS) // detecta cables desconectados
        .build()
    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var attempt = 0
    private var stopped = true
    private var open = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        ws = null
    }

    fun send(text: String): Boolean = open && (ws?.send(text) ?: false)

    fun sendAudio(pcm: ByteArray, length: Int): Boolean = open && (ws?.send(pcm.toByteString(0, length)) ?: false)

    private fun connect() {
        onStatus(if (attempt == 0) LinkStatus.CONNECTING else LinkStatus.RECONNECTING, null)
        val socket = client.newWebSocket(Request.Builder().url(url).build(), Listener())
        ws = socket
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(Protocol.hello(token()))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = Protocol.parse(text) ?: return
            main.post {
                if (webSocket !== ws) return@post
                if (event is ServerEvent.Ready) {
                    attempt = 0
                    open = true
                    onStatus(LinkStatus.OPEN, null)
                }
                onEvent(event)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (webSocket === ws) onAudio(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
            closed(webSocket, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = closed(webSocket, null)

        private fun closed(webSocket: WebSocket, code: Int?) {
            main.post {
                if (webSocket !== ws) return@post // conexión vieja
                ws = null
                open = false
                if (stopped) return@post
                if (code == CLOSE_UNAUTHORIZED) {
                    onStatus(LinkStatus.DENIED, null)
                    return@post
                }
                val delay = backoffDelay(attempt++)
                onStatus(LinkStatus.RECONNECTING, delay)
                main.postDelayed({ if (!stopped) connect() }, delay)
            }
        }
    }
}
