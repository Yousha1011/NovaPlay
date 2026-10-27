package io.github.novatv.play.ui.phone

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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.Tab
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Programme
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.LeaguesScreen
import io.github.novatv.play.ui.SettingsScreen
import io.github.novatv.play.ui.SportsScreen
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.clock
import io.github.novatv.play.ui.nowNext
import io.github.novatv.play.ui.playChannel

/** The tabs along the bottom of the iPhone app. */
private val phoneTabs = listOf(
    Tab.HOME to Icons.Rounded.Home, Tab.LIVE to Icons.Rounded.LiveTv, Tab.SPORTS to Icons.Rounded.SportsSoccer,
    Tab.MOVIES to Icons.Rounded.Movie, Tab.SERIES to Icons.Rounded.PlayCircle, Tab.SEARCH to Icons.Rounded.Search,
)

private fun label(t: Tab) = when (t) { Tab.SERIES -> "TV Shows"; else -> t.label }

/** The iPhone app: a screen per tab, a floating tab bar, and pages pushed over them with a back button. */
@Composable
fun PhoneRoot() {
    PhoneStyle()
    val nav = remember { Nav() }
    val state by App.repo.state.collectAsState()
    Box(Modifier.fillMaxSize().background(P.background)) {
        if (state.source == null && nav.stack.isEmpty()) {
            PhoneWelcome(nav)
            return@Box
        }
        val top = nav.stack.lastOrNull()
        if (top != null) {
            when (top) {
                is Route.MovieDetail -> PhoneDetail(nav, movie = top.movie)
                is Route.SeriesDetail -> PhoneDetail(nav, series = top.series)
                is Route.SeeAll -> SeeAll(nav, top.title, top.items)
                Route.Menu -> AppMenu(nav)
                Route.SearchSettings -> SearchSettings(nav)
                Route.Appearance -> Appearance(nav)
                Route.Settings -> Pushed(nav) { TvCanvas { SettingsScreen(nav) } }
                Route.AddSource -> PhoneAddSource(nav)
                Route.Leagues -> Pushed(nav) { TvCanvas { LeaguesScreen(nav) } }
            }
            return@Box
        }
        // The visited tabs stay built so switching back is instant.
        val visited = remember { mutableStateListOf<Tab>() }
        if (nav.tab !in phoneTabs.map { it.first }) nav.tab = Tab.HOME
        if (visited.lastOrNull() != nav.tab) { visited.remove(nav.tab); visited.add(nav.tab); while (visited.size > 4) visited.removeAt(0) }
        Box(Modifier.fillMaxSize()) {
            for (t in visited) key(t) {
                Box(if (t == nav.tab) Modifier.fillMaxSize() else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} }) {
                    when (t) {
                        Tab.HOME -> PhoneHome(nav)
                        Tab.LIVE -> PhoneLive(nav)
                        Tab.SPORTS -> Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                            TopBar(nav)
                            Box(Modifier.weight(1f).padding(bottom = 70.dp)) { TvCanvas { SportsScreen(nav) } }
                        }
                        Tab.MOVIES -> PhoneVod(nav, series = false)
                        Tab.SERIES -> PhoneVod(nav, series = true)
                        Tab.SEARCH -> PhoneSearch(nav)
                        else -> {}
                    }
                }
            }
        }
        TabBar(nav, Modifier.align(Alignment.BottomCenter))
    }
}

