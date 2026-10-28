package io.github.novatv.play.ui.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.Channel
import io.github.novatv.play.res.Res
import io.github.novatv.play.res.logo_mark
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.playChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource

private class Found(val channels: List<Channel> = emptyList(), val movies: List<Any> = emptyList(), val series: List<Any> = emptyList())

/** Search: one box for channels, films and series, recent searches under it while it is empty. */
@Composable
fun PhoneSearch(nav: Nav) {
    var q by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }
    var historyVersion by remember { mutableIntStateOf(0) }
    val found by produceState(Found(), q) {
        delay(250)
        val s = q.trim().lowercase()
        if (s.length < 2) { value = Found(); return@produceState }
        val lib = App.repo.state.value.lib
        value = withContext(Dispatchers.Default) {
            Found(lib.channels.filter { it.name.lowercase().contains(s) }.take(60),
                lib.movies.filter { it.name.lowercase().contains(s) }.take(40),
                lib.series.filter { it.name.lowercase().contains(s) }.take(40))
        }
    }
    fun saveSearch(text: String) { App.store.addSearch(text); historyVersion++ }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        if (!typing) {
            TopBar(nav) { RoundIcon(Icons.Rounded.Settings, onClick = { nav.push(Route.SearchSettings) }) }
            PageTitle("Search")
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(25.dp)).background(P.card).border(1.dp, P.line, RoundedCornerShape(25.dp))
                .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, size = 22.dp, tint = P.dim)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search", style = P.body.copy(color = P.dim))
                    BasicTextField(q, { q = it }, Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) typing = true },
                        singleLine = true, textStyle = P.body, cursorBrush = SolidColor(P.blue),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { saveSearch(q) }))
                }
            }
            if (typing) { Spacer(Modifier.width(10.dp)); RoundIcon(Icons.Rounded.Close, onClick = { q = ""; typing = false }, size = 50.dp) }
        }
        val s = q.trim()
        when {
            s.length < 2 && typing -> {
                val history = remember(historyVersion) { App.store.searchHistory }
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp)) {
                    items(history, key = { it }) { h ->
                        Column(Modifier.tap { q = h }) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(h, style = P.head, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(Icons.Rounded.History, size = 22.dp)
                            }
                            Box(Modifier.fillMaxWidth().height(1.dp).background(P.line))
                        }
                    }
                }
            }
            s.length < 2 -> Empty(Icons.Rounded.Search, "Content Search", "Search for channels, programmes, movies and TV shows")
            found.channels.isEmpty() && found.movies.isEmpty() && found.series.isEmpty() -> Empty(Icons.Rounded.Search, "No results", "Nothing matches \"$s\".")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                if (found.channels.isNotEmpty()) {
                    item { RowTitle("Channels (${found.channels.size})", null) }
                    items(found.channels.size, key = { "c" + found.channels[it].id }) { i ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 5.dp)) {
                            PhoneChannelRow(found.channels[i]) { saveSearch(s); playChannel(found.channels, i) }
                        }
                    }
                }
                item { PosterRow("Movies (${found.movies.size})", found.movies, { saveSearch(s); open(nav, it) }, null) }
                item { PosterRow("TV Shows (${found.series.size})", found.series, { saveSearch(s); open(nav, it) }, null) }
            }
        }
    }
}

