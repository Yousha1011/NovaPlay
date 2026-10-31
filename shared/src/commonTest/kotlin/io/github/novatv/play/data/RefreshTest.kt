package io.github.novatv.play.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** When playlists and the guide update, and syncing a playlist without clearing what the screens show. */
class RefreshTest {
    private val hour = 3600_000L
    private val now = 1000 * hour

    @Test fun somethingIsDueOnceItsIntervalHasPassedOrWhenItNeverUpdated() {
        assertTrue(Refresh.due(null, 12, now), "never updated")
        assertFalse(Refresh.due(now - 11 * hour, 12, now))
        assertTrue(Refresh.due(now - 12 * hour, 12, now))
        assertFalse(Refresh.due(now - 500 * hour, 0, now), "0 is only when asked")
        assertTrue(Refresh.due(null, 0, now), "but something never loaded still loads")
        assertEquals(Long.MAX_VALUE, Refresh.maxAgeMs(0))
        assertEquals(6 * hour, Refresh.maxAgeMs(6))
    }

    @Test fun theOvernightUpdateIsTheNextOccurrenceOfItsHour() {
        val midnight = 990 * hour                 // local midnight, as dayStart gives it
        val dayStart = { t: Long -> midnight + ((t - midnight) / (24 * hour)) * 24 * hour }
        assertEquals(midnight + 3 * hour, Refresh.nextOvernight(midnight + 1 * hour, 3, dayStart), "1 am: later tonight")
        assertEquals(midnight + 27 * hour, Refresh.nextOvernight(midnight + 3 * hour, 3, dayStart), "exactly 3 am: tomorrow")
        assertEquals(midnight + 27 * hour, Refresh.nextOvernight(midnight + 22 * hour, 3, dayStart), "10 pm: tomorrow night")
    }

    @Test fun labels() {
        assertEquals("Every 6 hours", Refresh.intervalLabel(6))
        assertEquals("Every day", Refresh.intervalLabel(24))
        assertEquals("Every 2 days", Refresh.intervalLabel(48))
        assertEquals("Only when I ask", Refresh.intervalLabel(0))
        assertEquals("never", Refresh.ago(null, now))
        assertEquals("just now", Refresh.ago(now - 20_000, now))
        assertEquals("12 min ago", Refresh.ago(now - 12 * 60_000, now))
        assertEquals("3 h ago", Refresh.ago(now - 3 * hour, now))
        assertEquals("3 days ago", Refresh.ago(now - 72 * hour, now))
    }

    private class Sources(override var sources: List<Source>, override var activeSourceId: String? = null) : SourceList
    private class MemoryStore : LibraryStore {
        val saved = HashMap<String, Pair<Library, Long>>()
        override fun read(sourceId: String) = saved[sourceId]
        override fun write(sourceId: String, lib: Library) { saved[sourceId] = lib to 0L }
        override fun delete(sourceId: String) { saved.remove(sourceId) }
    }
    private class FakeProvider(var lib: Library, var fail: Exception? = null) : Provider {
        var loads = 0
        override fun load(progress: (String) -> Unit): Library { loads++; fail?.let { throw it }; return lib }
        override fun liveUrl(ch: Channel) = ""
        override fun movieUrl(m: Movie) = ""
        override fun episodeUrl(s: Series, e: Episode) = ""
        override fun movieDetails(m: Movie) = Details()
        override fun seriesDetails(s: Series) = Details()
    }
    private fun src(id: String) = Source(id, "Playlist $id", SourceType.M3U, "http://example.invalid/$id.m3u")
    private fun lib(vararg ch: String) = Library(channels = ch.mapIndexed { i, n -> Channel(n, n, null, "c", null, null, i + 1) })

    @Test fun syncingTheOpenPlaylistSwapsItsLibraryInPlaceAndRecordsTheTime() = runTest {
        val a = FakeProvider(lib("Old"))
        val b = FakeProvider(lib("Other"))
        val cache = MemoryStore()
        val state = MutableStateFlow(Repo.LibState())
        val synced = mutableListOf<Triple<String, Long, String?>>()
        val repo = CatalogRepository(this, state, Sources(listOf(src("a"), src("b"))), cache, providerOf = { if (it.id == "a") a else b },
            clock = { now }, onSynced = { id, at, e -> synced += Triple(id, at, e) })
        repo.open(src("a"), force = true); advanceUntilIdle()
        val source = state.value.source
        synced.clear()

        a.lib = lib("New")
        assertNull(repo.sync(src("a")))
        assertSame(source, state.value.source, "the open playlist stays open; nothing is cleared")
        assertEquals(listOf("New"), state.value.lib.channels.map { it.name })
        assertEquals(listOf("New"), state.value.index!!.lib.channels.map { it.name })
        assertEquals(listOf(Triple("a", now, null as String?)), synced)

        // Another playlist: saved for later, the open one untouched.
        assertNull(repo.sync(src("b")))
        assertEquals(listOf("Other"), cache.saved.getValue("b").first.channels.map { it.name })
        assertEquals(listOf("New"), state.value.lib.channels.map { it.name })
    }

    @Test fun aFailedSyncKeepsTheLibraryAndRecordsTheError() = runTest {
        val a = FakeProvider(lib("Kept"))
        val state = MutableStateFlow(Repo.LibState())
        val synced = mutableListOf<String?>()
        val repo = CatalogRepository(this, state, Sources(listOf(src("a"))), MemoryStore(), providerOf = { a }, clock = { now },
            onSynced = { _, _, e -> synced += e })
        repo.open(src("a"), force = true); advanceUntilIdle()
        a.fail = IllegalStateException("Server unreachable")
        assertEquals("Server unreachable", repo.sync(src("a")))
        assertEquals(listOf("Kept"), state.value.lib.channels.map { it.name })
        assertEquals("Server unreachable", synced.last())
    }
}
