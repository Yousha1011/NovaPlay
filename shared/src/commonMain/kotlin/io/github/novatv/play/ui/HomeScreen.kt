package io.github.novatv.play.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import io.github.novatv.play.Tab
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.play
import io.github.novatv.play.sports.State
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.Time
import io.github.novatv.play.res.logo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.data.Details
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.Series
import io.github.novatv.play.data.Store
import kotlinx.coroutines.withContext

/** What the home hero shows: the last thing watched, or else the newest film or series in the playlist. */
private class Hero(val label: String, val movie: Movie?, val series: Series?, val resume: Store.Resume?)

@Composable
fun HomeScreen(nav: Nav) {
    val st by App.repo.state.collectAsState()
    val sports by App.repo.sports.collectAsState()
    LaunchedEffect(Unit) { App.repo.refreshSports(5 * 60_000L) }
    val lib = st.lib
    val store = App.store
    // Looked up by id in the library's index: no work per channel.
    val prefix = App.repo.channelKeyPrefix()
    val favChannels = remember(st.index, store.favourites) { st.index?.channelsFor(store.favourites.filter { it.startsWith(prefix) })?.sortedBy { it.number }.orEmpty() }
    val rev = History.rev
    val recent = remember(st.index, store.recentChannels, rev) { st.index?.channelsFor(store.recentChannels.filter { it.startsWith(prefix) }).orEmpty() }
    val liveSports = remember(sports) { sports.events.filter { it.state == State.LIVE && io.github.novatv.play.sports.Follow.shows(it) }.take(15) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val resume = remember(lib, rev) { store.continueWatching }
    val newMovies = remember(st.index) { st.index?.moviesByAdded?.take(20).orEmpty() }
    val newSeries = remember(st.index) { st.index?.seriesByAdded?.take(20).orEmpty() }
    val hero = remember(lib, resume, newMovies, newSeries) {
        resume.firstNotNullOfOrNull { r ->
            lib.movies.firstOrNull { App.repo.movieKey(it) == r.key }?.let { Hero("Continue watching", it, null, r) }
                ?: lib.series.firstOrNull { r.key.startsWith(App.repo.seriesKey(it).replaceFirst("se:", "ep:") + ":") }?.let { Hero("Continue watching", null, it, r) }
        } ?: newMovies.firstOrNull()?.let { Hero("New movie", it, null, null) }
            ?: newSeries.firstOrNull()?.let { Hero("New series", null, it, null) }
    }
    val nothing = hero == null && favChannels.isEmpty() && recent.isEmpty() && liveSports.isEmpty() && lib.channels.isEmpty()

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // The hero takes about half the screen whatever its size, within limits that keep small and huge TVs readable.
        val heroHeight = (maxHeight * 0.56f).coerceIn(300.dp, 520.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(S.rowGap)) {
            item(key = "status") { Box(Modifier.padding(start = S.gutter, end = S.gutter, top = if (hero == null) 34.dp else 0.dp)) { SourceStatus() } }
            if (hero != null) item(key = "hero") { HeroBanner(hero, heroHeight, nav) }
            else if (!nothing) item(key = "header") {
                Header(greeting(), listOfNotNull(st.source?.name, "${lib.channels.size} channels").joinToString("  ·  "), Modifier.padding(horizontal = S.gutter))
            }
            if (favChannels.isNotEmpty()) item(key = "fav") { ChannelRow("On now", "Favourites", favChannels) }
            if (recent.isNotEmpty()) item(key = "recent") { RecentRow(recent) }
            if (resume.isNotEmpty()) item(key = "continue") {
                var all by remember { mutableStateOf(false) }
                var gone by remember { mutableStateOf(setOf<String>()) }
                var ask by remember { mutableStateOf<Store.Resume?>(null) }
                ask?.let { r -> RemoveDialog(r.title, "Continue watching", onRemove = { gone = gone + r.key }, onDismiss = { ask = null }) }
                Column {
                    ClearableTitle("Continue watching") { all = true }
                    LazyRow(Modifier.dust(all) { store.clearContinue(); History.rev++ }, contentPadding = PaddingValues(horizontal = S.gutter),
                        horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
                        items(resume, key = { it.key }) { r ->
                            PosterCard(r.title, r.poster, timeLeft(r.dur - r.pos),
                                modifier = Modifier.width(150.dp).dust(r.key in gone) { store.removeContinue(r.key); History.rev++ },
                                onLongClick = { ask = r },
                                progress = if (r.dur > 0) r.pos.toFloat() / r.dur else null, onClick = { open(r, nav) })
                        }
                    }
                }
            }
            if (liveSports.isNotEmpty()) item(key = "sports") {
                Column {
                    SectionTitle("Live sports", Modifier.padding(horizontal = S.gutter), trailing = "${liveSports.size} on now")
                    LazyRow(contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
                        items(liveSports, key = { it.id }) { e ->
                            EventCard(e, onClick = {
                                // Broadcaster lookups go to the network: off the main thread, then play the first channel found.
                                scope.launch {
                                    val a = kotlinx.coroutines.withContext(IO) {
                                        App.repo.matcher().airings(e, io.github.novatv.play.sports.Broadcasters.forEvent(e))
                                    }
                                    if (a.isNotEmpty()) playChannel(a.map { it.channel }, 0) else nav.tab = Tab.SPORTS
                                }
                            })
                        }
                    }
                }
            }
            if (newMovies.isNotEmpty()) item(key = "movies") {
                PosterRow("New movies", newMovies, { it.id }) { m ->
                    PosterCard(m.name, m.poster, listOfNotNull(m.year, m.rating?.let { "★ $it" }).joinToString("  ·  "), modifier = Modifier.width(150.dp),
                        onClick = { nav.push(Route.MovieDetail(m)) })
                }
            }
            if (newSeries.isNotEmpty()) item(key = "series") {
                PosterRow("New series", newSeries, { it.id }) { se ->
                    PosterCard(se.name, se.poster, se.year, modifier = Modifier.width(150.dp), onClick = { nav.push(Route.SeriesDetail(se)) })
                }
            }
            if (favChannels.isEmpty() && recent.isEmpty() && lib.channels.isNotEmpty()) item(key = "tip") {
                Text("Tip: hold OK on a channel to add it to your favourites. They show up here with what's on now.", style = T.small,
                    modifier = Modifier.padding(horizontal = S.gutter))
            }
        }
        // Nothing loaded yet, or a playlist with nothing in it: say so instead of an empty page.
        if (nothing) Message(if (st.status.isNotBlank()) "Loading your playlist" else "Nothing here yet",
            if (st.status.isNotBlank()) st.status else st.error ?: "Your playlist has no channels, movies or series.")
    }
}

