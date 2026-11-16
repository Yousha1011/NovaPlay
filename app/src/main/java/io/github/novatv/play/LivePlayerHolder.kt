package io.github.novatv.play

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import io.github.novatv.play.data.Http

/**
 * The one live player the TV app keeps for Live TV's window, built when first needed and kept while it is in use.
 *
 * It is built the way the full-screen player builds its own (same decoders, same buffer limits) so that a later step
 * can hand this very player to full screen without opening the stream again. While it plays in the small window
 * ([Mode.PREVIEW]) it behaves as the preview always has: muted, held to 720p, about 10 seconds of buffer, starting after
 * a second of picture. All calls on the main thread.
 */
@SuppressLint("UnsafeOptInUsageError")
class LivePlayerHolder(private val ctx: Context) {
    enum class Mode { PREVIEW, FULL }

    /** The player, or null when none is held. Observed, so the window drawing it rebinds when it changes. */
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    private var load: ModeLoadControl? = null
    /** Who has the player. Observed: the small window draws it only in [Mode.PREVIEW]. */
    var mode by mutableStateOf(Mode.PREVIEW)
        private set

    private val main = Handler(Looper.getMainLooper())
    /**
     * The release waiting to happen, if any, and its ticket. A ticket is good only while it is the latest one: [acquire]
     * and every new [stop] take a new ticket, so a timer left from before can never let go of a player in use again.
     */
    private var pendingRelease: Runnable? = null
    private var releaseTicket = 0

    /** The player, built if there isn't one; cancels a release that was waiting. */
    fun acquire(): ExoPlayer {
        cancelPendingRelease()
        player?.let { return it }
        // A new load control per player: the buffer setting is read when the player is built, as full screen does.
        val l = ModeLoadControl.create(ctx)
        val data = OkHttpDataSource.Factory(TvHttp.client).setUserAgent(Http.UA)
        val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setLoadControl(l)
            .setMediaSourceFactory(novaSources(data))
            .build()
        load = l
        player = p
        toPreview()
        return p
    }

    /** The small window's settings: no sound, 720p at most, the short buffer. */
    fun toPreview() {
        mode = Mode.PREVIEW
        load?.preview = true
        val p = player ?: return
        p.volume = 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
    }

    /**
     * Full screen takes the player as it is: sound on, any picture size, the full buffer. A release that was waiting is
     * cancelled, so no timer can take the player from full screen.
     */
    fun toFull() {
        HandoverLog.i("holder: to full screen")
        cancelPendingRelease()
        mode = Mode.FULL
        load?.preview = false
        val p = player ?: return
        p.volume = 1f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().clearVideoSizeConstraints().build()
    }

    /** Playing (or loading) a stream with no error: fit to hand to full screen as it is. */
    fun healthy(): Boolean {
        val p = player ?: return false
        return mode == Mode.PREVIEW && p.playerError == null && p.mediaItemCount > 0 &&
            (p.playbackState == androidx.media3.common.Player.STATE_READY || p.playbackState == androidx.media3.common.Player.STATE_BUFFERING)
    }

    /**
     * Stops the stream at once, which closes the provider connection and frees the decoder, and lets the player itself
     * go after [delayMs] unless something plays again first (a channel change stops, then starts straight away).
     */
    fun stop(delayMs: Long = RELEASE_DELAY_MS) {
        val p = player ?: return
        // The stream and its decoder go now: the provider sees the connection close at once.
        p.stop()
        p.clearMediaItems()
        cancelPendingRelease()
        val ticket = releaseTicket
        val r = Runnable {
            // Only the idle player in the small window, and only if nothing has used it since this release was asked for.
            val cur = player
            if (ticket == releaseTicket && cur === p && mode == Mode.PREVIEW && cur.mediaItemCount == 0) releaseNow()
        }
        pendingRelease = r
        main.postDelayed(r, delayMs)
    }

