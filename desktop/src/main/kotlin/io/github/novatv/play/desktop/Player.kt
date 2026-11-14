@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.novatv.play.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import io.github.novatv.play.App
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Programme
import io.github.novatv.play.platform.IO
import io.github.novatv.play.ui.Bar
import io.github.novatv.play.ui.Badge
import io.github.novatv.play.ui.C
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Logo
import io.github.novatv.play.ui.Pill
import io.github.novatv.play.ui.Spinner
import io.github.novatv.play.ui.T
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.clock
import io.github.novatv.play.ui.duration
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer

/** VLC's engine, loaded once from the libvlc shipped beside the app. */
object Vlc {
    private var factoryRef: uk.co.caprica.vlcj.factory.MediaPlayerFactory? = null

    val factory: uk.co.caprica.vlcj.factory.MediaPlayerFactory
        @Synchronized get() = factoryRef ?: run {
            val dir = System.getProperty("compose.application.resources.dir")?.let { File(it, "vlc") }
                ?.takeIf { it.exists() } ?: File("resources/windows/vlc").takeIf { it.exists() }
            if (dir != null) {
                System.setProperty("jna.library.path", dir.absolutePath)
                com.sun.jna.NativeLibrary.addSearchPath("libvlc", dir.absolutePath)
            }
            uk.co.caprica.vlcj.factory.MediaPlayerFactory("--no-video-title-show", "--quiet", "--http-reconnect").also { factoryRef = it }
        }

    /**
     * VLC's calls that can wait on the network (start, stop, free a player) run here, one after another and in the
     * order made, never on the window's thread: the window never freezes, and a stream still closes before the next
     * one opens, so the provider never sees two.
     */
    private val control = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vlc-control").apply { isDaemon = true } }
    fun later(f: () -> Unit) { runCatching { control.execute { runCatching(f).onFailure { System.err.println("vlc: $it") } } } }

    /** Closing the app: what is queued finishes first (up to a few seconds), then the engine goes. */
    fun release() {
        runCatching { control.submit { }.get(5, java.util.concurrent.TimeUnit.SECONDS) }
        factoryRef?.release(); factoryRef = null
    }
}

/** VLC draws each frame into memory; Compose paints it. */
internal class Frames {
    var bitmap: Bitmap? = null
    /** When the last frame arrived, to spot a picture that has frozen. */
    @Volatile var lastFrameAt = 0L
    /** Development only: when the picture's content last changed (set while a test scenario runs). */
    @Volatile var changedAt = 0L
    /** Development only: frames VLC has handed over (set while a test scenario runs). */
    @Volatile var displayed = 0L
    /** Development only: average brightness (0-255) of the sampled pixels of the last frame. */
    @Volatile var brightness = -1
    /** No frame yet: a value no frame's fingerprint can have (an all-black frame's is 0). */
    private var sample = Long.MIN_VALUE
    var size = IntSize.Zero
    private var bytes = ByteArray(0)
    var version by mutableIntStateOf(0)

    val format = object : BufferFormatCallback {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            synchronized(this@Frames) {
                size = IntSize(sourceWidth, sourceHeight)
                bitmap = Bitmap().apply { allocPixels(ImageInfo(sourceWidth, sourceHeight, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) }
                bytes = ByteArray(sourceWidth * sourceHeight * 4)
            }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }
        override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
    }

    val render = object : RenderCallback {
        override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
            synchronized(this@Frames) {
                val b = bitmap ?: return
                val buf = nativeBuffers[0]
                buf.rewind()
                if (bytes.size != buf.remaining()) return
                buf.get(bytes)
                b.installPixels(b.imageInfo, bytes, size.width * 4)
                // Development only (test driver): when the picture's content last changed, sampled cheaply.
                if (AutoTest.scenario != null) {
                    var h = 0; var i = 0; val step = maxOf(1, bytes.size / 4096)
                    while (i < bytes.size) { h = h * 31 + bytes[i]; i += step }
                    if (h.toLong() != sample) { sample = h.toLong(); changedAt = System.currentTimeMillis() }
                    displayed++
                    var sum = 0L; var cnt = 0; var j = 0
                    while (j + 2 < bytes.size) { sum += (bytes[j].toInt() and 255) + (bytes[j + 1].toInt() and 255) + (bytes[j + 2].toInt() and 255); cnt += 3; j += step * 4 }
                    brightness = if (cnt > 0) (sum / cnt).toInt() else -1
                }
            }
            lastFrameAt = System.currentTimeMillis()
            version++
        }
    }
}

