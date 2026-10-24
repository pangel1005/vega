package com.asistente.personal

import com.asistente.personal.local.LocalAssistant
import com.asistente.personal.local.LocalMemory
import com.asistente.personal.local.ModelFile
import com.asistente.personal.local.Segmenter
import com.asistente.personal.local.Skills
import com.asistente.personal.local.missingModels
import com.asistente.personal.local.parseManifest
import com.asistente.personal.local.sentences
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.ServerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.sin

/** Vega sin PC, con oído y voz de mentira: comprueba el recorrido completo de una frase. */
class LocalAssistantTest {
    private val now = LocalDateTime.of(2026, 10, 24, 15, 0)

    /** PCM16 de `ms` milisegundos: tono (como si alguien hablara) o silencio. */
    private fun pcm(ms: Int, loud: Boolean): ByteArray {
        val n = 16 * ms
        val out = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = if (loud) (sin(2 * PI * 220 * i / 16_000) * 8000).toInt() else 0
            out[2 * i] = v.toByte()
            out[2 * i + 1] = (v shr 8).toByte()
        }
        return out
    }

    private inner class Rig(
        heard: List<String>,
        private val pcAnswers: Map<String, String> = emptyMap(),
        pcSpeaks: Boolean = false,
        talker: com.asistente.personal.local.Talker? = null,
    ) {
        val pcSaid = mutableListOf<String>()
        val heardSizes = mutableListOf<Int>()
        val askedPc = mutableListOf<String>()
        val queue = ArrayDeque(heard)
        val events = mutableListOf<ServerEvent>()
        val said = mutableListOf<String>()
        var clock = 0L
        val memory = LocalMemory(null)
        val vega = LocalAssistant(
            name = "Vega",
            skills = Skills(memory, { now }),
            memory = memory,
            ears = { heardSizes += it.size; queue.removeFirst() },
            mouth = { text, onAudio -> said += text; onAudio(ByteArray(4), 22_050) },
            events = { events += it },
            audio = { _, _ -> },
            clock = { clock },
            now = { now },
            runner = { it.run() }, // sin hilo: la prueba ve el resultado al momento
            pc = { askedPc += it; pcAnswers[it] },
            pcVoice = { if (pcSpeaks) pcSaid += it; pcSpeaks },
            talker = talker,
        )

        /** Alguien habla 5 s seguidos (charla, la tele…). */
        fun longPhrase() {
            vega.hear(pcm(500, false))
            vega.hear(pcm(5000, true))
            vega.hear(pcm(700, false))
        }

        /** Medio segundo de calma, una frase de 1 s y silencio para cerrarla. */
        fun phrase() {
            vega.hear(pcm(500, false))
            vega.hear(pcm(1000, true))
            vega.hear(pcm(700, false))
        }
    }

    @Test fun elSegmentadorEntregaUnaFrase() {
        val s = Segmenter()
        assertTrue(s.feed(pcm(500, false)).isEmpty())
        assertTrue(s.feed(pcm(1000, true)).isEmpty())
        val done = s.feed(pcm(700, false))
        assertEquals(1, done.size)
        assertTrue(done[0].size / 32 in 1000..1800) // ~1 s de voz + margen y silencio final, en ms
    }

    @Test fun contestaCuandoLaLlaman() {
        val rig = Rig(listOf("Vega, ¿qué hora es?"))
        rig.phrase()
        assertEquals(listOf("Son las tres de la tarde."), rig.said)
        assertTrue(rig.events.any { it is ServerEvent.Done && it.text == "Son las 15:00." })
        assertEquals(ServerEvent.State(AssistantState.IDLE), rig.events.last())
    }

    @Test fun ignoraLoQueNoEsParaElla() {
        val rig = Rig(listOf("y entonces le dije que no"))
        rig.phrase()
        assertTrue(rig.said.isEmpty())
        assertTrue(rig.events.isEmpty()) // ni siquiera se muestra lo que se oyó
    }

    @Test fun siguesSinRepetirSuNombre() {
        val rig = Rig(listOf("Vega", "apunta pan", "apunta leche"))
        rig.phrase()
        assertEquals(listOf("¿Sí?"), rig.said)
        rig.vega.playbackDone()
        rig.clock = 3_000
        rig.phrase()
        assertEquals("Apuntado: pan.", rig.said.last())
        rig.vega.playbackDone()
        rig.clock = 3_000 + 9_000 // pasaron más de 8 s: vuelve a hacer falta el nombre
        rig.phrase()
        assertEquals(2, rig.said.size)
    }

    @Test fun loQueNoSabeLoDice() {
        val rig = Rig(listOf("Vega, ¿cuál es la capital de Francia?"))
        rig.phrase()
        assertEquals(Skills.UNKNOWN, (rig.events.first { it is ServerEvent.Done } as ServerEvent.Done).text)
    }

    @Test fun avisaDeLosRecordatoriosVencidos() {
        val rig = Rig(emptyList())
        rig.memory.addReminder("timer", "temporizador de 5 minutos", now.minusSeconds(1))
        rig.vega.tick()
        assertEquals(listOf("¡Tiempo!", "Terminó el temporizador de 5 minutos."), rig.said)
        assertTrue(rig.events.first() is ServerEvent.Reminder)
        assertTrue(rig.memory.pending().isEmpty())
    }

    @Test fun cortaEnFrases() {
        assertEquals(listOf("Vale.", "Te recuerdo: pan."), sentences("Vale. Te recuerdo: pan."))
    }

    @Test fun bajaSoloLosModelosQueFaltan() {
        val dir = Files.createTempDirectory("modelos").toFile()
        File(dir, "whisper-base").mkdirs()
        File(dir, "whisper-base/a.onnx").writeBytes(ByteArray(10))
        File(dir, "whisper-base/b.onnx").writeBytes(ByteArray(3)) // a medias
        val files = parseManifest("""{"files":[{"path":"whisper-base/a.onnx","size":10},{"path":"whisper-base/b.onnx","size":9},{"path":"piper/c.onnx","size":5}]}""")
        assertEquals(listOf(ModelFile("whisper-base/b.onnx", 9), ModelFile("piper/c.onnx", 5)), missingModels(dir, files))
    }

    @Test fun loDelPcLoResuelveElPc() {
        val rig = Rig(listOf("Vega, abre Spotify", "Vega, ¿qué hora es?"), mapOf("abre Spotify" to "Abriendo Spotify."))
        rig.phrase()
        assertEquals("Abriendo Spotify.", rig.said.last())
        rig.phrase() // el PC no sabe la hora sin su modelo: la dice el teléfono
        assertEquals("Son las tres de la tarde.", rig.said.last())
        assertEquals(listOf("abre Spotify", "¿qué hora es?"), rig.askedPc)
    }

    @Test fun conPcHablaConLaVozDelPc() {
        val rig = Rig(listOf("Vega, ¿qué hora es?"), pcSpeaks = true)
        rig.phrase()
        assertEquals(listOf("Son las 15:00."), rig.pcSaid) // el PC lo lee a su manera (Kokoro)
        assertTrue(rig.said.isEmpty()) // Piper no habla
    }

    @Test fun deLasFrasesLargasMiraSoloElPrincipio() {
        val rig = Rig(listOf("y entonces fuimos al cine"))
        rig.longPhrase()
        assertEquals(listOf(com.asistente.personal.local.PEEK_SAMPLES), rig.heardSizes) // solo el principio
        assertTrue(rig.events.isEmpty())
    }

    @Test fun siLaLlamanEnUnaFraseLargaLaTranscribeEntera() {
        val rig = Rig(listOf("Vega, recuérdame", "Vega, recuérdame llamar a mamá en 20 minutos"))
        rig.longPhrase()
        assertEquals(2, rig.heardSizes.size)
        assertTrue(rig.heardSizes[1] > com.asistente.personal.local.PEEK_SAMPLES)
        assertEquals("Vale, en 20 minutos te recuerdo: llamar a mamá.", (rig.events.first { it is ServerEvent.Done } as ServerEvent.Done).text)
    }

    @Test fun lasOrdenesCortasNoEsperan() {
        val rig = Rig(listOf("Vega, ¿qué hora es?"))
        rig.phrase()
        assertEquals(1, rig.heardSizes.size) // una sola pasada: sin retraso extra
    }

    @Test fun enElSeguimientoLoQueNoEntiendeSeCalla() {
        // Tras contestar, oye la tele: no es una orden y no debe decir «no sé» (abría un bucle).
        val rig = Rig(listOf("Vega, ¿qué hora es?", "y el partido terminó empatado"))
        rig.phrase()
        rig.vega.playbackDone()
        rig.phrase()
        assertEquals(1, rig.said.size)
        assertTrue(rig.events.none { it is ServerEvent.Done && it.text == Skills.UNKNOWN })
    }

    @Test fun noSeContestaASiMisma() {
        // Su respuesta vuelve por el micrófono justo después de decirla.
        // El PC «resolvería» lo que le llegue: sin el filtro de eco, se contestaría a sí misma.
        val rig = Rig(listOf("Vega, ¿qué hora es?", "son las tres de la tarde"), mapOf("son las tres de la tarde" to "Hecho."))
        rig.phrase()
        rig.vega.playbackDone()
        rig.phrase()
        assertEquals(1, rig.said.size)
        assertEquals(listOf("¿qué hora es?"), rig.askedPc) // el eco ni siquiera llega al PC
    }

    @Test fun siTocasLaCaraYNoEntiendeLoDice() {
        val rig = Rig(listOf("¿cuál es la capital de Francia?"))
        rig.vega.attention()
        rig.phrase()
        assertEquals(Skills.UNKNOWN, (rig.events.last { it is ServerEvent.Done } as ServerEvent.Done).text)
    }

    /** Modelo de mentira: escribe el texto trozo a trozo, como llama.cpp. */
    private fun fakeTalker(text: String?) = com.asistente.personal.local.Talker { _, onText ->
        if (text == null) return@Talker null
        val out = StringBuilder()
        for (piece in Regex("\\S+\\s*").findAll(text).map { it.value }) {
            out.append(piece)
            if (!onText(piece)) break
        }
        out.toString()
    }

    @Test fun charlaConElModeloDelTelefonoFraseAFrase() {
        val rig = Rig(
            listOf("Vega, ¿qué es un volcán?"),
            talker = fakeTalker("Un volcán es una montaña que expulsa lava. Sale del interior de la Tierra. Hay muchos en Chile. Y en Japón."),
        )
        rig.phrase()
        // Dice cada frase según la escribe, y corta a las tres.
        assertEquals(listOf("Un volcán es una montaña que expulsa lava.", "Sale del interior de la Tierra.", "Hay muchos en Chile."), rig.said)
        assertTrue(rig.events.any { it is ServerEvent.Token })
        assertEquals(ServerEvent.State(AssistantState.IDLE), rig.events.last())
    }

    @Test fun siElModeloNoPuedeLoDice() {
        val rig = Rig(listOf("Vega, ¿qué es un volcán?"), talker = fakeTalker(null))
        rig.phrase()
        assertEquals(Skills.UNKNOWN, (rig.events.last { it is ServerEvent.Done } as ServerEvent.Done).text)
    }

    @Test fun lasHabilidadesVanAntesQueLaCharla() {
        val rig = Rig(listOf("Vega, ¿qué hora es?"), talker = fakeTalker("No lo sé."))
        rig.phrase()
        assertEquals(listOf("Son las tres de la tarde."), rig.said)
    }
}
