package io.github.novatv.play.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.SourceType
import kotlinx.coroutines.launch
import io.github.novatv.play.platform.IO
import io.github.novatv.play.toast

@Composable
fun SettingsScreen(nav: Nav) {
    val st by App.repo.state.collectAsState()
    var v by remember { mutableIntStateOf(0) }
    val sources = remember(v, st.source) { App.store.sources }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 30.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column { Header("Settings"); SourceStatus() } }
        item { SectionTitle("Playlists", trailing = "OK switches to a playlist") }
        items(sources, key = { it.id }) { s ->
            val active = s.id == st.source?.id
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile({ if (!active) App.repo.open(s, force = false) }, Modifier.weight(1f).height(66.dp), shape = RoundedCornerShape(12.dp), scale = 1.02f,
                    color = if (active) C.accent.copy(alpha = 0.2f) else C.panel) {
                    Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = T.body)
                            Text("${s.type.label} · ${s.url.substringAfter("://").substringBefore('/').substringBefore('?')}", style = T.small, maxLines = 1)
                        }
                        if (active) Badge("IN USE", C.accent)
                    }
                }
                if (active) Pill("Refresh", onClick = { App.repo.refresh() }, icon = Icons.Rounded.Refresh)
                Pill("Remove", onClick = {
                    App.repo.removeSource(s.id); v++
                    toast("Removed ${s.name}")
                }, icon = Icons.Rounded.Delete)
            }
        }
        item { Pill("Add a playlist", onClick = { nav.push(Route.AddSource) }, icon = Icons.Rounded.Add, primary = true) }

        item { SectionTitle("TV guide", Modifier.padding(top = 16.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(remember(st.epgVersion) { App.repo.epg.summary() }, style = T.body, modifier = Modifier.weight(1f))
                Pill("Reload guide", onClick = { App.repo.reloadGuide() }, icon = Icons.Rounded.Refresh)
            }
        }
        item {
            Column {
                Text("Extra TV guides (XMLTV links, separated by spaces), loaded together with your playlist's own guide", style = T.small,
                    modifier = Modifier.padding(bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    var url by remember { mutableStateOf(App.store.extraEpg.joinToString(" ")) }
                    InputField(url, "http://example.com/epg.xml.gz", { url = it }, Modifier.weight(1f))
                    Pill("Save and reload", onClick = { App.store.extraEpg = url.split(' ', ',').map { it.trim() }.filter { it.startsWith("http") }; App.repo.reloadGuide() })
                }
            }
        }
        item { SectionTitle("Playback", Modifier.padding(top = 16.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Xtream live format", style = T.body, modifier = Modifier.width(260.dp))
                Pill("MPEG-TS (.ts)", onClick = { App.store.xtreamHls = false; v++ }, selected = !App.store.xtreamHls)
                Pill("HLS (.m3u8)", onClick = { App.store.xtreamHls = true; v++ }, selected = App.store.xtreamHls)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buffer before playing", style = T.body, modifier = Modifier.width(260.dp))
                for (ms in listOf(2500, 6000, 15000, 30000)) Pill("${ms / 1000.0}s".replace(".0s", "s"), onClick = { App.store.bufferMs = ms; v++ }, selected = App.store.bufferMs == ms)
            }
        }
        item { Text("A longer buffer starts a little slower but rides out a weak connection.", style = T.small) }

        item { SectionTitle("Sports", Modifier.padding(top = 16.dp)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Choose sports, competitions and clubs", onClick = { nav.push(Route.Leagues) })
                Pill("Clear favourite clubs (${App.store.favouriteTeams.size})", onClick = { App.store.favouriteTeams = emptySet(); v++ })
            }
        }
        item {
            Column {
                Text("Your country: its broadcasters are listed first", style = T.small, modifier = Modifier.padding(bottom = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(COUNTRIES) { c ->
                        Pill(c.ifEmpty { "Any" }, onClick = { App.store.sportsCountry = c; v++ }, selected = remember(v) { App.store.sportsCountry == c })
                    }
                }
            }
        }
        item { ApiKey("TheSportsDB premium key", "TV channels for every sport, by country", App.store.sportsDbKey,
            { App.store.sportsDbKey = it }, { io.github.novatv.play.sports.Broadcasters.testSportsDb() }) }
        item { ApiKey("SportMonks API token", "TV stations for football", App.store.sportMonksKey,
            { App.store.sportMonksKey = it }, { io.github.novatv.play.sports.Broadcasters.testSportMonks() }) }
        item { ApiKey("TMDB API key", "Ratings, cast, trailers and reviews on film and series pages (free at themoviedb.org)", App.store.tmdbKey,
            { App.store.tmdbKey = it }, { io.github.novatv.play.data.Tmdb.test() }) }
        item { Text("Scores and fixtures come from ESPN. Each match lists the channels showing it from TheSportsDB, SportMonks and ESPN, " +
            "and NovaPlay finds them in your playlist through your TV guide and channel names.", style = T.small) }

        item { SectionTitle("About", Modifier.padding(top = 16.dp)) }
        item { Text("NovaPlay 1.0 · a player for your own playlists. It does not provide any channels or content.", style = T.small) }
    }
}

/** Add a playlist: Xtream Codes login, M3U link or Stalker portal. Fields are typed with the Fire TV keyboard. */
@Composable
fun AddSourceScreen(nav: Nav) {
    var type by remember { mutableStateOf(SourceType.XTREAM) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("00:1A:79:") }
    var epg by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 120.dp, end = 120.dp, top = 40.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Header("Add a playlist", "Use the details your IPTV provider gave you. Press OK on a box to type; you can also speak or type from your phone.") }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(SourceType.entries.toList()) { t ->
                    Pill(t.label, onClick = { type = t }, selected = t == type, modifier = if (t == SourceType.XTREAM) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        item { Column { Label("Name (optional)"); InputField(name, "My TV", { name = it }, Modifier.fillMaxWidth()) } }
        item { Column {
            Label(when (type) { SourceType.XTREAM -> "Server URL"; SourceType.M3U -> "Playlist URL"; SourceType.STALKER -> "Portal URL" })
            InputField(url, when (type) {
                SourceType.XTREAM -> "http://example.com:8080"; SourceType.M3U -> "http://example.com/playlist.m3u"; SourceType.STALKER -> "http://example.com:8080/c/"
            }, { url = it }, Modifier.fillMaxWidth())
        } }
        if (type == SourceType.XTREAM) {
            item { Column { Label("Username"); InputField(user, "Username", { user = it }, Modifier.fillMaxWidth()) } }
            item { Column { Label("Password"); InputField(pass, "Password", { pass = it }, Modifier.fillMaxWidth()) } }
        }
        if (type == SourceType.STALKER) item { Column { Label("MAC address"); InputField(mac, "00:1A:79:XX:XX:XX", { mac = it }, Modifier.fillMaxWidth()) } }
        item { Column {
            Label(if (type == SourceType.XTREAM) "TV guide URL (optional, the server's own guide is used by default)" else "TV guide (XMLTV) URL (optional)")
            InputField(epg, "http://example.com/epg.xml.gz", { epg = it }, Modifier.fillMaxWidth())
        } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                Pill("Save and load", primary = true, modifier = Modifier.width(520.dp), onClick = {
                    val missing = when {
                        url.isBlank() -> "the URL"
                        type == SourceType.XTREAM && (user.isBlank() || pass.isBlank()) -> "the username and password"
                        type == SourceType.STALKER && mac.length < 17 -> "the full MAC address"
                        else -> null
                    }
                    if (missing != null) toast("Please enter $missing")
                    else { App.repo.addSource(name, type, url, user, pass, mac, epg); nav.stack.clear() }
                })
                Pill("Cancel", onClick = { nav.pop() })
            }
        }
    }
    AutoFocus(first)
}

@Composable
private fun Label(t: String) = Text(t, style = T.small, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))

/** Countries as TheSportsDB names them; "" is any. */
private val COUNTRIES = listOf("", "United Kingdom", "Ireland", "United States", "Canada", "Pakistan", "India", "Bangladesh",
    "United Arab Emirates", "Saudi Arabia", "Qatar", "Australia", "New Zealand", "South Africa", "Germany", "France", "Spain",
    "Italy", "Netherlands", "Portugal", "Turkey", "Brazil", "Mexico")

/** A key typed by the user, saved and checked against its service. */
@Composable
private fun ApiKey(title: String, what: String, current: String, save: (String) -> Unit, test: () -> String?) {
    var key by remember { mutableStateOf(current) }
    var status by remember { mutableStateOf(if (current.isBlank()) "Not set" else "Saved") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = T.body)
            Spacer(Modifier.width(10.dp))
            Text("$what · $status", style = T.small)
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            InputField(key, "Paste or type your key", { key = it }, Modifier.weight(1f), password = true)
            Pill("Save and test", onClick = {
                save(key)
                status = "Checking…"
                scope.launch {
                    val r = kotlinx.coroutines.withContext(IO) { if (key.isBlank()) "Not set" else test() }
                    status = r ?: "Working"
                }
            })
        }
    }
}
