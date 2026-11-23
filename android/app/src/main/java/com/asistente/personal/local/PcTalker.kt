package com.asistente.personal.local

/**
 * Charla libre «mixta»: la pregunta (que no es botón ni habilidad) se le hace primero al modelo grande del PC
 * —solo contesta si está libre, sin juego abierto, y en menos de un segundo— y, si no puede (juego, sin cable,
 * tarda), contesta el modelo pequeño del teléfono. Todo lo demás (oír, entender, botones, voz) sigue donde estaba.
 *
 * Recuerda los últimos intercambios para mandárselos al PC («¿y la de Italia?»): el PC no guarda nada.
 */
class PcTalker(
    /** Le pregunta al PC con lo anterior; devuelve su respuesta, o null si no puede ahora. */
    private val ask: (String, List<Pair<String, String>>) -> String?,
    private val local: Talker?,
    private val log: (String) -> Unit = {},
) : Talker, AutoCloseable {
    private val history = ArrayDeque<Pair<String, String>>()

    override fun reply(question: String, onText: (String) -> Boolean): String? {
        val fromPc = try {
            ask(question, synchronized(history) { history.toList() })
        } catch (_: Exception) {
            null
        }
        if (!fromPc.isNullOrBlank()) {
            log("charla: la contestó el PC")
            remember(question, fromPc)
            onText(fromPc)
            return fromPc
        }
        log("charla: la contesta el teléfono")
        val answer = local?.reply(question, onText)
        if (!answer.isNullOrBlank()) remember(question, answer)
        return answer
    }

    private fun remember(question: String, answer: String) = synchronized(history) {
        history.addLast(question to answer)
        while (history.size > TALK_MEMORY_TURNS) history.removeFirst()
    }

    override fun close() {
        (local as? AutoCloseable)?.close()
    }
}
