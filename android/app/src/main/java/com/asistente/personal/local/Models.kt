package com.asistente.personal.local

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Un archivo de modelo tal como lo lista el PC. */
data class ModelFile(val path: String, val size: Long)

/** Lista de archivos que publica el PC en `/app/models` (JSON: {"files": [{"path", "size"}]}). */
fun parseManifest(json: String): List<ModelFile> {
    val files = JSONObject(json).getJSONArray("files")
    return (0 until files.length()).map { i ->
        val f = files.getJSONObject(i)
        ModelFile(f.getString("path"), f.getLong("size"))
    }
}

/** Archivos que faltan o están a medias (tamaño distinto) en `dir`. */
fun missingModels(dir: File, files: List<ModelFile>): List<ModelFile> =
    files.filter { File(dir, it.path).let { f -> !f.isFile || f.length() != it.size } }

/**
 * Modelos de la IA del teléfono (~230 MB: Whisper base y la voz Piper). Se bajan del PC una
 * sola vez por el cable, como las actualizaciones, y luego funcionan sin él.
 */
class ModelStore(val dir: File, private val server: String = "http://127.0.0.1:8000") {
    private val http = OkHttpClient.Builder().callTimeout(30, TimeUnit.MINUTES).build()

    val whisper: File get() = File(dir, "whisper-base")
    val piper: File get() = File(dir, "piper-es_MX")

    /** Hay algo descargado: se puede intentar arrancar sin el PC. */
    fun present(): Boolean = File(whisper, "base-encoder.int8.onnx").isFile && File(piper, "es_MX-claude-high.onnx").isFile

    /**
     * Baja del PC lo que falte. `progress` recibe la fracción 0–1 del total. Devuelve null si
     * todo está, o el motivo por el que no se pudo.
     */
    fun download(progress: (Float) -> Unit): String? {
        val files = try {
            http.newCall(Request.Builder().url("$server/app/models").build()).execute().use { resp ->
                if (!resp.isSuccessful) return "El PC no tiene los modelos del teléfono (falta data/telefono)."
                parseManifest(resp.body.string())
            }
        } catch (e: Exception) {
            return "No pude hablar con el PC para bajar los modelos."
        }
        val missing = missingModels(dir, files)
        val total = missing.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        for (f in missing) {
            val out = File(dir, f.path)
            out.parentFile?.mkdirs()
            val part = File(out.path + ".part")
            try {
                http.newCall(Request.Builder().url("$server/app/models/${f.path}").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return "El PC no me dio ${f.path}."
                    part.outputStream().use { sink ->
                        val src = resp.body.byteStream()
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            done += n
                            progress(done.toFloat() / total)
                        }
                    }
                }
                out.delete()
                if (!part.renameTo(out)) return "No pude guardar ${f.path}."
            } catch (e: Exception) {
                part.delete()
                return "Se cortó la descarga de los modelos: ${e.message}"
            }
        }
        progress(1f)
        return null
    }
}
