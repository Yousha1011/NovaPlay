package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.IO
import io.github.novatv.play.toast
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 3 * 3600_000L

/**
 * The TV guide: a programme panel on top for whatever is focused, category chips, then a time grid of the next three
 * hours with a cyan "now" line. Left/Right page the grid by half an hour at its edges; fast-forward and rewind page by
 * three hours.
 */
@Composable
fun GuideScreen(nav: Nav) {
    val st by App.repo.state.collectAsState()
    val groups by androidx.compose.runtime.produceState(emptyList<Group>(), st.lib) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { liveGroups(st.lib).filter { it.channels.isNotEmpty() } }
    }
    var sel by remember { mutableStateOf<String?>(null) }
    val group = groups.firstOrNull { it.id == sel } ?: groups.firstOrNull { it.id == "fav" } ?: groups.firstOrNull()
    var start by remember { mutableLongStateOf(nowMs() / HALF_HOUR * HALF_HOUR) }
    var focused by remember { mutableStateOf<Pair<Channel, Programme?>?>(null) }
    val t = now()
    val earliest = { nowMs() / HALF_HOUR * HALF_HOUR }
    val pageLater = { by: Long -> if (start + by < nowMs() + 4 * 86_400_000L) start += by }
    val pageEarlier = { by: Long -> val n = maxOf(earliest(), start - by); val moved = n != start; start = n; moved }

    Column(Modifier.fillMaxSize().padding(start = S.gutter, top = 26.dp, end = S.gutter)) {
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (st.status.isNotBlank() || st.index == null) Spinner() else Message("No channels", "This playlist has no live channels.")
            }
            return@Column
        }
        GuidePanel(focused, t)
        SourceStatus()
        if (!App.repo.epg.hasData && st.epgStatus == null) {
            Text("This playlist has no TV guide. Add one in Settings (XMLTV link) to fill the grid.", style = T.small, modifier = Modifier.padding(bottom = 10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Pill("Now", onClick = { start = earliest() }, selected = start == earliest())
            Spacer(Modifier.width(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(groups, key = { it.id }) { g -> Pill(g.name, onClick = { sel = g.id }, selected = g.id == group?.id) }
            }
        }
        if (group == null) return@Column
        BoxWithConstraints(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(C.panel.copy(alpha = 0.5f))) {
            val chW = if (maxWidth < NARROW) 150.dp else 250.dp
            val gridW = maxWidth - chW
            Column {
                // Time ruler.
                Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${dayLabel(start)}".uppercase(), style = T.tiny.copy(letterSpacing = 1.4.sp), modifier = Modifier.width(chW).padding(start = 14.dp))
                    for (i in 0 until 6) Text(clock(start + i * HALF_HOUR), style = T.small.copy(color = C.text, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(gridW / 6))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(C.line))
                Box {
                    LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
                        itemsIndexed(group.channels, key = { _, c -> c.id }) { i, c ->
                            GuideRow(c, group.channels, i, start, chW, gridW, st.epgVersion,
                                // Left pages back only as far as the current half hour; from there it reaches the channel column.
                                onEarlier = { pageEarlier(HALF_HOUR) }, onLater = { pageLater(HALF_HOUR) },
                                onPage = { forward -> if (forward) pageLater(WINDOW) else pageEarlier(WINDOW) },
                                onFocus = { p -> focused = c to p })
                        }
                    }
                    // The "now" line, with its tag.
                    val x = ((t - start).toFloat() / WINDOW)
                    if (x in 0f..1f) {
                        Box(Modifier.offset(x = chW + gridW * x - 1.dp).width(2.dp).fillMaxHeight().background(C.accent))
                        Box(Modifier.offset(x = chW + gridW * x - 20.dp).clip(RoundedCornerShape(4.dp)).background(C.accent).padding(horizontal = 6.dp, vertical = 1.dp)) {
                            Text("NOW", style = T.tiny.copy(color = C.onAccent, fontWeight = FontWeight.Bold))
                        }
                    }
                }
            }
        }
    }
}

