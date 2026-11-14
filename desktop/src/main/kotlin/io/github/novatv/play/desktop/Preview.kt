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

/**
 * Windows' Live TV preview: its own muted VLC player (never the full-screen one), with no sound decoded and a small
 * cache. The stream closes as soon as the preview stops, before full screen opens its own, so the provider never sees
 * two at once; the player itself (decoder and all) is freed a moment later off the main thread. All calls on the main
 * thread, as the shared controller makes them.
 *
 * Ready is reported on the first frame (VLC says "playing" before any picture). A stream that drops gets one quiet
 * retry on the same address a few seconds later; another drop, or no picture in time, shows the error.
 */
object DesktopPreviewEngine : PreviewEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** VLC's slow calls (freeing a player) in order, off the main thread. */
    private val vlcThread = Executors.newSingleThreadExecutor { r -> Thread(r, "preview-vlc").apply { isDaemon = true } }

    /** Observed, so the window draws the new player's frames after a release. */
    internal var frames by androidx.compose.runtime.mutableStateOf(Frames())
        private set
    private var player: EmbeddedMediaPlayer? = null
    /** Raised by VLC's event thread (it must not be called back from there); the watcher acts on it. */
    @Volatile private var dropped = false
    private var watcher: Job? = null
    /** Bumped on every play and release, so a late event or retry from an earlier channel goes nowhere. */
    private var generation = 0

    /** Development only: what the test driver reads. */
    @Volatile var lastUrl: String? = null
        private set
    val hasPlayer: Boolean get() = player != null

    private fun build(): EmbeddedMediaPlayer {
        val f = Frames().also { frames = it }
        val p = Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer()
        p.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(f.format, f.render, true))
        p.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun error(mediaPlayer: MediaPlayer) { dropped = true }
            override fun finished(mediaPlayer: MediaPlayer) { dropped = true }
        })
        AutoTest.vlcCreated.incrementAndGet()
        return p
    }

    override fun play(url: String, onEvent: (PreviewEvent) -> Unit) {
        val gen = ++generation
        watcher?.cancel()
        val p = player ?: build().also { player = it }
        // The last channel's stream closes before this one opens.
        runCatching { p.controls().stop() }
        frames.lastFrameAt = 0L
        dropped = false
        val target = AutoTest.stream(url)
        lastUrl = target
        val opts = arrayOf(":http-user-agent=${io.github.novatv.play.data.Http.UA}", ":network-caching=3000", ":no-audio")
        testLog("PREVIEW play (${if (target.startsWith("http://127.0.0.1")) "relay" else "direct"})")
        p.media().play(target, *opts)
        var openedAt = System.currentTimeMillis()
        watcher = scope.launch {
            var ready = false
            var retried = false
            while (gen == generation) {
                delay(250)
                if (gen != generation) return@launch
                val now = System.currentTimeMillis()
                val fresh = frames.lastFrameAt > openedAt
                if (!ready && fresh) { ready = true; testLog("PREVIEW ready in ${now - openedAt} ms"); onEvent(PreviewEvent.Ready) }
                // Playing steadily again for a while: a later drop gets its own quiet retry.
                if (ready && retried && fresh && now - openedAt > 10_000) retried = false
                val stalled = !fresh && now - openedAt > 20_000
                if (dropped || stalled) {
                    dropped = false
                    if (!retried && !App.away) {
                        retried = true
                        testLog("PREVIEW ${if (stalled) "no picture" else "stream dropped"}: retrying once")
                        delay(RETRY_MS)
                        if (gen != generation) return@launch
                        runCatching { p.controls().stop() }
                        p.media().play(target, *opts)
                        openedAt = System.currentTimeMillis(); ready = false
                        continue
                    }
                    testLog("PREVIEW failed")
                    runCatching { p.controls().stop() }
                    onEvent(PreviewEvent.Error("This stream could not be played"))
                    return@launch
                }
            }
        }
    }

    override fun release() {
        generation++
        watcher?.cancel(); watcher = null
        val p = player ?: return
        player = null
        // The stream closes now (full screen may be about to open its own); the player is freed off the main thread.
        runCatching { p.controls().stop() }
        frames = Frames()
        lastUrl = null
        testLog("PREVIEW released")
        vlcThread.execute { runCatching { p.release() }; AutoTest.vlcReleased.incrementAndGet() }
    }

    @Composable override fun Video(modifier: Modifier) { VideoCanvas(frames, modifier) }

    private const val RETRY_MS = 3_000L
}

/** Development only: what the test driver reads; nothing is written outside a test run. */
private fun testLog(msg: String) { if (AutoTest.scenario != null) AutoTest.log(msg) }
