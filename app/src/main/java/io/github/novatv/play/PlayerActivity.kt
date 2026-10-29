package io.github.novatv.play

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.novatv.play.ui.Text
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Http
import io.github.novatv.play.platform.IO
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Programme
import io.github.novatv.play.ui.Badge
import io.github.novatv.play.ui.Bar
import io.github.novatv.play.ui.C
import io.github.novatv.play.ui.Logo
import io.github.novatv.play.ui.NovaTheme
import io.github.novatv.play.ui.Spinner
import io.github.novatv.play.ui.T
import io.github.novatv.play.ui.Tile
import io.github.novatv.play.ui.clock
import io.github.novatv.play.ui.duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The player. Live TV: Up/Down (or Channel +/-) zap, OK shows what's on, Left opens the channel list, Menu picks
 * audio and subtitles. Movies and episodes: OK plays/pauses, Left/Right seek, the next episode follows on its own.
 */
@SuppressLint("UnsafeOptInUsageError")
class PlayerActivity : ComponentActivity() {
    private lateinit var player: ExoPlayer
    private var item: PlayItem? = null

    // UI state read by the Compose overlay.
    private var title by mutableStateOf("")
    private var channelIndex by mutableIntStateOf(0)
    private var showInfo by mutableStateOf(true)
    private var showList by mutableStateOf(false)
    private var showTracks by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var epg by mutableStateOf<List<Programme>>(emptyList())
    private var position by mutableLongStateOf(0L)
    private var length by mutableLongStateOf(0L)
    private var playing by mutableStateOf(true)
    private var tracks by mutableStateOf<Tracks?>(null)
    private var resize by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var playerView: PlayerView? = null
    private var inPip by mutableStateOf(false)
    /** The toolbar under the info bar (Play/Pause, Favourite, Audio, Aspect, Channels, Multi-view). */
    private var showControls by mutableStateOf(false)

    // Multi-view: a second, muted-until-chosen stream beside the first. Only where the decoder can run two at once.
    private var player2: ExoPlayer? = null
    private var multi by mutableStateOf(false)
    private var channel2 by mutableIntStateOf(0)
    private var active by mutableIntStateOf(0)
    private var picking by mutableStateOf(false)
    private var loading2 by mutableStateOf(false)
    private var error2 by mutableStateOf<String?>(null)
    private var startJob2: Job? = null

