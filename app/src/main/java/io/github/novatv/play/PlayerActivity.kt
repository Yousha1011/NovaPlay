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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FiberManualRecord
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
import androidx.compose.foundation.layout.offset
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
    private var resize by mutableIntStateOf(when (App.store.defaultAspect) {
        "FILL" -> AspectRatioFrameLayout.RESIZE_MODE_FILL; "ZOOM" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    })
    private var playerView: PlayerView? = null
    private var inPip by mutableStateOf(false)
    /** The toolbar under the info bar (Play/Pause, Favourite, Audio, Aspect, Channels, Multi-view). */
    private var showControls by mutableStateOf(false)

    /** One more screen in multi-view: its own player, channel and reconnecting. The first screen is [player]. */
    private inner class Slot(var channel: Int) {
        val player = buildPlayer(light = true)
        var loading by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var job: Job? = null
        /** When it started waiting for picture (0 while playing), for the stall watchdog. */
        var waitingSince = 0L
        var failures = 0
    }

    // Multi-view: up to MultiView.MAX screens; the one with sound (and a cyan frame) is [active].
    private val extra = androidx.compose.runtime.mutableStateListOf<Slot>()
    private val multi get() = extra.isNotEmpty()
    private var active by mutableIntStateOf(0)
    /** The channel list is open to pick a channel for screen [pickFor], or for a new screen when it's -1. */
    private var picking by mutableStateOf(false)
    private var pickFor = -1
    /** The multi-view action bar (TV: OK opens it; phones: always shown). */
    private var multiMenu by mutableStateOf(false)
    private var mainWaitingSince = 0L
    private var mainFailures = 0

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
                if (loading) { if (mainWaitingSince == 0L) mainWaitingSince = System.currentTimeMillis() } else mainWaitingSince = 0L
                if (state == Player.STATE_READY) { error = null; retried = false; mainFailures = 0; length = player.duration.coerceAtLeast(0) }
                // A live stream that "ends" has dropped: open it again.
                if (state == Player.STATE_ENDED) { if (item is PlayItem.Live) reconnectMain() else onEnded() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onPlayerError(e: PlaybackException) {
                // Live streams drop now and then: one quiet retry before showing the error.
                // In multi-view every screen keeps reconnecting, so one dropped stream never stays stopped.
                if (item is PlayItem.Live && multi) { reconnectMain(); return }
                if (item is PlayItem.Live && !retried) { retried = true; lifecycleScope.launch { delay(1500); startCurrent() }; return }
                error = playbackMessage(e)
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
                if (multi) watchdog()
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

    // ---------------------------------------------------------------- multi-view

    /**
     * Why [screens] streams can't play here, or null when they can: the device must not be a low-memory one, and its
     * decoders for the current stream's format must allow that many at once (many Fire TV sticks allow one).
     */
    private fun multiViewProblem(screens: Int): String? {
        val am = getSystemService(android.app.ActivityManager::class.java)
        if (am.isLowRamDevice || am.memoryClass < 192) return "This device has too little memory to play two channels."
        val mime = player.videoFormat?.sampleMimeType ?: MimeTypes.VIDEO_H264
        val decoders = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } }
        val hardware = decoders.filter { android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated }
        val most = hardware.maxOfOrNull { runCatching { it.getCapabilitiesForType(mime).maxSupportedInstances }.getOrDefault(1) } ?: 0
        // Software decoders take up the slack on devices with memory to spare (phones, PCs running Android); decoder
        // fallback is on.
        val soft = decoders.size > hardware.size && am.memoryClass >= 256
        return when {
            most >= screens || soft -> null
            most < 2 -> "This device's video decoder can only play one channel at a time."
            else -> "This device can play $most channels at once."
        }
    }

    /** Adds a screen: checks the device, then opens the channel list to pick its channel. */
    private fun startMulti() {
        if (live == null) return
        val max = io.github.novatv.play.data.MultiView.MAX
        if (1 + extra.size >= max) { Toast.makeText(this, "Multi-view shows up to $max channels", Toast.LENGTH_SHORT).show(); return }
        multiViewProblem(2 + extra.size)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show(); return }
        showControls = false; showInfo = false; multiMenu = false
        pickFor = -1; picking = true; showList = true
    }

    /** Opens the channel list to change screen [i]'s channel. */
    private fun changeScreen(i: Int) { multiMenu = false; pickFor = i; picking = true; showList = true }

    private fun channelOf(i: Int) = if (i == 0) channelIndex else extra.getOrNull(i - 1)?.channel ?: channelIndex

    /** Screen [i] plays channel [index]. */
    private fun setScreen(i: Int, index: Int) {
        val l = live ?: return
        val idx = index.mod(l.channels.size)
        if (i == 0) { channelIndex = idx; mainFailures = 0; zap(0); return }
        val slot = extra.getOrNull(i - 1) ?: return
        slot.channel = idx; slot.failures = 0
        startSlot(slot)
    }

    private fun addScreen(index: Int) {
        val l = live ?: return
        val slot = Slot(index.mod(l.channels.size))
        slot.player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                slot.loading = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (slot.loading) { if (slot.waitingSince == 0L) slot.waitingSince = System.currentTimeMillis() } else slot.waitingSince = 0L
                if (state == Player.STATE_READY) { slot.error = null; slot.failures = 0 }
                if (state == Player.STATE_ENDED) reconnect(slot)
            }
            override fun onPlayerError(e: PlaybackException) { reconnect(slot) }
        })
        extra.add(slot)
        useView(extra.size)
        startSlot(slot)
        // A playlist that allows fewer streams than screens makes one of them stop: say so.
        val screens = 1 + extra.size
        lifecycleScope.launch {
            withContext(IO) { io.github.novatv.play.data.MultiView.limitWarning(screens) }?.let { Toast.makeText(this@PlayerActivity, it, Toast.LENGTH_LONG).show() }
        }
    }

    private fun startSlot(slot: Slot) {
        val l = live ?: return
        slot.job?.cancel()
        slot.error = null; slot.loading = true; slot.waitingSince = System.currentTimeMillis()
        slot.job = lifecycleScope.launch {
            val url = withContext(IO) { try { App.repo.provider?.liveUrl(l.channels[slot.channel]) } catch (e: Exception) { null } }
            if (url == null) { reconnect(slot); return@launch }
            slot.player.stop()
            slot.player.setMediaItem(mediaItem(url)); slot.player.prepare(); slot.player.playWhenReady = true
        }
    }

    /** A screen's stream dropped: try again shortly, and keep trying. */
    private fun reconnect(slot: Slot) {
        if (slot !in extra) return
        slot.failures++
        slot.error = "Reconnecting…"
        slot.job?.cancel()
        slot.job = lifecycleScope.launch { delay(io.github.novatv.play.data.MultiView.retryDelay(slot.failures)); if (slot in extra) startSlot(slot) }
    }

    private fun reconnectMain() {
        mainFailures++
        error = "Reconnecting…"
        startJob?.cancel()
        startJob = lifecycleScope.launch { delay(io.github.novatv.play.data.MultiView.retryDelay(mainFailures)); startCurrent() }
    }

    /** Every second in multi-view: a screen stuck buffering for too long gets its stream opened again. */
    private fun watchdog() {
        val now = System.currentTimeMillis()
        val limit = io.github.novatv.play.data.MultiView.STALL_MS
        if (mainWaitingSince > 0 && now - mainWaitingSince > limit) { mainWaitingSince = now; startCurrent() }
        for (slot in extra.toList()) {
            if (slot.waitingSince > 0 && now - slot.waitingSince > limit) { slot.waitingSince = now; startSlot(slot) }
            // Paused by the system (audio focus, a decoder reset): carry on.
            if (!slot.player.playWhenReady && slot.player.playbackState == Player.STATE_READY) slot.player.play()
        }
        if (!player.playWhenReady && player.playbackState == Player.STATE_READY) player.play()
    }

    /** Sound follows the chosen screen; the others play muted. */
    private fun useView(a: Int) {
        active = a.coerceIn(0, extra.size)
        player.volume = if (active == 0) 1f else 0f
        extra.forEachIndexed { i, slot -> slot.player.volume = if (active == i + 1) 1f else 0f }
    }

    /** Removes screen [i]; with one left, that one goes full screen. */
    private fun removeScreen(i: Int) {
        multiMenu = false
        if (extra.size == 1) { endMulti(if (i == 0) 1 else 0); return }
        if (i == 0) {
            // The first screen's player goes on with the next screen's channel.
            val next = extra.removeAt(0)
            next.job?.cancel(); next.player.release()
            channelIndex = next.channel; zap(0)
        } else extra.removeAt(i - 1).let { it.job?.cancel(); it.player.release() }
        useView(minOf(active, extra.size))
    }

    /** Leaves multi-view, keeping screen [keep] full screen. */
    private fun endMulti(keep: Int) {
        val ch = channelOf(keep)
        for (slot in extra) { slot.job?.cancel(); slot.player.release() }
        extra.clear()
        picking = false; multiMenu = false
        player.volume = 1f; active = 0; mainFailures = 0
        if (keep != 0) { channelIndex = ch; zap(0) }
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
                        is PlayItem.Vod -> it.movie?.let { m -> m.url?.takeIf { u -> u.startsWith("file:") } ?: p.movieUrl(m) }
                            ?: p.episodeUrl(it.series!!, it.episode!!)
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
        if (picking) { picking = false; if (pickFor < 0) addScreen(index) else setScreen(pickFor, index); return }
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
        // The next episode follows unless that's turned off in Settings.
        if (next != null && series != null && App.store.autoNextEpisode) {
            Toast.makeText(this, "Next: S${next.season} E${next.number}", Toast.LENGTH_SHORT).show()
            item = PlayItem.Vod(App.repo.episodeKey(series, next), "${series.name} · S${next.season} E${next.number}", episode = next,
                series = series, nextEpisodes = v.nextEpisodes.drop(1))
            startCurrent()
        } else finish()
    }

    private fun saveProgress(ended: Boolean = false) {
        val v = item as? PlayItem.Vod ?: return
        if (v.key.startsWith("rec:")) return
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
        if (multi && multiMenu) {
            // The action bar has the D-pad; Back closes it.
            if (e.keyCode == KeyEvent.KEYCODE_BACK || e.keyCode == KeyEvent.KEYCODE_MENU) { if (e.action == KeyEvent.ACTION_UP) multiMenu = false; return true }
            return super.dispatchKeyEvent(e)
        }
        if (multi) {
            // Back on release: acting on the press would leave multi-view, and the release would then close the player.
            if (e.keyCode == KeyEvent.KEYCODE_BACK) { if (e.action == KeyEvent.ACTION_UP && !e.isCanceled) endMulti(active); return true }
            if (e.action == KeyEvent.ACTION_UP) return e.keyCode != KeyEvent.KEYCODE_HOME || super.dispatchKeyEvent(e)
            val n = 1 + extra.size
            val cols = io.github.novatv.play.data.MultiView.grid(n).first
            when (e.keyCode) {
                // The arrows move the sound (and the frame) around the grid.
                KeyEvent.KEYCODE_DPAD_LEFT -> { useView((active - 1).mod(n)); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { useView((active + 1).mod(n)); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { if (active - cols >= 0) useView(active - cols); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (active + cols < n) useView(active + cols); return true }
                KeyEvent.KEYCODE_CHANNEL_UP -> { setScreen(active, channelOf(active) - 1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { setScreen(active, channelOf(active) + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU -> { multiMenu = true; return true }
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
        extra.forEach { it.player.pause() }
    }

    override fun onStart() {
        super.onStart()
        if (::player.isInitialized && player.playbackState != Player.STATE_IDLE) player.play()
        extra.forEach { it.player.play() }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::player.isInitialized) player.release()
        extra.forEach { it.job?.cancel(); it.player.release() }
        extra.clear()
    }

    /** Subtitle size, background and height from Settings › Movies & Series (text subtitles; image ones keep their look). */
    private fun styleSubtitles(v: androidx.media3.ui.SubtitleView) {
        val st = App.store
        val bg = when (st.subtitleBackground) { "BOX" -> 0xB3000000.toInt(); "SOLID" -> 0xFF000000.toInt(); else -> android.graphics.Color.TRANSPARENT }
        val edge = if (st.subtitleBackground == "SHADOW") androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW else androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
        v.setStyle(androidx.media3.ui.CaptionStyleCompat(android.graphics.Color.WHITE, bg, android.graphics.Color.TRANSPARENT, edge, android.graphics.Color.BLACK, null))
        val size = when (st.subtitleSize) { "SMALL" -> 0.8f; "LARGE" -> 1.25f; "XLARGE" -> 1.5f; else -> 1f }
        v.setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * size)
        v.setApplyEmbeddedStyles(false)
        v.setBottomPaddingFraction(when (st.subtitlePosition) { "RAISED" -> 0.16f; "HIGH" -> 0.28f; else -> androidx.media3.ui.SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION })
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
                    subtitleView?.let { styleSubtitles(it) }
                }
            }, update = { it.resizeMode = resize }, modifier = Modifier.fillMaxSize())

            if (inPip) return@Box
            // Settings › Live TV & Guide: the time stays in the corner while the details are hidden.
            if (App.store.playerClock && !showInfo) {
                val time by androidx.compose.runtime.produceState(System.currentTimeMillis()) { while (true) { delay(10_000); value = System.currentTimeMillis() } }
                Box(Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x99000000))
                    .padding(horizontal = 12.dp, vertical = 4.dp)) { Text(clock(time), style = T.body) }
            }
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
                if (showControls || PHONE) Toolbar() else if (App.store.showHints) Text(
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
                if (App.host.recorder != null) item {
                    val v = io.github.novatv.play.data.Recordings.version
                    val rec = remember(v, c) { io.github.novatv.play.data.Recordings.at(c, System.currentTimeMillis()) }
                    io.github.novatv.play.ui.Pill(if (rec != null) "Stop recording" else "Record", icon = I.FiberManualRecord, selected = rec != null, onClick = {
                        if (rec != null) { io.github.novatv.play.data.Recordings.cancel(rec.id); Toast.makeText(this@PlayerActivity, "Recording stopped", Toast.LENGTH_SHORT).show() }
                        else Toast.makeText(this@PlayerActivity, io.github.novatv.play.data.Recordings.schedule(c, epg.firstOrNull()?.takeIf { it.isLive })
                            ?: "Recording ${epg.firstOrNull()?.takeIf { it.isLive }?.title ?: "the next hour"}", Toast.LENGTH_SHORT).show()
                    })
                }
                item { io.github.novatv.play.ui.Pill("Multi-view", icon = I.GridView, onClick = { startMulti() }) }
            }
        }
    }

    /** Two to four channels in a grid; the one with sound has a cyan frame. */
    @Composable
    private fun MultiView() {
        val l = live ?: return
        val n = 1 + extra.size
        val (cols, rows) = io.github.novatv.play.data.MultiView.grid(n)
        Column(Modifier.fillMaxSize().padding(if (PHONE) 8.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Each screen is 16:9 and as big as fits; a third one sits in the middle of the second row. The screens are
            // one flat list placed by position (not rows), so a screen keeps its picture when the grid changes shape.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val gap = 10.dp
                val tileW = minOf((maxWidth - gap * (cols - 1)) / cols, (maxHeight - gap * (rows - 1)) / rows * (16f / 9f))
                val tileH = tileW * (9f / 16f)
                val top = (maxHeight - (tileH * rows + gap * (rows - 1))) / 2
                for (i in 0 until n) {
                    val r = i / cols
                    val inRow = minOf(cols, n - r * cols)
                    val x = (maxWidth - (tileW * inRow + gap * (inRow - 1))) / 2 + (tileW + gap) * (i % cols)
                    val y = top + (tileH + gap) * r
                    val slot = extra.getOrNull(i - 1)
                    val c = l.channels.getOrNull(channelOf(i))
                    androidx.compose.runtime.key(if (slot == null) 0 else System.identityHashCode(slot)) {
                        Box(Modifier.offset(x, y).size(tileW, tileH).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0E13))
                            .then(if (active == i) Modifier.androidxBorder(3.dp, C.accent) else Modifier.androidxBorder(1.dp, C.line))
                            .then(if (PHONE) Modifier.clickable { useView(i) } else Modifier)) {
                            AndroidView(factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                }
                            }, update = { v -> v.player = slot?.player ?: player }, modifier = Modifier.fillMaxSize().padding(3.dp))
                            val busy = if (slot == null) loading else slot.loading
                            val err = if (slot == null) error else slot.error
                            if (busy && err == null) Spinner(Modifier.align(Alignment.Center), 36.dp)
                            err?.let { Text(it, style = T.small.copy(color = C.text), modifier = Modifier.align(Alignment.Center)) }
                            if (c != null) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                                .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (c.number > 0) { Text("${c.number}", style = T.body); Spacer(Modifier.width(8.dp)) }
                                Text(c.name, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                if (active == i) Text("♪", style = T.h2.copy(color = C.accent))
                            }
                        }
                    }
                }
            }
            if (multiMenu || PHONE) MultiActions(n)
            else if (App.store.showHints) Text("◀ ▶ ▲ ▼  Choose screen (sound follows)     OK  Add · change · remove     CH +/-  Zap     BACK  Full screen",
                style = T.tiny, modifier = Modifier.align(Alignment.End))
        }
    }

    /** What can be done with the chosen screen: add one, change its channel, remove it, or watch it full screen. */
    @Composable
    private fun MultiActions(n: Int) {
        val first = remember { FocusRequester() }
        LaunchedEffect(multiMenu) { if (multiMenu && !PHONE) repeat(5) { try { first.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(50) } } }
        val I = androidx.compose.material.icons.Icons.Rounded
        val full = n >= io.github.novatv.play.data.MultiView.MAX
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!full) item {
                io.github.novatv.play.ui.Pill("Add a screen", icon = I.Add, primary = true, modifier = Modifier.focusRequester(first), onClick = { startMulti() })
            }
            item {
                io.github.novatv.play.ui.Pill("Change channel", icon = I.List, onClick = { changeScreen(active) },
                    modifier = if (full) Modifier.focusRequester(first) else Modifier)
            }
            item { io.github.novatv.play.ui.Pill("Remove this screen", icon = I.Close, onClick = { removeScreen(active) }) }
            item { io.github.novatv.play.ui.Pill("Full screen", icon = I.Fullscreen, onClick = { endMulti(active) }) }
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
            if (picking) item { Text(if (pickFor < 0) "Pick a channel to add" else "Pick a channel for this screen", style = T.h2, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)) }
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
