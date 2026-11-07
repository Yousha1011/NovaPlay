package io.github.novatv.play.ui

import androidx.compose.foundation.background
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.novatv.play.App
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Programme
import io.github.novatv.play.platform.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The guide over the picture while watching (TiviMate's overlay): a see-through panel from the left with the channels of
 * the list playing and what's on each, and the highlighted channel's schedule beside them. Left from the channels opens
 * the groups (moving onto one shows its channels); Right shows the highlighted channel's schedule; OK plays.
 * [onPlay] gets the list and the position picked (a group's list when it isn't the one playing).
 */
@Composable
fun OverlayGuide(playing: List<Channel>, playingIndex: Int, onPlay: (List<Channel>, Int) -> Unit) {
    val st = App.repo.state.value
    val groups by produceState(emptyList<Group>(), st.lib) { value = withContext(kotlinx.coroutines.Dispatchers.Default) { liveGroups(st.lib) } }
    var groupsOpen by remember { mutableStateOf(false) }
    // The list shown: the one playing until another group is picked.
    var shown by remember { mutableStateOf<Group?>(null) }
    val list = shown?.channels ?: playing
    val startAt = if (shown == null) playingIndex else 0
    var focused by remember { mutableStateOf<Channel?>(list.getOrNull(startAt)) }
    val first = remember { FocusRequester() }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (startAt - 3).coerceAtLeast(0))
    LaunchedEffect(shown) { repeat(5) { try { first.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(50) } } }

    Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color(0xF2080B10), 0.62f to Color(0xD9080B10), 1f to Color(0x00080B10)))
        .padding(start = 28.dp, top = 28.dp, bottom = 20.dp)) {
        if (groupsOpen) {
            LazyColumn(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                itemsIndexed(groups, key = { _, g -> g.id }) { _, g ->
                    val on = (shown?.id ?: "") == g.id
                    Tile({ shown = g; groupsOpen = false }, Modifier.fillMaxWidth().height(46.dp).onFocusChanged { if (it.isFocused) shown = g }
                        .onPreviewKeyEvent { k -> if (k.type == KeyEventType.KeyDown && k.key == Key.DirectionRight) { groupsOpen = false; true } else false },
                        shape = RoundedCornerShape(10.dp), scale = 1.0f, color = if (on) C.panelHi else Color(0x661A2028)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name, style = T.small.copy(color = if (on) C.text else C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${g.channels.size}", style = T.tiny.copy(color = C.dim))
                        }
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.width(470.dp).fillMaxHeight()) {
            Text(shown?.name ?: "Channels", style = T.h2, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
            LazyColumn(Modifier.fillMaxSize(), state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 30.dp)) {
                itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                    val nn = nowNext(c)
                    val p = nn.firstOrNull()
                    val t = now()
                    val current = shown == null && i == playingIndex
                    Tile({ onPlay(list, i) }, Modifier.fillMaxWidth().height(66.dp).let { if (i == startAt) it.focusRequester(first) else it }
                        .onFocusChanged { if (it.isFocused) focused = c }
                        .onPreviewKeyEvent { k -> if (k.type == KeyEventType.KeyDown && k.key == Key.DirectionLeft && !groupsOpen) { groupsOpen = true; true } else false },
                        shape = RoundedCornerShape(12.dp), scale = 1.0f, color = if (current) C.accent.copy(alpha = 0.22f) else Color(0x661A2028)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (c.number > 0) "${c.number}" else "", style = T.small.copy(color = C.dim), modifier = Modifier.width(52.dp), maxLines = 1)
                            Logo(c.logo, c.name, Modifier.size(48.dp, 34.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p?.title ?: "No guide information", style = T.small.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (p != null) Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.width(140.dp).padding(top = 3.dp), color = C.text)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        focused?.let { Schedule(it, Modifier.width(470.dp).fillMaxHeight()) }
    }
}

/** The highlighted channel's programmes from now to the next 12 hours, the one on now first and in full. */
@Composable
private fun Schedule(c: Channel, modifier: Modifier) {
    val progs by produceState(emptyList<Programme>(), c.id) {
        value = withContext(IO) {
            val t = io.github.novatv.play.platform.nowMs()
            App.repo.epg.key(c)?.let { App.repo.epg.programmes(it, t, t + 12 * 3600_000L) }.orEmpty().filter { it.stop > t }
        }
    }
    val t = now()
    Column(modifier) {
        Text(c.name, style = T.small.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 6.dp))
        val p = progs.firstOrNull()
        if (p == null) { Text("No guide information for this channel.", style = T.body.copy(color = C.dim)); return@Column }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, style = T.h2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (p.isLive) { Spacer(Modifier.width(10.dp)); LiveMark() }
        }
        Text("${clock(p.start)} – ${clock(p.stop)}" + if (p.isLive) "  ·  ${((p.stop - t) / 60_000).coerceAtLeast(0)}m left" else "", style = T.small.copy(color = C.dim))
        p.desc?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(6.dp)); Text(it, style = T.small, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(14.dp))
        Text("LATER", style = T.eyebrow)
        Spacer(Modifier.height(6.dp))
        progs.drop(1).take(8).forEach { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(clock(n.start), style = T.small.copy(color = C.dim), modifier = Modifier.width(70.dp))
                Text(n.title, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
