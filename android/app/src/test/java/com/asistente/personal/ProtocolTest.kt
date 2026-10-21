package com.asistente.personal

import com.asistente.personal.audio.rmsPcm16
import com.asistente.personal.net.AssistantState
import com.asistente.personal.net.BASE_DELAY_MS
import com.asistente.personal.net.MAX_DELAY_MS
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
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
