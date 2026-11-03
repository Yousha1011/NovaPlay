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
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
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
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/** What happens to multi-view's screens, for working out why one stops: %TEMP%\novaplay-multiview.log, when NOVAPLAY_LOG is set. */
internal fun multiLog(msg: String) {
    if (System.getenv("NOVAPLAY_LOG") == null) return
    runCatching { java.io.File(System.getProperty("java.io.tmpdir"), "novaplay-multiview.log").appendText("${java.time.LocalTime.now().withNano(0)} $msg\n") }
}

/** A VLC player drawing into its own frames. */
private class Vlcp(val frames: Frames = Frames()) {
    val player: EmbeddedMediaPlayer = Vlc.factory.mediaPlayers().newEmbeddedMediaPlayer().also {
        it.videoSurface().set(Vlc.factory.videoSurfaces().newVideoSurface(frames.format, frames.render, true))
    }
    @Volatile var dropped = false
    private val stats = uk.co.caprica.vlcj.media.MediaStatistics()

    /** Bytes read from the stream so far (stops growing when the server closes it). */
    fun bytesRead(): Int = runCatching { if (player.media().info().statistics(stats)) stats.inputBytesRead() else -1 }.getOrDefault(-1)

    fun release() { runCatching { player.controls().stop() }; runCatching { player.release() } }
}

/**
 * One multi-view screen: its VLC player and picture. VLC's events only raise flags (calling the player from its own
 * event thread can deadlock it); the screen's loop acts on them.
 *
 * Servers that allow fewer streams than screens close the extra ones every half minute or so. VLC still plays what it
 * has cached, so as soon as the stream stops arriving a [standby] player opens the channel again, paused, and takes over
 * the screen when the cache runs out: the picture carries on.
 */
private class VlcScreen(var channel: Int) {
    var current by mutableStateOf(Vlcp())
    var standby: Vlcp? = null
    var buffering by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    var failures = 0
    /** When it was (re)opened, to give a new stream time before the frozen-picture check. */
    var openedAt = 0L
    /** Playing the HD version of a 4K channel. */
    var hd by mutableStateOf(false)
    /** When the picture started (0 until it has), and when the stream last grew. */
    var playingSince = 0L
    var lastBytes = 0
    var bytesAt = 0L

    val player get() = current.player
    val frames get() = current.frames

    init { listen(current) }

    fun listen(v: Vlcp) {
        v.player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { if (v === current) { buffering = false; error = null } }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { if (v === current) buffering = newCache < 100f }
            override fun error(mediaPlayer: MediaPlayer) { multiLog("channel $channel: stream error"); v.dropped = true }
            override fun finished(mediaPlayer: MediaPlayer) { multiLog("channel $channel: stream ended"); v.dropped = true }
        })
    }

    fun release() { current.release(); standby?.release(); standby = null }
}

/**
 * Two to four live channels at once, in a grid. A click picks the screen with sound; double-click watches it full
 * screen. Every screen reconnects on its own when its stream drops or its picture freezes. [onExit] gets the channel
 * to carry on with full screen.
 */
