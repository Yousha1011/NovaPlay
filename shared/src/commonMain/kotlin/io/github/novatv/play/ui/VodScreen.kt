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
import io.github.novatv.play.data.Tmdb
import io.github.novatv.play.data.TmdbInfo
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private enum class Sort(val label: String) { ADDED("Recently added"), AZ("A–Z"), RATING("Top rated") }

/**
 * Movies or series. Overview: what you're watching, your list, recent and top-rated titles and a row per category.
 * Browse: categories and sort orders over a poster grid. Hold OK on a poster to add it to My list.
 */
@Composable
fun VodScreen(nav: Nav, series: Boolean) {
    val st by App.repo.state.collectAsState()
    var browse by remember(series) { mutableStateOf(false) }
    var cat by remember(series) { mutableStateOf("all") }
    val count = if (series) st.lib.series.size else st.lib.movies.size
    Column(Modifier.fillMaxSize().padding(top = 30.dp)) {
        Header(if (series) "Series" else "Movies", if (st.index == null) "Loading…" else "$count titles", Modifier.padding(horizontal = S.gutter)) {
            Pill("Overview", onClick = { browse = false }, selected = !browse)
            Pill("Browse all", onClick = { browse = true }, selected = browse)
        }
        Box(Modifier.padding(horizontal = S.gutter)) { SourceStatus() }
        when {
            st.index == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            count == 0 -> Message(if (series) "No series in this playlist" else "No movies in this playlist",
                "Your provider's list has none. Other playlists can be added in Settings.")
            browse -> Browse(nav, series, cat) { cat = it }
            else -> Overview(nav, series) { c -> cat = c; browse = true }
        }
    }
}

