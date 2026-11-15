package io.github.novatv.play

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.novatv.play.data.PreviewEngine
import io.github.novatv.play.data.PreviewEvent

/** A stream address as a media item, with its type set from the address (both the full-screen player and previews). */
fun mediaItem(url: String): MediaItem = MediaItem.Builder().setUri(Uri.parse(url)).apply {
    val path = url.substringBefore('?').lowercase()
    when {
        path.endsWith(".m3u8") || path.contains("/hls/") -> setMimeType(MimeTypes.APPLICATION_M3U8)
        path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
        path.endsWith(".ts") -> setMimeType(MimeTypes.VIDEO_MP2T)
    }
}.build()

/** A short reason for a playback error, as the full-screen player words it. */
fun playbackMessage(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this stream"
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Could not reach the server"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream's format is not supported"
    else -> "Playback failed (${e.errorCodeName})"
}

/**
 * Android TV's preview player: its own ExoPlayer (never the full-screen one), muted, held to 720p and a small buffer
 * so it stays light on a Fire TV stick, and released (decoder and all) whenever the preview stops.
 */
@SuppressLint("UnsafeOptInUsageError")
class ExoPreviewEngine(private val ctx: Context) : PreviewEngine {
    /** The one live player, kept between channels and (later) shared with full screen. */
    val holder = LivePlayerHolder(ctx)