/** What's focused in the grid: the programme's title, channel, times, minutes left, progress and description. */
@Composable
private fun GuidePanel(f: Pair<Channel, Programme?>?, t: Long) {
    Row(Modifier.fillMaxWidth().height(132.dp).padding(bottom = 14.dp).clip(RoundedCornerShape(12.dp)).background(C.panel)) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(C.accent))
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            if (f == null) { Text("Move through the grid to see a programme's details.", style = T.small); return@Column }
            val (c, p) = f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p?.title ?: c.name, style = T.h2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (p?.isLive == true) { Spacer(Modifier.width(12.dp)); LiveMark() }
            }
            Text("${if (c.number > 0) "${c.number}  " else ""}${c.name}", style = T.small.copy(color = C.accent), maxLines = 1)
            if (p != null) {
                val extra = if (p.isLive) "   ·   ${((p.stop - t) / 60_000).coerceAtLeast(0)} min left" else if (p.start > t) "   ·   ${dayLabel(p.start)}" else ""
                Text("${clock(p.start)} – ${clock(p.stop)}$extra", style = T.small, maxLines = 1)
                if (p.isLive) { Spacer(Modifier.height(6.dp)); Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.width(420.dp)) }
                p.desc?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(4.dp)); Text(it, style = T.tiny.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else Text("No guide information for this channel.", style = T.small)
        }
    }
}

@Composable
private fun GuideRow(c: Channel, list: List<Channel>, index: Int, start: Long, chW: Dp, gridW: Dp, epgVersion: Int,
                     onEarlier: () -> Boolean, onLater: () -> Unit, onPage: (Boolean) -> Unit, onFocus: (Programme?) -> Unit) {
    val end = start + WINDOW
    val progs by produceState(emptyList<Programme>(), c.id, start, epgVersion) {
        value = withContext(IO) { App.repo.epg.key(c)?.let { App.repo.epg.programmes(it, start, end) } ?: emptyList() }
    }
    Row(Modifier.height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Tile({ playChannel(list, index) }, Modifier.width(chW).fillMaxHeight().padding(2.dp).onFocusChanged { if (it.isFocused) onFocus(null) },
            shape = RoundedCornerShape(6.dp), scale = 1.0f, color = Color.Transparent) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (c.number > 0) Text("${c.number}", style = T.small.copy(color = C.faint), maxLines = 1, modifier = Modifier.width(44.dp))
                Logo(c.logo, c.name, Modifier.size(40.dp, 28.dp))
                Spacer(Modifier.width(8.dp))
                Text(c.name, style = T.small.copy(color = C.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.width(gridW).fillMaxHeight()) {
            if (progs.isEmpty()) {
                GuideCell("No information", null, Modifier.fillMaxSize().padding(2.dp), first = true, last = true, onEarlier, onLater, onPage, { onFocus(null) }) { playChannel(list, index) }
            } else progs.forEachIndexed { i, p ->
                val s = maxOf(p.start, start); val e = minOf(p.stop, end)
                val x = gridW * ((s - start).toFloat() / WINDOW)
                val w = (gridW * ((e - s).toFloat() / WINDOW)).coerceAtLeast(8.dp)
                GuideCell(p.title, p, Modifier.offset(x = x).width(w).fillMaxHeight().padding(2.dp), i == 0, i == progs.lastIndex, onEarlier, onLater, onPage, { onFocus(p) }) {
                    if (p.isLive || p.start <= nowMs()) playChannel(list, index)
                    else toast("${p.title} starts ${dayLabel(p.start)} at ${clock(p.start)}")
                }
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, p: Programme?, modifier: Modifier, first: Boolean, last: Boolean,
                      onEarlier: () -> Boolean, onLater: () -> Unit, onPage: (Boolean) -> Unit, onFocus: () -> Unit, onClick: () -> Unit) {
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
    }, shape = RoundedCornerShape(6.dp), scale = 1.0f, color = if (live) C.panelHi else C.panel) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = T.small.copy(color = if (p == null) C.faint else C.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}
