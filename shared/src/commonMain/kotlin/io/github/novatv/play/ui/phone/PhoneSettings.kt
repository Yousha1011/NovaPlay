package io.github.novatv.play.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.platform.IO
import io.github.novatv.play.toast
import io.github.novatv.play.ui.COUNTRIES
import io.github.novatv.play.ui.Text
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A glass card holding one setting or group of settings. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/** A small glass capsule for an action inside a card. */
@Composable
private fun SmallButton(label: String, color: Color = Color.White, onClick: () -> Unit) {
    Box(Modifier.glass(RoundedCornerShape(50)).tap(onClick).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Text(label, style = P.body.copy(fontSize = 15.sp, color = color), maxLines = 1)
    }
}

/** Playlist Manager: every saved playlist, the one in use marked, each used, refreshed or removed here. */
@Composable
fun PhonePlaylists(nav: Nav) {
    val st by App.repo.state.collectAsState()
    var v by remember { mutableIntStateOf(0) }
    val sources = remember(v, st.source) { App.store.sources }
    Pushed(nav) {
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageTitle("Playlist Manager") }
            if (sources.isEmpty()) item { Text("No playlists yet.", style = P.body.copy(color = P.dim), modifier = Modifier.padding(horizontal = 20.dp)) }
            items(sources, key = { it.id }) { s ->
                val active = s.id == st.source?.id
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = P.head.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${s.type.label} · ${s.url.substringAfter("://").substringBefore('/').substringBefore('?')}", style = P.small, maxLines = 1)
                        }
                        if (active) Text("In use", style = P.small.copy(color = P.blue))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (active) SmallButton("Refresh") { App.repo.refresh() }
                        else SmallButton("Use") { App.repo.open(s, force = false) }
                        SmallButton("Remove", P.red) { App.repo.removeSource(s.id); v++; toast("Removed ${s.name}") }
                    }
                }
            }
            item { BigButton("Add a playlist", Modifier.padding(horizontal = 16.dp).padding(top = 8.dp)) { nav.push(Route.AddSource) } }
        }
    }
}

/** EPG Sources: what the TV guide holds, a reload, and extra XMLTV guides. */
@Composable
fun PhoneEpgSources(nav: Nav) {
    val st by App.repo.state.collectAsState()
    var urls by remember { mutableStateOf(App.store.extraEpg.joinToString(" ")) }
    Pushed(nav) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageTitle("EPG Sources") }
            item { SectionLabel("TV guide") }
            item {
                Card {
                    Text(remember(st.epgVersion) { App.repo.epg.summary() }, style = P.body)
                    st.epgStatus?.takeIf { it.isNotBlank() }?.let { Text(it, style = P.small) }
                    SmallButton("Reload guide") { App.repo.reloadGuide() }
                }
            }
            item { SectionLabel("Extra guides") }
            item {
                Card {
                    Text("XMLTV links, separated by spaces, loaded together with your playlist's own guide.", style = P.small)
                    Field("Guide links", urls, "http://example.com/epg.xml.gz") { urls = it }
                }
            }
            item {
                BigButton("Save and reload", Modifier.padding(horizontal = 16.dp)) {
                    App.store.extraEpg = urls.split(' ', ',', '\n').map { it.trim() }.filter { it.startsWith("http") }
                    App.repo.reloadGuide()
                    toast("Reloading the guide")
                }
            }
        }
    }
}

/** Configurations: playback, sports and the keys for online extras. */
@Composable
fun PhoneConfigurations(nav: Nav) {
    var v by remember { mutableIntStateOf(0) }
    val store = App.store
    Pushed(nav) {
        LazyColumn(Modifier.imePadding(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageTitle("Configurations") }
            item { SectionLabel("Playback") }
            item {
                Card {
                    Text("Xtream live format", style = P.head)
                    val hls = remember(v) { store.xtreamHls }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Choice("MPEG-TS (.ts)", !hls) { store.xtreamHls = false; v++ }
                        Choice("HLS (.m3u8)", hls) { store.xtreamHls = true; v++ }
                    }
                }
            }
            item {
                Card {
                    Text("Buffer before playing", style = P.head)
                    val buf = remember(v) { store.bufferMs }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (ms in listOf(2500, 6000, 15000, 30000)) Choice("${ms / 1000.0}s".replace(".0s", "s"), buf == ms) { store.bufferMs = ms; v++ }
                    }
                    Text("A longer buffer starts a little slower but rides out a weak connection.", style = P.small)
                }
            }
            item { SectionLabel("Sports") }
            item {
                Card {
                    Text("Your country", style = P.head)
                    Text("Its broadcasters are listed first on match pages.", style = P.small)
                    val country = remember(v) { store.sportsCountry }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(COUNTRIES) { c -> Choice(c.ifEmpty { "Any" }, country == c) { store.sportsCountry = c; v++ } }
                    }
                    val n = remember(v) { store.favouriteTeams.size }
                    if (n > 0) SmallButton("Clear favourite clubs ($n)", P.red) { store.favouriteTeams = emptySet(); v++ }
                }
            }
            item { SectionLabel("Online extras") }
            item { KeyCard("TMDB API key", "Ratings, cast, trailers and reviews on film and series pages (free at themoviedb.org).", store.tmdbKey,
                { store.tmdbKey = it }, { io.github.novatv.play.data.Tmdb.test() }) }
            item { KeyCard("TheSportsDB premium key", "TV channels for every sport, by country.", store.sportsDbKey,
                { store.sportsDbKey = it }, { io.github.novatv.play.sports.Broadcasters.testSportsDb() }) }
            item { KeyCard("SportMonks API token", "TV stations for football.", store.sportMonksKey,
                { store.sportMonksKey = it }, { io.github.novatv.play.sports.Broadcasters.testSportMonks() }) }
            item { SectionLabel("About") }
            item { Card { Text("NovaPlay 1.0 · a player for your own playlists. It does not provide any channels or content.", style = P.small) } }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    Box((if (on) Modifier.clip(RoundedCornerShape(50)).background(Color.White) else Modifier.glass(RoundedCornerShape(50)))
        .tap(onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(label, style = P.body.copy(fontSize = 15.sp, color = if (on) P.red else Color.White), maxLines = 1)
    }
}

/** A key the user types, saved and checked against its service. */
@Composable
private fun KeyCard(title: String, what: String, current: String, save: (String) -> Unit, test: () -> String?) {
    var key by remember { mutableStateOf(current) }
    var status by remember { mutableStateOf(if (current.isBlank()) "Not set" else "Saved") }
    val scope = rememberCoroutineScope()
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = P.head, modifier = Modifier.weight(1f))
            Text(status, style = P.small.copy(color = if (status == "Working") Color(0xFF30D158) else P.dim))
        }
        Text(what, style = P.small)
        Field("Key", key, "Paste or type your key") { key = it }
        SmallButton("Save and test") {
            save(key)
            status = "Checking…"
            scope.launch { status = withContext(IO) { if (key.isBlank()) "Not set" else test() } ?: "Working" }
        }
    }
}