@Composable
fun MultiScreen(live: PlayItem.Live, first: Int, second: Int, onExit: (io.github.novatv.play.data.Channel) -> Unit) {
    // The channels screens play: the list being zapped, plus any picked from Sports.
    val chans = remember { mutableStateListOf<io.github.novatv.play.data.Channel>().apply { addAll(live.channels) } }
    /** A Sports channel joins the list (once) so a screen can hold it by position. */
    fun indexOf(ch: io.github.novatv.play.data.Channel): Int = chans.indexOfFirst { it.id == ch.id }.takeIf { it >= 0 } ?: run { chans.add(ch); chans.lastIndex }
    /** In multi-view a 4K channel plays its HD version, when the playlist has one. Blocking. */
    fun streamFor(ch: io.github.novatv.play.data.Channel) = io.github.novatv.play.data.HdTwin.of(ch, App.repo.state.value.lib.channels) ?: ch
    val screens = remember { mutableStateListOf(VlcScreen(first), VlcScreen(second)) }
    var active by remember { mutableIntStateOf(0) }
    /** The channel list is open for screen [picking], or for a new screen when it's -1; null when closed. */
    // The second screen starts on the next channel with the list open, to pick the one wanted.
    var picking by remember { mutableStateOf<Int?>(1) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    // A few seconds of cache at least, so a closed stream plays on while the next one opens.
    val opts = arrayOf(":http-user-agent=${io.github.novatv.play.data.Http.UA}", ":network-caching=${App.store.bufferMs.coerceIn(5000, 15000)}")
    fun open(s: VlcScreen, quiet: Boolean = false) {
        s.current.dropped = false; s.openedAt = System.currentTimeMillis(); s.playingSince = 0L; s.lastBytes = 0; s.bytesAt = s.openedAt
        if (!quiet) { s.error = null; s.buffering = true }
        s.frames.lastFrameAt = 0L
        scope.launch {
            val stream = withContext(IO) { streamFor(chans[s.channel]) }
            s.hd = stream.id != chans[s.channel].id
            val url = withContext(IO) { try { App.repo.provider?.liveUrl(stream) } catch (e: Exception) { null } }
            if (s !in screens) return@launch
            if (url == null) { s.current.dropped = true; return@launch }
            val v = s.current
            withContext(IO) { v.player.media().play(url, *opts) }
        }
    }
    /** Opens [s]'s channel again in a paused standby player, ready to take over. */
    fun openStandby(s: VlcScreen) {
        if (s.standby != null) return
        val sb = Vlcp()
        s.listen(sb)
        s.standby = sb
        scope.launch {
            val url = withContext(IO) { try { App.repo.provider?.liveUrl(streamFor(chans[s.channel])) } catch (e: Exception) { null } }
            if (url == null || s.standby !== sb) return@launch
            withContext(IO) { sb.player.media().play(url, *opts, ":start-paused") }
        }
    }
    /** The standby takes over [s]: its picture carries on from the old one's last frame. */
    fun handOver(s: VlcScreen, now: Long) {
        val sb = s.standby ?: return
        val old = s.current
        s.standby = null
        s.current = sb
        sb.player.audio().isMute = screens.indexOf(s) != active
        sb.player.controls().play()
        s.openedAt = now; s.playingSince = now; s.lastBytes = 0; s.bytesAt = now
        multiLog("channel ${s.channel}: handed over")
        scope.launch(IO) { old.release() }
    }
    fun sound(a: Int) {
        active = a.coerceIn(0, screens.lastIndex)
        screens.forEachIndexed { i, s -> s.player.audio().isMute = i != active }
    }
    var limitWarned by remember { mutableStateOf(false) }
    /** A screen keeps failing: when the playlist's stream limit may be why, say so (once). */
    fun warnLimit() {
        if (limitWarned) return
        limitWarned = true
        val n = screens.size
        scope.launch { withContext(IO) { MultiView.limitWarning(n) }?.let { Desktop.toast(it) } }
    }
    fun add(channel: Int) {
        if (screens.size >= MultiView.MAX) return
        val s = VlcScreen(channel.mod(chans.size))
        screens.add(s); open(s); sound(screens.lastIndex)
    }
    fun change(i: Int, channel: Int) {
        val s = screens.getOrNull(i) ?: return
        s.channel = channel.mod(chans.size); s.failures = 0
        open(s)
    }
    fun remove(i: Int) {
        if (screens.size <= 2) { onExit(chans[screens[if (i == 0) 1 else 0].channel]); return }
        screens.removeAt(i).release()
        sound(minOf(active, screens.lastIndex))
    }

    DisposableEffect(Unit) {
        screens.forEach { open(it) }
        sound(0)
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
                if (s.playingSince == 0L && s.frames.lastFrameAt > s.openedAt) s.playingSince = now
                // The stream stopped arriving after playing a while: the server closed it. Open the next one now.
                val bytes = s.current.bytesRead()
                if (bytes > s.lastBytes) { s.lastBytes = bytes; s.bytesAt = now }
                else if (s.standby == null && s.playingSince > 0 && now - s.playingSince > MultiView.SEAMLESS_AFTER_MS && now - s.bytesAt > 1500) {
                    multiLog("channel ${s.channel}: cut off, opening the next stream")
                    openStandby(s)
                }
                s.standby?.let { sb -> if (sb.dropped) { s.standby = null; scope.launch(IO) { sb.release() } } }
                val wasPlaying = s.playingSince > 0 && now - s.playingSince > MultiView.SEAMLESS_AFTER_MS
                when {
                    (s.current.dropped || now - last > MultiView.STALL_MS) && s.standby != null -> handOver(s, now)
                    s.current.dropped && wasPlaying -> { s.failures = 0; open(s, quiet = true) }
                    s.current.dropped -> {
                        s.failures++
                        if (s.failures == MultiView.WARN_AFTER) warnLimit()
                        s.error = "Reconnecting…"
                        s.current.dropped = false
                        val wait = MultiView.retryDelay(s.failures)
                        s.openedAt = now + wait
                        launch { delay(wait); if (s in screens) open(s) }
                    }
                    now - last > MultiView.STALL_MS -> { multiLog("channel ${s.channel}: picture frozen, reopening"); s.error = "Reconnecting…"; open(s) }
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
                Key.Escape, Key.Backspace -> { onExit(chans[screens[active].channel]); true }
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
                            val c = chans.getOrNull(s.channel)
                            Box(Modifier.size(tileW, tileH).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B0E13))
                                .border(if (active == i) 3.dp else 1.dp, if (active == i) C.accent else C.line, RoundedCornerShape(10.dp))
                                .pointerInput(i) { detectTapGestures(onTap = { sound(i) }, onDoubleTap = { onExit(chans[s.channel]) }) }) {
                                VideoCanvas(s.current.frames, Modifier.fillMaxSize().padding(3.dp))
                                if (s.error != null) Text(s.error!!, style = T.small.copy(color = C.text), modifier = Modifier.align(Alignment.Center))
                                else if (s.buffering) Spinner(Modifier.align(Alignment.Center), size = 36.dp)
                                if (c != null) Row(Modifier.align(Alignment.BottomStart).fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    if (c.number > 0) { Text("${c.number}", style = T.body); Spacer(Modifier.width(8.dp)) }
                                    Text(c.name, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                    if (s.hd) { Spacer(Modifier.width(8.dp)); Text("HD", style = T.tiny.copy(color = C.accent)) }
                                    if (active == i) Text("♪", style = T.h2.copy(color = C.accent))
                                }
                            }
                        }
                    }
                }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Back", onClick = { onExit(chans[screens[active].channel]) }, icon = Icons.AutoMirrored.Rounded.ArrowBack)
                if (n < MultiView.MAX) Pill("Add a screen", onClick = { picking = -1 }, icon = Icons.Rounded.Add, primary = true)
                Pill("Change channel", onClick = { picking = active }, icon = Icons.Rounded.List)
                Pill("Remove this screen", onClick = { remove(active) }, icon = Icons.Rounded.Close)
                Pill("Full screen", onClick = { onExit(chans[screens[active].channel]) }, icon = Icons.Rounded.Fullscreen)
                Spacer(Modifier.weight(1f))
                Text("Click: sound · Double-click: full screen · ←→↑↓ choose · PgUp/PgDn zap · Esc back", style = T.tiny)
            }
        }
        picking?.let { target ->
            ScreenPicker(chans, if (target >= 0) screens.getOrNull(target)?.channel ?: 0 else screens[active].channel,
                if (target < 0) "Add a screen" else "Change this screen",
                onPick = { ch -> picking = null; val at = indexOf(ch); if (target < 0) add(at) else change(target, at) }, onCancel = { picking = null })
        }
    }
}

