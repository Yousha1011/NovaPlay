package io.github.novatv.play.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.novatv.play.App
import io.github.novatv.play.data.PreviewEngine
import io.github.novatv.play.data.PreviewEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import java.util.concurrent.Executors

/** The preview's player as full screen takes it: VLC player, the frames it draws into, and the address playing. */
internal class HandedPlayer(val player: EmbeddedMediaPlayer, val frames: Frames, val url: String)

/**
 * Windows' Live TV preview: its own muted VLC player with a small cache. The stream closes as soon as the preview
 * stops, before full screen opens its own, so the provider never sees two at once; the player itself (decoder and all)
 * is freed a moment later off the main thread. All calls on the main thread, as the shared controller makes them.
 *
 * Ready is reported on the first frame (VLC says "playing" before any picture). A stream that drops gets one quiet
 * retry on the same address a few seconds later; another drop, or no picture in time, shows the error.
 *
 * Seamless full screen (Settings): a channel playing here goes full screen in this very player, stream and all
 * ([handOff]), and comes back to the window, still playing, on Back ([takeBack]). Its sound is therefore decoded but
 * muted while it is the preview; with seamless off no sound is decoded at all.
 */
object DesktopPreviewEngine : PreviewEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Observed, so the window draws the new player's frames after a release. */
    internal var frames by androidx.compose.runtime.mutableStateOf(Frames(PREVIEW_MAX_WIDTH))
        private set
    private var player: EmbeddedMediaPlayer? = null
    /** Lent to full screen: the preview mustn't touch it until it comes back. */
    private var lent: HandedPlayer? = null
    /** Raised by VLC's event thread (it must not be called back from there); the watcher acts on it. */
    @Volatile private var dropped = false
    private var watcher: Job? = null
    /** Bumped on every play and release, so a late event or retry from an earlier channel goes nowhere. */
    private var generation = 0
    /** Whether the playing channel has shown a picture (only then can it go full screen as it is). */
    private var ready = false

    /** Development only: what the test driver reads. */
    @Volatile var lastUrl: String? = null
        private set
    val hasPlayer: Boolean get() = player != null
    val isLent: Boolean get() = lent != null

    private fun build(): EmbeddedMediaPlayer {
        val f = Frames(PREVIEW_MAX_WIDTH).also { frames = it }
        val p = Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer()
        p.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(f.format, f.render, true))
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) { if (mediaPlayer === player) dropped = true }
            override fun finished(mediaPlayer: MediaPlayer) { if (mediaPlayer === player) dropped = true }
        })
        AutoTest.vlcCreated.incrementAndGet()
        return p
    }

    private fun opts(): Array<String> = arrayOf(":http-user-agent=${io.github.novatv.play.data.Http.UA}", ":network-caching=3000", ":adaptive-maxheight=1080") +
        // Its sound is only needed if it may go full screen as it is.
        (if (App.store.seamlessFullscreen) emptyArray() else arrayOf(":no-audio"))

    /**
     * A channel asked for while full screen is open (a click's highlight can land just after a double click opened it):
     * it waits, so the two never play at once, and starts when full screen closes ([fullScreenClosed]).
     */
    private var deferred: Pair<String, (PreviewEvent) -> Unit>? = null

    override fun play(url: String, onEvent: (PreviewEvent) -> Unit) {
        if (lent != null) { onEvent(PreviewEvent.Error("Playing full screen")); return }
        if (Desktop.playing != null) {
            generation++; watcher?.cancel(); deferred = url to onEvent
            testLog("PREVIEW waits: full screen is open")
            return
        }
        deferred = null
        val gen = ++generation
        watcher?.cancel()
        val p = player ?: build().also { player = it }
        // The last channel's stream closes before this one opens (both queued, in order, off the window's thread).
        frames.lastFrameAt = 0L
        dropped = false; ready = false
        val target = AutoTest.stream(url)
        lastUrl = target
        val o = opts()
        p.audio().isMute = true
        testLog("PREVIEW play (${if (target.startsWith("http://127.0.0.1")) "relay" else "direct"})")
        Vlc.later { p.controls().stop(); p.media().play(target, *o, *vlcHeaders(url)) }
        watch(p, gen, onEvent, System.currentTimeMillis(), alreadyReady = false) { Vlc.later { p.controls().stop(); p.media().play(target, *o, *vlcHeaders(url)) } }
    }

    /**
     * Follows [p]'s picture for this channel: Ready on the first frame, one quiet retry ([replay]) when the stream
     * drops or never shows a picture, then the error. Keeps the sound muted (VLC only applies it once its audio output
     * exists).
     */
    private fun watch(p: EmbeddedMediaPlayer, gen: Int, onEvent: (PreviewEvent) -> Unit, opened: Long, alreadyReady: Boolean, replay: () -> Unit) {
        watcher = scope.launch {
            var openedAt = opened
            var retried = false
            ready = alreadyReady
            while (gen == generation) {
                delay(250)
                if (gen != generation || player !== p) return@launch
                if (!p.audio().isMute) p.audio().isMute = true
                val now = System.currentTimeMillis()
                val fresh = frames.lastFrameAt > openedAt
                if (!ready && fresh) { ready = true; testLog("PREVIEW ready in ${now - openedAt} ms"); onEvent(PreviewEvent.Ready) }
                // Playing steadily again for a while: a later drop gets its own quiet retry.
                if (ready && retried && fresh && now - openedAt > 10_000) retried = false
                val stalled = !ready && now - openedAt > 20_000
                if (dropped || stalled) {
                    dropped = false
                    if (!retried && !App.away) {
                        retried = true
                        testLog("PREVIEW ${if (stalled) "no picture" else "stream dropped"}: retrying once")
                        delay(RETRY_MS)
                        if (gen != generation || player !== p) return@launch
                        replay()
                        openedAt = System.currentTimeMillis(); ready = false
                        continue
                    }
                    testLog("PREVIEW failed")
                    Vlc.later { p.controls().stop() }
                    ready = false
                    onEvent(PreviewEvent.Error("This stream could not be played"))
                    return@launch
                }
            }
        }
    }

    override fun canHandOver(): Boolean = player != null && ready && lent == null && lastUrl != null

    /**
     * Full screen takes the playing channel's player as it is: the preview stops following it and forgets it (the
     * window shows nothing of its own meanwhile). Null if there is nothing fit to hand over.
     */
    internal fun handOff(): HandedPlayer? {
        val p = player ?: return null
        val url = lastUrl ?: return null
        if (!ready || lent != null) return null
        generation++
        watcher?.cancel(); watcher = null
        val h = HandedPlayer(p, frames, url)
        lent = h
        player = null
        frames = Frames(PREVIEW_MAX_WIDTH)
        testLog("PREVIEW handed to full screen")
        return h
    }

    /**
     * Full screen gives the player back, still playing (perhaps another channel than it took, playing [url]): the
     * window shows it again, muted, with no new stream.
     */
    internal fun giveBack(h: HandedPlayer, url: String): Boolean {
        if (lent !== h || player != null) return false
        back = h to url
        return true
    }
    private var back: Pair<HandedPlayer, String>? = null

    /** Full screen closed: a channel asked for meanwhile starts now. */
    fun fullScreenClosed() { val d = deferred ?: return; deferred = null; if (lent == null && player == null) play(d.first, d.second) }

    override fun takeBack(onEvent: (PreviewEvent) -> Unit): Boolean {
        deferred = null
        val (h, url) = back ?: return false
        back = null
        if (lent !== h) return false
        lent = null
        player = h.player
        frames = h.frames
        lastUrl = url
        dropped = false
        h.player.audio().isMute = true
        val gen = ++generation
        testLog("PREVIEW took the player back")
        val o = opts()
        watch(h.player, gen, onEvent, frames.lastFrameAt.coerceAtLeast(1L) - 1, alreadyReady = true) { Vlc.later { h.player.controls().stop(); h.player.media().play(url, *o, *vlcHeaders(url)) } }
        return true
    }

    /** Full screen closed without giving the player back: it was freed there, so the preview forgets it. */
    internal fun lost(h: HandedPlayer) { if (lent === h) { lent = null; back = null } }

    override fun release() {
        deferred = null
        generation++
        watcher?.cancel(); watcher = null
        // Full screen has the player: nothing here to stop, and that one isn't the preview's to touch.
        val p = player ?: return
        player = null
        ready = false
        // The stream closes now (full screen may be about to open its own); the player is freed off the main thread.
        frames = Frames(PREVIEW_MAX_WIDTH)
        lastUrl = null
        testLog("PREVIEW released")
        Vlc.later { p.controls().stop(); p.release(); AutoTest.vlcReleased.incrementAndGet() }
    }

    @Composable override fun Video(modifier: Modifier) { VideoCanvas(frames, modifier) }

    private const val RETRY_MS = 3_000L
    /** The preview's widest picture (full HD, as it may go full screen): VLC scales 4K and 8K channels down to it. */
    private const val PREVIEW_MAX_WIDTH = 1920
}

/** Development only: what the test driver reads; nothing is written outside a test run. */
private fun testLog(msg: String) { if (AutoTest.scenario != null) AutoTest.log(msg) }
