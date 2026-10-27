package io.github.novatv.play.ui.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.Details
import io.github.novatv.play.data.Episode
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Series
import io.github.novatv.play.data.Tmdb
import io.github.novatv.play.data.TmdbInfo
import io.github.novatv.play.openUrl
import io.github.novatv.play.platform.IO
import io.github.novatv.play.play
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.duration
import io.github.novatv.play.ui.nextEpisode
import io.github.novatv.play.ui.playEpisode
import kotlinx.coroutines.withContext

private enum class Order(val label: String) { ADDED("Recently added"), AZ("A–Z"), RATING("Top rated") }

private fun keyOf(it: Any) = if (it is Movie) App.repo.movieKey(it) else App.repo.seriesKey(it as Series)

fun open(nav: Nav, it: Any) = nav.push(if (it is Movie) Route.MovieDetail(it) else Route.SeriesDetail(it as Series))

/** Movies or TV Shows: chips (Favorites, Watched History, all, categories), a carousel of the newest, then rows. */
@Composable
fun PhoneVod(nav: Nav, series: Boolean) {
    val st by App.repo.state.collectAsState()
    val idx = st.index
    var chip by remember(series) { mutableStateOf<String?>(null) }
    var order by remember(series) { mutableStateOf(Order.ADDED) }
    val word = if (series) "TV Shows" else "Movies"
    val cats = if (series) st.lib.seriesCategories else st.lib.movieCategories
    val all: List<Any> = remember(idx, series) { (if (series) idx?.seriesByAdded else idx?.moviesByAdded).orEmpty() }
    val favs = remember(all, App.store.favourites) { val f = App.store.favourites; all.filter { keyOf(it) in f } }
    val watched = remember(all, App.store.watched) {
        val byKey = all.associateBy { keyOf(it) }
        App.store.watched.mapNotNull { byKey[it] }
    }
    val chips = listOf("fav" to "Favorites", "hist" to "Watched History", "all" to "All $word") + cats.map { it.id to it.name }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopBar(nav) { RoundIcon(Icons.Rounded.SwapVert, onClick = { order = Order.entries[(order.ordinal + 1) % Order.entries.size] }) }
        Chips(chips, chip, { chip = if (chip == it) null else it }, Modifier.padding(vertical = 8.dp))
        val c = chip
        if (c != null) {
            val list = when (c) {
                "fav" -> favs; "hist" -> watched; "all" -> all
                else -> (if (series) idx?.seriesByCat?.get(c) else idx?.moviesByCat?.get(c)).orEmpty()
            }
            Grid(nav, sorted(list, order), when (c) { "fav" -> "No favourites yet"; "hist" -> "Nothing watched yet"; else -> "Nothing here" })
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
            if (all.isNotEmpty()) item { Hero(nav, all.take(8)) }
            if (watched.isNotEmpty()) item { PosterRow("Watched History (${watched.size})", watched, { open(nav, it) }, { nav.push(Route.SeeAll("Watched History", watched)) }) }
            if (favs.isNotEmpty()) item { PosterRow("Favorites (${favs.size})", favs, { open(nav, it) }, { nav.push(Route.SeeAll("Favorites", favs)) }) }
            item { PosterRow("All $word (${io.github.novatv.play.platform.grouped(all.size)})", all, { open(nav, it) }, { chip = "all" }) }
            items(cats.take(40), key = { it.id }) { cat ->
                val list: List<Any> = (if (series) idx?.seriesByCat?.get(cat.id) else idx?.moviesByCat?.get(cat.id)).orEmpty()
                PosterRow("${cat.name} (${list.size})", list, { open(nav, it) }, { chip = cat.id })
            }
        }
    }
}

private fun sorted(l: List<Any>, o: Order): List<Any> = when (o) {
    Order.ADDED -> l
    Order.AZ -> l.sortedBy { nameOf(it).lowercase() }
    Order.RATING -> l.sortedByDescending { ratingOf(it)?.toFloatOrNull() ?: 0f }
}