/** Rows: Continue watching, My list, Recently added, Top rated, then each category; "See all" opens it in Browse. */
@Composable
private fun Overview(nav: Nav, series: Boolean, openCategory: (String) -> Unit) {
    val st by App.repo.state.collectAsState()
    val idx = st.index ?: return
    val rev = History.rev
    val cats = if (series) st.lib.seriesCategories else st.lib.movieCategories
    val keyPrefix = if (series) "ep:" else "mv:"
    val resume = remember(rev, series) { App.store.continueWatching.filter { it.key.startsWith(keyPrefix) } }
    var favVersion by remember { mutableIntStateOf(0) }
    val rows: List<Pair<String, List<Any>>> by produceState(emptyList<Pair<String, List<Any>>>(), idx, series, favVersion) {
        value = withContext(Dispatchers.Default) {
            val all: List<Any> = if (series) idx.seriesByAdded else idx.moviesByAdded
            val fav = App.store.favourites
            val byCat: Map<String, List<Any>> = if (series) idx.seriesByCat else idx.moviesByCat
            buildList {
                add("My list" to all.filter { key(it) in fav }.take(30))
                add("Recently added" to all.take(30))
                add("Top rated" to idx.sortedOnce<Any>("$series|all|${Sort.RATING}") { all.sortedByDescending { rating(it) } }.take(30))
                // The biggest categories first: they are usually the main ones (a provider lists hundreds of small ones).
                for (c in cats.sortedByDescending { byCat[it.id]?.size ?: 0 }.take(12)) byCat[c.id]?.let { add("${c.id}|${c.name}" to it.take(30)) }
            }.filter { it.second.isNotEmpty() }
        }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 60.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(S.rowGap)) {
        if (resume.isNotEmpty()) item(key = "continue") {
            Column {
                SectionTitle("Continue watching", Modifier.padding(horizontal = S.gutter))
                LazyRow(contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
                    items(resume, key = { it.key }) { r ->
                        PosterCard(r.title, r.poster, if (r.dur > 0) "${((r.dur - r.pos) / 60_000).coerceAtLeast(1)}m left" else null,
                            modifier = Modifier.width(150.dp), progress = if (r.dur > 0) r.pos.toFloat() / r.dur else null, onClick = {
                                val lib = App.repo.state.value.lib
                                lib.movies.firstOrNull { App.repo.movieKey(it) == r.key }?.let { play(PlayItem.Vod(r.key, it.name, movie = it)) }
                                    ?: lib.series.firstOrNull { r.key.startsWith(App.repo.seriesKey(it).replaceFirst("se:", "ep:") + ":") }
                                        ?.let { nav.push(Route.SeriesDetail(it)) }
                            })
                    }
                }
            }
        }
        items(rows, key = { it.first }) { (k, list) ->
            val catId = k.substringBefore('|', "")
            Column {
                Row(Modifier.padding(horizontal = S.gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionTitle(k.substringAfter('|'))
                    if (catId.isNotEmpty()) Pill("See all", onClick = { openCategory(catId) }, modifier = Modifier.padding(bottom = 10.dp))
                }
                LazyRow(contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
                    items(list, key = { key(it) }) { VodPoster(nav, it) { favVersion++ } }
                }
            }
        }
    }
}

/** Categories and sort orders over a grid that fits as many 150-unit posters as the screen holds. */
@Composable
private fun Browse(nav: Nav, series: Boolean, cat: String, onCat: (String) -> Unit) {
    val st by App.repo.state.collectAsState()
    var sort by remember(series) { mutableStateOf(Sort.ADDED) }
    var favVersion by remember { mutableIntStateOf(0) }
    val cats = if (series) st.lib.seriesCategories else st.lib.movieCategories
    // Looked up in the library's index (built when the playlist loaded); other sort orders are made once, off the main thread.
    val items: List<Any>? by produceState<List<Any>?>(null, st.index, series, cat, sort, favVersion) {
        val idx = st.index ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val byAdded: List<Any> = when (cat) {
                "all" -> if (series) idx.seriesByAdded else idx.moviesByAdded
                "fav" -> (if (series) idx.seriesByAdded else idx.moviesByAdded).let { l -> val f = App.store.favourites; l.filter { key(it) in f } }
                else -> (if (series) idx.seriesByCat[cat] else idx.moviesByCat[cat]).orEmpty()
            }
            if (sort == Sort.ADDED || cat == "fav") byAdded.let { l ->
                when (sort) { Sort.ADDED -> l; Sort.AZ -> l.sortedBy { name(it).lowercase() }; Sort.RATING -> l.sortedByDescending { rating(it) } }
            } else idx.sortedOnce<Any>("$series|$cat|$sort") {
                when (sort) { Sort.AZ -> byAdded.sortedBy { name(it).lowercase() }; else -> byAdded.sortedByDescending { rating(it) } }
            }
        }
    }
    Column {
        LazyRow(contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
            items(Sort.entries) { s -> Pill(s.label, onClick = { sort = s }, selected = sort == s) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = S.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            item { Pill("All", onClick = { onCat("all") }, selected = cat == "all") }
            item { Pill("My list", onClick = { onCat("fav") }, selected = cat == "fav") }
            items(cats, key = { it.id }) { c -> Pill(c.name, onClick = { onCat(c.id) }, selected = cat == c.id) }
        }
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            list.isEmpty() -> Message(if (cat == "fav") "Your list is empty" else "Nothing in this category",
                if (cat == "fav") "Hold OK on a poster, or use My list on its page, to add it." else null)
            else -> LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(start = S.gutter, end = S.gutter, bottom = 60.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                items(list, key = { key(it) }) { VodPoster(nav, it, Modifier) { favVersion++ } }
            }
        }
    }
}

