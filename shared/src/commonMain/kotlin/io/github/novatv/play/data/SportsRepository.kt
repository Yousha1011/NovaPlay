package io.github.novatv.play.data

import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.Espn
import io.github.novatv.play.sports.League
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * The Sports feed: the followed leagues' matches (refreshed on request, at most as often as asked) and, per sport, two
 * weeks of matches for picking competitions and clubs. Feeds, league choice and clock are passed in, so tests can
 * stand in for ESPN.
 */
class SportsRepository(
    private val scope: CoroutineScope,
    private val fetch: (League, Int) -> List<SportEvent> = { l, days -> Espn.fetch(l, days) },
    private val followed: () -> List<League> = { io.github.novatv.play.sports.Follow.leaguesToFetch() },
    private val catalog: () -> List<League> = { Catalog.leagues },
    private val clock: () -> Long = { nowMs() },
) {
    data class SportsState(val events: List<SportEvent> = emptyList(), val loading: Boolean = false, val updatedAt: Long = 0L)

    private val _state = MutableStateFlow(SportsState())
    val state: StateFlow<SportsState> = _state
    private var job: Job? = null

    /** Reloads the followed leagues' matches; skipped while a load runs or when the last one is younger than [minAgeMs]. */
    fun refresh(minAgeMs: Long = 60_000L) {
        if (job?.isActive == true) return
        if (clock() - _state.value.updatedAt < minAgeMs) return
        job = scope.launch {
            _state.update { it.copy(loading = true) }
            val all = fetchAll(followed(), 7)
            _state.value = SportsState(all.distinctBy { it.id }.sortedBy { it.start }, false, clock())
        }
    }

    private val cache = SafeMap<Sport, Pair<Long, List<SportEvent>>>()

    /** Two weeks of [sport]'s matches (cached for an hour). */
    suspend fun catalog(sport: Sport): List<SportEvent> = withContext(IO) {
        cache[sport]?.let { if (clock() - it.first < 3600_000L) return@withContext it.second }
        val list = fetchAll(catalog().filter { it.sport == sport }, 14)
        cache[sport] = clock() to list
        list
    }

    /** At most six feeds at a time. */
    private suspend fun fetchAll(leagues: List<League>, days: Int): List<SportEvent> = coroutineScope {
        val gate = Semaphore(6)
        leagues.map { l -> async { gate.withPermit { fetch(l, days) } } }.awaitAll().flatten()
    }
}
