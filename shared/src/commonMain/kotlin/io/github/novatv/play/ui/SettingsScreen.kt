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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.Refresh
import io.github.novatv.play.data.Repo
import io.github.novatv.play.data.SourceType
import kotlinx.coroutines.launch
import io.github.novatv.play.platform.IO
import io.github.novatv.play.toast

/** Settings' sections: a list on the left, the open one's options on the right. */
private enum class Pane(val title: String, val about: String, val icon: ImageVector) {
    PLAYLISTS("Playlists & guide", "Your playlists and their TV guide", Icons.Rounded.Tv),
    LIVE("Live TV & guide", "Previews, the clock and the channel list", Icons.Rounded.LiveTv),
    VOD("Movies & series", "Episodes, subtitles and film details", Icons.Rounded.Movie),
    SPORTS("Sports", "Competitions, clubs and where to watch", Icons.Rounded.SportsSoccer),
    PLAYBACK("Playback", "Stream format, buffering and picture", Icons.Rounded.PlayCircle),
    STORAGE("Recording & storage", "Recordings, the image cache and search history", Icons.Rounded.Storage),
    LOOK("Appearance", "Hints and what Home shows", Icons.Rounded.Palette),
    TIPS("Remote tips", "Buttons worth knowing", Icons.Rounded.Lightbulb),
    ABOUT("About", "Version and where information comes from", Icons.Rounded.Info),
}

