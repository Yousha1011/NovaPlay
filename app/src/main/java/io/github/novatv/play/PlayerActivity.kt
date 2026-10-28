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

    private var hideJob: Job? = null
    private var startJob: Job? = null
    private var retried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        item = App.pending ?: run { finish(); return }
        val buffer = App.store.bufferMs
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(maxOf(15_000, buffer * 3), maxOf(50_000, buffer * 8), buffer.coerceAtMost(15_000), buffer)
            .build()
        val data = OkHttpDataSource.Factory(TvHttp.client).setUserAgent(Http.UA)
        player = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER))
            .setLoadControl(load)
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .build()
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
            val mi = MediaItem.Builder().setUri(Uri.parse(url)).apply {
                val path = url.substringBefore('?').lowercase()
                when {
                    path.endsWith(".m3u8") || path.contains("/hls/") -> setMimeType(MimeTypes.APPLICATION_M3U8)
                    path.endsWith(".mpd") -> setMimeType(MimeTypes.APPLICATION_MPD)
                    path.endsWith(".ts") -> setMimeType(MimeTypes.VIDEO_MP2T)
                }
            }.build()
            player.setMediaItem(mi)
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
        hideJob = lifecycleScope.launch { delay(ms); if (playing || item is PlayItem.Live) showInfo = false }
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
        channelIndex = index.coerceIn(0, l.channels.lastIndex)
        showList = false
        zap(0)
    }

    private fun onEnded() {
        val v = item as? PlayItem.Vod ?: return
        saveProgress(ended = true)
        val next = v.nextEpisodes.firstOrNull()
        val series = v.series
        if (next != null && series != null) {
            Toast.makeText(this, "Next: S${next.season} E${next.number}", Toast.LENGTH_SHORT).show()
            item = PlayItem.Vod(App.repo.episodeKey(series, next), "${series.name} Â· S${next.season} E${next.number}", episode = next,
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

    /** Keys go to the player first; the channel list and track panels take over the D-pad while open. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (showList || showTracks) {
            if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU) {
                if (e.action == KeyEvent.ACTION_UP) { showList = false; showTracks = false }
                return true
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
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (isLive) zap(1) else flashInfo(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (isLive) showList = true else seek(-10_000L * (1 + e.repeatCount / 5)); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (isLive) flashInfo() else seek(10_000L * (1 + e.repeatCount / 5)); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (error != null) { retried = false; startCurrent(); return true }
                if (isLive && e.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { if (showInfo) showInfo = false else flashInfo(8000) }
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
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && player.playbackState != Player.STATE_IDLE) player.play()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
    }

    // ---------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = this@PlayerActivity.player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    playerView = this
                }
            }, update = { it.resizeMode = resize }, modifier = Modifier.fillMaxSize())

            if (inPip) return@Box
            // Phones: a tap shows the details, and a button in the corner minimises the player.
            if (PHONE) Box(Modifier.fillMaxSize().clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null) {
                if (showInfo) showInfo = false else flashInfo()
            })
            if (PHONE && canPip()) Box(Modifier.align(Alignment.TopEnd).padding(20.dp).size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(Color(0x80000000)).clickable { minimise() }, contentAlignment = Alignment.Center) {
                io.github.novatv.play.ui.Icon(androidx.compose.material.icons.Icons.Rounded.PictureInPictureAlt, size = 26.dp, tint = Color.White)
            }
            if (loading && error == null) Spinner(Modifier.align(Alignment.Center), 56.dp)
            error?.let {
                Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp)).background(Color(0xE0101520)).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(it, style = T.h2)
                    Spacer(Modifier.height(6.dp))
                    Text(if (item is PlayItem.Live) "OK to try again Â· Up/Down for another channel" else "OK to try again Â· Back to leave", style = T.small)
                }
            }
            AnimatedVisibility(showInfo, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
                if (item is PlayItem.Live) LiveInfo() else VodInfo()
            }
            AnimatedVisibility(showList, enter = slideInHorizontally { -it } + fadeIn(), exit = slideOutHorizontally { -it } + fadeOut()) { ChannelList() }
            AnimatedVisibility(showTracks, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd)) { TracksPanel() }
        }
    }

    @Composable
    private fun LiveInfo() {
        val c = channel ?: return
        val p = epg.firstOrNull(); val n = epg.getOrNull(1)
        val t = System.currentTimeMillis()
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE05070C)))).padding(start = 60.dp, end = 60.dp, top = 80.dp, bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${c.number}", style = T.hero.copy(color = C.dim), modifier = Modifier.width(110.dp))
                Logo(c.logo, c.name, Modifier.size(120.dp, 80.dp))
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, style = T.h1); Spacer(Modifier.width(12.dp)); Badge("LIVE")
                    }
                    if (p != null) {
                        Spacer(Modifier.height(6.dp))
                        Text("${clock(p.start)} â€“ ${clock(p.stop)}   ${p.title}", style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.width(520.dp))
                        if (n != null) { Spacer(Modifier.height(8.dp)); Text("Next  ${clock(n.start)}  ${n.title}", style = T.small, maxLines = 1) }
                    } else Text("No guide information", style = T.small)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(clock(t), style = T.h1)
                    Text("â—€ channels   â–²â–¼ zap   â˜° audio", style = T.tiny)
                }
            }
        }
    }

    @Composable
    private fun VodInfo() {
        Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE05070C)))).padding(start = 60.dp, end = 60.dp, top = 80.dp, bottom = 40.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (playing) "â–¶" else "âšâš", style = T.h1, modifier = Modifier.width(52.dp))
                    Text(title, style = T.h1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(clock(System.currentTimeMillis()), style = T.h2)
                }
                Spacer(Modifier.height(14.dp))
                Bar(if (length > 0) position.toFloat() / length else 0f, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(duration(position), style = T.small)
                    Spacer(Modifier.weight(1f))
                    Text("â—€ â–¶ seek   â–² audio & subtitles   " + (if (length > 0) "-${duration(length - position)}" else ""), style = T.small)
                }
            }
        }
    }

    @Composable
    private fun ChannelList() {
        val l = live ?: return
        val state = rememberLazyListState(initialFirstVisibleItemIndex = (channelIndex - 4).coerceAtLeast(0))
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(5) { try { focus.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(50) } } }
        LazyColumn(Modifier.width(460.dp).fillMaxHeight().background(Color(0xF00A0E17)), state = state,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        .distinct().joinToString(" Â· ").ifBlank { "Track ${k + 1}" }
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
                if (on) Text("âœ“", style = T.h2.copy(color = C.accent))
            }
        }
    }
}
