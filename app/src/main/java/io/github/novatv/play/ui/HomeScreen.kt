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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.Tab
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.play
import io.github.novatv.play.sports.State
import java.util.Calendar
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.size

@Composable
fun HomeScreen(nav: Nav) {
    val ctx = LocalContext.current
    val st by App.repo.state.collectAsStateWithLifecycle()
    val sports by App.repo.sports.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { App.repo.refreshSports(5 * 60_000L) }
    val lib = st.lib
    val store = App.store
    // Looked up by id in the library's index: no work per channel.
    val prefix = App.repo.channelKeyPrefix()
    val favChannels = remember(st.index, store.favourites) { st.index?.channelsFor(store.favourites.filter { it.startsWith(prefix) })?.sortedBy { it.number }.orEmpty() }
    val recent = remember(st.index, store.recentChannels) { st.index?.channelsFor(store.recentChannels.filter { it.startsWith(prefix) }).orEmpty() }
    val liveSports = remember(sports) { sports.events.filter { it.state == State.LIVE && io.github.novatv.play.sports.Follow.shows(it) }.take(15) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val resume = remember(lib) { store.continueWatching }
    val newMovies = remember(st.index) { st.index?.moviesByAdded?.take(20).orEmpty() }
    val newSeries = remember(st.index) { st.index?.seriesByAdded?.take(20).orEmpty() }
    val first = remember { FocusRequester() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 34.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(30.dp)) {
        item { Column {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            Header(when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" },
                listOfNotNull(st.source?.name, "${lib.channels.size} channels", "${lib.movies.size} movies", "${lib.series.size} series").joinToString("  ·  "))
            SourceStatus() }
        }
        if (liveSports.isNotEmpty()) item {
            Column {
                SectionTitle("Live sports", trailing = "${liveSports.size} on now")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(liveSports, key = { it.id }) { e ->
                        EventCard(e, onClick = {
                            // Broadcaster lookups go to the network: off the main thread, then play the first channel found.
                            scope.launch {
                                val a = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    App.repo.matcher().airings(e, io.github.novatv.play.sports.Broadcasters.forEvent(e))
                                }
                                if (a.isNotEmpty()) playChannel(ctx, a.map { it.channel }, 0) else nav.tab = Tab.SPORTS
                            }
                        })
                    }
                }
            }
        }
        if (resume.isNotEmpty()) item {
            Column {
                SectionTitle("Continue watching")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(resume, key = { it.key }) { r ->
                        PosterCard(r.title, r.poster, "${duration(r.dur - r.pos)} left", modifier = Modifier.width(150.dp),
                            progress = if (r.dur > 0) r.pos.toFloat() / r.dur else null, onClick = {
                                val movie = lib.movies.firstOrNull { App.repo.movieKey(it) == r.key }
                                val series = lib.series.firstOrNull { r.key.startsWith(App.repo.seriesKey(it).replaceFirst("se:", "ep:") + ":") }
                                when {
                                    movie != null -> play(ctx, PlayItem.Vod(r.key, movie.name, movie = movie))
                                    series != null -> nav.push(Route.SeriesDetail(series))
                                }
                            })
                    }
                }
            }
        }
        if (favChannels.isNotEmpty()) item { ChannelRow("Favourite channels", favChannels, Modifier.focusRequester(first)) }
        if (recent.isNotEmpty()) item { ChannelRow("Recently watched", recent) }
        if (newMovies.isNotEmpty()) item {
            Column {
                SectionTitle("New movies")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(newMovies, key = { it.id }) { m ->
                        PosterCard(m.name, m.poster, listOfNotNull(m.year, m.rating?.let { "★ $it" }).joinToString("  "), modifier = Modifier.width(150.dp),
                            onClick = { nav.push(Route.MovieDetail(m)) })
                    }
                }
            }
        }
        if (newSeries.isNotEmpty()) item {
            Column {
                SectionTitle("New series")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(newSeries, key = { it.id }) { s ->
                        PosterCard(s.name, s.poster, s.year, modifier = Modifier.width(150.dp), onClick = { nav.push(Route.SeriesDetail(s)) })
                    }
                }
            }
        }
        if (favChannels.isEmpty() && recent.isEmpty() && lib.channels.isNotEmpty()) item {
            Text("Tip: hold OK on a channel to add it to your favourites. They show up here.", style = T.small)
        }
    }
}

@Composable
private fun ChannelRow(title: String, list: List<io.github.novatv.play.data.Channel>, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column {
        SectionTitle(title)
        LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list.size, key = { list[it].id }) { i -> ChannelCard(list[i], onClick = { playChannel(ctx, list, i) }) }
        }
    }
}

@Composable
fun WelcomeScreen(nav: Nav) {
    val r = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(720.dp)) {
            androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(io.github.novatv.play.R.drawable.logo), "NovaPlay",
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
