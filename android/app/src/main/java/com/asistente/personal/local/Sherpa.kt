package com.asistente.personal.local

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

// Whisper y Piper dentro de la app, con sherpa-onnx (sin internet ni PC).
// Medido en el Redmi 15C (docs/ia-en-el-telefono.md): Whisper base ~2,2 s por frase de
// 3 s con 4 núcleos; Piper, 2,5 veces más rápido que el tiempo real.

/** Whisper base (int8) en español. */
class WhisperEars(dir: File, threads: Int = 4) : Ears, AutoCloseable {
    private val recognizer = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, "base-encoder.int8.onnx").path,
                    decoder = File(dir, "base-decoder.int8.onnx").path,
                    language = "es",
                    task = "transcribe",
                ),
                tokens = File(dir, "base-tokens.txt").path,
                modelType = "whisper",
                numThreads = threads,
            ),
        ),
    )

    override fun close() = recognizer.release()

    override fun transcribe(samples: FloatArray): String {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, 16_000)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }
}

/** Voz Piper `es_MX-claude-high` (la que mejor sonó en las pruebas del teléfono). */
class PiperMouth(dir: File, threads: Int = 2) : Mouth, AutoCloseable {
    private val tts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = File(dir, "es_MX-claude-high.onnx").path,
                    tokens = File(dir, "tokens.txt").path,
                    dataDir = File(dir, "espeak-ng-data").path,
                ),
                numThreads = threads,
            ),
        ),
    )

    override fun close() = tts.release()

    override fun say(text: String, onAudio: (ByteArray, Int) -> Unit) {
        val audio = tts.generate(text, sid = 0, speed = 1.0f)
        if (audio.samples.isNotEmpty()) onAudio(floatToPcm16(audio.samples), audio.sampleRate)
    }
}
