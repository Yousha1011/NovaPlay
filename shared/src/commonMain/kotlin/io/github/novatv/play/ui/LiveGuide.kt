package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Programme
import io.github.novatv.play.data.Recordings
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.toast
import kotlinx.coroutines.withContext

private const val HALF = 30 * 60_000L
private const val SPAN = 2 * 3600_000L

/**
 * Live TV as a TiviMate-style guide in one screen: the focused programme large on top with the live preview beside it,
 * then the groups (with their counts), the channels and a two-hour timeline with a "now" line, all at once. Moving onto
 * a group shows its channels; OK on a channel or a programme on now plays it; hold OK on a channel adds a favourite and
 * on a later programme records it. Left at the timeline's first programme goes back to the channels, Left again to the
 * groups.
 */
@Composable
fun LiveGuideScreen(nav: Nav) {
    val st by App.repo.state.collectAsState()
    var favVersion by remember { mutableIntStateOf(0) }
    val groups by produceState(emptyList<Group>(), st.lib, favVersion) {
        value = withContext(kotlinx.coroutines.Dispatchers.Default) { liveGroups(st.lib) }
    }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (st.status.isNotBlank() || st.index == null) Spinner() else Message("No channels", "This playlist has no live channels.")
        }
        return
    }
    var sel by remember { mutableStateOf("all") }
    val group = groups.firstOrNull { it.id == sel } ?: groups.first()
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    var start by remember { mutableLongStateOf(nowMs() / HALF * HALF) }
    val t = now()
    val earliest = { nowMs() / HALF * HALF }
    val pageLater = { by: Long -> if (start + by < nowMs() + 4 * 86_400_000L) start += by }
    val pageEarlier = { by: Long -> val n = maxOf(earliest(), start - by); val moved = n != start; start = n; moved }

    // The preview follows the highlighted channel, as before, and stops when Live TV is hidden or left.
    val shown = focused?.first ?: group.channels.firstOrNull()
    val follower = remember { io.github.novatv.play.data.PagePreview(App.preview, "live") }
    val visible = nav.visibility(io.github.novatv.play.Tab.LIVE) == io.github.novatv.play.PageVisibility.VISIBLE
    androidx.compose.runtime.LaunchedEffect(focused?.first, visible) { follower.update(focused?.first, focused?.first, visible) }
    androidx.compose.runtime.LaunchedEffect(st.source?.id) { focused = null; App.preview.stop("live") }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { App.preview.stop("live") } }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 22.dp, end = 28.dp)) {
        if (shown != null) TopPanel(shown, focused?.second ?: nowNext(shown).firstOrNull(), t)
        SourceStatus()
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val groupW = if (maxWidth < NARROW) 170.dp else 230.dp
            val chW = if (maxWidth < NARROW) 190.dp else 300.dp
            val gridW = maxWidth - groupW - chW - 16.dp
            Row(Modifier.fillMaxSize()) {
                // Groups: search, then favourites, recent, everything and the playlist's own, each with its count.
                LazyColumn(Modifier.width(groupW).fillMaxHeight().rememberFocusInList(), contentPadding = PaddingValues(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item(key = "search") {
                        Tile({ nav.tab = io.github.novatv.play.Tab.SEARCH }, Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(10.dp), scale = 1.0f, color = C.panel) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("⌕  Search", style = T.small.copy(color = C.text, fontWeight = FontWeight.SemiBold))
                            }
                        }
                    }
                    itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                        val active = g.id == group.id
                        Tile({ sel = g.id }, Modifier.fillMaxWidth().height(44.dp).onFocusChanged { if (it.isFocused) sel = g.id }
                            .let { if (active) it.border(2.dp, C.text.copy(alpha = 0.85f), RoundedCornerShape(10.dp)) else it },
                            shape = RoundedCornerShape(10.dp), scale = 1.0f, color = if (active) C.panelHi else C.panel) {
                            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(g.name, style = T.small.copy(color = if (active) C.text else C.dim, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text("${g.channels.size}", style = T.tiny.copy(color = C.dim))
                            }
                        }
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // Time ruler: the day over the channels, half hours over the timeline, the time now at the end.
                    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dayLabel(start).replaceFirstChar { it.uppercase() }, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(chW).padding(start = 12.dp))
                        Box(Modifier.width(gridW).fillMaxHeight()) {
                            for (i in 0 until 4) Text(clock(start + i * HALF), style = T.small.copy(color = C.dim),
                                modifier = Modifier.offset(x = gridW / 4 * i + 6.dp).align(Alignment.CenterStart))
                            Box(Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp)).background(C.panelHi).padding(horizontal = 10.dp, vertical = 3.dp)) {
                                Text(clock(t), style = T.body.copy(color = C.text, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    if (group.channels.isEmpty()) {
                        Message(if (group.id == "fav") "No favourites yet" else "Nothing here", if (group.id == "fav") "Hold OK on any channel to add it." else null)
                    } else Box(Modifier.fillMaxSize()) {
                        LazyColumn(Modifier.fillMaxSize().rememberFocusInList(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            itemsIndexed(group.channels, key = { _, c -> c.id }) { i, c ->
                                GuideLine(c, group.channels, i, start, chW, gridW, st.epgVersion, focused?.first?.id == c.id,
                                    onFocus = { p -> focused = c to p },
                                    onFavourite = {
                                        val on = App.store.toggleFavourite(App.repo.channelKey(c)); favVersion++
                                        toast(if (on) "Added to favourites" else "Removed from favourites")
                                    },
                                    onEarlier = { pageEarlier(HALF) }, onLater = { pageLater(HALF) },
                                    onPage = { forward -> if (forward) pageLater(SPAN) else pageEarlier(SPAN) })
                            }
                        }
                        // The "now" line across the timeline, with a dot at its top.
                        val x = (t - start).toFloat() / SPAN
                        if (x in 0f..1f) {
                            Box(Modifier.offset(x = chW + 8.dp + gridW * x - 1.dp).width(2.dp).fillMaxHeight().background(C.text.copy(alpha = 0.9f)))
                            Box(Modifier.offset(x = chW + 8.dp + gridW * x - 7.dp, y = (-4).dp).size(14.dp).clip(RoundedCornerShape(7.dp)).background(C.text))
                        }
                    }
                }
            }
        }
    }
}

