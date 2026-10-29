package io.github.novatv.play

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.novatv.play.data.Http
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
    private var player by mutableStateOf<ExoPlayer?>(null)

    override fun play(url: String, onEvent: (PreviewEvent) -> Unit) {
        release()
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(4_000, 10_000, 1_000, 2_000).build()
        val p = ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(TvHttp.client).setUserAgent(Http.UA)))
            .build()
        p.volume = 0f
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setMaxVideoSize(1280, 720).build()
        var ready = false
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && !ready) { ready = true; onEvent(PreviewEvent.Ready) }
            }
            override fun onPlayerError(e: PlaybackException) { onEvent(PreviewEvent.Error(playbackMessage(e))) }
        })
        p.setMediaItem(mediaItem(url))
        p.prepare()
        p.playWhenReady = true
        player = p
    }

    override fun release() {
        player?.release()
        player = null
    }

    @Composable
    override fun Video(modifier: Modifier) {
        val p = player
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