/** Posters in three columns. */
@Composable
private fun Grid(nav: Nav, list: List<Any>, empty: String) {
    if (list.isEmpty()) { Empty(Icons.Rounded.Movie, empty, null); return }
    LazyVerticalGrid(GridCells.Adaptive(108.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list, key = { keyOf(it) }) { m -> Poster(nameOf(m), posterOf(m), ratingOf(m), onClick = { open(nav, m) }) }
    }
}

/** A page of every title in a row. */
@Composable
fun SeeAll(nav: Nav, title: String, items: List<Any>) {
    Pushed(nav) {
        Column {
            Text(title, style = P.large, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Grid(nav, items, "Nothing here")
        }
    }
}

/** The newest titles, large, one at a time with page dots under them. */
@Composable
private fun Hero(nav: Nav, items: List<Any>) {
    val pager = rememberPagerState { items.size }
    Column(Modifier.padding(top = 8.dp)) {
        HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 12.dp) { i ->
            val m = items[i]
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).clip(RoundedCornerShape(20.dp)).background(P.card).tap { open(nav, m) }) {
                posterOf(m)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)), startY = 200f)))
                Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                    Text("${i + 1}", style = P.large.copy(fontSize = 64.sp, color = Color.White.copy(alpha = 0.9f)))
                    Spacer(Modifier.width(12.dp))
                    Text(nameOf(m), style = P.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            repeat(items.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(if (i == pager.currentPage) 22.dp else 7.dp, 7.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (i == pager.currentPage) P.blue else P.card2))
            }
        }
    }
}

// ---------------------------------------------------------------- Detail

