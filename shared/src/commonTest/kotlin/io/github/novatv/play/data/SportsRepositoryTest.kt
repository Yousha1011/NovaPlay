package io.github.novatv.play.data

import io.github.novatv.play.sports.League
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SportsRepositoryTest {
    private val football = League("eng.1", Sport.FOOTBALL, "Premier League")
    private val cricket = League("cricket", Sport.CRICKET, "All cricket")
    private fun event(id: String, league: League, start: Long) =
        SportEvent(id, league, null, "Match $id", start, State.PRE, "", null, null, emptyList(), null)

    private fun TestScope.repo(fetch: (League, Int) -> List<SportEvent>, leagues: List<League> = listOf(football, cricket), clock: () -> Long = { 1_000_000L }) =
        SportsRepository(this, fetch, followed = { leagues }, catalog = { leagues }, clock = clock)

    @Test fun refreshMergesSortsAndDeduplicates() = runTest {
        val r = repo({ l, _ ->
            if (l == football) listOf(event("b", l, 300), event("a", l, 100)) else listOf(event("a", l, 100), event("c", l, 200))
        })
        r.refresh(0)
        advanceUntilIdle()
        assertEquals(listOf("a", "c", "b"), r.state.value.events.map { it.id })
        assertFalse(r.state.value.loading)
        assertEquals(1_000_000L, r.state.value.updatedAt)
    }

    @Test fun refreshIsSkippedWhileFresh() = runTest {
        var calls = 0
        var now = 1_000_000L
        val r = repo({ _, _ -> calls++; emptyList() }, listOf(football), clock = { now })
        r.refresh(0); advanceUntilIdle()
        assertEquals(1, calls)
        now += 30_000
        r.refresh(60_000); advanceUntilIdle()
        assertEquals(1, calls, "a load younger than the minimum age is reused")
        now += 31_000
        r.refresh(60_000); advanceUntilIdle()
        assertEquals(2, calls)
    }

    @Test fun followedLeaguesUseAWeekAndCatalogTwoWeeks() = runTest {
        val days = mutableListOf<Int>()
        val r = repo({ _, d -> days += d; emptyList() }, listOf(football))
        r.refresh(0); advanceUntilIdle()
        r.catalog(Sport.FOOTBALL)
        assertEquals(listOf(7, 14), days)
    }

    @Test fun catalogKeepsOneSportAndIsCachedForAnHour() = runTest {
        var calls = 0
        var now = 1_000_000L
        val r = repo({ l, _ -> calls++; listOf(event(l.id, l, 0)) }, clock = { now })
        assertEquals(listOf("cricket"), r.catalog(Sport.CRICKET).map { it.id })
        r.catalog(Sport.CRICKET)
        assertEquals(1, calls)
        now += 3_600_001L
        r.catalog(Sport.CRICKET)
        assertEquals(2, calls)
    }
}
