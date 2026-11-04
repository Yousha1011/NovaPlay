package io.github.novatv.play

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import io.github.novatv.play.data.Epg
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Repo
import io.github.novatv.play.data.Store
import io.github.novatv.play.platform.Device
import io.github.novatv.play.platform.Host
import kotlin.concurrent.Volatile

/** The app's single store, library and guide, shared by every screen; each platform calls [start] first. */
object App {
    lateinit var store: Store
        private set
    lateinit var repo: Repo
        private set
    lateinit var host: Host
        private set
    /** Live TV's preview window. Its stream address comes from the playlist's provider, as the full-screen player's does. */
    val preview: io.github.novatv.play.data.PreviewController by lazy {
        io.github.novatv.play.data.PreviewController(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main),
            host.previewEngine,
            resolve = { c -> kotlinx.coroutines.withContext(io.github.novatv.play.platform.IO) { repo.provider?.liveUrl(c) } },
            enabled = { store.livePreview },
        )
    }

    /** The app's window is out of sight: the full-screen player is over it, or the app is in the background. */
    var away by androidx.compose.runtime.mutableStateOf(false)

    /** What the player plays next (lists of channels are too big to pass around). */
    @Volatile var pending: PlayItem? = null

    private var started = false

    fun start(host: Host) {
        if (started) return
        started = true
        this.host = host
        store = Store()
        // A reinstalled app starts empty: its playlists come back from the platform's backup, then every change updates it.
        host.restoreBackup()?.let { store.restore(it) }
        store.backup = { host.saveBackup(it) }
        if (store.sources.isNotEmpty()) host.saveBackup(store.snapshot())
        io.github.novatv.play.platform.Time.use24h = store.clock24
        io.github.novatv.play.data.AccountSync.watch()
        repo = Repo(store, Epg())
        repo.start()
        io.github.novatv.play.data.AccountSync.start()
    }

    val device: Device get() = host.device
}

/** Opens the player full screen. */
fun play(item: PlayItem) {
    // The preview's player is let go before the full-screen one starts, so the two never compete for a decoder.
    if (App.host.previewEngine != null) App.preview.stop()
    App.pending = item
    if (item is PlayItem.Vod) {
        item.movie?.let { App.store.addWatched(App.repo.movieKey(it)) }
        item.series?.let { App.store.addWatched(App.repo.seriesKey(it)) }
    }
    App.host.play(item)
}

/** Opens a web address (a trailer) in the phone's browser or YouTube. */
fun openUrl(url: String) = App.host.openUrl(url)

/** A short message at the bottom of the screen. */
fun toast(msg: String) = App.host.toast(msg)