/** A film's or series' page: artwork, poster, Watch Now, plot, episodes, then trailers, ratings, reviews and cast. */
@Composable
fun PhoneDetail(nav: Nav, movie: Movie? = null, series: Series? = null) {
    val title = movie?.name ?: series!!.name
    val details by produceState(Details(), movie, series) {
        value = withContext(IO) {
            val p = App.repo.provider ?: return@withContext Details()
            try { if (movie != null) p.movieDetails(movie) else p.seriesDetails(series!!) } catch (e: Exception) { Details() }
        }
    }
    val tmdb by produceState<TmdbInfo?>(null, movie, series) {
        value = withContext(IO) { Tmdb.lookup(title, movie?.year ?: series?.year, series != null) }
    }
    var favVersion by remember { mutableIntStateOf(0) }
    val favKey = if (movie != null) App.repo.movieKey(movie) else App.repo.seriesKey(series!!)
    val fav = remember(favVersion) { App.store.isFavourite(favKey) }
    var season by remember { mutableStateOf<Int?>(null) }
    val poster = movie?.poster ?: series?.poster ?: tmdb?.poster
    val rating = (details.rating ?: movie?.rating ?: series?.rating)?.takeIf { (it.toFloatOrNull() ?: 0f) > 0f }
        ?: tmdb?.score?.let { ((it * 10).toInt() / 10.0).toString() }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(520.dp)) {
                    val art = tmdb?.backdrop ?: details.backdrop ?: poster
                    if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, Color(0xCC000000), Color.Black))))
                    Row(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalAlignment = Alignment.Bottom) {
                        Poster(title, poster, rating, onClick = {}, modifier = Modifier.width(118.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = P.title.copy(fontSize = 26.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val date = details.released ?: tmdb?.released ?: movie?.year ?: series?.year
                                if (date != null) { Icon(Icons.Rounded.CalendarMonth, size = 16.dp, tint = P.dim); Spacer(Modifier.width(5.dp)); Text(date, style = P.small.copy(color = Color(0xFFD0D0D5))) }
                                val len = details.duration ?: tmdb?.runtimeMin?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}:00" }
                                if (len != null) { Spacer(Modifier.width(12.dp)); Icon(Icons.Rounded.Schedule, size = 16.dp, tint = P.dim); Spacer(Modifier.width(5.dp)); Text(len, style = P.small.copy(color = Color(0xFFD0D0D5))) }
                            }
                            val genres = details.genre?.split(',', '/')?.map { it.trim() }?.filter { it.isNotBlank() }?.ifEmpty { null } ?: tmdb?.genres.orEmpty()
                            if (genres.isNotEmpty()) {
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    for (g in genres.take(3)) Text(g, style = P.small.copy(color = Color.White),
                                        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 4.dp))
                                }
                            }
                            val cast = details.cast ?: tmdb?.cast?.take(5)?.joinToString(", ") { it.name }
                            if (!cast.isNullOrBlank()) { Spacer(Modifier.height(8.dp)); Text(cast, style = P.small.copy(color = Color(0xFFD0D0D5)), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val label: String
                    val action: () -> Unit
                    if (movie != null) {
                        val key = App.repo.movieKey(movie)
                        val prog = App.store.progress(key)
                        label = if (prog != null) "Resume ${duration(prog.first)}" else "Watch Now"
                        action = { play(PlayItem.Vod(key, movie.name, movie = movie)) }
                    } else {
                        val next = nextEpisode(series!!, details)
                        label = next?.second ?: "Loading episodes…"
                        action = { next?.let { playEpisode(series, details, it.first) } }
                    }
                    Row(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(26.dp)).background(P.blue).tap(action),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.PlayArrow, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = P.head.copy(fontSize = 18.sp))
                    }
                    Spacer(Modifier.width(12.dp))
                    RoundIcon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, size = 52.dp, tint = if (fav) P.red else Color.White,
                        onClick = { App.store.toggleFavourite(favKey); favVersion++ })
                }
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(P.line))
                val plot = details.plot ?: series?.plot ?: tmdb?.overview
                if (!plot.isNullOrBlank()) Text(plot, style = P.body.copy(fontSize = 17.sp, lineHeight = 24.sp), modifier = Modifier.padding(16.dp))
            }
            if (series != null) {
                val seasons = details.seasons
                if (seasons.isNotEmpty()) {
                    val cur = season ?: seasons.keys.first()
                    item {
                        Chips(seasons.keys.map { it.toString() to "Season $it" }, cur.toString(), { season = it.toInt() }, Modifier.padding(vertical = 8.dp))
                    }
                    items(seasons[cur].orEmpty(), key = { it.id }) { e -> EpisodeLine(series, e) { playEpisode(series, details, e) } }
                }
            }
            val t = tmdb
            if (t != null) {
                if (t.trailers.isNotEmpty()) item {
                    RowTitle("Trailers", null)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(t.trailers, key = { it.youtubeKey }) { v ->
                            Box(Modifier.width(260.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(P.card).tap { openUrl(v.url) }) {
                                AsyncImage(v.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))))
                                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                                    Text(v.name, style = P.head, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(v.kind, style = P.small)
                                }
                            }
                        }
                    }
                }
                item { Ratings(t, rating) }
                if (t.reviews.isNotEmpty()) item {
                    Text("AUDIENCE VOICES", style = P.caps, modifier = Modifier.padding(start = 16.dp, top = 22.dp))
                    Text("Reviews", style = P.title, modifier = Modifier.padding(start = 16.dp, top = 2.dp, bottom = 12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(t.reviews, key = { it.author + it.date }) { r -> ReviewCard(r) }
                    }
                }
                if (t.cast.isNotEmpty()) item {
                    RowTitle("Cast", null)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                        items(t.cast, key = { it.name + it.role }) { p ->
                            Column(Modifier.width(104.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(100.dp).clip(CircleShape).background(P.card), contentAlignment = Alignment.Center) {
                                    Text(io.github.novatv.play.ui.initials(p.name), style = P.title.copy(color = P.dim))
                                    if (p.photo != null) AsyncImage(p.photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(p.name, style = P.head.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(p.role, style = P.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            } else if (!Tmdb.enabled) item {
                Text("Add a TMDB key in Settings for trailers, ratings, reviews and cast.", style = P.small,
                    modifier = Modifier.padding(16.dp).tap { nav.push(Route.Settings) })
            }
        }
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = { nav.pop() },
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 6.dp))
    }
}

@Composable
private fun EpisodeLine(s: Series, e: Episode, onClick: () -> Unit) {
    val prog = App.store.progress(App.repo.episodeKey(s, e))
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(P.card).tap(onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(10.dp)).background(P.card2), contentAlignment = Alignment.Center) {
            Text("E${e.number}", style = P.head.copy(color = P.dim))
            if (e.image != null) AsyncImage(e.image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (prog != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(5.dp).height(3.dp).background(Color(0x55FFFFFF))) {
                Box(Modifier.fillMaxWidth(prog.first.toFloat() / prog.second.coerceAtLeast(1)).height(3.dp).background(P.blue))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${e.number}. ${e.title}", style = P.head, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.durationSec > 0) Text("${e.durationSec / 60} min", style = P.small)
        }
    }
}

/** A score ring with a verdict, then a tile per source (TMDB, the playlist's own rating). */
@Composable
private fun Ratings(t: TmdbInfo, provider: String?) {
    val score = t.score ?: return
    val pct = (score * 10).toInt()
    val verdict = when { pct >= 80 -> "Universal Acclaim"; pct >= 70 -> "Generally Favorable"; pct >= 55 -> "Mixed Reception"; else -> "Generally Unfavorable" }
    val ring = when { pct >= 70 -> Color(0xFF30D158); pct >= 55 -> Color(0xFFFF9F0A); else -> P.red }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("CRITICS & AUDIENCE", style = P.caps, modifier = Modifier.padding(top = 22.dp))
        Text("Ratings", style = P.title, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.horizontalGradient(listOf(ring.copy(alpha = 0.18f), P.card)))
            .border(1.dp, ring.copy(alpha = 0.35f), RoundedCornerShape(22.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(78.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawArc(Color(0x33FFFFFF), 0f, 360f, false, style = Stroke(6.dp.toPx()), size = Size(size.width, size.height), topLeft = Offset.Zero)
                    drawArc(ring, -90f, 360f * pct / 100f, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                }
                Text("$pct", style = P.title)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(verdict, style = P.title.copy(fontSize = 20.sp))
                Text("${io.github.novatv.play.platform.grouped(t.votes)} votes on TMDB", style = P.small)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScoreTile("TMDB", Color(0xFF01B4E4), "${(score * 10).toInt() / 10.0}/10")
            if (provider != null) ScoreTile("Playlist", P.blue, "$provider/10")
        }
    }
}

@Composable
private fun ScoreTile(name: String, color: Color, value: String) {
    Row(Modifier.clip(RoundedCornerShape(16.dp)).background(P.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = P.small.copy(color = Color.Black, fontSize = 12.sp),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 8.dp, vertical = 5.dp))
        Spacer(Modifier.width(10.dp))
        Text(value, style = P.head)
    }
}

@Composable
private fun ReviewCard(r: TmdbInfo.Review) {
    Column(Modifier.width(300.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF16140F)).border(1.dp, Color(0x33FFCC00), RoundedCornerShape(22.dp)).padding(18.dp)) {
        r.rating?.let { v ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val stars = (v / 2).toInt()
                Text("★".repeat(stars) + "☆".repeat(5 - stars), style = P.head.copy(color = P.gold))
                Spacer(Modifier.width(8.dp))
                Text("${v.toInt()}/10", style = P.small.copy(color = P.gold))
            }
            Spacer(Modifier.height(10.dp))
        }
        Text("“${r.text}", style = P.body.copy(fontSize = 18.sp, fontStyle = FontStyle.Italic, lineHeight = 26.sp), maxLines = 7, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(P.card2), contentAlignment = Alignment.Center) {
                Text(r.author.take(1).uppercase(), style = P.head.copy(color = P.dim))
            }
            Spacer(Modifier.width(10.dp))
            Column { Text(r.author, style = P.head); Text(r.date, style = P.small) }
        }
        if (r.url != null) Text("↗ Full Review", style = P.head.copy(color = P.blue), modifier = Modifier.padding(top = 12.dp).tap { openUrl(r.url) })
    }
}

