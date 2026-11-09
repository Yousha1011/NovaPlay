package io.github.novatv.play

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
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
    /** The player [listener] is on: one listener per player, added when the player is built. */
    private var listening: ExoPlayer? = null
    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY && !ready) { ready = true; onEvent?.invoke(PreviewEvent.Ready) }
        }
        override fun onPlayerError(e: PlaybackException) { onEvent?.invoke(PreviewEvent.Error(playbackMessage(e))) }
    }

    override fun play(url: String, onEvent: (PreviewEvent) -> Unit) {
        // Full screen owns the one player: the window waits rather than take it (it shows this until the user moves on).
        if (holder.mode == LivePlayerHolder.Mode.FULL) { onEvent(PreviewEvent.Error("Playing full screen")); return }
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

    /**
     * Full screen takes the player: the preview stops listening to it (full screen has its own listener and its own
     * retries), and the small window stops drawing it. Returns the player, or null if it isn't fit to hand over.
     */
    fun handOff(): ExoPlayer? {
        if (holder.mode != LivePlayerHolder.Mode.PREVIEW) return null
        // Still loading (or failed): the player goes as an empty one, and full screen opens the channel in it.
        val p = if (holder.healthy()) holder.player ?: return null
            else holder.acquire().also { it.stop(); it.clearMediaItems() }
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
        if (holder.mode != LivePlayerHolder.Mode.FULL) return false
        holder.toPreview()
        if (listening !== p) { p.addListener(listener); listening = p }
        this.onEvent = onEvent
        ready = p.playbackState == Player.STATE_READY
        return true
    }

    override fun release() {
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
        AndroidView(factory = { c ->
            PlayerView(c).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }, update = { it.player = p }, onRelease = { it.player = null }, modifier = modifier)
    }

    companion object {
        /** TV app only, and not on devices Android marks as low on memory. */
        fun supported(ctx: Context): Boolean =
            !PHONE && !ctx.getSystemService(android.app.ActivityManager::class.java).isLowRamDevice
    }
}