/** Search settings: keep history or not, and the history itself. */
@Composable
fun SearchSettings(nav: Nav) {
    var v by remember { mutableIntStateOf(0) }
    val off = remember(v) { App.store.searchHistoryOff }
    val history = remember(v) { App.store.searchHistory }
    Pushed(nav) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 60.dp)) {
            item { Text("Search Settings", style = P.large, modifier = Modifier.padding(vertical = 8.dp)) }
            item {
                Text("Search Options", style = P.head.copy(color = P.dim), modifier = Modifier.padding(top = 14.dp, bottom = 8.dp, start = 16.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.card).tap { App.store.searchHistoryOff = !off; v++ }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Disable Search History", style = P.body, modifier = Modifier.weight(1f))
                    Switch(off)
                }
                Text("Disabling search history will not show the history below the search bar and will not save any search queries.",
                    style = P.small, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            item {
                Row(Modifier.padding(top = 10.dp, bottom = 8.dp, start = 16.dp, end = 16.dp)) {
                    Text("Search History List", style = P.head.copy(color = P.dim), modifier = Modifier.weight(1f))
                    Text("Delete All", style = P.body.copy(color = P.red), modifier = Modifier.tap { App.store.searchHistory = emptyList(); v++ })
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.card)) {
                    if (history.isEmpty()) Text("No searches yet", style = P.body.copy(color = P.dim), modifier = Modifier.padding(18.dp))
                    history.forEachIndexed { i, h ->
                        if (i > 0) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(P.line))
                        Text(h, style = P.body, modifier = Modifier.padding(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Switch(on: Boolean) {
    Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFF30D158) else P.card2).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(Color.White))
    }
}

/** The app menu: playlists, guides, sports, settings and about, in grouped cards. */
@Composable
fun AppMenu(nav: Nav) {
    val st by App.repo.state.collectAsState()
    Pushed(nav, brand = false) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Text("App Menu", style = P.large, modifier = Modifier.padding(vertical = 8.dp))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.card).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Image(painterResource(Res.drawable.logo_mark), "NovaPlay", Modifier.size(52.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("NovaPlay", style = P.title)
                        Text(st.source?.let { "${it.name} · ${io.github.novatv.play.platform.grouped(st.lib.channels.size)} channels" } ?: "No playlist yet",
                            style = P.small.copy(color = P.blue))
                    }
                }
            }
            item {
                Group(listOf(
                    MenuItem(Icons.Rounded.List, "Playlist Manager") { nav.push(Route.Playlists) },
                    MenuItem(Icons.Rounded.Add, "Add a playlist") { nav.push(Route.AddSource) },
                    MenuItem(Icons.Rounded.Event, "EPG Sources") { nav.push(Route.EpgSources) },
                ))
            }
            item {
                Group(listOf(
                    MenuItem(Icons.Rounded.SportsSoccer, "Choose sports") { nav.push(Route.Leagues) },
                    MenuItem(Icons.Rounded.Search, "Search Settings") { nav.push(Route.SearchSettings) },
                    MenuItem(Icons.Rounded.Palette, "Appearance") { nav.push(Route.Appearance) },
                    MenuItem(Icons.Rounded.Tune, "Configurations") { nav.push(Route.Configurations) },
                ))
            }
            item {
                Group(listOf(MenuItem(Icons.Rounded.Info, "About NovaPlay") { io.github.novatv.play.toast("NovaPlay 1.0 for iPhone") }))
            }
        }
    }
}

private class MenuItem(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun Group(items: List<MenuItem>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.card)) {
        items.forEachIndexed { i, m ->
            if (i > 0) Box(Modifier.padding(start = 60.dp).fillMaxWidth().height(1.dp).background(P.line))
            Row(Modifier.fillMaxWidth().tap(m.onClick).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(15.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(m.icon, size = 18.dp, tint = Color.Black)
                }
                Spacer(Modifier.width(14.dp))
                Text(m.label, style = P.body.copy(fontSize = 18.sp), modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, size = 22.dp, tint = P.dim)
            }
        }
    }
}


/** Appearance: the colour themes, each shown as a swatch of its background and accents. */
@Composable
fun Appearance(nav: Nav) {
    Pushed(nav) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { Text("Appearance", style = P.large, modifier = Modifier.padding(vertical = 8.dp)) }
            item { Text("THEME", style = P.caps, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
            items(P.themes, key = { it.id }) { t ->
                val on = P.theme.id == t.id
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(t.top, t.bottom)))
                    .border(if (on) 2.dp else 1.dp, if (on) t.accent else P.line, RoundedCornerShape(22.dp))
                    .tap { P.theme = t; App.store.phoneTheme = t.id }.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = P.title.copy(fontSize = androidx.compose.ui.unit.TextUnit(21f, androidx.compose.ui.unit.TextUnitType.Sp)))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (c in listOf(t.card, t.card2, t.accent, t.highlight)) Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(c).border(1.dp, P.line, RoundedCornerShape(13.dp)))
                        }
                    }
                    if (on) Icon(Icons.Rounded.Check, size = 28.dp, tint = t.accent)
                }
            }
        }
    }
}
