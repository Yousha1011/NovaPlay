package io.github.novatv.play.data

import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.Espn
import io.github.novatv.play.sports.Matcher
import io.github.novatv.play.sports.SportEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import io.github.novatv.play.platform.Lock
import io.github.novatv.play.platform.dataDir
import io.github.novatv.play.platform.locked
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.logW
import io.github.novatv.play.platform.SafeMap

/** The active source's library, its guide and the Sports feed, shared by every screen. */
class Repo(val store: Store, val epg: Epg) {
    data class LibState(
        val source: Source? = null,
        val lib: Library = Library(),
        val loading: Boolean = false,
        val status: String = "",
        val error: String? = null,
        /** Bumped when the guide finishes importing, so screens re-read now/next. */
        val epgVersion: Int = 0,
        /** Sorted and grouped views of [lib], built in the background; null while building. */
        val index: LibIndex? = null,
        val epgStatus: String? = null,
    )

    data class SportsState(val events: List<SportEvent> = emptyList(), val loading: Boolean = false, val updatedAt: Long = 0L)

    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val _state = MutableStateFlow(LibState())
    val state: StateFlow<LibState> = _state
    private val _sports = MutableStateFlow(SportsState())
    val sports: StateFlow<SportsState> = _sports

    @Volatile var provider: Provider? = null
        private set
    private var loadJob: Job? = null
    @Volatile private var matcher: Pair<Int, Matcher>? = null

    fun start() {
        store.addSportsGuides()
        epg.loadIndex()
        val src = store.sources.firstOrNull { it.id == store.activeSourceId } ?: store.sources.firstOrNull()
        if (src != null) open(src, force = false)
    }

    /** Opens [src]: the cached library at once, then a refresh when the cache is older than [maxAgeMs] or [force]. */
    fun open(src: Source, force: Boolean, maxAgeMs: Long = 12 * 3600_000L) {
        store.activeSourceId = src.id
        val p = Provider.of(src)
        provider = p
        matcher = null
        loadJob?.cancel()
        _state.update { LibState(source = src, epgVersion = it.epgVersion) }
        loadJob = scope.launch {
            val cacheFile = LibraryCache.file(dataDir(), src.id)
            val cached = LibraryCache.read(cacheFile)
            if (cached != null) _state.update { it.copy(lib = cached.first, index = LibIndex(cached.first)) }
            if (force || cached == null || cached.second < nowMs() - maxAgeMs) {
                _state.update { it.copy(loading = true, status = "Loading ${src.name}", error = null) }
                try {
                    val lib = p.load { msg -> _state.update { s -> s.copy(status = msg) } }
                    LibraryCache.write(cacheFile, lib)
                    matcher = null
                    val idx = LibIndex(lib)
                    _state.update { it.copy(lib = lib, index = idx, loading = false, status = "", error = null) }
                } catch (e: OutOfMemoryError) {
                    _state.update { it.copy(loading = false, status = "", error = "This playlist is too large for the memory of this device") }
                } catch (e: Exception) {
                    logW("load failed", e)
                    _state.update { it.copy(loading = false, status = "", error = e.message ?: e::class.simpleName.orEmpty()) }
                }
            }
            loadGuide(force)
        }
    }

    private fun loadGuide(force: Boolean) {
        // The playlist's own guide, then any extra guides from Settings.
        val urls = (listOfNotNull(_state.value.lib.epgUrl) + store.extraEpg).distinct()
        if (urls.isNotEmpty()) {
            if (!force && epg.fresh(urls)) epg.loadIndex()
            else {
                _state.update { it.copy(epgStatus = "Loading TV guide") }
                try {
                    epg.import(urls) { msg -> _state.update { it.copy(epgStatus = msg) } }
                } catch (e: Throwable) {
                    logW("guide import failed", e)
                    _state.update { it.copy(epgStatus = "The TV guide could not be loaded (${e.message ?: e::class.simpleName.orEmpty()})") }
                }
            }
        }
        // Event channels list their own match in their names: made into listings after every import.
        try { epg.addEvents(_state.value.lib.channels) } catch (e: Exception) { logW("event listings failed", e) }
        matcher = null
        _state.update { it.copy(epgVersion = it.epgVersion + 1) }
    }