    /** The next channel's address is being looked up: the idle player waits for it, however long that takes. */
    fun keep() = cancelPendingRelease()

    private fun cancelPendingRelease() {
        releaseTicket++
        pendingRelease?.let { main.removeCallbacks(it) }
        pendingRelease = null
    }

    /**
     * Android is short of memory: an idle player (the preview's, stopped, waiting for the next channel) goes now instead
     * of after its delay. One playing, or one full screen has, is left alone. True if one was let go.
     */
    fun releaseIfIdle(): Boolean {
        val p = player ?: return false
        if (mode != Mode.PREVIEW || p.mediaItemCount > 0) return false
        releaseNow()
        return true
    }

    /** Lets the player go now, decoder, threads and all. */
    fun releaseNow() {
        if (player != null) HandoverLog.i("holder: player released (mode $mode)")
        cancelPendingRelease()
        player?.release()
        player = null
        load = null
        mode = Mode.PREVIEW
    }

    companion object {
        /**
         * How long a stopped (idle) player is kept for the next channel before it is let go: long enough for a channel
         * change (the preview's 0.7 s pause, then the stream address lookup), which at 1 s often outran it.
         */
        const val RELEASE_DELAY_MS = 3_000L
    }
}

/**
 * The full-screen player's buffer settings, with the preview's limits on top while [preview] is set. ExoPlayer fixes a
 * player's load control when it is built, so both sizes live in one: Media3 1.4.1's [DefaultLoadControl] has a
 * protected constructor for subclasses and lets [shouldContinueLoading] and [shouldStartPlayback] be overridden, and
 * both are asked continually during playback, so switching [preview] takes effect straight away.
 */
@SuppressLint("UnsafeOptInUsageError")
class ModeLoadControl private constructor(minMs: Int, maxMs: Int, startMs: Int, restartMs: Int, bytes: Int) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE), minMs, maxMs, startMs, restartMs, bytes,
    /* prioritizeTimeOverSizeThresholds = */ false, /* backBufferDurationMs = */ 0, /* retainBackBufferFromKeyframe = */ false
) {
    @Volatile var preview = true

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        // Asked first in every case, so its own loading state and the byte limit stay current.
        val full = super.shouldContinueLoading(parameters)
        // The preview's old player kept at most 10 seconds.
        return if (preview && parameters.bufferedDurationUs >= PREVIEW_MAX_US) false else full
    }

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!preview) return super.shouldStartPlayback(parameters)
        // The preview's old player started after 1 second of picture, 2 after running dry (a live stream's own target
        // offset can ask for less, as DefaultLoadControl allows).
        var need = if (parameters.rebuffering) PREVIEW_RESTART_US else PREVIEW_START_US
        if (parameters.targetLiveOffsetUs != C.TIME_UNSET) need = minOf(need, parameters.targetLiveOffsetUs / 2)
        return need <= 0 || parameters.bufferedDurationUs >= need || super.shouldStartPlayback(parameters)
    }

    companion object {
        private const val PREVIEW_MAX_US = 10_000_000L
        private const val PREVIEW_START_US = 1_000_000L
        private const val PREVIEW_RESTART_US = 2_000_000L

        /**
         * The same figures as the full-screen player (PlayerActivity.buildPlayer, light = false), repeated here so that
         * file stays untouched for now: buffer from Settings, capped in bytes for low-memory boxes.
         */
        fun create(ctx: Context): ModeLoadControl {
            val buffer = App.store.bufferMs
            val mi = android.app.ActivityManager.MemoryInfo()
            ctx.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            val low = mi.totalMem / (1024 * 1024) < 2048
            return ModeLoadControl(
                minMs = maxOf(15_000, buffer * 3),
                maxMs = maxOf(50_000, buffer * 8),
                startMs = buffer.coerceAtMost(15_000),
                restartMs = buffer,
                bytes = (if (low) 24 else 128) * 1024 * 1024,
            )
        }
    }
}
