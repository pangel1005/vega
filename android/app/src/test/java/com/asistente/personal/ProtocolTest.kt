package com.asistente.personal

import com.asistente.personal.audio.rmsPcm16
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.BASE_DELAY_MS
import com.asistente.personal.net.MAX_DELAY_MS
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.net.Upcoming
import com.asistente.personal.net.Voice
import com.asistente.personal.net.backoffDelay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtocolTest {
    @Test fun `interpreta los eventos del servidor`() {
        assertEquals(ServerEvent.Ready("Vega", "qwen3.5:4b"), Protocol.parse("""{"type":"ready","assistant":"Vega","model":"qwen3.5:4b"}"""))
        assertEquals(ServerEvent.State(AssistantState.THINKING), Protocol.parse("""{"type":"state","state":"thinking"}"""))
        assertEquals(
            ServerEvent.Transcript("hola", 1.5, 180),
            Protocol.parse("""{"type":"transcript","text":"hola","audio_seconds":1.5,"elapsed_ms":180}"""),
        )
        assertEquals(ServerEvent.Token("Ho"), Protocol.parse("""{"type":"token","text":"Ho"}"""))
    }

    @Test fun `interpreta voz y voces`() {
        assertEquals(ServerEvent.Audio(1, 22050, "Hola."), Protocol.parse("""{"type":"audio","seq":1,"rate":22050,"text":"Hola."}"""))
        assertEquals(ServerEvent.AudioEnd, Protocol.parse("""{"type":"audio_end"}"""))
        assertEquals(
            ServerEvent.Voices(listOf(Voice("es_MX-claude-high", "Claude · México")), "es_MX-claude-high"),
            Protocol.parse("""{"type":"voices","items":[{"id":"es_MX-claude-high","label":"Claude · México"}],"current":"es_MX-claude-high"}"""),
        )
        val msg = org.json.JSONObject(Protocol.setVoice("x")) // el orden de las claves no importa
        assertEquals("set_voice", msg.getString("type"))
        assertEquals("x", msg.getString("voice"))
    }

    @Test fun `interpreta avisos de recordatorio`() {
        assertEquals(
            ServerEvent.Reminder("timer", "¡Tiempo!"),
            Protocol.parse("""{"type":"reminder","kind":"timer","text":"¡Tiempo!"}"""),
        )
    }

    @Test fun `interpreta la telemetría del PC`() {
        val e = Protocol.parse(
            """{"type":"telemetry","cpu":21,"ram_used":23.6,"ram_total":31.9,"gpu_temp":62,"gpu_util":52,""" +
                """"vram_used":7.0,"vram_total":8.0,"upcoming":[{"kind":"timer","text":"temporizador","due_in":299}]}""",
        ) as ServerEvent.Stats
        assertEquals(21, e.telemetry.cpu)
        assertEquals(62, e.telemetry.gpuTemp)
        assertEquals(listOf(Upcoming("timer", "temporizador", 299)), e.telemetry.upcoming)
        // Sin gráfica NVIDIA: esos campos llegan vacíos.
        val noGpu = Protocol.parse(
            """{"type":"telemetry","cpu":3,"ram_used":1.0,"ram_total":2.0,"gpu_temp":null,"vram_used":null,"vram_total":null,"upcoming":[]}""",
        ) as ServerEvent.Stats
        assertNull(noGpu.telemetry.gpuTemp)
        assertNull(noGpu.telemetry.vramTotal)
    }

    @Test fun `interpreta lo que suena en el PC`() {
        val e = Protocol.parse(
            """{"type":"media","active":true,"app":"Spotify","title":"Human Nature","artist":"Madonna",""" +
                """"album":"Bedtime Stories","playing":true,"position":197.0,"duration":293.9,"art":"abc",""" +
                """"can_previous":true,"can_next":true}""",
        ) as ServerEvent.Media
        assertEquals("Human Nature", e.media?.title)
        assertEquals(197.0, e.media!!.position, 0.01)
        assertNull((Protocol.parse("""{"type":"media","active":false}""") as ServerEvent.Media).media)
        val msg = org.json.JSONObject(Protocol.mediaControl("next"))
        assertEquals("media_control", msg.getString("type"))
        assertEquals("next", msg.getString("command"))
        assertEquals("3:17", com.asistente.personal.ui.clockTime(197.4))
    }

    @Test fun `cuenta atrás legible`() {
        assertEquals("04:59", com.asistente.personal.ui.countdown(299))
        assertEquals("1:00:05", com.asistente.personal.ui.countdown(3605))
        assertEquals("00:00", com.asistente.personal.ui.countdown(-4))
    }

    @Test fun `ignora lo desconocido o roto`() {
        assertNull(Protocol.parse("basura"))
        assertNull(Protocol.parse("""{"type":"desconocido"}"""))
        assertNull(Protocol.parse("""{"type":"state","state":"bailando"}"""))
    }

    @Test fun `mensajes del cliente`() {
        assertEquals("""{"type":"hello","token":"t"}""", Protocol.hello("t"))
        assertEquals("""{"type":"listen_start"}""", Protocol.simple("listen_start"))
    }

    @Test fun `espera creciente con tope`() {
        assertEquals(BASE_DELAY_MS, backoffDelay(0, 0.5))
        assertEquals(BASE_DELAY_MS * 8, backoffDelay(3, 0.5))
        assertEquals(MAX_DELAY_MS, backoffDelay(20, 0.5))
        assertEquals(1600L, backoffDelay(2, 0.0))
        assertEquals(2400L, backoffDelay(2, 1.0))
    }

    @Test fun `volumen de un bloque pcm16`() {
        assertEquals(0f, rmsPcm16(ByteArray(10), 10), 1e-6f)
        val max = byteArrayOf(0xFF.toByte(), 0x7F, 0x00, 0x80.toByte()) // 32767, -32768
        assertEquals(1f, rmsPcm16(max, 4), 1e-3f)
    }
}