@Composable
fun SettingsScreen(nav: Nav) {
    val st by App.repo.state.collectAsState()
    var v by remember { mutableIntStateOf(0) }
    val changed: () -> Unit = { v++ }
    var pane by remember { mutableStateOf(Pane.PLAYLISTS) }
    val syncing by App.repo.syncing.collectAsState()
    Row(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 30.dp)) {
        Column(Modifier.width(320.dp).fillMaxHeight()) {
            Header("Settings")
            // The sections: moving onto one opens it, as the menu does with pages.
            LazyColumn(Modifier.weight(1f).rememberFocusInList(), contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(Pane.entries.toList()) { p ->
                    val open = p == pane
                    Tile({ pane = p }, Modifier.fillMaxWidth().height(52.dp).onFocusChanged { if (it.isFocused) pane = p },
                        shape = RoundedCornerShape(10.dp), scale = 1.0f, color = if (open) C.accent.copy(alpha = 0.14f) else Color.Transparent) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(p.icon, size = 22.dp, tint = if (open) C.accent else C.dim)
                            Spacer(Modifier.width(14.dp))
                            Text(p.title, style = T.body.copy(color = if (open) C.text else C.dim), maxLines = 1, modifier = Modifier.weight(1f))
                            if (p == Pane.PLAYLISTS) Text("${App.store.sources.size}", style = T.tiny, maxLines = 1)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(pane.title, style = T.h1)
            Text(pane.about, style = T.small, modifier = Modifier.padding(bottom = 14.dp))
            SourceStatus()
            key(pane) {
                LazyColumn(Modifier.fillMaxSize().rememberFocusInList(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Read here so a change re-reads every row's value; the rows (and focus on them) stay put.
                    if (v < 0) return@LazyColumn
                    when (pane) {
                        Pane.PLAYLISTS -> playlistsPane(nav, st, syncing, changed)
                        Pane.LIVE -> livePane(changed)
                        Pane.VOD -> vodPane(changed)
                        Pane.SPORTS -> sportsPane(nav, changed)
                        Pane.PLAYBACK -> playbackPane(changed)
                        Pane.STORAGE -> storagePane(changed)
                        Pane.LOOK -> lookPane(changed)
                        Pane.TIPS -> tipsPane()
                        Pane.ABOUT -> aboutPane(st)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.section(t: String) = item { SectionTitle(t, Modifier.padding(top = 10.dp)) }

private fun LazyListScope.playlistsPane(nav: Nav, st: Repo.LibState, syncing: String?, changed: () -> Unit) {
    section("Playlists")
    items(App.store.sources, key = { it.id }) { s ->
        val active = s.id == st.source?.id
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile({ if (!active) App.repo.open(s, force = false) }, Modifier.weight(1f).height(66.dp), shape = RoundedCornerShape(12.dp), scale = 1.02f,
                color = if (active) C.accent.copy(alpha = 0.2f) else C.panel) {
                Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = T.body)
                        Text("${s.type.label} · ${s.url.substringAfter("://").substringBefore('/').substringBefore('?')}", style = T.small, maxLines = 1)
                    }
                    if (active) Badge("IN USE", C.accent) else Text("OK to switch", style = T.tiny)
                }
            }
            if (active) Pill("Refresh", onClick = { App.repo.refresh() }, icon = Icons.Rounded.Refresh)
            Pill("Remove", onClick = { App.repo.removeSource(s.id); changed(); toast("Removed ${s.name}") }, icon = Icons.Rounded.Delete)
        }
    }
    item { Pill("Add a playlist", onClick = { nav.push(Route.AddSource) }, icon = Icons.Rounded.Add, primary = true) }
    statusSection(st, syncing)
    updatesSection(changed)
    section("TV guide")
    item { Text(remember(st.epgVersion) { App.repo.epg.summary() }, style = T.body) }
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
}

/** When each playlist and the guide last updated, with buttons to update them now. */
private fun LazyListScope.statusSection(st: Repo.LibState, syncing: String?) {
    val now = io.github.novatv.play.platform.nowMs()
    section("Status")
    item {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(syncing ?: st.epgStatus?.takeIf { it.startsWith("Loading") } ?: "Everything is up to date as of the times below.",
                style = T.small.copy(color = if (syncing != null) C.accent else C.dim), modifier = Modifier.weight(1f))
            Pill("Update everything", onClick = { if (syncing == null) App.repo.syncAllNow() }, icon = Icons.Rounded.Refresh)
        }
    }
    items(App.store.sources, key = { "status-" + it.id }) { src ->
        val err = App.store.syncError(src.id)
        StatusRow(src.name, if (err != null) "Last try failed: $err" else "Channels, films and series updated ${Refresh.ago(App.store.syncedAt(src.id), now)}",
            err != null) { if (syncing == null) App.repo.syncPlaylist(src) }
    }
    item(key = "status-guide") {
        StatusRow("TV guide", "Updated ${Refresh.ago(remember(st.epgVersion) { App.repo.guideUpdatedAt() }, now)}", false) {
            if (syncing == null) App.repo.reloadGuide() }
    }
    if (App.host.backgroundSync) item(key = "status-night") {
        StatusRow("Overnight update", App.store.overnightLast ?: if (App.store.overnightSync) "Not run yet" else "Off", false, button = null) {}
    }
}

@Composable
private fun StatusRow(name: String, line: String, failed: Boolean, button: String? = "Update", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel).padding(start = 18.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (failed) C.live else C.accent))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = T.body, maxLines = 1)
            Text(line, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (button != null) Pill(button, onClick = onClick, icon = Icons.Rounded.Refresh)
    }
}

/** How often playlists and the guide update, and the overnight update where the device can run one. */
private fun LazyListScope.updatesSection(changed: () -> Unit) {
    val s = App.store
    section("Updates")
    item { Choice("Update playlists", "How often channels, films and series are read again from your provider: when the app opens or comes back",
        Refresh.INTERVALS.map { "$it" to Refresh.intervalLabel(it) }, "${s.playlistRefreshHours}") { s.playlistRefreshHours = it.toInt(); changed() } }
    item { Choice("Update the TV guide", "How often the programme guide is downloaded again",
        Refresh.INTERVALS.map { "$it" to Refresh.intervalLabel(it) }, "${s.guideRefreshHours}") { s.guideRefreshHours = it.toInt(); changed() } }
    if (App.host.backgroundSync) {
        item { Toggle("Update overnight", "Playlists and the guide update once a night while the app is closed, so they're fresh in the morning",
            s.overnightSync) { s.overnightSync = it; App.host.scheduleOvernight(it, s.overnightHour); changed() } }
        item { Choice("Overnight time", "It starts within two hours of this time",
            (1..6).map { "$it" to io.github.novatv.play.platform.Time.hm(it, 0, io.github.novatv.play.platform.Time.use24h) }, "${s.overnightHour}") {
            s.overnightHour = it.toInt(); App.host.scheduleOvernight(s.overnightSync, s.overnightHour); changed() } }
        item { Action("Run the overnight update now", "Runs it in the background, as at night; Status shows the result", "Run") {
            App.host.runOvernightNow(); toast("Updating playlists and the TV guide") } }
    }
}

private fun LazyListScope.livePane(changed: () -> Unit) {
    val s = App.store
    if (App.host.previewEngine != null) {
        section("Previews")
        item { Toggle("Live TV and guide preview", "The highlighted channel plays muted beside the list after a short pause. OK opens it full screen.",
            s.livePreview) { s.livePreview = it; if (!it) App.preview.stop(); changed() } }
    }
    section("Clock")
    item { Toggle("24-hour clock", "Times as 20:05 instead of 8:05 PM, everywhere in the app", s.clock24) { s.clock24 = it; changed() } }
    item { Toggle("Clock in the player", "Keep the time in the top corner while a channel or film plays", s.playerClock) { s.playerClock = it; changed() } }
    section("Channel list")
    item { Toggle("Channel numbers", "In the Live TV list and the guide", s.showChannelNumbers) { s.showChannelNumbers = it; changed() } }
    item { Toggle("Channel logos", "In the Live TV list and the guide; off makes long lists lighter", s.showChannelLogos) { s.showChannelLogos = it; changed() } }
}

private fun LazyListScope.vodPane(changed: () -> Unit) {
    val s = App.store
    section("Episodes")
    item { Toggle("Play the next episode", "When an episode ends, the next one starts by itself", s.autoNextEpisode) { s.autoNextEpisode = it; changed() } }
    section("Subtitles")
    item { Choice("Subtitle size", "Text subtitles in films and series (picture-based ones keep their own look)",
        listOf("SMALL" to "Small", "MEDIUM" to "Medium", "LARGE" to "Large", "XLARGE" to "Extra large"), s.subtitleSize) { s.subtitleSize = it; changed() } }
    item { Choice("Subtitle background", "What sits behind the words",
        listOf("SHADOW" to "Shadow", "NONE" to "None", "BOX" to "Dark box", "SOLID" to "Black box"), s.subtitleBackground) { s.subtitleBackground = it; changed() } }
    item { Choice("Subtitle position", "How high on the screen subtitles sit",
        listOf("BOTTOM" to "Bottom", "RAISED" to "Raised", "HIGH" to "Higher"), s.subtitlePosition) { s.subtitlePosition = it; changed() } }
    section("Film and series details")
    item { ApiKey("TMDB API key", "Ratings, cast, trailers and reviews on film and series pages (free at themoviedb.org)", s.tmdbKey,
        { s.tmdbKey = it }, { io.github.novatv.play.data.Tmdb.test() }) }
}

private fun LazyListScope.sportsPane(nav: Nav, changed: () -> Unit) {
    val s = App.store
    section("What to follow")
    item { Action("Sports, competitions and clubs", "Choose what the Sports Hub shows", "Choose") { nav.push(Route.Leagues) } }
    item { Action("Favourite clubs", "Followed clubs get a star. OK clears them all.",
        if (s.favouriteTeams.isEmpty()) "None" else "${s.favouriteTeams.size} · Clear") { s.favouriteTeams = emptySet(); changed() } }
    item {
        Column {
            Text("Your country: its broadcasters are listed first", style = T.small, modifier = Modifier.padding(bottom = 6.dp))
            LazyRow(Modifier.rememberFocusInList(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(COUNTRIES) { c -> Pill(c.ifEmpty { "Any" }, onClick = { s.sportsCountry = c; changed() }, selected = s.sportsCountry == c) }
            }
        }
    }
    section("Matches")
    item { Toggle("Hide scores", "Spoiler-safe: match cards show no score; open a match to see it", s.hideScores) { s.hideScores = it; changed() } }
    section("Where to watch")
    item { ApiKey("TheSportsDB premium key", "TV channels for every sport, by country", s.sportsDbKey,
        { s.sportsDbKey = it }, { io.github.novatv.play.sports.Broadcasters.testSportsDb() }) }
    item { ApiKey("SportMonks API token", "TV stations for football", s.sportMonksKey,
        { s.sportMonksKey = it }, { io.github.novatv.play.sports.Broadcasters.testSportMonks() }) }
    item { Text("Scores and fixtures come from ESPN. Each match lists the channels showing it from TheSportsDB, SportMonks and ESPN, " +
        "and NovaPlay finds them in your playlist through your TV guide and channel names.", style = T.small) }
}

private fun LazyListScope.playbackPane(changed: () -> Unit) {
    val s = App.store
    section("Streams")
    item { Choice("Xtream live format", "Some servers play more smoothly one way; applies to the next channel",
        listOf("TS" to "MPEG-TS (.ts)", "HLS" to "HLS (.m3u8)"), if (s.xtreamHls) "HLS" else "TS") { s.xtreamHls = it == "HLS"; changed() } }
    item { Choice("Buffer before playing", "A longer buffer starts a little slower but rides out a weak connection",
        listOf(2500, 6000, 15000, 30000).map { "$it" to "${it / 1000.0}s".replace(".0s", "s") }, "${s.bufferMs}") { s.bufferMs = it.toInt(); changed() } }
    section("Picture")
    item { Choice("Picture shape", "How the picture first fills the screen; the player's Aspect button still changes it",
        listOf("FIT" to "Fit", "FILL" to "Stretch", "ZOOM" to "Zoom"), s.defaultAspect) { s.defaultAspect = it; changed() } }
}

private fun LazyListScope.storagePane(changed: () -> Unit) {
    val s = App.store
    if (App.host.recorder != null) {
        section("Recording")
        item { Choice("Start early", "Minutes before the programme's listed start",
            listOf(0, 1, 2, 5, 10, 15).map { "$it" to "$it min" }, "${s.recordBeforeMin}") { s.recordBeforeMin = it.toInt(); changed() } }
        item { Choice("Run late", "Minutes after the programme's listed end",
            listOf(0, 2, 5, 10, 15, 30).map { "$it" to "$it min" }, "${s.recordAfterMin}") { s.recordAfterMin = it.toInt(); changed() } }
    }
    section("Storage")
    item { ImageCacheRow() }
    item { Action("Clear search history", "Recent searches on this device",
        if (s.searchHistory.isEmpty()) "Empty" else "${s.searchHistory.size} · Clear") { s.searchHistory = emptyList(); changed() } }
    item { Toggle("Remember searches", "Keep recent searches to pick again", !s.searchHistoryOff) {
        s.searchHistoryOff = !it; if (!it) s.searchHistory = emptyList(); changed() } }
}

private fun LazyListScope.lookPane(changed: () -> Unit) {
    val s = App.store
    section("Pages")
    item { Toggle("Button hints", "The line of remote hints under Live TV and in the player", s.showHints) { s.showHints = it; changed() } }
    item { Toggle("Hide adult categories on Home", "Keeps them out of Home's rows; Movies and Series still list them", s.hideAdultOnHome) {
        s.hideAdultOnHome = it; changed() } }
    if (Look.modern) {
        section("Theme")
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(io.github.novatv.play.ui.phone.P.themes) { t ->
                    Pill(t.name, onClick = { io.github.novatv.play.ui.phone.P.theme = t; App.store.phoneTheme = t.id; changed() },
                        selected = io.github.novatv.play.ui.phone.P.theme.id == t.id)
                }
            }
        }
    }
}

