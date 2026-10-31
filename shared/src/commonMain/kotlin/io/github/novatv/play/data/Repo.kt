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

/**
 * What the screens use: the library (owned by [CatalogRepository]), the guide and matching (here), and Sports (owned
 * by [SportsRepository]). Kept as one facade so screens didn't change when the parts moved out.
 */
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

    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val _state = MutableStateFlow(LibState())
    val state: StateFlow<LibState> = _state

    /** Playlists and the open one's library (see [CatalogRepository]); the guide is loaded after each open. */
    val catalog = CatalogRepository(scope, _state, store, afterLoad = { force -> loadGuide(force) }, onLibraryChanged = { matcher = null },
        onSynced = { id, at, error -> store.recordSync(id, at, error) },
        // Playlists saved before update times were kept: their saved copy's date stands in.
        onCached = { id, at -> if (store.syncedAt(id) == null) store.recordSync(id, at, null) })

    /** The Sports feed lives in its own repository; these keep the screens' calls as they were. */
    val sportsRepo = SportsRepository(scope)
    val sports: StateFlow<SportsRepository.SportsState> get() = sportsRepo.state

    val provider: Provider? get() = catalog.provider
    @Volatile private var matcher: Pair<Int, Matcher>? = null

    fun start() {
        store.addSportsGuides()
        epg.loadIndex()
        catalog.initialSource()?.let { open(it, force = false) }
    }

    /** Opens [src]: the cached library at once, then a refresh when the cache is older than [maxAgeMs] or [force]. */
    fun open(src: Source, force: Boolean, maxAgeMs: Long = Refresh.maxAgeMs(store.playlistRefreshHours)) { catalog.open(src, force, maxAgeMs) }

    private fun loadGuide(force: Boolean) {
        // The playlist's own guide, then any extra guides from Settings.
        val urls = (listOfNotNull(_state.value.lib.epgUrl) + store.extraEpg).distinct()
        if (urls.isNotEmpty()) {
            if (!force && epg.fresh(urls, Refresh.maxAgeMs(store.guideRefreshHours))) epg.loadIndex()
            else {
                _state.update { it.copy(epgStatus = "Loading TV guide") }
                try {
                    epg.import(urls) { msg -> _state.update { it.copy(epgStatus = msg) } }
                    // Done: the progress line goes away.
                    _state.update { it.copy(epgStatus = null) }
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

    fun refresh() = catalog.refresh()

    /** Reimports the TV guide now. */
    fun reloadGuide() { scope.launch { loadGuide(force = true) } }

    /** When the guide was last imported; null if never. */
    fun guideUpdatedAt(): Long? = try { epg.meta("at")?.toLongOrNull() } catch (e: Exception) { null }

    /** A sync running now ("Updating My TV"), for the Status rows; null when none is. */
    private val _syncing = MutableStateFlow<String?>(null)
    val syncing: StateFlow<String?> = _syncing

    /** Reads one playlist from its server again (see [CatalogRepository.sync]). */
    fun syncPlaylist(src: Source) {
        scope.launch { _syncing.value = "Updating ${src.name}"; try { catalog.sync(src) } finally { _syncing.value = null } }
    }

    /**
     * Every playlist read again, one at a time, then the guide. Returns a line for the Status rows, like
     * "2 playlists and the guide updated" or which ones failed.
     */
    suspend fun syncAll(record: (String) -> Unit = {}): String {
        val failed = mutableListOf<String>()
        val all = store.sources
        for (s in all) {
            _syncing.value = "Updating ${s.name}"
            catalog.sync(s)?.let { failed += s.name }
        }
        try {
            _syncing.value = "Updating the TV guide"
            loadGuide(force = true)
            val ok = all.size - failed.size
            val result = when {
                all.isEmpty() -> "No playlists to update"
                failed.isEmpty() -> "${plural(ok, "playlist")} and the guide updated"
                else -> "${failed.joinToString()} could not be updated" + if (ok > 0) "; ${plural(ok, "other playlist")} and the guide updated" else ""
            }
            // Saved before the "updating" line clears, so the Status rows show it as they redraw.
            record(result)
            return result
        } finally { _syncing.value = null }
    }

    fun syncAllNow() { scope.launch { syncAll() } }

    /** Coming back to the app: reads the open playlist and the guide again if their interval has passed. */
    fun refreshIfDue() {
        val src = _state.value.source ?: return
        val now = nowMs()
        // Not while opening: the open itself reads a playlist that is due.
        if (_syncing.value != null || _state.value.loading || catalog.busy) return
        if (store.playlistRefreshHours > 0 && Refresh.due(store.syncedAt(src.id), store.playlistRefreshHours, now)) syncPlaylist(src)
        if (store.guideRefreshHours > 0 && Refresh.due(guideUpdatedAt(), store.guideRefreshHours, now)) scope.launch { loadGuide(force = false) }
    }

    private fun plural(n: Int, what: String) = if (n == 1) "1 $what" else "$n ${what}s"

    fun addSource(name: String, type: SourceType, url: String, user: String, pass: String, mac: String, epgUrl: String): Source =
        catalog.addSource(name, type, url, user, pass, mac, epgUrl)

    fun removeSource(id: String) = catalog.removeSource(id)

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

    /** Reloads the followed leagues' matches; skipped when the last load is younger than [minAgeMs]. */
    fun refreshSports(minAgeMs: Long = 60_000L) = sportsRepo.refresh(minAgeMs)

    /** Two weeks of [sport]'s matches, for picking its competitions and clubs (cached for an hour). */
    suspend fun sportCatalog(sport: io.github.novatv.play.sports.Sport): List<SportEvent> = sportsRepo.catalog(sport)
}
