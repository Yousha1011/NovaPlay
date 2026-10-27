package io.github.novatv.play.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.Details
import io.github.novatv.play.data.Episode
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Series
import io.github.novatv.play.play
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.novatv.play.platform.IO
import io.github.novatv.play.toast

private enum class Sort(val label: String) { ADDED("Recently added"), AZ("A–Z"), RATING("Top rated") }

/** Movies or series: categories across the top and a poster grid. Hold OK on a poster to favourite it. */
@Composable
fun VodScreen(nav: Nav, series: Boolean) {
    val st by App.repo.state.collectAsState()
    val lib = st.lib
    var cat by remember(series) { mutableStateOf("all") }
    var sort by remember(series) { mutableStateOf(Sort.ADDED) }
    var favVersion by remember { mutableIntStateOf(0) }
    val cats = if (series) lib.seriesCategories else lib.movieCategories
    // Looked up in the library's index (built when the playlist loaded); other sort orders are made once, off the main thread.
    val items: List<Any>? by androidx.compose.runtime.produceState<List<Any>?>(null, st.index, series, cat, sort, favVersion) {
        val idx = st.index ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val byAdded: List<Any> = when (cat) {
                "all" -> if (series) idx.seriesByAdded else idx.moviesByAdded
                "fav" -> (if (series) idx.seriesByAdded else idx.moviesByAdded).let { l -> val f = App.store.favourites; l.filter { key(it) in f } }
                else -> (if (series) idx.seriesByCat[cat] else idx.moviesByCat[cat]).orEmpty()
            }
            if (sort == Sort.ADDED || cat == "fav") byAdded.let { l ->
                when (sort) { Sort.ADDED -> l; Sort.AZ -> l.sortedBy { name(it).lowercase() }
                    Sort.RATING -> l.sortedByDescending { ((it as? Movie)?.rating ?: (it as? Series)?.rating)?.toFloatOrNull() ?: 0f } }
            } else idx.sortedOnce<Any>("$series|$cat|$sort") {
                when (sort) {
                    Sort.AZ -> byAdded.sortedBy { name(it).lowercase() }
                    else -> byAdded.sortedByDescending { ((it as? Movie)?.rating ?: (it as? Series)?.rating)?.toFloatOrNull() ?: 0f }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 30.dp, end = 30.dp)) {
        Header(if (series) "Series" else "Movies", if (items == null) "Loading…" else "${items!!.size} titles") {
            for (s in Sort.entries) Pill(s.label, onClick = { sort = s }, selected = sort == s)
        }
        SourceStatus()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 18.dp)) {
            item { Pill("All", onClick = { cat = "all" }, selected = cat == "all") }
            item { Pill("Favourites", onClick = { cat = "fav" }, selected = cat == "fav") }
            items(cats, key = { it.id }) { c -> Pill(c.name, onClick = { cat = c.id }, selected = cat == c.id) }
        }
        val list = items
        if (list == null) { androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }; return@Column }
        if (list.isEmpty()) { Message(if (cat == "fav") "No favourites yet" else "Nothing here", if (cat == "fav") "Hold OK on a poster to add it." else null); return@Column }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(bottom = 60.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            items(list, key = { key(it) }) { it ->
                val fav = App.store.isFavourite(key(it))
                val toggle = {
                    val on = App.store.toggleFavourite(key(it)); favVersion++
                    toast(if (on) "Added to favourites" else "Removed from favourites")
                }
                when (it) {
                    is Movie -> PosterCard(it.name, it.poster, listOfNotNull(if (fav) "★" else null, it.year, it.rating?.let { r -> "$r/10" }).joinToString("  "),
                        onClick = { nav.push(Route.MovieDetail(it)) }, onLongClick = toggle,
                        progress = App.store.progress(App.repo.movieKey(it))?.let { p -> p.first.toFloat() / p.second.coerceAtLeast(1) })
                    is Series -> PosterCard(it.name, it.poster, listOfNotNull(if (fav) "★" else null, it.year, it.rating?.let { r -> "$r/10" }).joinToString("  "),
                        onClick = { nav.push(Route.SeriesDetail(it)) }, onLongClick = toggle)
                }
            }
        }
    }
}

private fun key(it: Any) = if (it is Movie) App.repo.movieKey(it) else App.repo.seriesKey(it as Series)
private fun name(it: Any) = (it as? Movie)?.name ?: (it as Series).name