/** The big banner at the top: artwork across the right, the title, details and buttons on the left. */
@Composable
private fun HeroBanner(h: Hero, height: androidx.compose.ui.unit.Dp, nav: Nav) {
    // The page's details (backdrop, plot, genre, length) load off the main thread; the playlist's own facts show meanwhile.
    val details by produceState(Details(), h.movie, h.series) {
        value = withContext(IO) {
            val p = App.repo.provider ?: return@withContext Details()
            try { if (h.movie != null) p.movieDetails(h.movie) else p.seriesDetails(h.series!!) } catch (e: Exception) { Details() }
        }
    }
    val title = h.movie?.name ?: h.series?.name ?: ""
    val art = details.backdrop ?: h.movie?.poster ?: h.series?.poster
    val genre = details.genre?.split(',', '/')?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    Box(Modifier.fillMaxWidth().height(height)) {
        if (!art.isNullOrBlank()) AsyncImage(art, null, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.72f).fillMaxHeight(),
            contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        // Fades the artwork into the page on the left and at the bottom, so the text stays readable on any picture.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to C.bg, 0.30f to C.bg, 0.62f to C.bg.copy(alpha = 0.55f), 1f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to C.bg)))
        Column(Modifier.align(Alignment.BottomStart).padding(start = S.gutter, bottom = 10.dp).fillMaxWidth(0.55f)) {
            Text(listOfNotNull(h.label, genre).joinToString("  ·  ").uppercase(), style = T.eyebrow)
            Spacer(Modifier.height(8.dp))
            Text(title, style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Text(listOfNotNull(details.released?.take(4) ?: h.movie?.year ?: h.series?.year, if (h.movie != null) "Movie" else "Series", details.duration,
                (details.rating ?: h.movie?.rating ?: h.series?.rating)?.takeIf { it.isNotBlank() && it != "0" }?.let { "★ $it" }).joinToString("  ·  "),
                style = T.small, maxLines = 1)
            val plot = details.plot ?: h.series?.plot
            if (!plot.isNullOrBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(plot, style = T.body.copy(color = C.dim, lineHeight = 22.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val r = h.resume
            if (r != null && r.dur > 0) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar(r.pos.toFloat() / r.dur, Modifier.width(220.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(timeLeft(r.dur - r.pos), style = T.tiny.copy(color = C.dim))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill(if (r != null) "Resume" else "Play", primary = true, icon = Icons.Rounded.PlayArrow, onClick = {
                    when {
                        r != null -> open(r, nav)
                        h.movie != null -> play(PlayItem.Vod(App.repo.movieKey(h.movie), h.movie.name, movie = h.movie))
                        else -> nav.push(Route.SeriesDetail(h.series!!))
                    }
                })
                Pill("More info", icon = Icons.Rounded.Info, onClick = {
                    if (h.movie != null) nav.push(Route.MovieDetail(h.movie)) else nav.push(Route.SeriesDetail(h.series!!))
                })
            }
        }
    }
}

/** Opens a Continue watching item: films play from where they stopped, series open on their page. */
private fun open(r: Store.Resume, nav: Nav) {
    val lib = App.repo.state.value.lib
    val movie = lib.movies.firstOrNull { App.repo.movieKey(it) == r.key }
    val series = lib.series.firstOrNull { r.key.startsWith(App.repo.seriesKey(it).replaceFirst("se:", "ep:") + ":") }
    when {
        movie != null -> play(PlayItem.Vod(r.key, movie.name, movie = movie))
        series != null -> nav.push(Route.SeriesDetail(series))
    }
}

/** "1h 25m left", "12m left". */
private fun timeLeft(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(1)
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else "${m}m left"
}

private fun greeting(): String {
    val h = Time.hourOfDay()
    return when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }
}

/** A titled row of posters that scrolls edge to edge, its first poster lined up with the page's text. */
@Composable
private fun <T> PosterRow(title: String, list: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    Column {
        SectionTitle(title, Modifier.padding(horizontal = S.gutter))
        LazyRow(contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
            items(list, key = key) { card(it) }
        }
    }
}

/** A section title with a Clear all button beside it. */
@Composable
private fun ClearableTitle(text: String, onClear: () -> Unit) {
    Row(Modifier.padding(horizontal = S.gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle(text)
        Pill("Clear all", onClick = onClear, modifier = Modifier.padding(bottom = 10.dp))
    }
}

/** Recently watched channels: hold OK on one to remove it, or clear them all. */
@Composable
private fun RecentRow(list: List<io.github.novatv.play.data.Channel>) {
    var all by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(setOf<String>()) }
    var ask by remember { mutableStateOf<io.github.novatv.play.data.Channel?>(null) }
    ask?.let { c -> RemoveDialog(c.name, "Recently watched", onRemove = { gone = gone + c.id }, onDismiss = { ask = null }) }
    Column {
        ClearableTitle("Recent channels") { all = true }
        LazyRow(Modifier.dust(all) { App.store.clearRecentChannels(); History.rev++ }, contentPadding = PaddingValues(horizontal = S.gutter),
            horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
            items(list.size, key = { list[it].id }) { i ->
                val c = list[i]
                ChannelCard(c, onClick = { playChannel(list, i) }, onLongClick = { ask = c },
                    modifier = Modifier.dust(c.id in gone) { App.store.removeRecentChannel(App.repo.channelKey(c)); History.rev++ })
            }
        }
    }
}

@Composable
private fun ChannelRow(title: String, note: String?, list: List<io.github.novatv.play.data.Channel>, modifier: Modifier = Modifier) {
    Column {
        SectionTitle(title, Modifier.padding(horizontal = S.gutter), trailing = note)
        LazyRow(modifier, contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
            items(list.size, key = { list[it].id }) { i -> ChannelCard(list[i], onClick = { playChannel(list, i) }) }
        }
    }
}

@Composable
fun WelcomeScreen(nav: Nav) {
    val r = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(720.dp)) {
            androidx.compose.foundation.Image(org.jetbrains.compose.resources.painterResource(io.github.novatv.play.res.Res.drawable.logo), "NovaPlay",
                Modifier.size(300.dp))
            Spacer(Modifier.height(10.dp))
            Text("Live TV, a full TV guide, movies, series and a Sports centre for the playlists you already have.", style = T.body.copy(color = C.dim))
            Spacer(Modifier.height(8.dp))
            Text("NovaPlay is a player: it does not come with any channels. Add your Xtream Codes login, M3U playlist or Stalker portal.",
                style = T.small)
            Spacer(Modifier.height(30.dp))
            Pill("Add a playlist", onClick = { nav.push(Route.AddSource) }, icon = Icons.Rounded.Add, primary = true, modifier = Modifier.focusRequester(r))
            Spacer(Modifier.fillMaxWidth().height(1.dp))
        }
    }
    AutoFocus(r)
    Row {}
}