/** NovaPlay's own remote gestures, page by page. */
internal val TIPS = listOf(
    "Everywhere" to listOf(
        "UP from a page's top row" to "Goes to the menu; DOWN comes straight back to where you were.",
        "BACK on a page" to "Goes to the menu. BACK there opens Home, and BACK on Home's menu leaves the app.",
        "Coming back to a page" to "Lands on the item you last used, with its lists where they were.",
    ),
    "Live TV" to listOf(
        "Hold OK on a channel" to "Adds it to Favourites, or takes it off.",
        "Rest on a channel" to "Plays a muted preview beside the list (Android TV).",
    ),
    "TV guide" to listOf(
        "FF / RW" to "Moves the grid three hours later or earlier.",
        "LEFT / RIGHT at the grid's edge" to "Moves the grid half an hour.",
        "Hold OK on a programme" to "Records it, where recording is available.",
    ),
    "Movies & series" to listOf(
        "Hold OK on a poster" to "Adds it to My list.",
    ),
    "Live player" to listOf(
        "UP / DOWN or CH+ / CH-" to "Changes channel.",
        "LEFT" to "Opens the channel list without leaving the player.",
        "OK" to "Shows the toolbar: favourite, audio and subtitles, picture shape, channels and two at once.",
        "MENU or BACK" to "Hides the details, then leaves the player.",
    ),
    "Film and episode player" to listOf(
        "OK" to "Plays or pauses.",
        "LEFT / RIGHT" to "Seeks 10 seconds; hold to go faster.",
        "UP" to "Audio and subtitles.",
        "DOWN" to "The toolbar.",
    ),
    "Recordings" to listOf(
        "Hold OK on a recording" to "Stops or deletes it.",
    ),
)

