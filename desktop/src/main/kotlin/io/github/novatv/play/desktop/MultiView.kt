@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package io.github.novatv.play.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.List
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.novatv.play.App
import io.github.novatv.play.data.MultiView
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.platform.IO
import io.github.novatv.play.ui.C
import io.github.novatv.play.ui.Logo
import io.github.novatv.play.ui.Pill
import io.github.novatv.play.ui.Spinner
import io.github.novatv.play.ui.T
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.Tile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * One multi-view screen: its own VLC player and picture. VLC's events only raise flags (calling the player from its
 * own event thread can deadlock it); the screen's loop acts on them.
 */
private class VlcScreen(var channel: Int) {
    val frames = Frames()
    val player = Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer()
    var buffering by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    @Volatile var dropped = false
    var failures = 0
    /** When it was (re)opened, to give a new stream time before the frozen-picture check. */
    var openedAt = 0L

    init {
        player.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(frames.format, frames.render, true))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { buffering = false; error = null }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { buffering = newCache < 100f }
            override fun error(mediaPlayer: MediaPlayer) { dropped = true }
            override fun finished(mediaPlayer: MediaPlayer) { dropped = true }
            override fun stopped(mediaPlayer: MediaPlayer) {}
        })
    }

    fun release() { runCatching { player.controls().stop() }; runCatching { player.release() } }
}

/**
 * Two to four live channels at once, in a grid. A click picks the screen with sound; double-click watches it full
 * screen. Every screen reconnects on its own when its stream drops or its picture freezes. [onExit] gets the channel
 * to carry on with full screen.
 */