    private var hideJob: Job? = null
    private var startJob: Job? = null
    private var retried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        item = App.pending ?: run { finish(); return }
        player = buildPlayer(light = false)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) { error = null; retried = false; length = player.duration.coerceAtLeast(0) }
                if (state == Player.STATE_ENDED) onEnded()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                // Live streams drop now and then: one quiet retry before showing the error.
                if (item is PlayItem.Live && !retried) { retried = true; lifecycleScope.launch { delay(1500); startCurrent() }; return }
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused this stream"
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Could not reach the server"
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This stream's format is not supported"
                    else -> "Playback failed (${e.errorCodeName})"
                }
            }
        })
        (item as? PlayItem.Live)?.let { channelIndex = it.channelIndex }
        if (canPip() && android.os.Build.VERSION.SDK_INT >= 31) setPictureInPictureParams(pipParams())
        setContent { NovaTheme { Screen() } }
        startCurrent()
        // Position ticker and periodic progress saving.
        lifecycleScope.launch {
            var n = 0
            while (isActive) {
                position = player.currentPosition; length = player.duration.coerceAtLeast(0)
                if (++n % 10 == 0) saveProgress()
                delay(1000)
            }
        }
    }

    /** An ExoPlayer with NovaPlay's buffer setting; [light] keeps a smaller buffer for multi-view's second stream. */
    private fun buildPlayer(light: Boolean): ExoPlayer {
        val buffer = App.store.bufferMs
        val load = if (light) DefaultLoadControl.Builder().setBufferDurationsMs(8_000, 20_000, 1_500, 3_000).build()
            else DefaultLoadControl.Builder()
                .setBufferDurationsMs(maxOf(15_000, buffer * 3), maxOf(50_000, buffer * 8), buffer.coerceAtMost(15_000), buffer)
                .build()
        val data = OkHttpDataSource.Factory(TvHttp.client).setUserAgent(Http.UA)
        return ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .build()
    }

    private fun mediaItem(url: String) = MediaItem.Builder().setUri(Uri.parse(url)).apply {
        val path = url.substringBefore('?').lowercase()
        when {
            path.endsWith(".m3u8") || path.contains("/hls/") -> setMimeType(MimeTypes.APPLICATION_M3U8)
            path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
            path.endsWith(".ts") -> setMimeType(MimeTypes.VIDEO_MP2T)
        }
    }.build()

    // ---------------------------------------------------------------- multi-view

    /**
     * Why two streams can't play here, or null when they can: the device must not be a low-memory one, and a hardware
     * decoder for the current stream's format must allow two instances (many Fire TV sticks allow one).
     */
    private fun multiViewProblem(): String? {
        val am = getSystemService(android.app.ActivityManager::class.java)
        if (am.isLowRamDevice || am.memoryClass < 192) return "This device has too little memory to play two channels."
        val mime = player.videoFormat?.sampleMimeType ?: MimeTypes.VIDEO_H264
        val decoders = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
            .filter { android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated }
        val most = decoders.maxOfOrNull { runCatching { it.getCapabilitiesForType(mime).maxSupportedInstances }.getOrDefault(1) } ?: 0
        return if (most < 2) "This device's video decoder can only play one channel at a time." else null
    }

    /** Multi-view: checks the device, then opens the channel list to pick the second channel. */
    private fun startMulti() {
        if (live == null) return
        multiViewProblem()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return }
        showControls = false; showInfo = false
        picking = true; showList = true
    }

    private fun startSecond(index: Int) {
        val l = live ?: return
        channel2 = index.mod(l.channels.size)
        val p2 = player2 ?: buildPlayer(light = true).also { np ->
            player2 = np
            np.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    loading2 = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                    if (state == Player.STATE_READY) error2 = null
                }
                override fun onPlayerError(e: PlaybackException) { error2 = "This channel would not play" }
            })
        }
        multi = true
        useView(active)
        startJob2?.cancel()
        error2 = null; loading2 = true
        startJob2 = lifecycleScope.launch {
            val url = withContext(IO) { try { App.repo.provider?.liveUrl(l.channels[channel2]) } catch (e: Exception) { null } }
            if (url == null) { error2 = "Could not get the stream address"; loading2 = false; return@launch }
            p2.setMediaItem(mediaItem(url)); p2.prepare(); p2.playWhenReady = true
        }
    }

    /** Sound follows the chosen view; the other one plays muted. */
    private fun useView(a: Int) {
        active = a
        player.volume = if (a == 0) 1f else 0f
        player2?.volume = if (a == 1) 1f else 0f
    }

    /** Leaves multi-view, keeping [keep] (0 or 1) full screen. */
    private fun endMulti(keep: Int) {
        startJob2?.cancel()
        player2?.release(); player2 = null
        multi = false; picking = false
        player.volume = 1f; active = 0
        if (keep == 1) { channelIndex = channel2; zap(0) }
    }

    /** Another title picked while the player sits in its corner window: play that one instead. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        saveProgress()
        item = App.pending ?: return
        (item as? PlayItem.Live)?.let { channelIndex = it.channelIndex }
        retried = false
        startCurrent()
    }

    // ---------------------------------------------------------------- picture in picture (phones)

    private fun pipParams() = android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16, 9)).apply {
        if (android.os.Build.VERSION.SDK_INT >= 31) setAutoEnterEnabled(PHONE).setSeamlessResizeEnabled(true)
    }.build()

    private fun canPip() = PHONE && android.os.Build.VERSION.SDK_INT >= 26 &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Shrinks the player into a small window in a corner; it keeps playing over other apps. */
    private fun minimise() {
        if (!canPip()) return
        showInfo = false; showList = false; showTracks = false
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    // Home while watching: off to the corner (Android 12 and later do it on their own with auto-enter).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (canPip() && android.os.Build.VERSION.SDK_INT < 31 && player.isPlaying) minimise()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) { showInfo = false; showList = false; showTracks = false }
    }

    private val live get() = item as? PlayItem.Live
    private val channel: Channel? get() = live?.let { it.channels.getOrNull(channelIndex) }

    /** Resolves the current item's URL (Stalker needs a request) and starts it. */
    private fun startCurrent() {
        startJob?.cancel()
        error = null; loading = true
        val it = item ?: return
        title = if (it is PlayItem.Live) it.channels[channelIndex].name else it.title
        startJob = lifecycleScope.launch {
            val url = withContext(IO) {
                try {
                    val p = App.repo.provider ?: return@withContext null
                    when (it) {
                        is PlayItem.Live -> p.liveUrl(it.channels[channelIndex])
                        is PlayItem.Vod -> it.movie?.let { m -> p.movieUrl(m) } ?: p.episodeUrl(it.series!!, it.episode!!)
                    }
                } catch (e: Exception) { null }
            }
            if (url == null) { error = "Could not get the stream address"; loading = false; return@launch }
            player.setMediaItem(mediaItem(url))
            if (it is PlayItem.Vod) App.store.progress(it.key)?.let { p -> player.seekTo(p.first) }
            player.prepare()
            player.playWhenReady = true
            if (it is PlayItem.Live) {
                epg = emptyList()
                val ch = it.channels[channelIndex]
                epg = withContext(IO) { App.repo.nowNext(ch) }
            }
        }
        flashInfo()
    }

    private fun flashInfo(ms: Long = 5000) {
        showInfo = true
        hideJob?.cancel()
        hideJob = lifecycleScope.launch { delay(ms); if (playing || item is PlayItem.Live) { showInfo = false; showControls = false } }
    }

    private fun zap(delta: Int) {
        val l = live ?: return
        channelIndex = (channelIndex + delta).mod(l.channels.size)
        val c = l.channels[channelIndex]
        App.store.addRecentChannel(App.repo.channelKey(c)); App.store.lastChannel = App.repo.channelKey(c)
        retried = false
        startCurrent()
    }

    private fun jumpTo(index: Int) {
        val l = live ?: return
        showList = false
        if (picking) { picking = false; startSecond(index); return }
        channelIndex = index.coerceIn(0, l.channels.lastIndex)
        zap(0)
    }

    /** Shows the toolbar and keeps it up while it is being used. */
    private fun openControls() { showControls = true; flashInfo(10_000) }

    private fun onEnded() {
        val v = item as? PlayItem.Vod ?: return
        saveProgress(ended = true)
        val next = v.nextEpisodes.firstOrNull()
        val series = v.series
        if (next != null && series != null) {
            Toast.makeText(this, "Next: S${next.season} E${next.number}", Toast.LENGTH_SHORT).show()
            item = PlayItem.Vod(App.repo.episodeKey(series, next), "${series.name} · S${next.season} E${next.number}", episode = next,
                series = series, nextEpisodes = v.nextEpisodes.drop(1))
            startCurrent()
        } else finish()
    }

    private fun saveProgress(ended: Boolean = false) {
        val v = item as? PlayItem.Vod ?: return
        val dur = player.duration
        if (dur <= 0) return
        val poster = v.movie?.poster ?: v.series?.poster
        App.store.saveProgress(v.key, v.title, poster, if (ended) dur else player.currentPosition, dur)
    }

    private fun seek(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceIn(0, maxOf(0, player.duration)))
        position = player.currentPosition
        flashInfo()
    }

    /**
     * Keys go to the player first; the channel list, track panel and toolbar take over the D-pad while open.
     * Live: Up/Down zap, Left opens channels, OK opens the toolbar. Films: OK plays/pauses, Left/Right seek, Down opens
     * the toolbar. Multi-view: Left/Right pick the view with sound, Up/Down change its channel, OK or Back keep it full screen.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (showList || showTracks || showControls) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU) {
                if (e.action == KeyEvent.ACTION_UP) {
                    if (showList || showTracks) { showList = false; showTracks = false; picking = false } else { showControls = false; showInfo = false }
                }
                return true
            }
            if (showControls && e.action == KeyEvent.ACTION_DOWN) flashInfo(10_000)
            return super.dispatchKeyEvent(e)
        }
        if (multi) {
            if (e.action == KeyEvent.ACTION_UP) return e.keyCode != KeyEvent.KEYCODE_HOME || super.dispatchKeyEvent(e)
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { useView(0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { useView(1); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (active == 0) zap(-1) else startSecond(channel2 - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (active == 0) zap(1) else startSecond(channel2 + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BACK -> { endMulti(active); return true }
            }
            return super.dispatchKeyEvent(e)
        }
        if (e.action != KeyEvent.ACTION_DOWN) return when (e.keyCode) {
            KeyEvent.KEYCODE_BACK -> { if (!e.isCanceled) finish(); true }
            else -> e.keyCode != KeyEvent.KEYCODE_HOME && super.dispatchKeyEvent(e)
        }
        val isLive = item is PlayItem.Live
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (isLive) zap(-1) else { showTracks = true }; return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (isLive) zap(1) else openControls(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (isLive) showList = true else seek(-10_000L * (1 + e.repeatCount / 5)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isLive) flashInfo() else seek(10_000L * (1 + e.repeatCount / 5)); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (error != null) { retried = false; startCurrent(); return true }
                if (isLive && e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) openControls()
                else { if (player.isPlaying) player.pause() else player.play(); flashInfo(if (player.isPlaying) 3000 else 600_000) }
                return true
            }
            KeyEvent.KEYCODE_MENU -> { showTracks = true; return true }
            KeyEvent.KEYCODE_BACK -> return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        // The corner window was closed: stop for good.
        if (inPip) { finish(); return }
        if (::player.isInitialized) player.pause()
        player2?.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && player.playbackState != Player.STATE_IDLE) player.play()
        player2?.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
        player2?.release(); player2 = null
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (multi) MultiView() else AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = this@PlayerActivity.player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    playerView = this
                }
            }, update = { it.resizeMode = resize }, modifier = Modifier.fillMaxSize())

            if (inPip) return@Box
            // Phones: a tap shows the details and toolbar, and a button in the corner minimises the player.
            if (PHONE && !multi) Box(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                if (showInfo) { showInfo = false; showControls = false } else openControls()
            })
            if (PHONE && canPip() && !multi) Box(Modifier.align(Alignment.TopEnd).padding(20.dp).size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0x80000000)).clickable { minimise() }, contentAlignment = Alignment.Center) {
                io.github.novatv.play.ui.Icon(androidx.compose.material.icons.Icons.Rounded.PictureInPictureAlt, size = 26.dp, tint = Color.White)
            }
            if (!multi) {
                if (loading && error == null) Spinner(Modifier.align(Alignment.Center), 56.dp)
                error?.let {
                    Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(14.dp)).background(Color(0xE0101318)).padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(it, style = T.h2)
                        Spacer(Modifier.height(6.dp))
                        Text(if (item is PlayItem.Live) "OK to try again · Up/Down for another channel" else "OK to try again · Back to leave", style = T.small)
                    }
                }
                AnimatedVisibility(showInfo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) { Overlay() }
            }
            AnimatedVisibility(showList, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) { ChannelList() }
            AnimatedVisibility(showTracks, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)) { TracksPanel() }
        }
    }

    /** "1080p · H264 · AAC 2.0": what the stream actually is, from the decoder's formats. */
    private fun techLine(): String? {
        val v = player.videoFormat; val a = player.audioFormat
        fun codec(m: String?) = when {
            m == null -> null
            m.contains("avc") -> "H264"; m.contains("hevc") -> "HEVC"; m.contains("av01") -> "AV1"; m.contains("vp9") -> "VP9"
            m.contains("mp4a") || m.contains("aac") -> "AAC"; m.contains("eac3") -> "E-AC3"; m.contains("ac3") -> "AC3"; m.contains("mpeg") -> "MP2"
            else -> m.substringAfter('/').uppercase()
        }
        return listOfNotNull(v?.height?.takeIf { it > 0 }?.let { "${it}p" }, codec(v?.sampleMimeType),
            listOfNotNull(codec(a?.sampleMimeType), a?.channelCount?.takeIf { it > 0 }?.let { if (it == 6) "5.1" else "$it.0" }).joinToString(" ").ifBlank { null })
            .joinToString(" · ").ifBlank { null }
    }

    /** The info layer: a top bar (channel or title, tech details, clock) and the bottom bar with progress and toolbar. */
    @Composable
    private fun Overlay() {
        val t = System.currentTimeMillis()
        val c = channel
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC05070A), Color.Transparent)))
                .padding(start = 48.dp, end = 48.dp, top = 28.dp, bottom = 50.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c != null) {
                    Logo(c.logo, c.name, Modifier.size(64.dp, 42.dp)); Spacer(Modifier.width(16.dp))
                    if (c.number > 0) { Text("${c.number}", style = T.hero); Spacer(Modifier.width(14.dp)) }
                    Text(c.name, style = T.h1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(14.dp)); io.github.novatv.play.ui.LiveMark()
                } else Text(title, style = T.h1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                techLine()?.let {
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(it, style = T.tiny.copy(color = C.dim))
                    }
                    Spacer(Modifier.width(20.dp))
                }
                Text(clock(t), style = T.h2)
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF205070A))))
                .padding(start = 48.dp, end = 48.dp, top = 70.dp, bottom = 28.dp)) {
                if (c != null) LiveBar(t) else VodBar()
                Spacer(Modifier.height(18.dp))
                if (showControls || PHONE) Toolbar() else Text(
                    if (c != null) "OK  Toolbar     ◀  Channels     ▲▼  Zap     ☰  Audio & subtitles" else "OK  Play/Pause     ◀ ▶  Seek     ▼  Toolbar     ▲  Audio & subtitles",
                    style = T.tiny)
            }
        }
    }

    @Composable
    private fun LiveBar(t: Long) {
        val p = epg.firstOrNull(); val n = epg.getOrNull(1)
        if (p == null) { Text("No guide information for this channel", style = T.body.copy(color = C.dim)); return }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(p.title, style = T.hero, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(20.dp))
            Text("${clock(p.start)} – ${clock(p.stop)}  ·  ${((p.stop - t) / 60_000).coerceAtLeast(0)} min left", style = T.small, maxLines = 1)
            Spacer(Modifier.weight(1f))
            if (n != null) {
                Text("UP NEXT  ", style = T.tiny.copy(letterSpacing = androidx.compose.ui.unit.TextUnit(1.4f, androidx.compose.ui.unit.TextUnitType.Sp)))
                Text("${clock(n.start)}  ${n.title}", style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(320.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Row { Text(clock(p.start), style = T.tiny); Spacer(Modifier.weight(1f)); Text(clock(p.stop), style = T.tiny) }
    }

    @Composable
    private fun VodBar() {
        Bar(if (length > 0) position.toFloat() / length else 0f, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row {
            Text(duration(position), style = T.small)
            Spacer(Modifier.weight(1f))
            if (length > 0) Text("-${duration(length - position)}", style = T.small)
        }
    }

    /** The toolbar: only actions that work here. Focus starts on Play/Pause. */
    @Composable
    private fun Toolbar() {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { if (!PHONE) repeat(5) { try { first.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(50) } } }
        val c = channel
        var favs by remember { mutableIntStateOf(0) }
        val I = androidx.compose.material.icons.Icons.Rounded
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                io.github.novatv.play.ui.Pill(if (playing) "Pause" else "Play", primary = true, modifier = Modifier.focusRequester(first),
                    icon = if (playing) I.Pause else I.PlayArrow, onClick = { if (player.isPlaying) player.pause() else player.play() })
            }
            if (c != null) item {
                val key = App.repo.channelKey(c)
                val fav = remember(favs, c) { App.store.isFavourite(key) }
                io.github.novatv.play.ui.Pill(if (fav) "Favourited" else "Favourite", selected = fav, icon = if (fav) I.Star else I.StarBorder,
                    onClick = { App.store.toggleFavourite(key); favs++ })
            }
            if (c == null) item { io.github.novatv.play.ui.Pill("Start over", icon = I.Replay, onClick = { player.seekTo(0) }) }
            item { io.github.novatv.play.ui.Pill("Audio & subtitles", icon = I.Subtitles, onClick = { showControls = false; showTracks = true }) }
            item {
                val names = mapOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit", AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch")
                io.github.novatv.play.ui.Pill("Aspect: ${names[resize] ?: "Fit"}", icon = I.AspectRatio, onClick = {
                    resize = when (resize) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
                })
            }
            if (c != null) {
                item { io.github.novatv.play.ui.Pill("Channels", icon = I.List, onClick = { showControls = false; showList = true }) }
                if (!PHONE) item { io.github.novatv.play.ui.Pill("Multi-view", icon = I.GridView, onClick = { startMulti() }) }
            }
        }
    }

    /** Two channels side by side; the one with sound has a cyan frame. */
    @Composable
    private fun MultiView() {
        val l = live ?: return
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0..1) {
                    val c = l.channels.getOrNull(if (i == 0) channelIndex else channel2)
                    Box(Modifier.weight(1f).androidxAspect().clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0E13))
                        .then(if (active == i) Modifier.androidxBorder(3.dp, C.accent) else Modifier.androidxBorder(1.dp, C.line))) {
                        AndroidView(factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            }
                        }, update = { v -> v.player = if (i == 0) player else player2 }, modifier = Modifier.fillMaxSize().padding(3.dp))
                        if (i == 0 && loading || i == 1 && loading2) Spinner(Modifier.align(Alignment.Center), 40.dp)
                        (if (i == 0) error else error2)?.let { Text(it, style = T.small.copy(color = C.text), modifier = Modifier.align(Alignment.Center)) }
                        if (c != null) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (c.number > 0) { Text("${c.number}", style = T.h2); Spacer(Modifier.width(10.dp)) }
                            Text(c.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (active == i) Text("♪", style = T.h2.copy(color = C.accent))
                        }
                    }
                }
            }
            Text("◀ ▶  Sound     ▲▼  Change channel     OK / BACK  Full screen", style = T.tiny, modifier = Modifier.align(Alignment.End))
        }
    }

    private fun Modifier.androidxAspect() = this.then(Modifier.aspectRatio(16f / 9f))
    private fun Modifier.androidxBorder(w: androidx.compose.ui.unit.Dp, c: Color) = this.then(Modifier.border(w, c, RoundedCornerShape(10.dp)))

    @Composable
    private fun ChannelList() {
        val l = live ?: return
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (channelIndex - 4).coerceAtLeast(0))
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(5) { try { focus.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(50) } } }
        LazyColumn(Modifier.width(460.dp).fillMaxHeight().background(Color(0xF00A0E17)), state = state,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (picking) item { Text("Pick the second channel", style = T.h2, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)) }
            itemsIndexed(l.channels, key = { _, c -> c.id }) { i, c ->
                Tile({ jumpTo(i) }, Modifier.fillMaxWidth().height(58.dp).let { if (i == channelIndex) it.focusRequester(focus) else it },
                    shape = RoundedCornerShape(10.dp), scale = 1.02f, color = if (i == channelIndex) C.accent.copy(alpha = 0.25f) else Color.Transparent) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${c.number}", style = T.small, modifier = Modifier.width(48.dp))
                        Logo(c.logo, c.name, Modifier.size(56.dp, 38.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @Composable
    private fun TracksPanel() {
        val t = tracks
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(5) { try { focus.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(50) } } }
        LazyColumn(Modifier.width(420.dp).fillMaxHeight().background(Color(0xF00A0E17)), contentPadding = PaddingValues(22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Text("Picture", style = T.h2, modifier = Modifier.padding(bottom = 6.dp)) }
            val modes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit", AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill (crop)", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch")
            modes.forEachIndexed { i, (m, label) ->
                item { Choice(label, resize == m, if (i == 0) Modifier.focusRequester(focus) else Modifier) { resize = m } }
            }
            for ((type, label) in listOf(MC.TRACK_TYPE_AUDIO to "Audio", MC.TRACK_TYPE_TEXT to "Subtitles")) {
                val groups = t?.groups?.filter { it.type == type }.orEmpty()
                if (groups.isEmpty()) continue
                item { Text(label, style = T.h2, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                if (type == MC.TRACK_TYPE_TEXT) item {
                    val off = player.trackSelectionParameters.disabledTrackTypes.contains(MC.TRACK_TYPE_TEXT)
                    Choice("Off", off) { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(MC.TRACK_TYPE_TEXT, true).build() }
                }
                for (g in groups) for (k in 0 until g.length) {
                    val f = g.getTrackFormat(k)
                    val name = listOfNotNull(f.label, f.language?.let { java.util.Locale(it).displayLanguage }, f.channelCount.takeIf { it > 0 }?.let { "$it ch" })
                        .distinct().joinToString(" · ").ifBlank { "Track ${k + 1}" }
                    item {
                        Choice(name, g.isTrackSelected(k)) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(type, false)
                                .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, k)).build()
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Choice(label: String, on: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
        Tile(onClick, modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(10.dp), scale = 1.02f,
            color = if (on) C.accent.copy(alpha = 0.25f) else Color.Transparent) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = T.body, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (on) Text("✓", style = T.h2.copy(color = C.accent))
            }
        }
    }
}
