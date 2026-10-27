package io.github.novatv.play

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
        repo = Repo(store, Epg())
        repo.start()
    }

    val device: Device get() = host.device
}

/** Opens the player full screen. */
fun play(item: PlayItem) {
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
