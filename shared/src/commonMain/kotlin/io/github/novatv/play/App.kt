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
    /** The open tab and whether the menu has focus, for the Windows dev build's test driver (read only; nothing acts on it). */
    /**
     * Windows dev build's test driver only: where clickable things are (name, left, top, right, bottom in window pixels),
     * so it can click them with the mouse. Null otherwise, and then nothing reports.
     */
    @Volatile var testBounds: ((String, Float, Float, Float, Float) -> Unit)? = null
    /** Windows test driver only: a match whose page Sports opens. */
    val testMatch = kotlinx.coroutines.flow.MutableStateFlow<io.github.novatv.play.sports.SportEvent?>(null)
    @Volatile var openTab: String = "HOME"
    @Volatile var menuFocused: Boolean = false

    /** What the player plays next (lists of channels are too big to pass around). */
    @Volatile var pending: PlayItem? = null

    /**
     * The preview's player is on its way to the full-screen player, stream and all ([play] set it). The main screen must
     * not stop the preview while this holds; the full-screen player clears it as it opens, and it lapses on its own
     * after a few seconds in case the full-screen player never opens.
     */
    /**
     * Raised when a player drawn over the app (Windows) closes: nothing has focus then, so the open page takes it back,
     * on the item last used. Android's player is its own screen and focus comes back on its own, so it never raises it.
     */
    var playerClosed by androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile var handingOver = false
    @Volatile private var handoverUntil = 0L
    fun startHandover() { handoverUntil = io.github.novatv.play.platform.nowMs() + 5_000; handingOver = true }
    /** Why the last [play] of a live channel didn't hand the preview's player over (for the TV app's log), or null. */
    @Volatile var handoverRefusal: String? = null
    val handoverActive: Boolean get() = handingOver && io.github.novatv.play.platform.nowMs() < handoverUntil

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
        io.github.novatv.play.data.Diag.note("Started ${io.github.novatv.play.data.Updates.CURRENT}, ${store.sources.size} playlists")
        io.github.novatv.play.data.Diag.sendPendingCrash()
        io.github.novatv.play.data.Storage.cleanUp()
        repo = Repo(store, Epg())
        repo.start()
        io.github.novatv.play.data.AccountSync.start()
    }

    val device: Device get() = host.device
}

/** Opens the player full screen. */
fun play(item: PlayItem) {
    // A live channel already playing in the preview window goes full screen in that same player, without opening the
    // stream again; one still loading there goes full screen in that player too, which then opens it. Otherwise the preview's player is let go before the full-screen one starts, so the two never
    // compete for a decoder.
    val engine = App.host.previewEngine
    val target = (item as? PlayItem.Live)?.let { it.channels.getOrNull(it.channelIndex) }
    val ps = App.preview.state.value
    App.handingOver = false
    val shown = when (ps) {
        is io.github.novatv.play.data.PreviewController.State.Playing -> ps.channel
        is io.github.novatv.play.data.PreviewController.State.Loading -> ps.channel
        is io.github.novatv.play.data.PreviewController.State.Waiting -> ps.channel
        else -> null
    }
    App.handoverRefusal = when {
        engine == null || target == null -> null
        !App.store.seamlessFullscreen -> "seamless fullscreen off"
        ps is io.github.novatv.play.data.PreviewController.State.Failed -> "preview had failed on ${ps.channel.name}"
        shown == null -> "preview not playing (${ps::class.simpleName})"
        shown.id != target.id -> "preview playing another channel (${shown.name})"
        !(engine.canHandOver() || engine.canTakeOver()) -> "player not available"
        else -> null
    }
    if (engine != null && target != null && shown?.id == target.id && App.store.seamlessFullscreen &&
        (engine.canHandOver() || engine.canTakeOver())) {
        App.startHandover()
        // Full screen clears the flag as it opens; still set when the time is up, it never opened.
        App.preview.handOver { !App.handingOver }
    }
    else if (engine != null) App.preview.stop()
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
