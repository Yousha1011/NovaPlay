package io.github.novatv.play.data

import io.github.novatv.play.platform.dataDir
import io.github.novatv.play.platform.logW
import io.github.novatv.play.platform.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.concurrent.Volatile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** The saved playlists and which one is open ([Store] in the app). */
interface SourceList {
    var sources: List<Source>
    var activeSourceId: String?
}

/** Loaded libraries kept on disk per playlist, with when each was saved. */
interface LibraryStore {
    fun read(sourceId: String): Pair<Library, Long>?
    fun write(sourceId: String, lib: Library)
    fun delete(sourceId: String)
    /** Saves a part-loaded library ([Provider.loadStaged]); its time is the negative stage. */
    fun writePartial(sourceId: String, lib: Library, stage: Int) {}
}

/** [LibraryCache] files in the app's data folder. */
object FileLibraryStore : LibraryStore {
    override fun read(sourceId: String) = LibraryCache.read(LibraryCache.file(dataDir(), sourceId))
    override fun write(sourceId: String, lib: Library) = LibraryCache.write(LibraryCache.file(dataDir(), sourceId), lib)
    override fun delete(sourceId: String) = LibraryCache.delete(LibraryCache.file(dataDir(), sourceId))
    override fun writePartial(sourceId: String, lib: Library, stage: Int) = LibraryCache.writePartial(LibraryCache.file(dataDir(), sourceId), lib, stage)
}

/**
 * The playlists and the open one's library: which source is active, its [Provider], loading it (the cached copy at
 * once, then a refresh when the copy is older than the allowed age or a refresh is forced), the loading, status and
 * error lines, and the library's [LibIndex]. Adding, switching and removing playlists go through here.
 *
 * It writes the catalogue fields of the shared [Repo.LibState]; the guide fields (epgVersion, epgStatus) belong to
 * [Repo] and are carried over. [afterLoad] runs at the end of every open (Repo loads the guide there) and
 * [onLibraryChanged] whenever the channel list may have changed.
 */
