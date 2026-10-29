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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Library
import io.github.novatv.play.data.LibIndex
import io.github.novatv.play.toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The categories the Live and Guide screens offer: everything, favourites, recent, then the source's own. */
data class Group(val id: String, val name: String, val channels: List<Channel>)

fun liveGroups(lib: Library): List<Group> {
    val store = App.store; val repo = App.repo
    val idx = repo.state.value.index?.takeIf { it.lib === lib } ?: LibIndex(lib)
    val prefix = repo.channelKeyPrefix()
    val byCat = idx.channelsByCat
    return buildList {
        add(Group("all", "All channels", lib.channels))
        add(Group("fav", "Favourites", idx.channelsFor(store.favourites.filter { it.startsWith(prefix) }).sortedBy { it.number }))
        add(Group("recent", "Recently watched", idx.channelsFor(store.recentChannels.filter { it.startsWith(prefix) })))
        lib.liveCategories.forEach { c -> byCat[c.id]?.let { add(Group(c.id, c.name, it)) } }
        // Channels whose category the source never listed.
        val other = idx.otherChannels
        if (other.isNotEmpty() && other.size != lib.channels.size) add(Group("other", "Other", other))
    }
}

/**
 * Live TV: categories on the left, a dense channel list with what's on now, and (on wide screens) a panel with the
 * focused channel's programme, its progress and what's next. OK plays; hold OK adds to favourites.
 */
@Composable
fun LiveScreen(nav: Nav) {
    val st by App.repo.state.collectAsState()
    var favVersion by remember { mutableIntStateOf(0) }
    val groups by androidx.compose.runtime.produceState(emptyList<Group>(), st.lib, favVersion) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { liveGroups(st.lib) }
    }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (st.status.isNotBlank() || st.index == null) Spinner() else Message("No channels", "This playlist has no live channels.")
        }
        return
    }
    var sel by remember { mutableStateOf("all") }
    val group = groups.firstOrNull { it.id == sel } ?: groups.first()
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(start = S.gutter, top = 30.dp, end = S.gutter)) {
        Header("Live TV", "${group.name} · ${group.channels.size} channels")
        SourceStatus()
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < NARROW
        val catWidth = if (narrow) 160.dp else 220.dp
        // The info panel needs room: shown only when the list keeps at least ~520 units.
        val panel = maxWidth - catWidth >= 900.dp
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(catWidth).fillMaxHeight(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(groups, key = { _, g -> g.id }) { i, g ->
                    val active = g.id == group.id
                    // Moving onto a category shows its channels straight away, like a TV's channel list.
                    Tile({ sel = g.id }, Modifier.fillMaxWidth().height(40.dp).onFocusChanged { if (it.isFocused) sel = g.id }
                        .let { if (i == 0) it.focusRequester(first) else it },
                        shape = RoundedCornerShape(8.dp), scale = 1.0f, color = if (active) C.accent.copy(alpha = 0.14f) else Color.Transparent) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (active) { Box(Modifier.width(3.dp).height(18.dp).background(C.accent, RoundedCornerShape(2.dp))); Spacer(Modifier.width(9.dp)) }
                            Text(g.name, style = T.small.copy(color = if (active) C.text else C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${g.channels.size}", style = T.tiny)
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (group.channels.isEmpty()) {
                    Message(if (group.id == "fav") "No favourites yet" else "Nothing here",
                        if (group.id == "fav") "Hold OK on any channel to add it." else null)
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp)) {
                        Text("CHANNEL", style = T.tiny.copy(letterSpacing = 1.4.sp), modifier = Modifier.weight(1f))
                        Text("ON NOW · ${clock(now())}", style = T.tiny.copy(letterSpacing = 1.4.sp))
                    }
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        itemsIndexed(group.channels, key = { _, c -> c.id }) { i, c ->
                            ChannelLine(c, onClick = { playChannel(group.channels, i) }, onLongClick = {
                                val on = App.store.toggleFavourite(App.repo.channelKey(c))
                                favVersion++
                                toast(if (on) "Added to favourites" else "Removed from favourites")
                            }, onFocus = { focusedChannel = c })
                        }
                    }
                }
            }
            if (panel) {
                Spacer(Modifier.width(20.dp))
                ChannelPanel(focusedChannel ?: group.channels.firstOrNull(), Modifier.width(360.dp))
            }
        }
        }
    }
}

/** The focused channel's programme: title, times, minutes left, progress, description and what's next. */
@Composable
private fun ChannelPanel(c: Channel?, modifier: Modifier) {
    Column(modifier.padding(top = 22.dp)) {
        if (c == null) return@Column
        val nn = nowNext(c)
        val p = nn.firstOrNull()
        val t = now()
        Box(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF07090C)), contentAlignment = Alignment.Center) {
            Logo(c.logo, c.name, Modifier.size(150.dp, 90.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(C.panel)) {
            Box(Modifier.width(3.dp).height(if (p != null) 190.dp else 70.dp).background(C.accent))
            Column(Modifier.padding(16.dp)) {
                Text("ON NOW · ${if (c.number > 0) "${c.number} " else ""}${c.name}".uppercase(), style = T.eyebrow, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (p == null) { Text("No guide information for this channel.", style = T.small); return@Column }
                Text(p.title, style = T.h2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                val left = ((p.stop - t) / 60_000).coerceAtLeast(0)
                Text("${clock(p.start)} – ${clock(p.stop)}   ·   $left min left", style = T.small)
                Spacer(Modifier.height(10.dp))
                Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth())
                p.desc?.takeIf { it.isNotBlank() }?.let { Spacer(Modifier.height(10.dp)); Text(it, style = T.small, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                nn.getOrNull(1)?.let { n -> Spacer(Modifier.height(10.dp)); Text("Next  ${clock(n.start)}  ${n.title}", style = T.tiny.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("OK  Play      Hold OK  Favourite", style = T.tiny)
    }
}

/** One dense channel row: number, logo, name, the programme on now and its progress. */
@Composable
fun ChannelLine(c: Channel, onClick: () -> Unit, onLongClick: () -> Unit, onFocus: () -> Unit = {}) {
    val nn = nowNext(c)
    val p = nn.firstOrNull()
    val t = now()
    val fav = App.store.isFavourite(App.repo.channelKey(c))
    Tile(onClick, Modifier.fillMaxWidth().height(52.dp).onFocusChanged { if (it.isFocused) onFocus() }, onLongClick = onLongClick, scale = 1.0f,
        shape = RoundedCornerShape(8.dp), color = Color.Transparent) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (c.number > 0) "${c.number}" else "", style = T.small.copy(color = C.faint), modifier = Modifier.width(52.dp), maxLines = 1)
            Logo(c.logo, c.name, Modifier.size(52.dp, 34.dp))
            Spacer(Modifier.width(12.dp))
            // Shares, rather than fixed widths, so the row also fits a phone.
            Row(Modifier.weight(0.42f), verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = T.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (fav) { Spacer(Modifier.width(6.dp)); Text("★", style = T.small.copy(color = C.gold)) }
                if (c.catchup) { Spacer(Modifier.width(6.dp)); Badge("CATCH-UP", C.panelHi) }
            }
            Spacer(Modifier.width(14.dp))
            Row(Modifier.weight(0.58f), verticalAlignment = Alignment.CenterVertically) {
                if (p != null) {
                    Text(p.title, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.width(90.dp))
                } else Text("No guide information", style = T.small.copy(color = C.faint))
            }
        }
    }
}
