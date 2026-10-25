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

    fun release() { factoryRef?.release(); factoryRef = null }
}

/** VLC draws each frame into memory; Compose paints it. */
private class Frames {
    var bitmap: Bitmap? = null
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
            }
            version++
        }
    }
}

@Composable
fun PlayerScreen(start: PlayItem, window: WindowState, onClose: () -> Unit) {
    var item by remember { mutableStateOf(start) }
    var index by remember { mutableIntStateOf((start as? PlayItem.Live)?.channelIndex ?: 0) }
    val frames = remember { Frames() }
    val player: EmbeddedMediaPlayer = remember { Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var length by remember { mutableLongStateOf(0L) }
    var overlay by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var epg by remember { mutableStateOf<List<Programme>>(emptyList()) }
    var retry by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }

    DisposableEffect(Unit) {
        player.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(frames.format, frames.render, true))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { playing = true; buffering = false; error = null }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { length = newLength }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { position = newTime }
            override fun error(mediaPlayer: MediaPlayer) { error = "This stream could not be played"; buffering = false }
            override fun finished(mediaPlayer: MediaPlayer) {
                val v = item as? PlayItem.Vod ?: return
                saveProgress(v, length, length)
                val next = v.nextEpisodes.firstOrNull() ?: return
                val s = v.series ?: return
                item = PlayItem.Vod(App.repo.episodeKey(s, next), "${s.name} · S${next.season} E${next.number}", episode = next, series = s,
                    nextEpisodes = v.nextEpisodes.drop(1))
            }
        })
        onDispose {
            (item as? PlayItem.Vod)?.let { saveProgress(it, player.status().time(), player.status().length()) }
            player.controls().stop()
            player.release()
        }
    }

    // Resolve the stream (Stalker portals hand out links on request), then play it.
    LaunchedEffect(item, index, retry) {
        buffering = true; error = null; position = 0; length = 0
        val it = item
        val url = withContext(IO) {
            try {
                val p = App.repo.provider ?: return@withContext null
                when (it) {
                    is PlayItem.Live -> p.liveUrl(it.channels[index])
                    is PlayItem.Vod -> it.movie?.let { m -> p.movieUrl(m) } ?: p.episodeUrl(it.series!!, it.episode!!)
                }
            } catch (e: Exception) { null }
        }
        if (url == null) { error = "This stream could not be opened"; buffering = false; return@LaunchedEffect }
        val opts = arrayOf(":http-user-agent=${io.github.novatv.play.data.Http.UA}", ":network-caching=${App.store.bufferMs.coerceIn(1000, 15000)}")
        player.media().play(url, *opts)
        if (it is PlayItem.Vod) App.store.progress(it.key)?.let { p -> delay(800); player.controls().setTime(p.first) }
        if (it is PlayItem.Live) {
            val c = it.channels[index]
            App.store.addRecentChannel(App.repo.channelKey(c)); App.store.lastChannel = App.repo.channelKey(c)
            epg = emptyList()
            epg = withContext(IO) { App.repo.nowNext(c) }
        }
        overlay = System.currentTimeMillis()
    }
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
                    Key.Enter -> { if (error != null) retry++ else overlay = System.currentTimeMillis(); true }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Move) { overlay = System.currentTimeMillis() }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { fullscreen() }, onTap = { toggle() }) },
    ) {
        // The picture, fitted to the window.
        Canvas(Modifier.fillMaxSize()) {
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
        if (buffering && error == null) Spinner(Modifier.align(Alignment.Center), size = 56.dp)
        error?.let {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, style = T.h2)
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Try again", onClick = { retry++ }, primary = true)
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
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("${c.number}  ${c.name}", style = T.h2, maxLines = 1); Spacer(Modifier.width(10.dp)); Badge("LIVE") }
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
                    Text("Space pause · ↑↓ change channel · F full screen · Esc back", style = T.tiny)
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
    if (dur <= 0) return
    App.store.saveProgress(v.key, v.title, v.movie?.poster ?: v.series?.poster, pos, dur)
}
