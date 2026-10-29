package io.github.novatv.play.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CatalogRepositoryTest {
    private val now = 100L * 3600_000L
    private val hour = 3600_000L

    private class Sources(override var sources: List<Source>, override var activeSourceId: String? = null) : SourceList

    private class MemoryStore : LibraryStore {
        val saved = HashMap<String, Pair<Library, Long>>()
        val deleted = mutableListOf<String>()
        override fun read(sourceId: String) = saved[sourceId]
        override fun write(sourceId: String, lib: Library) { saved[sourceId] = lib to 0L }
        override fun delete(sourceId: String) { deleted += sourceId; saved.remove(sourceId) }
    }

    /** A playlist that returns [lib] (reporting progress first), or throws [fail]. */
    private class FakeProvider(val lib: () -> Library, val fail: Throwable? = null) : Provider {
        var loads = 0
        override fun load(progress: (String) -> Unit): Library { loads++; progress("Loading channels"); fail?.let { throw it }; return lib() }
        override fun liveUrl(ch: Channel) = ""
        override fun movieUrl(m: Movie) = ""
        override fun episodeUrl(s: Series, e: Episode) = ""
        override fun movieDetails(m: Movie) = Details()
        override fun seriesDetails(s: Series) = Details()
    }

    private fun src(id: String) = Source(id, "Playlist $id", SourceType.M3U, "http://example.invalid/$id.m3u")
    private fun lib(vararg channels: String) = Library(channels = channels.mapIndexed { i, n -> Channel(n, n, null, "c", null, null, i + 1) })

    private class Setup(val repo: CatalogRepository, val state: MutableStateFlow<Repo.LibState>, val cache: MemoryStore,
                        val sources: Sources, val providers: Map<String, FakeProvider>, val afterLoads: MutableList<Boolean>)

    private fun TestScope.setup(sources: List<Source>, providers: Map<String, FakeProvider>, cache: MemoryStore = MemoryStore(),
                                state: MutableStateFlow<Repo.LibState> = MutableStateFlow(Repo.LibState())): Setup {
        val list = Sources(sources)
        val after = mutableListOf<Boolean>()
        val repo = CatalogRepository(this, state, list, cache, providerOf = { providers.getValue(it.id) }, clock = { now },
            newId = { "new" }, afterLoad = { after += it })
        return Setup(repo, state, cache, list, providers, after)
    }

    @Test fun cachedLibraryShowsAtOnceAndFreshCacheIsNotReloaded() = runTest {
        val cache = MemoryStore().apply { saved["a"] = lib("Cached") to now - hour }
        val s = setup(listOf(src("a")), mapOf("a" to FakeProvider({ lib("Fresh") })), cache)
        s.repo.open(src("a"), force = false)
        runCurrent()
        assertEquals(listOf("Cached"), s.state.value.lib.channels.map { it.name })
        assertNotNull(s.state.value.index)
        advanceUntilIdle()
        assertEquals(0, s.providers.getValue("a").loads)
        assertFalse(s.state.value.loading)
        assertEquals(listOf(false), s.afterLoads, "the guide still loads after a cached open")
        assertEquals("a", s.sources.activeSourceId)
    }

    @Test fun staleCacheRefreshesAndRebuildsTheIndex() = runTest {
        val cache = MemoryStore().apply { saved["a"] = lib("Old") to now - 13 * hour }
        val s = setup(listOf(src("a")), mapOf("a" to FakeProvider({ lib("New1", "New2") })), cache)
        s.repo.open(src("a"), force = false)
        advanceUntilIdle()
        assertEquals(1, s.providers.getValue("a").loads)
        assertEquals(listOf("New1", "New2"), s.state.value.lib.channels.map { it.name })
        assertSame(s.state.value.lib, s.state.value.index?.lib)
        assertEquals(2, s.state.value.index?.channelById?.size)
        assertEquals(listOf("New1", "New2"), cache.saved["a"]?.first?.channels?.map { it.name }, "the refreshed library is cached")
    }

    @Test fun forcedRefreshReloadsAFreshCache() = runTest {
        val cache = MemoryStore().apply { saved["a"] = lib("Cached") to now }
        val s = setup(listOf(src("a")), mapOf("a" to FakeProvider({ lib("Fresh") })), cache)
        s.repo.open(src("a"), force = false); advanceUntilIdle()
        s.repo.refresh(); advanceUntilIdle()
        assertEquals(1, s.providers.getValue("a").loads)
        assertEquals("Fresh", s.state.value.lib.channels.single().name)
        assertEquals(listOf(false, true), s.afterLoads)
    }

    @Test fun loadingAndStatusLinesFollowTheLoad() = runTest {
        val seen = mutableListOf<Pair<Boolean, String>>()
        val state = MutableStateFlow(Repo.LibState(epgVersion = 7))
        val p = object : Provider by FakeProvider({ lib() }) {
            override fun load(progress: (String) -> Unit): Library {
                seen += state.value.loading to state.value.status
                progress("Loading movies")
                seen += state.value.loading to state.value.status
                return lib("X")
            }
        }
        val list = Sources(listOf(src("a")))
        val repo = CatalogRepository(this, state, list, MemoryStore(), providerOf = { p }, clock = { now })
        repo.open(src("a"), force = false)
        advanceUntilIdle()
        assertEquals(listOf(true to "Loading Playlist a", true to "Loading movies"), seen)
        assertFalse(state.value.loading)
        assertEquals("", state.value.status)
        assertNull(state.value.error)
        assertEquals(7, state.value.epgVersion, "the guide's version is carried over")
    }

    @Test fun providerFailureShowsItsMessageAndKeepsTheCache() = runTest {
        val cache = MemoryStore().apply { saved["a"] = lib("Cached") to now - 20 * hour }
        val s = setup(listOf(src("a")), mapOf("a" to FakeProvider({ lib() }, IllegalStateException("Server said no"))), cache)
        s.repo.open(src("a"), force = false); advanceUntilIdle()
        assertEquals("Server said no", s.state.value.error)
        assertFalse(s.state.value.loading)
        assertEquals("Cached", s.state.value.lib.channels.single().name)
        assertEquals(listOf(false), s.afterLoads)
    }

    @Test fun outOfMemoryIsReportedPlainly() = runTest {
        val s = setup(listOf(src("a")), mapOf("a" to FakeProvider({ lib() }, OutOfMemoryError())))
        s.repo.open(src("a"), force = false); advanceUntilIdle()
        assertEquals("This playlist is too large for the memory of this device", s.state.value.error)
        assertFalse(s.state.value.loading)
    }

    @Test fun switchingSourcesShowsTheNewOneOnly() = runTest {
        val s = setup(listOf(src("a"), src("b")), mapOf("a" to FakeProvider({ lib("A") }), "b" to FakeProvider({ lib("B") })))
        s.repo.open(src("a"), force = false); advanceUntilIdle()
        s.repo.open(src("b"), force = false)
        runCurrent()
        assertEquals("b", s.state.value.source?.id)
        assertSame(s.providers["b"], s.repo.provider)
        advanceUntilIdle()
        assertEquals("B", s.state.value.lib.channels.single().name)
        assertEquals("b", s.sources.activeSourceId)
        assertEquals(s.sources.sources[1], s.repo.initialSource())
    }

    @Test fun removingTheOpenSourceOpensTheNextOrClears() = runTest {
        val s = setup(listOf(src("a"), src("b")), mapOf("a" to FakeProvider({ lib("A") }), "b" to FakeProvider({ lib("B") })))
        s.repo.open(src("a"), force = false); advanceUntilIdle()
        s.repo.removeSource("a"); advanceUntilIdle()
        assertEquals(listOf("a"), s.cache.deleted)
        assertEquals(listOf("b"), s.sources.sources.map { it.id })
        assertEquals("B", s.state.value.lib.channels.single().name)
        s.repo.removeSource("b"); advanceUntilIdle()
        assertNull(s.repo.provider)
        assertNull(s.state.value.source)
        assertTrue(s.state.value.lib.channels.isEmpty())
    }

    @Test fun removingAnotherSourceLeavesTheOpenOneAlone() = runTest {
        val s = setup(listOf(src("a"), src("b")), mapOf("a" to FakeProvider({ lib("A") }), "b" to FakeProvider({ lib("B") })))
        s.repo.open(src("a"), force = false); advanceUntilIdle()
        s.repo.removeSource("b"); advanceUntilIdle()
        assertEquals("a", s.state.value.source?.id)
        assertEquals(1, s.providers.getValue("a").loads)
    }

    @Test fun addingASourceSavesAndOpensItForced() = runTest {
        val cache = MemoryStore()
        val s = setup(emptyList(), mapOf("new" to FakeProvider({ lib("N") })), cache)
        val added = s.repo.addSource("", SourceType.XTREAM, " http://host ", " user ", " pass ", "", "")
        advanceUntilIdle()
        assertEquals("Xtream Codes", added.name)
        assertEquals("http://host", added.url)
        assertEquals("user", added.username)
        assertEquals(listOf(added), s.sources.sources)
        assertEquals(listOf(true), s.afterLoads)
        assertEquals("N", s.state.value.lib.channels.single().name)
    }
}
