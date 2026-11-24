package com.asistente.personal

import com.asistente.personal.state.Message
import com.asistente.personal.state.Role
import com.asistente.personal.net.DeckAction
import com.asistente.personal.net.MediaInfo
import com.asistente.personal.ui.KeyState
import com.asistente.personal.ui.keyState
import com.asistente.personal.ui.lowBattery
import com.asistente.personal.ui.recentChat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTest {
    @Test fun `la conversacion reciente deja fuera los avisos internos y se queda con lo ultimo`() {
        val all = (1..10).map { Message(it, if (it % 2 == 0) Role.ASSISTANT else Role.USER, "m$it") } + Message(11, Role.NOTE, "interno")
        val shown = recentChat(all, 4)
        assertEquals(listOf("m7", "m8", "m9", "m10"), shown.map { it.text })
        assertEquals(emptyList<Message>(), recentChat(emptyList()))
        assertArrayEquals(arrayOf(Role.USER, Role.ASSISTANT), recentChat(all.take(2)).map { it.role }.toTypedArray())
    }

    private fun key(id: String, label: String, icon: String = "app") = DeckAction(id, label, "", icon, "orange", false, quick = true)
    private fun song(app: String = "Spotify", playing: Boolean = true) = MediaInfo(
        app = app, title = "t", artist = "a", album = "", playing = playing, position = 0.0, duration = 10.0, art = "",
        canPrevious = true, canNext = true,
    )

    @Test fun `la tecla de reproducir dice lo que hara y se enciende mientras suena`() {
        val play = key("play", "Play / Pausa", icon = "play")
        assertEquals(KeyState("Play / Pausa", false), keyState(play, null)) // nada suena: como siempre
        assertEquals(KeyState("Pausa", true), keyState(play, song(playing = true)))
        assertEquals(KeyState("Play", false), keyState(play, song(playing = false)))
    }

    @Test fun `la tecla de la app que suena se enciende y las demas no`() {
        assertEquals(KeyState("Spotify", true), keyState(key("spotify", "Spotify"), song("Spotify")))
        assertEquals(KeyState("Spotify", true), keyState(key("spotify", "Spotify"), song("spotify.exe"))) // el nombre del proceso lleva la extensi\u00f3n
        assertEquals(KeyState("Spotify", false), keyState(key("spotify", "Spotify"), song("Spotify", playing = false))) // en pausa, no
        assertEquals(KeyState("Discord", false), keyState(key("discord", "Discord"), song("Spotify")))
        assertEquals(KeyState("PC", false), keyState(key("pc", "PC"), song("Spotify PC"))) // un nombre de 2 letras no coincide por casualidad
        assertEquals(KeyState("Grabar", false), keyState(key("grabar", "Grabar", icon = "record"), null))
    }

    @Test fun `la bateria baja avisa solo sin cargador`() {
        assertTrue(lowBattery(15, charging = false))
        assertTrue(lowBattery(3, false))
        assertTrue(!lowBattery(16, false))
        assertTrue(!lowBattery(10, charging = true))
        assertTrue(!lowBattery(-1, false)) // sin lectura no se avisa
    }
}
