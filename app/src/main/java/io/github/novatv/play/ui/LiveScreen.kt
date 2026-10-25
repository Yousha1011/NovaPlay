package io.github.novatv.play.ui

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Library
import io.github.novatv.play.data.LibIndex

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

@Composable
fun LiveScreen(nav: Nav) {
    val ctx = LocalContext.current
    val st by App.repo.state.collectAsStateWithLifecycle()
    var favVersion by remember { mutableIntStateOf(0) }
    val groups by androidx.compose.runtime.produceState(emptyList<Group>(), st.lib, favVersion) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { liveGroups(st.lib) }
    }
    if (groups.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }; return }
    var sel by remember { mutableStateOf("all") }
    val group = groups.firstOrNull { it.id == sel } ?: groups.first()
    val first = remember { FocusRequester() }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 30.dp, end = 30.dp)) {
        Header("Live TV", "${group.name} · ${group.channels.size} channels")
        SourceStatus()
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(300.dp).fillMaxHeight(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(groups, key = { _, g -> g.id }) { i, g ->
                    val active = g.id == group.id
                    // Moving onto a category shows its channels straight away, like a TV's channel list.
                    Tile({ sel = g.id }, Modifier.fillMaxWidth().height(48.dp).onFocusChanged { if (it.isFocused) sel = g.id }
                        .let { if (i == 0) it.focusRequester(first) else it },
                        shape = RoundedCornerShape(10.dp), scale = 1.02f, color = if (active) C.accent.copy(alpha = 0.2f) else Color.Transparent) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(g.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${g.channels.size}", style = T.small)
                        }
                    }
                }
            }
            Spacer(Modifier.width(22.dp))
            if (group.channels.isEmpty()) {
                Message(if (group.id == "fav") "No favourites yet" else "Nothing here",
                    if (group.id == "fav") "Hold OK on any channel to add it." else null)
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(group.channels, key = { _, c -> c.id }) { i, c ->
                    ChannelLine(c, onClick = { playChannel(ctx, group.channels, i) }, onLongClick = {
                        val on = App.store.toggleFavourite(App.repo.channelKey(c))
                        favVersion++
                        Toast.makeText(ctx, if (on) "Added to favourites" else "Removed from favourites", Toast.LENGTH_SHORT).show()
                    })
                }
            }
        }
    }
}

@Composable
fun ChannelLine(c: Channel, onClick: () -> Unit, onLongClick: () -> Unit) {
    val nn = nowNext(c)
    val p = nn.firstOrNull()
    val t = now()
    val fav = App.store.isFavourite(App.repo.channelKey(c))
    Tile(onClick, Modifier.fillMaxWidth().height(76.dp), onLongClick = onLongClick, scale = 1.02f, shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.number}", style = T.small, modifier = Modifier.width(46.dp))
            Logo(c.logo, c.name, Modifier.size(78.dp, 50.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.width(260.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (fav) { Spacer(Modifier.width(6.dp)); Text("★", style = T.small.copy(color = C.gold)) }
                    if (c.catchup) { Spacer(Modifier.width(6.dp)); Badge("CATCH-UP", C.panelHi) }
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (p != null) {
                    Row {
                        Text("${clock(p.start)}  ", style = T.small.copy(color = C.accent))
                        Text(p.title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.width(140.dp))
                        nn.getOrNull(1)?.let { n ->
                            Spacer(Modifier.width(12.dp))
                            Text("Next ${clock(n.start)} ${n.title}", style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else Text("No guide information", style = T.small.copy(color = C.faint))
            }
        }
    }
}