private fun LazyListScope.tipsPane() {
    for ((page, tips) in TIPS) {
        section(page)
        items(tips) { (keys, what) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.panel).padding(horizontal = 18.dp, vertical = 12.dp)) {
                Text(keys, style = T.body.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(320.dp))
                Text(what, style = T.small.copy(color = C.text), modifier = Modifier.weight(1f))
            }
        }
    }
}

private fun LazyListScope.aboutPane(st: Repo.LibState) {
    section("NovaPlay")
    item { Action("Version", "A player for your own playlists. It does not provide any channels or content.", "1.1") {} }
    item { Action("Playlist in use", st.source?.name ?: "None",
        "${st.lib.channels.size} channels · ${st.lib.movies.size} films · ${st.lib.series.size} series") {} }
    section("Where information comes from")
    item { Text("Scores and fixtures: ESPN. Broadcasters: TheSportsDB and SportMonks, with your keys. Film and series details: TMDB, with your key. " +
        "Programme guides: your playlist's own, plus any XMLTV links you add.", style = T.small) }
}

/** One setting: its name and what it does on the left, its value on the right. */
@Composable
private fun SettingRow(title: String, desc: String?, value: String?, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Tile(onClick, Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(10.dp), scale = 1.0f) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = T.body)
                desc?.let { Text(it, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Spacer(Modifier.width(16.dp))
            if (trailing != null) trailing() else value?.let { Text(it, style = T.body.copy(color = C.accent), maxLines = 1) }
        }
    }
}

