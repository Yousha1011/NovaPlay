package io.github.novatv.play.sports

import io.github.novatv.play.platform.Time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** My Sports: a section per followed competition (A to Z), its picked teams only, soonest first, and the day tags. */
class MySportsTest {
    private val pl = Catalog.league("eng.1")!!
    private val ucl = Catalog.league("uefa.champions")!!
    private val laliga = Catalog.league("esp.1")!!
    private val now = Time.dayStart(1_800_000_000_000L) + 12 * 3_600_000L // noon, local
    private val hour = 3_600_000L
    private val day = 86_400_000L

    private fun side(id: String, name: String) = Side(id, name, name, name.take(3), null, null)
    private fun game(id: String, l: League, start: Long, home: String, away: String, state: State = State.PRE) =
        SportEvent(id, l, null, "$home v $away", start, state, "", side(home, home), side(away, away), emptyList(), null)

    @Test fun sectionPerCompetitionAToZSoonestFirst() {
        val events = listOf(
            game("1", pl, now + 5 * hour, "Arsenal", "Chelsea"),
            game("2", pl, now + 2 * hour, "Spurs", "Fulham"),
            game("3", ucl, now + 30 * hour, "Real", "Bayern"),
            game("4", laliga, now + 3 * hour, "Getafe", "Elche"), // not followed
        )
        val s = MySports.sections(events, listOf(pl, ucl), { emptyList() }, { null }, now)
        assertEquals(listOf("Champions League", "Premier League"), s.map { it.league.name })
        assertEquals(listOf("2", "1"), s[1].games.map { it.id })
    }

    @Test fun pickedTeamsNarrowTheirCompetitionOnly() {
        val events = listOf(
            game("1", pl, now + 5 * hour, "Arsenal", "Chelsea"),
            game("2", pl, now + 2 * hour, "Spurs", "Fulham"),
            game("3", ucl, now + 30 * hour, "Real", "Bayern"),
        )
        val picks = mapOf("eng.1" to listOf("Arsenal" to "Arsenal"))
        val s = MySports.sections(events, listOf(pl, ucl), { picks[it].orEmpty() }, { null }, now)
        assertEquals(listOf("3"), s.first { it.league.id == "uefa.champions" }.games.map { it.id })
        assertEquals(listOf("1"), s.first { it.league.id == "eng.1" }.games.map { it.id })
    }

    @Test fun liveFirstByTimeFinishedAndFarOffLeftOut() {
        val events = listOf(
            game("live", pl, now - hour, "A", "B", State.LIVE),
            game("done", pl, now - 3 * hour, "C", "D", State.POST),
            game("far", pl, now + 20 * day, "E", "F"),
            game("soon", pl, now + hour, "G", "H"),
        )
        val s = MySports.sections(events, listOf(pl), { emptyList() }, { null }, now)
        assertEquals(listOf("live", "soon"), s.single().games.map { it.id })
    }

    @Test fun tags() {
        assertEquals(MySports.Tag.LIVE, MySports.tag(game("a", pl, now - hour, "A", "B", State.LIVE), now))
        assertEquals(MySports.Tag.TOMORROW, MySports.tag(game("b", pl, now + day, "A", "B"), now))
        assertEquals(MySports.Tag.DATE, MySports.tag(game("c", pl, now + 2 * day, "A", "B"), now))
        assertEquals(MySports.Tag.DATE, MySports.tag(game("d", pl, now + 2 * hour, "A", "B"), now))
        assertEquals("TOMORROW", MySports.tagText(game("b", pl, now + day, "A", "B"), now))
    }

    @Test fun tomorrowAndLaterInAmPm() {
        val t = MySports.timeText(game("b", pl, now + day + 2 * hour, "A", "B"), now)
        assertTrue(t.endsWith("AM") || t.endsWith("PM"), t)
    }

    @Test fun unidentifiedGamesHaveUnknownSides() {
        val g = UnidentifiedGame(ucl, now + hour, now + 3 * hour, "UEFA Champions League", emptyList()).asEvent(now)
        assertEquals("? Team 1", g.home?.name); assertEquals("? Team 2", g.away?.name)
        assertEquals(State.PRE, g.state)
        assertEquals(State.LIVE, UnidentifiedGame(ucl, now - hour, now + hour, "Champions League", emptyList()).asEvent(now).state)
    }

    @Test fun liveTabIsOnNowAndWithinTheHour() {
        val events = listOf(
            game("on", pl, now - hour, "A", "B", State.LIVE),
            game("soon", pl, now + 50 * 60_000L, "C", "D"),
            game("later", pl, now + 2 * hour, "E", "F"),
            game("done", pl, now - 3 * hour, "G", "H", State.POST),
        )
        assertEquals(listOf("on", "soon"), MySports.live(events, now).map { it.id })
        assertEquals(listOf("later"), MySports.upcoming(events, now).map { it.id })
    }

    @Test fun sortedByTimeThenSportThenCompetition() {
        val nba = Catalog.league("nba")!!
        val cricket = Catalog.league("cricket")!!
        val t = now + 3 * hour
        val events = listOf(
            game("nba", nba, t, "A", "B"),
            game("pl", pl, t, "C", "D"),
            game("cricket", cricket, t, "E", "F"),
            game("ucl", ucl, t, "G", "H"),
            game("early", nba, t - 30 * 60_000L, "I", "J"),
        )
        // Earliest first; at the same time Football (Champions League, then Premier League), Cricket, Basketball.
        assertEquals(listOf("early", "ucl", "pl", "cricket", "nba"), MySports.upcoming(events, now).map { it.id })
    }

    @Test fun groupedBySportThenCompetitionFriendliesMarked() {
        val nba = Catalog.league("nba")!!
        val friendly = Catalog.league("fifa.friendly")!!
        val t = now + 3 * hour
        val events = listOf(
            game("nba", nba, t - hour, "A", "B"),
            game("pl2", pl, t + hour, "C", "D"),
            game("pl1", pl, t, "E", "F"),
            game("ucl", ucl, t + 30 * 60_000L, "G", "H"),
            game("fr", friendly, t + 2 * hour, "I", "J"),
        )
        val g = MySports.grouped(events)
        // Basketball's match is earliest, so its section comes first.
        assertEquals(listOf(Sport.BASKETBALL, Sport.FOOTBALL), g.map { it.sport })
        val football = g[1].comps
        assertEquals(listOf("Premier League", "Champions League", "International friendlies"), football.map { it.name })
        assertEquals(listOf("pl1", "pl2"), football[0].games.map { it.id })
        assertEquals(listOf(false, false, true), football.map { it.friendly })
        assertEquals(5, g.sumOf { it.count })
    }
}