    /** Where this channel's events go; replaced for each channel, so a late event from the last one goes nowhere. */
    private var onEvent: ((PreviewEvent) -> Unit)? = null
    private var ready = false
    /** Frames drawn after a (re)attached surface: counted so a window can tell whether its picture arrived. */
    private var frames = 0
    /** The player [listener] is on: one listener per player, added when the player is built. */
    private var listening: ExoPlayer? = null
    /**
     * The one quiet retry after a network drop: pending, and whether it was used for this channel (reset when the stream
     * plays again, so a later drop gets its own). See [onPlayerError].
     */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var retry: Runnable? = null
    private var retried = false
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) retried = false
            if (state == Player.STATE_READY && !ready) { ready = true; onEvent?.invoke(PreviewEvent.Ready) }
        }
        override fun onPlayerError(e: PlaybackException) {
            // The network dropped (Wi-Fi blip, router, provider hiccup): one quiet retry on the same player a few seconds
            // later, as full screen does. Anything else, or a second failure, shows the error as before.
            val p = holder.player
            if (!retried && p != null && networkError(e)) { retried = true; scheduleRetry(p); return }
            onEvent?.invoke(PreviewEvent.Error(playbackMessage(e)))
        }
        override fun onRenderedFirstFrame() { frames++ }
    }

    /** A failure to reach or keep reaching the server, not a refusal or a format the device can't play. */
    private fun networkError(e: PlaybackException) = e.errorCode in setOf(
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
    )

    /**
     * Prepares [p] again after [RETRY_MS], on the same stream, if nothing has moved on: still this channel in the window
     * (not changed, not taken by full screen, not stopped) and the app on screen. The player sits idle in its error
     * meanwhile, so there is never a second connection.
     */
    private fun scheduleRetry(p: ExoPlayer) {
        cancelRetry()
        val item = p.currentMediaItem
        val events = onEvent
        val r = Runnable {
            retry = null
            val ok = holder.player === p && holder.mode == LivePlayerHolder.Mode.PREVIEW && p.currentMediaItem === item &&
                onEvent === events && events != null && !App.away
            if (!ok) return@Runnable
            android.util.Log.i(TAG, "network error: retrying the preview once")
            p.prepare()
        }
        retry = r
        main.postDelayed(r, RETRY_MS)
    }

    private fun cancelRetry() { retry?.let { main.removeCallbacks(it) }; retry = null }

    override fun play(url: String, onEvent: (PreviewEvent) -> Unit) {
        cancelRetry(); retried = false
        // Full screen owns the one player: the window waits rather than take it (it shows this until the user moves on).
        if (holder.mode == LivePlayerHolder.Mode.FULL) { HandoverLog.i("preview: full screen owns the player, waiting"); onEvent(PreviewEvent.Error("Playing full screen")); return }
        val p = holder.acquire()
        if (listening !== p) { p.addListener(listener); listening = p }
        // The last channel's stream closes before this one opens, so the provider never sees two at once.
        p.stop()
        p.clearMediaItems()
        this.onEvent = onEvent
        ready = false
        holder.toPreview()
        p.setMediaItem(mediaItem(url))
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Stops the preview: the stream and its decoder go at once; the player itself goes a moment later unless the next
     * channel starts first (a channel change stops, then plays).
     */
    override fun canHandOver(): Boolean = holder.healthy()

    /** OK on the window's channel: pause, or play on. Only while it's the preview's (full screen has its own controls). */
    override fun togglePause(): Boolean {
        val p = holder.player ?: return false
        if (holder.mode != LivePlayerHolder.Mode.PREVIEW) return false
        p.playWhenReady = !p.playWhenReady
        return !p.playWhenReady
    }

    /**
     * Full screen takes the player: the preview stops listening to it (full screen has its own listener and its own
     * retries), and the small window stops drawing it. Returns the player, or null if it isn't fit to hand over.
     */
    fun handOff(): ExoPlayer? {
        if (holder.mode != LivePlayerHolder.Mode.PREVIEW) return null
        // Still loading (or failed): the player goes as an empty one, and full screen opens the channel in it.
        val p = if (holder.healthy()) holder.player ?: return null
            else holder.acquire().also { it.stop(); it.clearMediaItems() }
        cancelRetry()
        p.removeListener(listener)
        listening = null
        onEvent = null
        holder.toFull()
        return p
    }

    override fun canTakeOver(): Boolean = holder.mode == LivePlayerHolder.Mode.PREVIEW

    override fun starting() = holder.keep()

    override fun takeBack(onEvent: (PreviewEvent) -> Unit): Boolean {
        val p = holder.player ?: return false
        if (holder.mode != LivePlayerHolder.Mode.FULL) { HandoverLog.i("preview: hand back refused (no full-screen player)"); return false }
        HandoverLog.i("preview: took the player back")
        holder.toPreview()
        if (listening !== p) { p.addListener(listener); listening = p }
        this.onEvent = onEvent
        ready = p.playbackState == Player.STATE_READY
        cancelRetry(); retried = false
        return true
    }

    override fun release() {
        cancelRetry()
        onEvent = null
        // Full screen has the player: the preview has nothing of its own to stop, and must not touch that player
        // (the main screen stops its preview when full screen covers it, after the hand over is done).
        if (holder.mode == LivePlayerHolder.Mode.FULL) return
        // Leaving the app (Home, another app, the screen off, full screen covering it) or previews turned off: nothing
        // will play here soon, so the player goes now. Otherwise (a channel change) it waits a moment for the next one.
        if (App.away || !App.store.livePreview) holder.releaseNow() else holder.stop()
    }

    @Composable
    override fun Video(modifier: Modifier) {
        // While full screen has the player, the hidden window behind it must not take the picture back.
        val p = holder.player.takeIf { holder.mode == LivePlayerHolder.Mode.PREVIEW }
        var view by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<PlayerView?>(null) }
        AndroidView(factory = { c ->
            PlayerView(c).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }.also { view = it }
        }, update = { it.player = p }, onRelease = { it.player = null; view = null }, modifier = modifier)
        // Each time this window takes the player (a tab switch, Back from full screen), check the picture arrives.
        val v = view
        androidx.compose.runtime.LaunchedEffect(p, v) { if (p != null && v != null) watchPicture(p, v) }
    }

    /**
     * Some Fire TV decoders don't draw again after the video surface moves: sound plays, the window stays black or
     * frozen. A stream with a video track that has played for [NO_PICTURE_MS] without drawing a frame since the window
     * took it gets, once, its surface attached again and then, once, its stream prepared again; then the check gives up.
     * Black programmes still draw (black) frames and audio-only streams have no video track, so neither counts; time
     * spent buffering doesn't count either. It ends when the window lets go, the channel changes, full screen takes the
     * player or the app leaves the screen (the effect is cancelled, or the checks below fail).
     */
    private suspend fun watchPicture(p: ExoPlayer, v: PlayerView) {
        val item = p.currentMediaItem
        var seen = frames
        var played = 0L
        var step = 0
        while (true) {
            kotlinx.coroutines.delay(CHECK_MS)
            if (App.away || holder.mode != LivePlayerHolder.Mode.PREVIEW || holder.player !== p || p.currentMediaItem !== item) return
            if (frames != seen) return
            val video = p.currentTracks.groups.any { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected }
            if (!video || !p.isPlaying) continue
            played += CHECK_MS
            if (played < NO_PICTURE_MS) continue
            played = 0
            seen = frames
            when (step++) {
                0 -> { android.util.Log.i(TAG, "no picture after attach: attaching the surface again"); v.player = null; v.player = p }
                1 -> { android.util.Log.i(TAG, "still no picture: preparing the stream again"); p.stop(); p.prepare() }
                else -> { android.util.Log.i(TAG, "still no picture: giving up"); return }
            }
        }
    }

    companion object {
        private const val TAG = "NovaPreview"
        private const val CHECK_MS = 500L
        /** Wait before the one network retry. */
        private const val RETRY_MS = 3_000L
        /** Playing time with a video track and no frame drawn before the window acts. */
        private const val NO_PICTURE_MS = 4_000L

        /** TV app only, and not on devices Android marks as low on memory. */
        fun supported(ctx: Context): Boolean =
            !PHONE && !ctx.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
    }
}