/** A floating capsule of tabs; the open one sits on a lighter pill in blue. */
@Composable
private fun TabBar(nav: Nav, modifier: Modifier) {
    Row(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .clip(RoundedCornerShape(36.dp)).background(Color(0xB0000000)).glass(RoundedCornerShape(36.dp), strength = 1.4f).padding(5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for ((t, icon) in phoneTabs) {
            val on = nav.tab == t
            Column(
                Modifier.weight(1f).then(if (on) Modifier.glass(RoundedCornerShape(30.dp), strength = 0.8f) else Modifier)
                    .tap { nav.tab = t }.padding(vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(icon, size = 25.dp, tint = if (on) P.blue else Color.White)
                Text(label(t), style = P.small.copy(color = if (on) P.blue else Color.White), maxLines = 1)
            }
        }
    }
}

/** Account and settings on the left, the playlist in the middle, an action on the right. */
@Composable
fun TopBar(nav: Nav, right: @Composable () -> Unit = {}) {
    val st by App.repo.state.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Rounded.Person, onClick = { nav.push(Route.Menu) })
        Spacer(Modifier.weight(1f))
        Row(Modifier.glass(RoundedCornerShape(22.dp)).tap { nav.push(Route.Menu) }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(st.source?.name ?: "NovaPlay", style = P.head, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.size(22.dp).clip(CircleShape).background(P.card2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.KeyboardArrowDown, size = 16.dp) }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { right() }
    }
    if (st.loading || st.status.isNotBlank() || st.error != null) {
        Text(st.error?.let { "Could not load: $it" } ?: st.status.ifBlank { st.epgStatus.orEmpty() },
            style = P.small.copy(color = if (st.error != null) P.red else P.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/** A page over the tabs with a round back button at its top left. */
@Composable
fun Pushed(nav: Nav, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(P.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxSize().padding(top = 56.dp)) { content() }
        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = { nav.pop() }, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun PhoneHome(nav: Nav) {
    val st by App.repo.state.collectAsState()
    val store = App.store
    val prefix = App.repo.channelKeyPrefix()
    val favChannels = remember(st.index, store.favourites) { st.index?.channelsFor(store.favourites.filter { it.startsWith(prefix) })?.sortedBy { it.number }.orEmpty() }
    val recent = remember(st.index, store.recentChannels) { st.index?.channelsFor(store.recentChannels.filter { it.startsWith(prefix) }).orEmpty() }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars), contentPadding = PaddingValues(bottom = 120.dp)) {
        item { TopBar(nav) }
        item {
            val h = io.github.novatv.play.platform.Time.hourOfDay()
            Text(when { h < 12 -> "Good morning"; h < 18 -> "Good afternoon"; else -> "Good evening" }, style = P.large, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        }
        val resume = store.continueWatching
        if (resume.isNotEmpty()) item {
            RowTitle("Continue watching", null)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                items(resume, key = { it.key }) { r ->
                    Poster(r.title, r.poster, null, progress = if (r.dur > 0) r.pos.toFloat() / r.dur else null, modifier = Modifier.width(126.dp), onClick = {
                        val m = st.lib.movies.firstOrNull { App.repo.movieKey(it) == r.key }
                        val s = st.lib.series.firstOrNull { r.key.startsWith(App.repo.seriesKey(it).replaceFirst("se:", "ep:") + ":") }
                        when { m != null -> nav.push(Route.MovieDetail(m)); s != null -> nav.push(Route.SeriesDetail(s)) }
                    })
                }
            }
        }
        if (favChannels.isNotEmpty()) item { ChannelStrip("Favourite channels", favChannels) }
        if (recent.isNotEmpty()) item { ChannelStrip("Recently watched", recent) }
        val idx = st.index
        if (idx != null) {
            item { PosterRow("New movies", idx.moviesByAdded.take(20), onOpen = { nav.push(Route.MovieDetail(it as io.github.novatv.play.data.Movie)) },
                onSeeAll = { nav.tab = Tab.MOVIES }) }
            item { PosterRow("New TV shows", idx.seriesByAdded.take(20), onOpen = { nav.push(Route.SeriesDetail(it as io.github.novatv.play.data.Series)) },
                onSeeAll = { nav.tab = Tab.SERIES }) }
        }
    }
}

@Composable
private fun ChannelStrip(title: String, list: List<Channel>) {
    RowTitle(title, null)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        items(list.size, key = { list[it].id }) { i ->
            val c = list[i]
            val p = nowNext(c).firstOrNull()
            Column(Modifier.width(150.dp).clip(RoundedCornerShape(16.dp)).background(P.card).tap { playChannel(list, i) }.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                ChannelLogo(c.logo, c.name, Modifier.size(84.dp, 56.dp))
                Spacer(Modifier.height(8.dp))
                Text(c.name, style = P.head.copy(fontSize = 14.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(p?.title ?: "No information", style = P.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ---------------------------------------------------------------- Live TV

@Composable
private fun PhoneLive(nav: Nav) {
    val st by App.repo.state.collectAsState()
    val idx = st.index
    var cat by remember { mutableStateOf("fav") }
    val chips = remember(st.lib) { listOf("fav" to "Favorites", "recent" to "Watched History") + st.lib.liveCategories.map { it.id to it.name } }
    val prefix = App.repo.channelKeyPrefix()
    val list = remember(idx, cat, App.store.favourites, App.store.recentChannels) {
        when (cat) {
            "fav" -> idx?.channelsFor(App.store.favourites.filter { it.startsWith(prefix) })?.sortedBy { it.number }
            "recent" -> idx?.channelsFor(App.store.recentChannels.filter { it.startsWith(prefix) })
            else -> idx?.channelsByCat?.get(cat)
        }.orEmpty()
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopBar(nav)
        Chips(chips, cat, { cat = it }, Modifier.padding(vertical = 8.dp))
        if (list.isEmpty()) {
            Empty(Icons.Rounded.Tv, if (cat == "fav") "No favourite channels" else "No channels here",
                if (cat == "fav") "Open a channel and tap the heart to add it." else null)
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(list.size, key = { list[it].id }) { i -> PhoneChannelRow(list[i]) { playChannel(list, i) } }
        }
    }
}

@Composable
fun PhoneChannelRow(c: Channel, onClick: () -> Unit) {
    val nn = nowNext(c)
    val p: Programme? = nn.firstOrNull()
    val now = io.github.novatv.play.ui.now()
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).tap(onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(c.logo, c.name, Modifier.size(72.dp, 50.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, style = P.head, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) {
                Text("${clock(p.start)}  ${p.title}", style = P.small.copy(color = Color(0xFFCCCCD0)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(P.card2)) {
                    Box(Modifier.fillMaxWidth(((now - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f)).height(3.dp).background(P.blue))
                }
            } else Text("No information", style = P.small)
        }
    }
}