/** A movie's or a series' page: artwork, details, play or resume, and for series the seasons and episodes. */
@Composable
fun DetailScreen(nav: Nav, movie: Movie? = null, series: Series? = null) {
    val title = movie?.name ?: series!!.name
    val poster = movie?.poster ?: series?.poster
    val details by produceState(Details(), movie, series) {
        value = withContext(IO) {
            val p = App.repo.provider ?: return@withContext Details()
            try { if (movie != null) p.movieDetails(movie) else p.seriesDetails(series!!) } catch (e: Exception) { Details() }
        }
    }
    var favVersion by remember { mutableIntStateOf(0) }
    val favKey = if (movie != null) App.repo.movieKey(movie) else App.repo.seriesKey(series!!)
    val fav = remember(favVersion) { App.store.isFavourite(favKey) }
    var season by remember { mutableStateOf<Int?>(null) }
    val playFocus = remember { FocusRequester() }

    Box(Modifier.fillMaxSize()) {
        val art = details.backdrop ?: poster
        if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(C.bg, C.bg.copy(alpha = 0.85f), Color.Transparent))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg), startY = 300f)))
        Row(Modifier.fillMaxSize().padding(start = 60.dp, top = 50.dp, end = 50.dp)) {
            Column(Modifier.width(240.dp)) {
                PosterCard(title, poster, null, onClick = {}, modifier = Modifier.width(220.dp))
            }
            Spacer(Modifier.width(40.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = T.hero, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Text(listOfNotNull(details.released?.take(4) ?: movie?.year ?: series?.year, details.genre, details.duration,
                    (details.rating ?: movie?.rating ?: series?.rating)?.let { "★ $it" }).joinToString("   ·   "), style = T.small.copy(color = C.dim))
                Spacer(Modifier.height(14.dp))
                Text(details.plot ?: series?.plot ?: "", style = T.body.copy(color = C.dim), maxLines = 5, overflow = TextOverflow.Ellipsis)
                details.cast?.let { Spacer(Modifier.height(10.dp)); Text("Cast: $it", style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                details.director?.let { Text("Director: $it", style = T.small, maxLines = 1) }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (movie != null) {
                        val key = App.repo.movieKey(movie)
                        val prog = App.store.progress(key)
                        Pill(if (prog != null) "Resume ${duration(prog.first)}" else "Play", onClick = { play(PlayItem.Vod(key, movie.name, movie = movie)) },
                            icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus))
                        if (prog != null) Pill("Start over", onClick = { App.store.saveProgress(key, movie.name, movie.poster, 0, 0); play(PlayItem.Vod(key, movie.name, movie = movie)) },
                            icon = Icons.Rounded.Replay)
                    } else {
                        val next = nextEpisode(series!!, details)
                        if (next != null) Pill(next.second, onClick = { playEpisode(series, details, next.first) },
                            icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.focusRequester(playFocus))
                    }
                    Pill(if (fav) "Favourite" else "Add to favourites", onClick = { App.store.toggleFavourite(favKey); favVersion++ },
                        icon = if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, selected = fav)
                    Pill("Back", onClick = { nav.pop() })
                }
                if (series != null) {
                    Spacer(Modifier.height(24.dp))
                    val seasons = details.seasons
                    if (seasons.isEmpty()) Spinner(size = 28.dp) else {
                        val cur = season ?: seasons.keys.first()
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                            items(seasons.keys.toList()) { s -> Pill("Season $s", onClick = { season = s }, selected = s == cur) }
                        }
                        val eps = seasons[cur].orEmpty()
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                            items(eps, key = { it.id }) { e -> EpisodeRow(series, e) { playEpisode(series, details, e) } }
                        }
                    }
                }
            }
        }
    }
    AutoFocus(playFocus, details)
}

@Composable
private fun EpisodeRow(s: Series, e: Episode, onClick: () -> Unit) {
    val prog = App.store.progress(App.repo.episodeKey(s, e))
    Tile(onClick, Modifier.fillMaxWidth().height(86.dp), shape = RoundedCornerShape(12.dp), scale = 1.02f) {
        Row(Modifier.fillMaxSize().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(128.dp, 66.dp).clip(RoundedCornerShape(8.dp)).background(C.panelHi), contentAlignment = Alignment.Center) {
                Text("E${e.number}", style = T.h2.copy(color = C.faint))
                if (e.image != null) AsyncImage(e.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (prog != null) Bar(prog.first.toFloat() / prog.second.coerceAtLeast(1), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(4.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("${e.number}. ${e.title}", style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                e.plot?.let { Text(it, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            if (e.durationSec > 0) Text("${e.durationSec / 60} min", style = T.small)
        }
    }
}

/** The episode to offer first: the one in progress, else the one after the last watched, else the first. */
internal fun nextEpisode(s: Series, d: Details): Pair<Episode, String>? {
    val all = d.seasons.values.flatten()
    if (all.isEmpty()) return null
    all.firstOrNull { App.store.progress(App.repo.episodeKey(s, it)) != null }?.let { return it to "Resume S${it.season} E${it.number}" }
    return all.first() to "Play S${all.first().season} E${all.first().number}"
}

internal fun playEpisode(s: Series, d: Details, e: Episode) {
    val all = d.seasons.values.flatten()
    val after = all.dropWhile { it.id != e.id }.drop(1)
    play(PlayItem.Vod(App.repo.episodeKey(s, e), "${s.name} · S${e.season} E${e.number}", episode = e, series = s, nextEpisodes = after))
}
