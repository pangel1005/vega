package com.asistente.personal.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Tras la última frase, cuánto se sigue considerando «hablando» (eco de la sala, cola del audio). */
private const val TAIL_MS = 350L
/** Cada cuánto se mide el volumen para mover la boca (~25 veces por segundo). */
private const val LEVEL_MS = 40

/**
 * Reproduce la voz que llega del PC frase a frase, sin cortes entre ellas.
 *
 * Un hilo propio escribe en un `AudioTrack` en modo continuo. Mientras suena, mide el
 * volumen de cada trozo de 40 ms y lo pasa a la interfaz (la boca se mueve con la voz
 * real) y avisa de cuándo empieza y termina de hablar (para cortar el micrófono).
 *
 * @param onLevel volumen 0–1 de lo que está sonando (llamado desde el hilo de audio).
 * @param onSpeaking true al empezar a sonar, false cuando termina (desde el hilo de audio).
 */
class Player(
    private val onLevel: (Float) -> Unit,
    private val onSpeaking: (Boolean) -> Unit,
) {
    private class Chunk(val pcm: ByteArray, val rate: Int)

    private val queue = LinkedBlockingQueue<Chunk>()
    @Volatile private var running = true
    @Volatile var speaking = false
        private set
    private var track: AudioTrack? = null
    private var trackRate = 0

    private val worker = thread(name = "voz", priority = Thread.MAX_PRIORITY) { loop() }

    fun enqueue(pcm: ByteArray, rate: Int) {
        if (pcm.isNotEmpty()) queue.put(Chunk(pcm, rate))
    }

    /** Corta lo que esté sonando (p. ej. al irse la app a segundo plano). */
    fun stop() {
        queue.clear()
        track?.pause()
        track?.flush()
    }

    fun release() {
        running = false
        worker.interrupt()
    }

    private fun loop() {
        try {
            while (running) {
                val chunk = queue.poll(if (speaking) TAIL_MS else 1_000, TimeUnit.MILLISECONDS)
                if (chunk == null) {
                    if (speaking) setSpeaking(false) // no llegó más voz: terminó de hablar
                    continue
                }
                if (!speaking) setSpeaking(true)
                play(chunk)
            }
        } catch (_: InterruptedException) {
            // la app se cierra
        } finally {
            track?.release()
        }
    }

    private fun setSpeaking(value: Boolean) {
        speaking = value
        if (!value) onLevel(0f)
        onSpeaking(value)
    }

    private fun play(chunk: Chunk) {
        val t = trackFor(chunk.rate)
        if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        val step = chunk.rate * 2 * LEVEL_MS / 1000 // bytes de 40 ms
        var offset = 0
        while (offset < chunk.pcm.size && running) {
            val len = minOf(step, chunk.pcm.size - offset)
            onLevel(rmsPcm16(chunk.pcm.copyOfRange(offset, offset + len), len))
            // write() bloquea hasta que hay sitio: así el volumen va al ritmo del sonido.
            val written = t.write(chunk.pcm, offset, len)
            if (written <= 0) break
            offset += written
        }
    }

    private fun trackFor(rate: Int): AudioTrack {
        track?.takeIf { trackRate == rate }?.let { return it }
        track?.release()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, rate / 5 * 2)) // ~200 ms: poca latencia, sin cortes
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also {
                track = it
                trackRate = rate
            }
    }
}
