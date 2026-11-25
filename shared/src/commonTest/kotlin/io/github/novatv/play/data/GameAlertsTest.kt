package io.github.novatv.play.data

import io.github.novatv.play.sports.League
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Game notifications: 30 minutes before and at kick-off, for games still to come in the next 3 days. */
class GameAlertsTest {
    private val league = League("eng.1", Sport.FOOTBALL, "Premier League")
    private fun game(id: String, start: Long, state: State = State.PRE) =
        SportEvent(id, league, null, "Arsenal v Chelsea $id", start, state, "", null, null, emptyList(), null)

    @Test fun beforeAndAtStart() {
        val now = 1_000_000_000_000L
        val a = GameAlerts.plan(listOf(game("1", now + 2 * 3_600_000L)), now, before = true, start = true)
        assertEquals(listOf("before:1", "start:1"), a.map { it.id })
        assertEquals(now + 90 * 60_000L, a[0].at)
        assertTrue(a[0].body.startsWith("Starts in 30 minutes"))
        assertTrue(a[1].body.startsWith("Starting now"))
    }

    @Test fun switchesAndLimits() {
        val now = 1_000_000_000_000L
        val games = listOf(
            game("soon", now + 10 * 60_000L),          // too late for "30 minutes before"
            game("live", now - 60_000L, State.LIVE),   // already on: nothing
            game("far", now + 4 * 86_400_000L),        // beyond 3 days: nothing
        )
        assertEquals(listOf("start:soon"), GameAlerts.plan(games, now, before = true, start = true).map { it.id })
        assertEquals(emptyList(), GameAlerts.plan(games, now, before = true, start = false).map { it.id })
        val many = (1..100).map { game("g$it", now + it * 60 * 60_000L) }
        assertEquals(GameAlerts.MAX, GameAlerts.plan(many, now, before = true, start = true).size)
    }
}