/** Adding or changing a screen: Live TV (the channel list) or Sports (a game, then the channels showing it). */
@Composable
private fun ScreenPicker(chans: List<io.github.novatv.play.data.Channel>, current: Int, title: String,
                         onPick: (io.github.novatv.play.data.Channel) -> Unit, onCancel: () -> Unit) {
    var source by remember { mutableStateOf<String?>(null) }
    var game by remember { mutableStateOf<io.github.novatv.play.sports.SportEvent?>(null) }
    val back = { when { game != null -> game = null; source != null -> source = null; else -> onCancel() } }
    when (source) {
        null -> Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }
            .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false },
            contentAlignment = Alignment.Center) {
            Column(Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xF00A0E17)).pointerInput(Unit) { detectTapGestures { } }.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(title, style = T.h1)
                Spacer(Modifier.height(6.dp))
                Text("Pick a channel, or a game and the channel showing it", style = T.small)
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Pill("Live TV", onClick = { source = "live" }, icon = Icons.Rounded.LiveTv, primary = true)
                    Pill("Sports", onClick = { source = "sports" }, icon = Icons.Rounded.SportsSoccer)
                    Pill("Cancel", onClick = onCancel)
                }
            }
        }
        "live" -> ChannelPicker(chans, current, "Pick a channel", onPick, back)
        else -> GamePicker(game, { game = it }, onPick, back)
    }
}