@Composable
fun MultiScreen(live: PlayItem.Live, first: Int, second: Int, onExit: (Int) -> Unit) {
    val screens = remember { mutableStateListOf(VlcScreen(first), VlcScreen(second)) }
    var active by remember { mutableIntStateOf(0) }
    /** The channel list is open for screen [picking], or for a new screen when it's -1; null when closed. */
    // The second screen starts on the next channel with the list open, to pick the one wanted.
    var picking by remember { mutableStateOf<Int?>(1) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    fun open(s: VlcScreen) {
        s.dropped = false; s.error = null; s.buffering = true; s.openedAt = System.currentTimeMillis()
        s.frames.lastFrameAt = 0L
        scope.launch {
            val url = withContext(IO) { try { App.repo.provider?.liveUrl(live.channels[s.channel]) } catch (e: Exception) { null } }
            if (s !in screens) return@launch
            if (url == null) { s.dropped = true; return@launch }
            val opts = arrayOf(":http-user-agent=${io.github.novatv.play.data.Http.UA}", ":network-caching=${App.store.bufferMs.coerceIn(1000, 15000)}")
            withContext(IO) { s.player.media().play(url, *opts) }
        }
    }
    fun sound(a: Int) {
        active = a.coerceIn(0, screens.lastIndex)
        screens.forEachIndexed { i, s -> s.player.audio().isMute = i != active }
    }
    fun warnLimit() {
        val n = screens.size
        scope.launch { withContext(IO) { MultiView.limitWarning(n) }?.let { Desktop.toast(it) } }
    }
    fun add(channel: Int) {
        if (screens.size >= MultiView.MAX) return
        val s = VlcScreen(channel.mod(live.channels.size))
        screens.add(s); open(s); sound(screens.lastIndex); warnLimit()
    }
    fun change(i: Int, channel: Int) {
        val s = screens.getOrNull(i) ?: return
        s.channel = channel.mod(live.channels.size); s.failures = 0
        open(s)
    }
    fun remove(i: Int) {
        if (screens.size <= 2) { onExit(screens[if (i == 0) 1 else 0].channel); return }
        screens.removeAt(i).release()
        sound(minOf(active, screens.lastIndex))
    }

    DisposableEffect(Unit) {
        screens.forEach { open(it) }
        sound(0); warnLimit()
        onDispose { screens.forEach { it.release() }; screens.clear() }
    }
    // The watchdog: a dropped stream reconnects after a short wait (longer after each failure in a row); a picture that
    // stops changing for too long, or never starts, is opened again.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            val now = System.currentTimeMillis()
            for (s in screens.toList()) {
                val last = maxOf(s.frames.lastFrameAt, s.openedAt)
                when {
                    s.dropped -> {
                        s.failures++
                        s.error = "Reconnecting…"
                        s.dropped = false
                        val wait = MultiView.retryDelay(s.failures)
                        s.openedAt = now + wait
                        launch { delay(wait); if (s in screens) open(s) }
                    }
                    now - last > MultiView.STALL_MS -> { s.error = "Reconnecting…"; open(s) }
                    s.frames.lastFrameAt > s.openedAt -> s.failures = 0
                }
            }
        }
    }
    LaunchedEffect(picking) { if (picking == null) repeat(5) { try { focus.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(60) } } }

    val n = screens.size
    val (cols, rows) = MultiView.grid(n)
    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
        .onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown || picking != null) return@onKeyEvent false
            when (e.key) {
                Key.Escape, Key.Backspace -> { onExit(screens[active].channel); true }
                Key.DirectionLeft -> { sound((active - 1).mod(n)); true }
                Key.DirectionRight -> { sound((active + 1).mod(n)); true }
                Key.DirectionUp -> { if (active - cols >= 0) sound(active - cols); true }
                Key.DirectionDown -> { if (active + cols < n) sound(active + cols); true }
                Key.PageUp -> { change(active, screens[active].channel - 1); true }
                Key.PageDown -> { change(active, screens[active].channel + 1); true }
                else -> false
            }
        }) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Each screen is 16:9 and as big as fits; a third one sits in the middle of the second row.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val gap = 10.dp
                val tileW = minOf((maxWidth - gap * (cols - 1)) / cols, (maxHeight - gap * (rows - 1)) / rows * (16f / 9f))
                val tileH = tileW * (9f / 16f)
                Column(verticalArrangement = Arrangement.spacedBy(gap), horizontalAlignment = Alignment.CenterHorizontally) {
                for (r in 0 until rows) Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (col in 0 until cols) {
                        val i = r * cols + col
                        val s = screens.getOrNull(i)
                        if (s == null) continue
                        key(System.identityHashCode(s)) {
                            val c = live.channels.getOrNull(s.channel)
                            Box(Modifier.size(tileW, tileH).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0E13))
                                .border(if (active == i) 3.dp else 1.dp, if (active == i) C.accent else C.line, RoundedCornerShape(10.dp))
                                .pointerInput(i) { detectTapGestures(onTap = { sound(i) }, onDoubleTap = { onExit(s.channel) }) }) {
                                VideoCanvas(s.frames, Modifier.fillMaxSize().padding(3.dp))
                                if (s.error != null) Text(s.error!!, style = T.small.copy(color = C.text), modifier = Modifier.align(Alignment.Center))
                                else if (s.buffering) Spinner(Modifier.align(Alignment.Center), size = 36.dp)
                                if (c != null) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (c.number > 0) { Text("${c.number}", style = T.body); Spacer(Modifier.width(8.dp)) }
                                    Text(c.name, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (active == i) Text("♪", style = T.h2.copy(color = C.accent))
                                }
                            }
                        }
                    }
                }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Back", onClick = { onExit(screens[active].channel) }, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                if (n < MultiView.MAX) Pill("Add a screen", onClick = { picking = -1 }, icon = Icons.Rounded.Add, primary = true)
                Pill("Change channel", onClick = { picking = active }, icon = Icons.Rounded.List)
                Pill("Remove this screen", onClick = { remove(active) }, icon = Icons.Rounded.Close)
                Pill("Full screen", onClick = { onExit(screens[active].channel) }, icon = Icons.Rounded.Fullscreen)
                Spacer(Modifier.weight(1f))
                Text("Click: sound · Double-click: full screen · ←→↑↓ choose · PgUp/PgDn zap · Esc back", style = T.tiny)
            }
        }
        picking?.let { target ->
            ChannelPicker(live, if (target >= 0) screens.getOrNull(target)?.channel ?: 0 else screens[active].channel,
                if (target < 0) "Pick a channel to add" else "Pick a channel for this screen",
                onPick = { ch -> picking = null; if (target < 0) add(ch) else change(target, ch) }, onCancel = { picking = null })
        }
    }
}

/** The channel list down the left, for multi-view. */
@Composable
private fun ChannelPicker(live: PlayItem.Live, current: Int, title: String, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }
        .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false }) {
        Column(Modifier.width(460.dp).fillMaxHeight().background(Color(0xF00A0E17)).pointerInput(Unit) { detectTapGestures { } }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = T.h2, modifier = Modifier.weight(1f))
                Pill("Cancel", onClick = onCancel)
            }
            LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(live.channels, key = { _, c -> c.id }) { i, c ->
                    Tile({ onPick(i) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(10.dp), scale = 1.02f,
                        color = if (i == current) C.accent.copy(alpha = 0.25f) else Color.Transparent) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${c.number}", style = T.small, modifier = Modifier.width(52.dp))
                            Logo(c.logo, c.name, Modifier.width(56.dp).height(38.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(c.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