/** A movie or series poster: title, year and rating underneath, a ★ when it's in My list. Hold OK toggles My list. */
@Composable
private fun VodPoster(nav: Nav, it: Any, modifier: Modifier = Modifier.width(150.dp), onFav: () -> Unit) {
    val k = key(it)
    val fav = App.store.isFavourite(k)
    val toggle = { val on = App.store.toggleFavourite(k); onFav(); toast(if (on) "Added to My list" else "Removed from My list") }
    val r = rating(it).takeIf { it > 0f }
    val sub = listOfNotNull(if (fav) "★ My list" else null, (it as? Movie)?.year ?: (it as? Series)?.year, r?.let { v -> "★ ${fmt(v)}" }).joinToString("  ·  ")
    when (it) {
        is Movie -> PosterCard(it.name, it.poster, sub, onClick = { nav.push(Route.MovieDetail(it)) }, onLongClick = toggle, modifier = modifier,
            progress = App.store.progress(k)?.let { p -> p.first.toFloat() / p.second.coerceAtLeast(1) })
        is Series -> PosterCard(it.name, it.poster, sub, onClick = { nav.push(Route.SeriesDetail(it)) }, onLongClick = toggle, modifier = modifier)
    }
}

private fun key(it: Any) = if (it is Movie) App.repo.movieKey(it) else App.repo.seriesKey(it as Series)
private fun name(it: Any) = (it as? Movie)?.name ?: (it as Series).name
private fun rating(it: Any) = ((it as? Movie)?.rating ?: (it as? Series)?.rating)?.toFloatOrNull() ?: 0f
private fun fmt(v: Float) = ((v * 10).toInt() / 10f).toString()