/** Games on now and in the next 12 hours (live first); with one chosen, the user's channels showing it. */
@Composable
private fun GamePicker(game: io.github.novatv.play.sports.SportEvent?, choose: (io.github.novatv.play.sports.SportEvent) -> Unit,
                       onPick: (io.github.novatv.play.data.Channel) -> Unit, onBack: () -> Unit) {
    val sports by App.repo.sports.collectAsState()
    LaunchedEffect(Unit) { App.repo.sportsRepo.refresh() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures(onTap = { onBack() }) }
        .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onBack(); true } else false }) {
        Column(Modifier.width(560.dp).fillMaxHeight().background(Color(0xF00A0E17)).pointerInput(Unit) { detectTapGestures { } }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(game?.title ?: "Pick a game", style = T.h2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Pill("Back", onClick = onBack)
            }
            if (game == null) {
                val now = System.currentTimeMillis()
                val games = remember(sports.events) {
                    sports.events.filter { it.state == io.github.novatv.play.sports.State.LIVE ||
                        (it.state == io.github.novatv.play.sports.State.PRE && it.start - now < 12 * 3600_000L) }
                        .sortedWith(compareBy({ it.state != io.github.novatv.play.sports.State.LIVE }, { it.start }))
                }
                if (games.isEmpty()) Text(if (sports.loading) "Loading games…" else "No games on now or in the next 12 hours", style = T.small, modifier = Modifier.padding(16.dp))
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(games, key = { _, g -> g.id }) { _, g ->
                        val live = g.state == io.github.novatv.play.sports.State.LIVE
                        Tile({ choose(g) }, Modifier.fillMaxWidth().height(66.dp), shape = RoundedCornerShape(10.dp), scale = 1.02f, color = Color.Transparent) {
                            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.Center) {
                                Text(g.title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text((if (live) "LIVE · ${g.detail}" else io.github.novatv.play.ui.clock(g.start)) + " · ${g.league.name}",
                                    style = T.tiny.copy(color = if (live) C.live else C.dim), maxLines = 1)
                            }
                        }
                    }
                }
            } else {
                val epg = App.repo.state.collectAsState().value.epgVersion
                val airings by produceState<List<io.github.novatv.play.sports.Airing>?>(null, game.id, epg) {
                    value = withContext(IO) { App.repo.matcher().airings(game, limit = 150) }
                    val more = withContext(IO) { App.repo.matcher().airings(game, io.github.novatv.play.sports.Broadcasters.forEvent(game), limit = 150) }
                    value = (value.orEmpty() + more).distinctBy { it.channel.id }
                }
                val list = airings
                when {
                    list == null -> Text("Looking through your channels…", style = T.small, modifier = Modifier.padding(16.dp))
                    list.isEmpty() -> Text("None of your channels show this game.", style = T.small, modifier = Modifier.padding(16.dp))
                }
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list.orEmpty(), key = { _, a -> a.channel.id }) { _, a ->
                        Tile({ onPick(a.channel) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(10.dp), scale = 1.02f, color = Color.Transparent) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Logo(a.channel.logo, a.channel.name, Modifier.width(56.dp).height(38.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(a.channel.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The channel list down the left, for multi-view. */
@Composable
private fun ChannelPicker(chans: List<io.github.novatv.play.data.Channel>, current: Int, title: String,
                          onPick: (io.github.novatv.play.data.Channel) -> Unit, onCancel: () -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (current - 4).coerceAtLeast(0))
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures(onTap = { onCancel() }) }
        .onKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Escape) { onCancel(); true } else false }) {
        Column(Modifier.width(460.dp).fillMaxHeight().background(Color(0xF00A0E17)).pointerInput(Unit) { detectTapGestures { } }) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = T.h2, modifier = Modifier.weight(1f))
                Pill("Cancel", onClick = onCancel)
            }
            LazyColumn(state = state, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(chans, key = { _, c -> c.id }) { i, c ->
                    Tile({ onPick(c) }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(10.dp), scale = 1.02f,
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