class CatalogRepository(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<Repo.LibState>,
    private val sourceList: SourceList,
    private val cache: LibraryStore = FileLibraryStore,
    private val providerOf: (Source) -> Provider = { Provider.of(it) },
    private val clock: () -> Long = { nowMs() },
    @OptIn(ExperimentalUuidApi::class) private val newId: () -> String = { Uuid.random().toString() },
    private val afterLoad: suspend (force: Boolean) -> Unit = {},
    private val onLibraryChanged: () -> Unit = {},
    /** Called after each read from a playlist's server: when, and the error (null when it worked). */
    private val onSynced: (sourceId: String, at: Long, error: String?) -> Unit = { _, _, _ -> },
    /** Called with when the saved copy of a playlist was written, each time it is read. */
    private val onCached: (sourceId: String, savedAt: Long) -> Unit = { _, _ -> },
) {
    @Volatile var provider: Provider? = null
        private set
    private var loadJob: Job? = null
    /** Opening a playlist (and its guide) is still under way. */
    val busy: Boolean get() = loadJob?.isActive == true

    /** The playlist chosen last time, or the first one; null when there are none. */
    fun initialSource(): Source? = sourceList.sources.let { all -> all.firstOrNull { it.id == sourceList.activeSourceId } ?: all.firstOrNull() }

    /** Opens [src]: the cached library at once, then a refresh when the cache is older than [maxAgeMs] or [force]. */
    fun open(src: Source, force: Boolean, maxAgeMs: Long = 12 * 3600_000L): Job {
        sourceList.activeSourceId = src.id
        val p = providerOf(src)
        provider = p
        onLibraryChanged()
        loadJob?.cancel()
        state.update { Repo.LibState(source = src, epgVersion = it.epgVersion) }
        return scope.launch {
            val cached = cache.read(src.id)
            // A part-loaded copy (the last load was cut short) shows at once, and the load carries on from it.
            val partial = cached?.takeIf { it.second < 0 }?.first
            if (cached != null) { onCached(src.id, maxOf(0L, cached.second)); state.update { it.copy(lib = cached.first, index = LibIndex(cached.first)) } }
            if (force || cached == null || partial != null || cached.second < clock() - maxAgeMs) {
                state.update { it.copy(loading = true, status = "Loading ${src.name}", error = null) }
                val t0 = clock()
                Diag.note("Loading playlist (${src.type}, cache ${if (cached == null) "none" else "old"})")
                try {
                    val lib = p.loadStaged({ msg -> state.update { s -> s.copy(status = msg) } }, partial) { part, stage ->
                        cache.writePartial(src.id, part, stage)
                        Diag.note("Saved stage $stage (${part.channels.size} channels, ${part.movies.size} movies)")
                        // Channels can be watched while films and series still load.
                        if (stage == 1 && state.value.lib.channels.isEmpty()) state.update { it.copy(lib = part, index = LibIndex(part)) }
                    }
                    cache.write(src.id, lib)
                    onSynced(src.id, clock(), null)
                    onLibraryChanged()
                    val idx = LibIndex(lib)
                    Diag.slow("Playlist load (${lib.channels.size} channels, ${lib.movies.size} movies, ${lib.series.size} series)", clock() - t0, 60_000)
                    state.update { it.copy(lib = lib, index = idx, loading = false, status = "", error = null) }
                } catch (e: OutOfMemoryError) {
                    Diag.failed("Playlist load (out of memory)", e)
                    state.update { it.copy(loading = false, status = "", error = "This playlist is too large for the memory of this device") }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    logW("load failed", e)
                    Diag.failed("Playlist load (${src.type})", e)
                    val msg = e.message ?: e::class.simpleName.orEmpty()
                    onSynced(src.id, clock(), msg)
                    state.update { it.copy(loading = false, status = "", error = msg) }
                }
            }
            afterLoad(force)
        }.also { loadJob = it }
    }

    /**
     * Reads [src] from its server again and saves it, without clearing what the screens show: when it is the open
     * playlist its new library replaces the old one in place. Returns the error, or null when it worked.
     */
    suspend fun sync(src: Source): String? {
        val p = if (state.value.source?.id == src.id) provider ?: providerOf(src) else providerOf(src)
        return try {
            val lib = p.loadStaged({ }, null) { part, stage -> cache.writePartial(src.id, part, stage) }
            cache.write(src.id, lib)
            onSynced(src.id, clock(), null)
            if (state.value.source?.id == src.id) {
                onLibraryChanged()
                state.update { it.copy(lib = lib, index = LibIndex(lib), error = null) }
            }
            null
        } catch (e: OutOfMemoryError) {
            "Too large for this device's memory".also { onSynced(src.id, clock(), it) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            logW("sync failed", e)
            (e.message ?: e::class.simpleName.orEmpty()).also { onSynced(src.id, clock(), it) }
        }
    }

    /** Reloads the open playlist from its server. */
    fun refresh() { state.value.source?.let { open(it, force = true) } }

    fun addSource(name: String, type: SourceType, url: String, user: String, pass: String, mac: String, epgUrl: String): Source {
        val s = Source(newId(), name.ifBlank { type.label }, type, url.trim(), user.trim(), pass.trim(), mac.trim(), epgUrl.trim())
        sourceList.sources = sourceList.sources + s
        open(s, force = true)
        return s
    }

    /** Removes a playlist and its cached library; when it was open, the next one opens (or nothing is). */
    fun removeSource(id: String) {
        sourceList.sources = sourceList.sources.filter { it.id != id }
        cache.delete(id)
        if (state.value.source?.id == id) {
            val next = sourceList.sources.firstOrNull()
            if (next != null) open(next, force = false) else { provider = null; state.value = Repo.LibState() }
        }
    }
}