/** The focused programme, large: channel and LIVE, title, times and time left, progress and description; the preview on the right. */
@Composable
private fun TopPanel(c: Channel, p: Programme?, t: Long) {
    Row(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(22.dp)).background(C.panel).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(132.dp, 168.dp).clip(RoundedCornerShape(14.dp)).background(C.panelHi), contentAlignment = Alignment.Center) {
            Logo(c.logo, c.name, Modifier.size(104.dp, 72.dp))
        }
        Spacer(Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(c.logo, c.name, Modifier.size(36.dp, 26.dp)); Spacer(Modifier.width(12.dp))
                Text("${if (c.number > 0) "${c.number}  " else ""}${c.name}", style = T.body.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (p?.isLive == true) { Spacer(Modifier.width(12.dp)); LiveMark() }
            }
            Spacer(Modifier.height(6.dp))
            Text(p?.title ?: c.name, style = T.h1.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                val left = ((p.stop - t) / 60_000).coerceAtLeast(0)
                val tail = when { p.isLive -> "  ·  ${if (left >= 60) "${left / 60}h ${left % 60}m" else "${left}m"} left"; p.start > t -> "  ·  ${dayLabel(p.start)}"; else -> "" }
                Text("${clock(p.start)} – ${clock(p.stop)}$tail", style = T.body.copy(color = C.dim))
                if (p.isLive) { Spacer(Modifier.height(10.dp)); Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.width(460.dp), color = C.text) }
                p.desc?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.small.copy(color = C.dim), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            } else Text("No guide information for this channel.", style = T.body.copy(color = C.dim))
        }
        Spacer(Modifier.width(18.dp))
        PreviewBox(c, Modifier.fillMaxHeight().aspectRatio(16f / 9f), "live")
    }
}

