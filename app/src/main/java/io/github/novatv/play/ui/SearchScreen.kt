package io.github.novatv.play.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private data class Results(val channels: List<Channel> = emptyList(), val movies: List<Movie> = emptyList(), val series: List<Series> = emptyList())

/** One box searches channels, movies and series. Type with the Fire TV keyboard, your voice or your phone. */
@Composable
fun SearchScreen(nav: Nav) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val box = remember { FocusRequester() }
    val res by produceState(Results(), q) {
        delay(250)
        val s = q.trim().lowercase()
        if (s.length < 2) { value = Results(); return@produceState }
        val lib = App.repo.state.value.lib
        value = withContext(Dispatchers.Default) {
            Results(
                lib.channels.filter { it.name.lowercase().contains(s) }.take(40),
                lib.movies.filter { it.name.lowercase().contains(s) }.take(40),
                lib.series.filter { it.name.lowercase().contains(s) }.take(40),
            )
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 36.dp, end = 36.dp, top = 30.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)) {
        item { Column {
            Header("Search", "Press OK to type. Hold the microphone button to speak, or type from the Fire TV app on your phone.")
            InputField(q, "Channels, movies, series", { q = it }, Modifier.fillMaxWidth().focusRequester(box))
        } }
        if (res.channels.isNotEmpty()) item {
            Column {
                SectionTitle("Channels", trailing = "${res.channels.size}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(res.channels.size) { i -> ChannelCard(res.channels[i], onClick = { playChannel(ctx, res.channels, i) }) }
                }
            }
        }
        if (res.movies.isNotEmpty()) item {
            Column {
                SectionTitle("Movies", trailing = "${res.movies.size}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(res.movies, key = { it.id }) { m -> PosterCard(m.name, m.poster, m.year, onClick = { nav.push(Route.MovieDetail(m)) }, modifier = Modifier.width(150.dp)) }
                }
            }
        }
        if (res.series.isNotEmpty()) item {
            Column {
                SectionTitle("Series", trailing = "${res.series.size}")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(res.series, key = { it.id }) { s -> PosterCard(s.name, s.poster, s.year, onClick = { nav.push(Route.SeriesDetail(s)) }, modifier = Modifier.width(150.dp)) }
                }
            }
        }
        if (q.trim().length >= 2 && res.channels.isEmpty() && res.movies.isEmpty() && res.series.isEmpty()) item {
            Text("Nothing matches \"$q\".", style = T.body.copy(color = C.dim), modifier = Modifier.padding(top = 20.dp))
        }
    }
    // No autofocus: the box would take focus from the side menu, so Down could never reach Settings.
}
