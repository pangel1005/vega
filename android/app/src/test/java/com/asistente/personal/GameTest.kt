package com.asistente.personal

import com.asistente.personal.net.GameInfo
import com.asistente.personal.net.LinkStatus
import com.asistente.personal.net.MatchInfo
import com.asistente.personal.net.Protocol
import com.asistente.personal.net.ServerEvent
import com.asistente.personal.state.Action
import com.asistente.personal.state.UiState
import com.asistente.personal.state.reduce
import com.asistente.personal.ui.GameSample
import com.asistente.personal.ui.GameSection
import com.asistente.personal.ui.SessionStats
import com.asistente.personal.ui.agoLabel
import com.asistente.personal.ui.friendsLabel
import com.asistente.personal.ui.gameSections
import com.asistente.personal.ui.hoursLabel
import com.asistente.personal.ui.matchSeconds
import com.asistente.personal.ui.playersLabel
import com.asistente.personal.ui.sessionClock
import com.asistente.personal.ui.sessionSeconds
import com.asistente.personal.ui.sessionStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** El juego abierto en el PC: lo que dice el PC y cómo se cuenta la sesión (la pantalla está en Screens2ShotTest). */
class GameTest {
    private val game = GameInfo(
        key = "steam:2767030", name = "Marvel Rivals", source = "Steam", played = 3_900.0, hours = 268.2, sizeGb = 92.1,
        art = "steam-2767030",
        summary = "¡Marvel Rivals es un juego de disparos de JcJ por equipos de superhéroes! Reúne un equipo de personajes de Marvel y combina sus poderes.",
        genres = listOf("Acción", "Aventura"), developer = "NetEase Games", released = "5 DIC 2024", rating = 79,
        tags = listOf("Multijugador", "JcJ"),
    )

    // --- Lo que se cuenta ---

    @Test fun theSessionKeepsCountingOnThePhone() {
        // El PC dijo «lleva 3 900 s» hace 90 s: ahora van 3 990.
        assertEquals(3_990, sessionSeconds(game, receivedAt = 10_000, now = 100_000))
        assertEquals(3_900, sessionSeconds(game, receivedAt = 10_000, now = 9_000)) // un reloj atrasado no resta
    }

    @Test fun timesAreWrittenForPeople() {
        assertEquals("4:09", sessionClock(249))
        assertEquals("1:05:09", sessionClock(3_909))
        assertEquals("45 min", hoursLabel(0.75))
        assertEquals("7,5 h", hoursLabel(7.5))
        assertEquals("268 h", hoursLabel(268.2))
    }

    @Test fun phrasesForPeople() {
        assertEquals("54 309", playersLabel(54_309))
        assertEquals("1 234 567", playersLabel(1_234_567))
        val now = 1_000_000L
        assertEquals("hoy", agoLabel(now - 3_600.0, now))
        assertEquals("ayer", agoLabel(now - 100_000.0, now))
        assertEquals("hace 4 días", agoLabel(now - 4 * 86_400.0, now))
        assertEquals("hace 3 semanas", agoLabel(now - 22 * 86_400.0, now))
        assertEquals("hace 5 meses", agoLabel(now - 150 * 86_400.0, now))
        assertEquals("hoy", agoLabel(now + 500.0, now)) // un reloj adelantado no da días negativos
        assertEquals("", friendsLabel(emptyList()))
        assertEquals("Ana está jugando", friendsLabel(listOf("Ana")))
        assertEquals("Ana y Beto están jugando", friendsLabel(listOf("Ana", "Beto")))
        assertEquals("Ana, Beto y 2 más están jugando", friendsLabel(listOf("Ana", "Beto", "Cris", "Dani")))
    }

    @Test fun theSessionStatsSummarizeTheReadings() {
        assertEquals(SessionStats(null, null, null), sessionStats(emptyList()))
        val s = sessionStats(listOf(GameSample(90, 70, 6.0, 30), GameSample(100, 78, 6.4, 40), GameSample(null, null, null, 20)))
        assertEquals(SessionStats(gpuAvg = 95, tempMax = 78, vramMax = 6.4), s)
    }