/**
 * A movie's or a series' page: the artwork across the top right, title and details, Resume / Play from beginning /
 * My list, then cast, the seasons and episodes, and more from the same category. Facts come from the provider, with
 * TMDB's cast photos when a TMDB key is set; nothing is filled in when neither knows.
 */
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
    val tmdb by produceState<TmdbInfo?>(null, title) { value = withContext(IO) { Tmdb.lookup(title, movie?.year ?: series?.year, series != null) } }
    var favVersion by remember { mutableIntStateOf(0) }
    val favKey = if (movie != null) App.repo.movieKey(movie) else App.repo.seriesKey(series!!)
    val fav = remember(favVersion) { App.store.isFavourite(favKey) }
    var season by remember { mutableStateOf<Int?>(null) }
    val firstFocus = remember { FocusRequester() }
    val st by App.repo.state.collectAsState()
    val similar = remember(st.index, movie, series) {
        val idx = st.index
        when {
            idx == null -> emptyList()
            movie != null -> idx.moviesByCat[movie.categoryId].orEmpty().filter { it.id != movie.id }.take(20)
            else -> idx.seriesByCat[series!!.categoryId].orEmpty().filter { it.id != series.id }.take(20)
        }
    }
    val cast: List<TmdbInfo.Person> = tmdb?.cast?.takeIf { it.isNotEmpty() }
        ?: details.cast?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(12)?.map { TmdbInfo.Person(it, "", null) }.orEmpty()
    val genres = details.genre ?: tmdb?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val heroHeight = (maxHeight * 0.66f).coerceAtLeast(340.dp)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(S.rowGap)) {
            item(key = "hero") {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    val art = details.backdrop ?: tmdb?.backdrop ?: poster
                    if (art != null) AsyncImage(art, null, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.70f).fillMaxHeight(),
                        contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to C.bg, 0.32f to C.bg, 0.65f to C.bg.copy(alpha = 0.6f), 1f to Color.Transparent)))
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to C.bg)))
                    Column(Modifier.align(Alignment.BottomStart).padding(start = S.gutter + 20.dp, bottom = 4.dp).fillMaxWidth(0.6f)) {
                        Text(listOfNotNull(if (movie != null) "Movie" else "Series", genres?.split(',')?.firstOrNull()?.trim()).joinToString(" · ").uppercase(), style = T.eyebrow)
                        Spacer(Modifier.height(8.dp))
                        Text(title, style = T.display, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(10.dp))
                        val score = (details.rating ?: movie?.rating ?: series?.rating)?.toFloatOrNull()?.takeIf { it > 0f } ?: tmdb?.score?.toFloat()?.takeIf { it > 0f }
                        Text(listOfNotNull(details.released?.take(4) ?: movie?.year ?: series?.year, details.duration ?: tmdb?.runtimeMin?.let { "${it / 60}h ${it % 60}m" },
                            score?.let { "★ ${fmt(it)}" }, genres).joinToString("   ·   "), style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val plot = details.plot ?: series?.plot ?: tmdb?.overview
                        if (!plot.isNullOrBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(plot, style = T.body.copy(color = C.dim, lineHeight = 23.sp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                        details.director?.takeIf { it.isNotBlank() }?.let {
                            Spacer(Modifier.height(8.dp)); Text("Director: $it", style = T.small.copy(color = C.faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            var first = true
                            fun Modifier.firstOne(): Modifier = if (first) { first = false; focusRequester(firstFocus) } else this
                            if (movie != null) {
                                val key = App.repo.movieKey(movie)
                                val prog = App.store.progress(key)
                                Pill(if (prog != null) "Resume from ${duration(prog.first)}" else "Play", onClick = { play(PlayItem.Vod(key, movie.name, movie = movie)) },
                                    icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.firstOne())
                                if (prog != null) Pill("Play from beginning", onClick = {
                                    App.store.saveProgress(key, movie.name, movie.poster, 0, 0); play(PlayItem.Vod(key, movie.name, movie = movie))
                                }, icon = Icons.Rounded.Replay)
                            } else {
                                val next = nextEpisode(series!!, details)
                                if (next != null) Pill(next.second, onClick = { playEpisode(series, details, next.first) },
                                    icon = Icons.Rounded.PlayArrow, primary = true, modifier = Modifier.firstOne())
                            }
                            Pill("My list", onClick = { App.store.toggleFavourite(favKey); favVersion++ },
                                icon = if (fav) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, selected = fav, modifier = Modifier.firstOne())
                        }
                    }
                }
            }
            if (cast.isNotEmpty()) item(key = "cast") {
                Column {
                    Text("CAST", style = T.eyebrow.copy(color = C.dim), modifier = Modifier.padding(start = S.gutter + 20.dp, bottom = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = S.gutter + 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(cast) { CastCard(it) }
                    }
                }
            }
            if (series != null) item(key = "episodes") {
                Column(Modifier.padding(horizontal = S.gutter + 20.dp)) {
                    val seasons = details.seasons
                    when {
                        seasons.isNotEmpty() -> {
                            val cur = season ?: nextEpisode(series, details)?.first?.season ?: seasons.keys.first()
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 14.dp)) {
                                items(seasons.keys.toList()) { s -> Pill("Season $s", onClick = { season = s }, selected = s == cur) }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (e in seasons[cur].orEmpty()) androidx.compose.runtime.key(e.id) { EpisodeRow(series, e) { playEpisode(series, details, e) } }
                            }
                        }
                        details == Details() -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Spinner(size = 22.dp); Spacer(Modifier.width(10.dp)); Text("Loading episodes…", style = T.small)
                        }
                        else -> Text("Your provider lists no episodes for this series.", style = T.small)
                    }
                }
            }
            if (similar.isNotEmpty()) item(key = "similar") {
                Column {
                    Text("MORE LIKE THIS", style = T.eyebrow.copy(color = C.dim), modifier = Modifier.padding(start = S.gutter + 20.dp, bottom = 10.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = S.gutter + 20.dp), horizontalArrangement = Arrangement.spacedBy(S.cardGap)) {
                        items(similar, key = { key(it) }) { VodPoster(nav, it) {} }
                    }
                }
            }
        }
    }
    AutoFocus(firstFocus)
}

/** A cast member: their photo when TMDB has one, otherwise initials, and the name (and role) under it. */
@Composable
private fun CastCard(p: TmdbInfo.Person) {
    Tile({}, Modifier.width(170.dp).height(if (p.photo != null) 200.dp else 100.dp), shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxSize()) {
            if (p.photo != null) AsyncImage(p.photo, null, Modifier.fillMaxWidth().height(110.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            else Text(initials(p.name), style = T.h2.copy(color = C.dim), modifier = Modifier.padding(start = 16.dp, top = 14.dp))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(p.name, style = T.small.copy(color = C.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.role.isNotBlank()) Text(p.role, style = T.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
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