    private fun bumpEpg(status: String?) = _state.update { it.copy(epgVersion = it.epgVersion + 1, epgStatus = status) }

    fun refresh() { _state.value.source?.let { open(it, force = true) } }

    /** Reimports the TV guide now. */
    fun reloadGuide() { scope.launch { loadGuide(force = true) } }

    @OptIn(ExperimentalUuidApi::class)
    fun addSource(name: String, type: SourceType, url: String, user: String, pass: String, mac: String, epgUrl: String): Source {
        val s = Source(Uuid.random().toString(), name.ifBlank { type.label }, type, url.trim(), user.trim(), pass.trim(), mac.trim(), epgUrl.trim())
        store.sources = store.sources + s
        open(s, force = true)
        return s
    }

    fun removeSource(id: String) {
        store.sources = store.sources.filter { it.id != id }
        LibraryCache.delete(LibraryCache.file(dataDir(), id))
        if (_state.value.source?.id == id) {
            val next = store.sources.firstOrNull()
            if (next != null) open(next, force = false) else { provider = null; _state.value = LibState() }
        }
    }

    // ---------- keys for favourites, progress and recents ----------

    private val sid get() = _state.value.source?.id ?: "-"
    fun channelKey(c: Channel) = "ch:$sid:${c.id}"
    fun channelKeyPrefix() = "ch:$sid:"
    fun movieKey(m: Movie) = "mv:$sid:${m.id}"
    fun seriesKey(s: Series) = "se:$sid:${s.id}"
    fun episodeKey(s: Series, e: Episode) = "ep:$sid:${s.id}:${e.id}"

    /** Guide now/next for a channel: the imported guide, or the source's own short guide. */
    fun nowNext(c: Channel): List<Programme> {
        epg.key(c)?.let { k -> epg.nowNext(k).let { if (it.isNotEmpty()) return it } }
        return provider?.shortEpg(c).orEmpty().filter { it.stop > nowMs() }.take(2)
    }

    /** Built once per playlist and guide; screens asking at the same time wait for the one build. */
    private val matcherLock = Lock()
    fun matcher(): Matcher = matcherLock.locked {
        val v = _state.value.epgVersion * 31 + _state.value.lib.channels.size
        matcher?.let { if (it.first == v) return@locked it.second }
        Matcher(epg, _state.value.lib.channels).also { matcher = v to it }
    }

    // ---------- Sports ----------

    private var sportsJob: Job? = null

    /** Reloads the followed leagues' matches; skipped when the last load is younger than [minAgeMs]. */
    fun refreshSports(minAgeMs: Long = 60_000L) {
        if (sportsJob?.isActive == true) return
        if (nowMs() - _sports.value.updatedAt < minAgeMs) return
        sportsJob = scope.launch {
            _sports.update { it.copy(loading = true) }
            val leagues = io.github.novatv.play.sports.Follow.leaguesToFetch()
            val gate = Semaphore(6)
            val all = leagues.map { l -> async { gate.withPermit { Espn.fetch(l) } } }.awaitAll().flatten()
            _sports.value = SportsState(all.distinctBy { it.id }.sortedBy { it.start }, false, nowMs())
        }
    }

    private val sportCache = SafeMap<io.github.novatv.play.sports.Sport, Pair<Long, List<SportEvent>>>()

    /** Two weeks of [sport]'s matches, for picking its competitions and clubs (cached for an hour). */
    suspend fun sportCatalog(sport: io.github.novatv.play.sports.Sport): List<SportEvent> = kotlinx.coroutines.withContext(IO) {
        sportCache[sport]?.let { if (nowMs() - it.first < 3600_000L) return@withContext it.second }
        val gate = Semaphore(6)
        val list = Catalog.leagues.filter { it.sport == sport }.map { l -> async { gate.withPermit { Espn.fetch(l, 14) } } }.awaitAll().flatten()
        sportCache[sport] = nowMs() to list
        list
    }
}
