package com.asistente.personal.local

import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** 20 ms de PCM16 mono a 16 kHz. */
const val FRAME_SAMPLES = 320
private const val FRAME_BYTES = FRAME_SAMPLES * 2

data class SegmenterConfig(
    val startFrames: Int = 3, // 60 ms seguidos de voz para empezar
    val endSilenceFrames: Int = 25, // 0,5 s de silencio para dar la frase por terminada
    val prerollFrames: Int = 15, // 300 ms de audio previo (para no cortar la primera sílaba)
    val minSpeechFrames: Int = 15, // frases de menos de 0,3 s se descartan (golpes, clics)
    val maxFrames: Int = 600, // 12 s como máximo por frase
    val warmupFrames: Int = 25, // 0,5 s al empezar solo para aprender el ruido de fondo
    val minThreshold: Float = 0.012f, // volumen mínimo (RMS 0–1) para considerar voz
    val noiseRatio: Float = 3.0f, // la voz debe superar el ruido de fondo en este factor
)

/**
 * Corta el micrófono en frases mirando el volumen frente al ruido de fondo, que va
 * aprendiendo. Copia de `SpeechSegmenter` (server/speech/listener.py): así Whisper solo
 * trabaja cuando alguien habla, no con el silencio.
 */
class Segmenter(private val cfg: SegmenterConfig = SegmenterConfig()) {
    private var pending = ByteArray(0)
    private val preroll = ArrayDeque<ByteArray>()
    private var frames = mutableListOf<ByteArray>()
    private var voiced = 0
    private var loudRun = 0
    private var silenceRun = 0
    private var noise = 0.004f
    private var warmup = cfg.warmupFrames
    var inSpeech = false
        private set

    val threshold: Float get() = maxOf(cfg.minThreshold, noise * cfg.noiseRatio)

    fun reset() {
        pending = ByteArray(0)
        preroll.clear()
        frames = mutableListOf()
        voiced = 0
        loudRun = 0
        silenceRun = 0
        inSpeech = false
    }

    /** Añade audio PCM16. Devuelve las frases completas que se hayan cerrado (casi siempre ninguna). */
    fun feed(chunk: ByteArray, length: Int = chunk.size): List<ByteArray> {
        val data = pending + chunk.copyOf(length)
        val usable = data.size - data.size % FRAME_BYTES
        pending = data.copyOfRange(usable, data.size)
        val done = mutableListOf<ByteArray>()
        var i = 0
        while (i < usable) {
            frame(data.copyOfRange(i, i + FRAME_BYTES))?.let(done::add)
            i += FRAME_BYTES
        }
        return done
    }

    private fun remember(frame: ByteArray) {
        preroll.addLast(frame)
        while (preroll.size > cfg.prerollFrames) preroll.removeFirst()
    }

    private fun frame(frame: ByteArray): ByteArray? {
        val rms = rms(frame)
        val loud = rms > threshold

        if (warmup > 0) {
            // Calibración inicial: media del ruido de fondo, sin buscar voz todavía.
            warmup--
            val n = cfg.warmupFrames - warmup
            noise += (rms - noise) / n
            remember(frame)
            return null
        }

        if (!inSpeech) {
            // El ruido de fondo se aprende solo fuera de las frases (sube despacio, baja rápido).
            val rate = if (rms > noise) 0.05f else 0.2f
            noise += (rms - noise) * rate
            remember(frame)
            loudRun = if (loud) loudRun + 1 else 0
            if (loudRun >= cfg.startFrames) {
                inSpeech = true
                frames = preroll.toMutableList()
                voiced = loudRun
                silenceRun = 0
                preroll.clear()
            }
            return null
        }

        frames += frame
        if (loud) {
            voiced++
            silenceRun = 0
        } else {
            silenceRun++
        }
        if (silenceRun >= cfg.endSilenceFrames || frames.size >= cfg.maxFrames) {
            val out = ByteArrayOutputStream(frames.size * FRAME_BYTES)
            frames.forEach(out::write)
            val heard = voiced
            reset()
            if (heard >= cfg.minSpeechFrames) return out.toByteArray()
        }
        return null
    }

    private fun rms(frame: ByteArray): Float {
        var sum = 0.0
        for (i in 0 until FRAME_SAMPLES) {
            val v = ((frame[2 * i + 1].toInt() shl 8) or (frame[2 * i].toInt() and 0xFF)).toShort() / 32768.0
            sum += v * v
        }
        return sqrt(sum / FRAME_SAMPLES).toFloat()
    }
}

/** PCM16 little-endian → muestras entre −1 y 1 (lo que esperan Whisper y sherpa-onnx). */
fun pcm16ToFloat(pcm: ByteArray): FloatArray = FloatArray(pcm.size / 2) { i ->
    ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort() / 32768f
}

/** Muestras entre −1 y 1 → PCM16 little-endian (lo que reproduce `Player`). */
fun floatToPcm16(samples: FloatArray): ByteArray {
    val out = ByteArray(samples.size * 2)
    for (i in samples.indices) {
        val v = (samples[i].coerceIn(-1f, 1f) * 32767f).toInt()
        out[2 * i] = v.toByte()
        out[2 * i + 1] = (v shr 8).toByte()
    }
    return out
}
