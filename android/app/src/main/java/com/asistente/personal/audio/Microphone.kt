package com.asistente.personal.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.audiofx.AudioEffect
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
/** 100 ms de PCM16 mono a 16 kHz: el mismo tamaño de bloque que la interfaz web. */
const val CHUNK_BYTES = SAMPLE_RATE / 10 * 2

/** Bloques de 100 ms seguidos con silencio exacto (5 s) tras los que se da el micrófono por muerto. */
const val SILENT_CHUNKS_LIMIT = 50

/** Volumen eficaz (RMS) entre 0 y 1 de un bloque PCM16 little-endian. */
fun rmsPcm16(buf: ByteArray, length: Int): Float {
    val samples = length / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val v = ((buf[2 * i + 1].toInt() shl 8) or (buf[2 * i].toInt() and 0xFF)).toShort() / 32768.0
        sum += v * v
    }
    return sqrt(sum / samples).toFloat()
}

/**
 * Micrófono → bloques PCM16 a 16 kHz. Se abre solo mientras se graba; la lectura va
 * en un hilo propio para no tocar el hilo de la interfaz.
 */
class Microphone(
    /** El micrófono dejó de funcionar solo (error de lectura o el sistema lo silenció): hay que reabrirlo. */
    private val onDied: () -> Unit = {},
    private val onChunk: (ByteArray, Int, Float) -> Unit,
) {
    @Volatile private var running = false
    private var worker: Thread? = null

    val active: Boolean get() = running

    /** Requiere el permiso RECORD_AUDIO concedido. Devuelve false si no se pudo abrir. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                // VOICE_RECOGNITION: pensado para dictado, sin el procesado agresivo de las llamadas.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, CHUNK_BYTES * 4),
            )
        } catch (_: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        // Se guardan los efectos: si no, el recolector de basura los libera y la reducción de ruido, la
        // ganancia y el eco desaparecen al cabo de un rato sin avisar.
        val session = record.audioSessionId
        val effects = buildList<AudioEffect> {
            if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; add(it) }
            if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(session)?.let { it.enabled = true; add(it) }
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = true; add(it) }
        }
        try {
            record.startRecording() // falla si otra app tiene el micrófono en exclusiva
        } catch (_: IllegalStateException) {
            effects.forEach(AudioEffect::release)
            record.release()
            return false
        }
        running = true
        worker = thread(name = "microfono", priority = Thread.MAX_PRIORITY) {
            val buf = ByteArray(CHUNK_BYTES)
            var died = false
            var silent = 0
            try {
                while (running) {
                    var filled = 0
                    while (running && filled < CHUNK_BYTES) {
                        val n = record.read(buf, filled, CHUNK_BYTES - filled)
                        if (n < 0) { // error del micrófono: se deja de grabar y se avisa
                            died = true
                            running = false
                        }
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled > 0) {
                        val level = rmsPcm16(buf, filled)
                        // Silencio digital exacto durante 5 s: el sistema le quitó el micrófono a la app
                        // (otra app lo usa) y entrega ceros sin fallar. Un micrófono vivo siempre tiene ruido.
                        silent = if (level == 0f) silent + 1 else 0
                        if (silent >= SILENT_CHUNKS_LIMIT) {
                            died = true
                            running = false
                        } else {
                            onChunk(buf.copyOf(filled), filled, level)
                        }
                    }
                }
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                effects.forEach(AudioEffect::release)
                if (died) onDied()
            }
        }
        return true
    }

    fun stop() {
        running = false
        worker?.join(300)
        worker = null
    }
}