/** On or off; OK flips it. */
@Composable
private fun Toggle(title: String, desc: String?, on: Boolean, set: (Boolean) -> Unit) =
    SettingRow(title, desc, null, { set(!on) }) {
        Box(Modifier.width(52.dp).height(28.dp).clip(RoundedCornerShape(14.dp)).background(if (on) C.accent else C.line),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.padding(3.dp).size(22.dp).clip(CircleShape).background(Color.White))
        }
    }

/** One of a few values; OK moves to the next. */
@Composable
private fun Choice(title: String, desc: String?, options: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
    val i = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
    SettingRow(title, desc, "‹  ${options[i].second}  ›", { set(nextChoice(options.map { it.first }, current)) })
}

/** The value after [current] in [values], wrapping round; the first if [current] isn't one of them. */
internal fun nextChoice(values: List<String>, current: String): String {
    val i = values.indexOf(current)
    return values[if (i < 0) 0 else (i + 1) % values.size]
}

@Composable
private fun Action(title: String, desc: String?, value: String?, onClick: () -> Unit) = SettingRow(title, desc, value, onClick)

/** The posters and logos kept on disk, and a way to clear them. */
@Composable
private fun ImageCacheRow() {
    val ctx = coil3.compose.LocalPlatformContext.current
    val loader = remember(ctx) { coil3.SingletonImageLoader.get(ctx) }
    var bytes by remember { mutableStateOf(loader.diskCache?.size ?: 0L) }
    Action("Clear image cache", "Posters and logos download again as you browse", "${bytes / (1024 * 1024)} MB · Clear") {
        loader.memoryCache?.clear(); loader.diskCache?.clear(); bytes = loader.diskCache?.size ?: 0L
        toast("Image cache cleared")
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
internal val COUNTRIES = listOf("", "United Kingdom", "Ireland", "United States", "Canada", "Pakistan", "India", "Bangladesh",
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