    @Test fun sectionsAppearOnlyWhenTheyHaveSomething() {
        val bare = game.copy(players = null, news = emptyList(), friends = emptyList(), achievements = null, achUnlocked = null)
        assertEquals(listOf(GameSection.SESSION, GameSection.GAME), gameSections(bare))
        assertEquals(
            listOf(GameSection.SESSION, GameSection.GAME, GameSection.COMMUNITY, GameSection.ACHIEVEMENTS),
            gameSections(bare.copy(players = 10, achievements = 49)),
        )
    }

    @Test fun theGameIsForgottenWhenTheLinkIsLost() {
        var s = reduce(UiState(link = LinkStatus.OPEN), Action.Game(game, at = 7))
        assertEquals(game, s.game)
        assertEquals(7L, s.gameAt)
        s = reduce(s, Action.Link(LinkStatus.RECONNECTING))
        assertNull(s.game) // sin enlace no se sabe si sigue abierto
    }

    // --- La partida en curso ---

    private val lol = MatchInfo(
        title = "Clásica · Grieta del Invocador", score = "9 – 9", clock = 1_472.0, clockStep = 1,
        stats = listOf("Campeón" to "Ahri", "Nivel" to "11", "K / D / A" to "5 / 2 / 7", "Súbditos" to "144 · 5,9/min", "Oro" to "8 540"),
        feed = listOf("Torre destruida", "Dragón", "Yo#EUW eliminó a Rival1"), hint = "",
        colors = listOf("#3DA9FF", "#FF4D5E"),
    )
    private val rocket = MatchInfo(
        title = "Partida en curso", score = "2 – 1", clock = 134.0, clockStep = -1,
        stats = listOf("Puntos" to "410", "Goles" to "2", "Asistencias" to "1", "Paradas" to "3", "Tiros" to "5", "Demoliciones" to "1"),
        feed = listOf("Yo: Save", "¡Gol de Yo!"), hint = "",
        colors = listOf("#3D8BFF", "#FF8A3D"),
    )

    @Test fun theMatchClockKeepsRunningOnThePhoneInTheRightDirection() {
        assertEquals(1_502L, matchSeconds(lol, receivedAt = 0, now = 30_000)) // League sube
        assertEquals(104L, matchSeconds(rocket, receivedAt = 0, now = 30_000)) // Rocket League baja
        assertEquals(0L, matchSeconds(rocket, receivedAt = 0, now = 500_000)) // sin pasarse de cero
        assertEquals(134L, matchSeconds(rocket.copy(clockStep = 0), receivedAt = 0, now = 30_000)) // repetición: parado
        assertNull(matchSeconds(lol.copy(clock = null), 0, 1_000))
    }

    @Test fun theMatchComesFirstWhenThereIsOne() {
        assertEquals(GameSection.MATCH, gameSections(game.copy(match = lol)).first())
        assertEquals(GameSection.SESSION, gameSections(game).first())
    }

    @Test fun theMatchIsReadFromTheEvent() {
        val e = Protocol.parse(
            """{"type":"game","active":true,"key":"riot:lol","name":"League of Legends","source":"Riot","played":5.0,""" +
                """"match":{"title":"Clásica","score":"9 – 9","clock":1472.5,"clock_step":1,""" +
                """"stats":[{"label":"Oro","value":"8 540"}],"feed":["Dragón"],"hint":""}}""",
        ) as ServerEvent.Game
        val m = e.game!!.match!!
        assertEquals("9 – 9", m.score)
        assertEquals(1, m.clockStep)
        assertEquals("Oro" to "8 540", m.stats.single())
        assertEquals(listOf("Dragón"), m.feed)
        // Sin API de partida (o sin partida): el campo no está y nada se rompe.
        assertNull((Protocol.parse("""{"type":"game","active":true,"key":"k","name":"X","source":"Steam","played":1}""") as ServerEvent.Game).game!!.match)
    }

    @Test fun teamColorsComeFromTheServer() {
        val e = Protocol.parse(
            """{"type":"game","active":true,"key":"k","name":"X","source":"Steam","played":1,""" +
                """"match":{"title":"t","score":"2 – 1","colors":["#3D8BFF","#FF8A3D"]}}""",
        ) as ServerEvent.Game
        assertEquals(listOf("#3D8BFF", "#FF8A3D"), e.game!!.match!!.colors)
    }
}