/** One channel: number, logo and name in a rounded box, then its programmes on the timeline as rounded boxes. */
@Composable
private fun GuideLine(c: Channel, list: List<Channel>, index: Int, start: Long, chW: Dp, gridW: Dp, epgVersion: Int, current: Boolean,
                      onFocus: (Programme?) -> Unit, onFavourite: () -> Unit,
                      onEarlier: () -> Boolean, onLater: () -> Unit, onPage: (Boolean) -> Unit) {
    val end = start + SPAN
    val progs by produceState(emptyList<Programme>(), c.id, start, epgVersion) {
        value = withContext(IO) { App.repo.epg.key(c)?.let { App.repo.epg.programmes(it, start, end) } ?: emptyList() }
    }
    val fav = App.store.isFavourite(App.repo.channelKey(c))
    Row(Modifier.height(56.dp).rememberFocusInList(), verticalAlignment = Alignment.CenterVertically) {
        Tile({ playChannel(list, index) }, Modifier.width(chW).fillMaxHeight().onFocusChanged { if (it.isFocused) onFocus(null) }
            .let { if (current) it.border(2.dp, C.live.copy(alpha = 0.8f), RoundedCornerShape(12.dp)) else it },
            onLongClick = onFavourite, shape = RoundedCornerShape(12.dp), scale = 1.0f, color = C.panel) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (App.store.showChannelNumbers) Text(if (c.number > 0) "${c.number}" else "", style = T.body.copy(color = C.dim), maxLines = 1, modifier = Modifier.width(58.dp))
                if (App.store.showChannelLogos) { Logo(c.logo, c.name, Modifier.size(40.dp, 30.dp)); Spacer(Modifier.width(12.dp)) }
                Text(c.name, style = T.body.copy(color = C.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (fav) { Spacer(Modifier.width(6.dp)); Text("★", style = T.small.copy(color = C.gold)) }
                if (c.catchup) { Spacer(Modifier.width(6.dp)); Badge("CATCH-UP", C.panelHi) }
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(gridW).fillMaxHeight()) {
            if (progs.isEmpty()) {
                Cell(c.name, null, Modifier.fillMaxSize(), first = true, last = true, onEarlier, onLater, onPage, { onFocus(null) }) { playChannel(list, index) }
            } else progs.forEachIndexed { i, p ->
                val s = maxOf(p.start, start); val e = minOf(p.stop, end)
                val x = gridW * ((s - start).toFloat() / SPAN)
                val w = (gridW * ((e - s).toFloat() / SPAN) - 6.dp).coerceAtLeast(10.dp)
                Cell(p.title, p, Modifier.offset(x = x).width(w).fillMaxHeight(), i == 0, i == progs.lastIndex, onEarlier, onLater, onPage, { onFocus(p) },
                    onLongClick = if (App.host.recorder == null) null else ({ toast(Recordings.schedule(c, p) ?: "Will record ${p.title}") }),
                    recording = Recordings.version.let { Recordings.at(c, p.start) != null }) {
                    if (p.isLive || p.start <= nowMs()) playChannel(list, index)
                    else toast("${p.title} starts ${dayLabel(p.start)} at ${clock(p.start)}")
                }
            }
        }
    }
}

/** A programme on the timeline: the one on now a shade lighter with a bar under it; a channel with no guide shows its name, faded. */
@Composable
private fun Cell(title: String, p: Programme?, modifier: Modifier, first: Boolean, last: Boolean,
                 onEarlier: () -> Boolean, onLater: () -> Unit, onPage: (Boolean) -> Unit, onFocus: () -> Unit,
                 onLongClick: (() -> Unit)? = null, recording: Boolean = false, onClick: () -> Unit) {
    val live = p?.isLive == true
    Tile(onClick, modifier.onFocusChanged { if (it.isFocused) onFocus() }.onPreviewKeyEvent { k ->
        if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            k.key == Key.DirectionLeft && first -> onEarlier()
            k.key == Key.DirectionRight && last -> { onLater(); true }
            k.key == Key.MediaFastForward || k.key == Key.PageDown -> { onPage(true); true }
            k.key == Key.MediaRewind || k.key == Key.PageUp -> { onPage(false); true }
            else -> false
        }
    }, onLongClick = onLongClick, shape = RoundedCornerShape(12.dp), scale = 1.0f, color = if (live) C.panelHi else C.panel) {
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (recording) { Dot(C.live, 8.dp); Spacer(Modifier.width(6.dp)) }
                Text(title, style = T.body.copy(color = if (p == null) C.faint else C.text, fontStyle = if (p == null) androidx.compose.ui.text.font.FontStyle.Italic else null),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (live) Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 4.dp).width(40.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(C.text.copy(alpha = 0.7f)))
        }
    }
}