@Composable
fun PlayerScreen(start: PlayItem, window: WindowState, onClose: () -> Unit) {
    var item by remember { mutableStateOf(start) }
    var index by remember { mutableIntStateOf((start as? PlayItem.Live)?.channelIndex ?: 0) }
    // The live channel already playing in the preview window (Seamless Fullscreen Playback): this screen takes that
    // player, stream and all, instead of opening the channel again. Anything else builds this screen's own player.
    val handed: HandedPlayer? = remember {
        val h = if (start is PlayItem.Live && App.handoverActive) runCatching { DesktopPreviewEngine.handOff() }.getOrNull() else null
        // The preview gave up its channel for this screen but no player came: clear it, so it doesn't wait for one.
        if (h == null && App.handoverActive) App.preview.stop()
        App.handingOver = false
        if (AutoTest.scenario != null) AutoTest.log(when {
            start !is PlayItem.Live -> "FULL own player (not live)"
            h != null -> "FULL took the preview's player"
            else -> "FULL own player (${App.handoverRefusal ?: "nothing to take"})"
        })
        h
    }
    /** Still holding the preview's player (it goes back to the window on Back, if fit). */
    var adopted by remember { mutableStateOf(handed != null) }
    /** The channel the preview's player was playing when taken: not opened again on arrival. */
    var skipFirstPlay by remember { mutableStateOf(handed != null) }
    val frames = remember { handed?.frames ?: Frames() }
    val player: EmbeddedMediaPlayer = remember { handed?.player ?: Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer() }
    /** The address playing now (handed back with the player). */
    var playingUrl by remember { mutableStateOf(handed?.url) }
    var playing by remember { mutableStateOf(handed != null) }
    var buffering by remember { mutableStateOf(handed == null) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var overlay by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var epg by remember { mutableStateOf<List<Programme>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    var multi by remember { mutableStateOf(false) }
    /** Times this live channel's stream has ended by itself since it was chosen or last played steadily (10 s). */
    var liveEnds by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        if (handed == null) AutoTest.vlcCreated.incrementAndGet()
        val listeners = mutableListOf<MediaPlayerEventAdapter>()
        // Development only: what the test driver reads (see AutoTest); read only.
        if (AutoTest.scenario != null) AutoTest.full = { AutoTest.FullState((item as? PlayItem.Live)?.channels?.getOrNull(index)?.name ?: item.title, playing, buffering, error, frames.changedAt,
            "idx=$index lum=${frames.brightness} vlc=${runCatching { player.status().state() }.getOrNull()} time=${runCatching { player.status().time() }.getOrNull()} shown=${frames.displayed} " +
            "video=${runCatching { player.media().info().videoTracks().joinToString { "${it.codecName()} ${it.width()}x${it.height()}" } }.getOrNull()}") }
        // Development only: every VLC event of this player into the test log, to see why a stream stops.
        if (AutoTest.scenario != null) player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            fun ev(what: String) = AutoTest.log("VLC idx=$index $what shown=${frames.displayed} buf=$buffering playing=$playing")
            override fun opening(mediaPlayer: MediaPlayer) = ev("opening")
            override fun playing(mediaPlayer: MediaPlayer) = ev("playing")
            override fun stopped(mediaPlayer: MediaPlayer) = ev("stopped")
            override fun finished(mediaPlayer: MediaPlayer) = ev("finished")
            override fun error(mediaPlayer: MediaPlayer) = ev("error")
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) = ev("video outputs $newCount")
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { if (newCache >= 100f) ev("buffered 100%") }
        }.also { listeners += it })
        // A handed-over player already draws into its frames; its sound comes on now.
        if (handed == null) player.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(frames.format, frames.render, true))
        else player.audio().isMute = false
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { playing = true; buffering = false; error = null }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { length = newLength }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                position = newTime
                // Played steadily for 10 s since it was (re)opened: a later end gets its own quiet retry again.
                if (newTime >= 10_000 && liveEnds != 0) liveEnds = 0
            }
            override fun error(mediaPlayer: MediaPlayer) { error = "This stream could not be played"; buffering = false }
            override fun finished(mediaPlayer: MediaPlayer) {
                // A live stream never ends on its own: the server closed it. Open it again once, quietly; if it ends
                // again, say so (instead of leaving the last picture, or black, with nothing on screen).
                if (item is PlayItem.Live) {
                    playing = false
                    if (liveEnds++ == 0) { buffering = true; retry++ } else { error = "This stream could not be played"; buffering = false }
                    return
                }
                val v = item as? PlayItem.Vod ?: return
                saveProgress(v, length, length)
                val next = v.nextEpisodes.firstOrNull() ?: return
                val s = v.series ?: return
                item = PlayItem.Vod(App.repo.episodeKey(s, next), "${s.name} · S${next.season} E${next.number}", episode = next, series = s,
                    nextEpisodes = v.nextEpisodes.drop(1))
            }
        }.also { listeners += it })
        onDispose {
            AutoTest.full = null
            (item as? PlayItem.Vod)?.let { saveProgress(it, player.status().time(), player.status().length()) }
            // Back to the preview with this very player: one live channel, playing, no error. Anything else lets it go.
            val ch = (item as? PlayItem.Live)?.channels?.getOrNull(index)
            val url = playingUrl
            val fit = adopted && handed != null && !multi && ch != null && url != null && error == null && playing
            if (fit) {
                listeners.forEach { runCatching { player.events().removeMediaPlayerEventListener(it) } }
                player.audio().isMute = true
                val ok = DesktopPreviewEngine.giveBack(handed!!, url!!) &&
                    runCatching { App.preview.adopt(ch!!, io.github.novatv.play.ui.TV_PREVIEW) }.getOrDefault(false)
                if (AutoTest.scenario != null) AutoTest.log(if (ok) "FULL handed back to the preview" else "FULL hand back refused")
                if (ok) return@onDispose
            } else if (adopted && AutoTest.scenario != null) AutoTest.log("FULL closed without hand back (multi=$multi error=${error != null} playing=$playing)")
            if (handed != null) { DesktopPreviewEngine.lost(handed); App.preview.stop() }
            Vlc.later { player.controls().stop(); player.release(); AutoTest.vlcReleased.incrementAndGet() }
        }
    }

    // Resolve the stream (Stalker portals hand out links on request), then play it.
    LaunchedEffect(item, index, retry) {
        // The channel the preview's player brought with it is already playing: nothing to open.
        if (skipFirstPlay) {
            skipFirstPlay = false
            (item as? PlayItem.Live)?.let { l ->
                val c = l.channels[index]
                App.store.addRecentChannel(App.repo.channelKey(c)); App.store.lastChannel = App.repo.channelKey(c)
                epg = withContext(IO) { App.repo.nowNext(c) }
            }
            return@LaunchedEffect
        }
        buffering = true; error = null; position = 0; length = 0
        val it = item
        val url = withContext(IO) {
            try {
                val p = App.repo.provider ?: return@withContext null
                when (it) {
                    is PlayItem.Live -> p.liveUrl(it.channels[index])
                    // A recording plays from its file, not through the provider.
                    is PlayItem.Vod -> it.movie?.let { m -> m.url?.takeIf { u -> u.startsWith("file:") } ?: p.movieUrl(m) } ?: p.episodeUrl(it.series!!, it.episode!!)
                }
            } catch (e: Exception) { null }
        }?.let { u -> if (u.startsWith("file:")) u else AutoTest.stream(u) }
        if (url == null) { error = "This stream could not be opened"; buffering = false; return@LaunchedEffect }
        playingUrl = url
        val opts = arrayOf(":http-user-agent=${io.github.novatv.play.data.Http.UA}", ":network-caching=${App.store.bufferMs.coerceIn(1000, 15000)}")
        if (AutoTest.scenario != null) AutoTest.log("PLAY idx=$index (${if (url.startsWith("http://127.0.0.1")) "relay" else "direct"})")
        Vlc.later { player.media().play(url, *opts) }
        if (it is PlayItem.Vod) App.store.progress(it.key)?.let { p -> delay(800); player.controls().setTime(p.first) }
        if (it is PlayItem.Live) {
            val c = it.channels[index]
            App.store.addRecentChannel(App.repo.channelKey(c)); App.store.lastChannel = App.repo.channelKey(c)
            epg = emptyList()
            epg = withContext(IO) { App.repo.nowNext(c) }
        }
        overlay = System.currentTimeMillis()
    }
    // Another channel or programme: its stream gets its own quiet retry.
    LaunchedEffect(item, index) { liveEnds = 0 }
    // VOD: save progress every half minute.
    LaunchedEffect(item) {
        while (true) { delay(30_000); (item as? PlayItem.Vod)?.let { saveProgress(it, position, length) } }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { repeat(5) { try { focus.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(60) } } }

    val live = item as? PlayItem.Live
    fun zap(d: Int) { val l = live ?: return; index = (index + d).mod(l.channels.size); overlay = System.currentTimeMillis() }
    fun toggle() { player.controls().pause(); overlay = System.currentTimeMillis() }
    fun seek(ms: Long) { if (live == null && length > 0) { player.controls().setTime((player.status().time() + ms).coerceIn(0, length)); overlay = System.currentTimeMillis() } }
    fun fullscreen() { window.placement = if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen }
    val showUi = now - overlay < 4000 || !playing || error != null

    // Back from multi-view: the keys go to the player again.
    LaunchedEffect(multi) { if (!multi) repeat(5) { try { focus.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(60) } } }
    if (multi && live != null) {
        MultiScreen(live, index, (index + 1).mod(live.channels.size)) { ch ->
            multi = false
            // One screen again: the channel itself (in 4K), joining the front of the list if it came from Sports.
            val at = live.channels.indexOfFirst { it.id == ch.id }
            when {
                at == index -> retry++
                at >= 0 -> index = at
                else -> { item = PlayItem.Live(listOf(ch) + live.channels, 0); index = 0 }
            }
        }
        return
    }
    fun startMulti() { if (live == null) return; Vlc.later { player.controls().stop() }; multi = true }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.Escape, Key.Backspace -> { onClose(); true }
                    Key.Spacebar, Key.K -> { toggle(); true }
                    Key.DirectionUp, Key.PageUp -> { zap(-1); true }
                    Key.DirectionDown, Key.PageDown -> { zap(1); true }
                    Key.DirectionLeft -> { seek(-10_000); true }
                    Key.DirectionRight -> { seek(30_000); true }
                    Key.F -> { fullscreen(); true }
                    Key.M -> { startMulti(); true }
                    Key.Enter -> { if (error != null) { liveEnds = 0; retry++ } else overlay = System.currentTimeMillis(); true }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { overlay = System.currentTimeMillis() }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { fullscreen() }, onTap = { toggle() }) },
    ) {
        // The picture, fitted to the window.
        VideoCanvas(frames, Modifier.fillMaxSize())
        if (buffering && error == null) Spinner(Modifier.align(Alignment.Center), size = 56.dp)
        error?.let {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, style = T.h2)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Try again", onClick = { liveEnds = 0; retry++ }, primary = true)
                    if (live != null) Pill("Next channel", onClick = { zap(1) })
                }
            }
        }
        if (showUi) {
            // Top: back and title.
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(24.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Pill("Back", onClick = onClose, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                Spacer(Modifier.width(18.dp))
                Text(live?.channels?.get(index)?.name ?: item.title, style = T.h1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(clock(now), style = T.h2)
                Spacer(Modifier.width(14.dp))
                if (live != null) { Pill("Multi-view", onClick = { startMulti() }, icon = Icons.Rounded.GridView); Spacer(Modifier.width(10.dp)) }
                Pill(if (window.placement == WindowPlacement.Fullscreen) "Window" else "Full screen", onClick = { fullscreen() }, icon = Icons.Rounded.Fullscreen)
            }
            // Bottom: now/next and zapping for live TV, the seek bar for films and episodes.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(horizontal = 32.dp, vertical = 26.dp)) {
                if (live != null) {
                    val c = live.channels[index]
                    val p = epg.firstOrNull(); val n = epg.getOrNull(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Logo(c.logo, c.name, Modifier.width(96.dp).height(64.dp))
                        Spacer(Modifier.width(18.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("${c.number}  ${c.name}", style = T.h2, maxLines = 1); Spacer(Modifier.width(10.dp)); io.github.novatv.play.ui.LiveMark() }
                            if (p != null) {
                                Text("${clock(p.start)} – ${clock(p.stop)}   ${p.title}", style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(6.dp))
                                Bar(((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth(0.6f))
                                if (n != null) Text("Next  ${clock(n.start)}  ${n.title}", style = T.small, maxLines = 1)
                            } else Text("No guide information", style = T.small)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Pill("Previous", onClick = { zap(-1) }, icon = Icons.Rounded.KeyboardArrowUp)
                            Pill("Next", onClick = { zap(1) }, icon = Icons.Rounded.KeyboardArrowDown)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Space pause · ↑↓ change channel · M multi-view · F full screen · Esc back", style = T.tiny)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Pill(if (playing) "Pause" else "Play", onClick = { toggle() }, icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow)
                        Spacer(Modifier.width(18.dp))
                        Text(duration(position), style = T.small)
                        Spacer(Modifier.width(12.dp))
                        SeekBar(if (length > 0) position.toFloat() / length else 0f, Modifier.weight(1f)) { f -> if (length > 0) player.controls().setTime((f * length).toLong()) }
                        Spacer(Modifier.width(12.dp))
                        Text(if (length > 0) "-${duration(length - position)}" else "", style = T.small)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Space pause · ← → skip · F full screen · Esc back", style = T.tiny)
                }
            }
        }
    }
}

/** The latest frame, fitted to the space. */
@Composable
internal fun VideoCanvas(frames: Frames, modifier: Modifier) {
    Canvas(modifier) {
        frames.version
        synchronized(frames) {
            val b = frames.bitmap ?: return@Canvas
            val w = frames.size.width.toFloat(); val h = frames.size.height.toFloat()
            if (w <= 0 || h <= 0) return@Canvas
            val s = minOf(size.width / w, size.height / h)
            val dw = w * s; val dh = h * s
            val img = org.jetbrains.skia.Image.makeFromBitmap(b)
            drawIntoCanvas { c ->
                c.nativeCanvas.drawImageRect(img, org.jetbrains.skia.Rect.makeWH(w, h),
                    org.jetbrains.skia.Rect.makeXYWH((size.width - dw) / 2, (size.height - dh) / 2, dw, dh),
                    org.jetbrains.skia.SamplingMode.LINEAR, null, true)
            }
            img.close()
        }
    }
}

/** A bar to click or drag to a point in the film. */
@Composable
private fun SeekBar(fraction: Float, modifier: Modifier, onSeek: (Float) -> Unit) {
    var width by remember { mutableIntStateOf(1) }
    Box(modifier.height(22.dp)
        .onPointerEvent(PointerEventType.Press) { e -> e.changes.firstOrNull()?.let { onSeek((it.position.x / width).coerceIn(0f, 1f)) } }
        .onSizeChanged { width = it.width.coerceAtLeast(1) },
        contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0x40FFFFFF)))
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp)).background(C.accent))
    }
}

private fun saveProgress(v: PlayItem.Vod, pos: Long, dur: Long) {
    // Recordings aren't kept in Continue Watching (as on Android).
    if (dur <= 0 || v.key.startsWith("rec:")) return
    App.store.saveProgress(v.key, v.title, v.movie?.poster ?: v.series?.poster, pos, dur)
}
