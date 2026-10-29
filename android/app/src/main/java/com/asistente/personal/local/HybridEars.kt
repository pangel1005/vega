package com.asistente.personal.local

/**
 * El oído de la IA del teléfono: con el PC conectado le manda la frase para que la transcriba (Whisper en el
 * procesador del PC, ~4 veces más rápido que el del teléfono: ~0,5 s frente a ~2 s en una frase de 3 s) y,
 * si el PC no contesta a tiempo o falla, la transcribe el teléfono como siempre.
 *
 * `remote` devuelve el texto, o null si no pudo (sin enlace, sin voz a texto en el PC, tiempo agotado).
 * Solo se registran tiempos y de dónde vino, nunca lo dicho.
 */
class HybridEars(
    private val local: Ears,
    private val remote: (FloatArray) -> String?,
    private val clock: () -> Long,
    private val log: (String) -> Unit = {},
) : Ears, AutoCloseable {
    override fun transcribe(samples: FloatArray): String {
        val t0 = clock()
        val fromPc = try {
            remote(samples)
        } catch (_: Exception) {
            null
        }
        if (fromPc != null) {
            log("oído: PC, ${clock() - t0} ms")
            return fromPc
        }
        val tLocal = clock()
        val text = local.transcribe(samples)
        log("oído: teléfono, ${clock() - tLocal} ms (el PC no contestó en ${tLocal - t0} ms)")
        return text
    }

    override fun close() = (local as? AutoCloseable)?.close() ?: Unit
}
