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
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.graphicsLayer
import io.github.novatv.play.res.logo_mark
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.Tab
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Programme
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.SettingsScreen
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
        val shown = remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val opened = nav.stack.size > shown.intValue
        androidx.compose.runtime.SideEffect { shown.intValue = nav.stack.size }
        // The visited tabs stay built so switching back is instant.
        val visited = remember { mutableStateListOf<Tab>() }
        if (nav.tab !in phoneTabs.map { it.first }) nav.tab = Tab.HOME
        if (visited.lastOrNull() != nav.tab) { visited.remove(nav.tab); visited.add(nav.tab); while (visited.size > 4) visited.removeAt(0) }
        val hide = Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} }
        Box(if (top != null) hide else Modifier.fillMaxSize()) {
            for (t in visited) key(t) {
                Box(if (t == nav.tab) Modifier.fillMaxSize().tabFlow(t) else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} }) {
                    when (t) {
                        Tab.HOME -> PhoneHome(nav)
                        Tab.LIVE -> PhoneLive(nav)
                        Tab.SPORTS -> PhoneSports(nav)
                        Tab.MOVIES -> PhoneVod(nav, series = false)
                        Tab.SERIES -> PhoneVod(nav, series = true)
                        Tab.SEARCH -> PhoneSearch(nav)
                        else -> {}
                    }
                }
            }
        }
        if (top == null) TabBar(nav, Modifier.align(Alignment.BottomCenter))
        if (top != null) {
            // A page flows in from the right when it is opened, like a drop running across the screen; one opened
            // from the App Menu, or one gone back to, just appears, as the tabs do.
            val fromMenu = nav.stack.size >= 2 && nav.stack[nav.stack.size - 2] == Route.Menu
            key(nav.stack.size, top) { Box(Modifier.fillMaxSize().then(if (opened && !fromMenu) Modifier.flowIn(fromX = 0.35f) else Modifier)) { when (top) {
                is Route.MovieDetail -> PhoneDetail(nav, movie = top.movie)
                is Route.SeriesDetail -> PhoneDetail(nav, series = top.series)
                is Route.SeeAll -> SeeAll(nav, top.title, top.items)
                Route.Menu -> AppMenu(nav)
                Route.SearchSettings -> SearchSettings(nav)
                Route.Appearance -> Appearance(nav)
                Route.Settings, Route.Configurations -> PhoneConfigurations(nav)
                Route.Playlists -> PhonePlaylists(nav)
                is Route.Find -> PhoneSearch(nav, top.scope)
                Route.EpgSources -> PhoneEpgSources(nav)
                Route.AddSource -> PhoneAddSource(nav)
                Route.Leagues -> PhoneLeagues(nav)
                is Route.Match -> MatchPage(nav, top.event)
                is Route.ChannelGroup -> ChannelGroupPage(nav, top.id, top.name)
            } } }
        }
    }
}

/**
 * A floating capsule of tabs with a glass drop on the open one. Press and slide along the bar and the drop follows the
 * finger, swelling into a lens with a rainbow rim; let go and it settles on the nearest tab with a wobble. A tap
 * sends it flowing to that tab.
 */
@Composable
private fun TabBar(nav: Nav, modifier: Modifier) {
    val index = phoneTabs.indexOfFirst { it.first == nav.tab }.coerceAtLeast(0)
    val pos = remember { androidx.compose.animation.core.Animatable(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val drop = androidx.compose.animation.core.spring<Float>(dampingRatio = 0.6f, stiffness = 260f)
    androidx.compose.runtime.LaunchedEffect(index) { if (!dragging) pos.animateTo(index.toFloat(), drop) }
    val swell by androidx.compose.animation.core.animateFloatAsState(if (dragging) 1f else 0f,
        androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 300f), label = "swell")
    // While it flows between tabs it stretches by how far it still has to go.
    val stretch = if (dragging) 0f else kotlin.math.abs(index - pos.value).coerceAtMost(1f)

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().height(68.dp),
    ) {
        val itemW = maxWidth / phoneTabs.size
        val itemPx = with(androidx.compose.ui.platform.LocalDensity.current) { itemW.toPx() }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(36.dp)).background(Color(0xB0000000)).glass(RoundedCornerShape(36.dp), strength = 1.4f))
        Row(Modifier.fillMaxSize().padding(5.dp)
            .pointerInput(itemPx) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = true; scope.launch { pos.snapTo((o.x / itemPx - 0.5f).coerceIn(0f, phoneTabs.size - 1f)) } },
                    onHorizontalDrag = { ch, _ -> scope.launch { pos.snapTo((ch.position.x / itemPx - 0.5f).coerceIn(0f, phoneTabs.size - 1f)) } },
                    onDragEnd = {
                        val to = kotlin.math.round(pos.value).toInt().coerceIn(0, phoneTabs.size - 1)
                        dragging = false
                        nav.tab = phoneTabs[to].first
                        scope.launch { pos.animateTo(to.toFloat(), drop) }
                    },
                    onDragCancel = { dragging = false; scope.launch { pos.animateTo(index.toFloat(), drop) } },
                )
            }) {
            for ((t, icon) in phoneTabs) {
                val on = nav.tab == t
                Column(Modifier.weight(1f).fillMaxHeight().tap { nav.tab = t },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(icon, size = 25.dp, tint = if (on) P.blue else Color.White)
                    Text(label(t), style = P.small.copy(color = if (on) P.blue else Color.White), maxLines = 1)
                }
            }
        }
        // The drop itself, over the icons (it is clear, so they show through it).
        val w = itemW * (1f + 0.45f * stretch + 0.35f * swell)
        val x = 5.dp + itemW * pos.value - (w - itemW) / 2 - 5.dp * pos.value / (phoneTabs.size - 1) * 2
        Box(Modifier.offset(x = x, y = (-6).dp * swell).width(w).height(58.dp + 18.dp * swell).align(Alignment.CenterStart)
            .graphicsLayer { scaleY = 1f - 0.12f * stretch }
            .clip(RoundedCornerShape(34.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f + 0.06f * swell), Color.White.copy(alpha = 0.03f))))
            .border(1.5.dp, androidx.compose.ui.graphics.Brush.sweepGradient(
                listOf(Color(0x99FFFFFF), Color(0x6649D6FF).copy(alpha = 0.4f * swell + 0.15f), Color(0x66FF5AD7).copy(alpha = 0.4f * swell + 0.1f),
                    Color(0x66FFD54F).copy(alpha = 0.4f * swell + 0.1f), Color(0x99FFFFFF))), RoundedCornerShape(34.dp)))
    }
}

/** The tab now open flows in from the side it lies on (left or right of the last one), as water would. */
@Composable
private fun Modifier.tabFlow(t: Tab): Modifier {
    // Tabs just appear, with no slide.
    return this
}

/**
 * Comes in like a drop of water: from [fromX] of the width away, a little squeezed, settling with a soft spring.
 * [key] restarts it.
 */
@Composable
fun Modifier.flowIn(fromX: Float, key: Any? = Unit): Modifier {
    val a = remember(key) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(key) {
        a.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.72f, stiffness = 220f))
    }
    return graphicsLayer {
        val v = a.value
        translationX = size.width * fromX * (1f - v)
        alpha = (0.25f + 0.75f * v).coerceIn(0f, 1f)
        scaleX = 0.94f + 0.06f * v
        scaleY = 0.97f + 0.03f * v
    }
}

/** The app menu on the left, the NovaPlay logo and name in the middle, an optional action on the right. */
@Composable
fun TopBar(nav: Nav, right: @Composable () -> Unit = {}) {
    val st by App.repo.state.collectAsState()
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp).height(46.dp)) {
        RoundIcon(Icons.Rounded.Person, onClick = { nav.push(Route.Menu) }, modifier = Modifier.align(Alignment.CenterStart))
        Brand(Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.CenterEnd)) { right() }
    }
    if (st.loading || st.status.isNotBlank() || st.error != null) {
        Text(st.error?.let { "Could not load: $it" } ?: st.status.ifBlank { st.epgStatus.orEmpty() },
            style = P.small.copy(color = if (st.error != null) P.red else P.dim), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp))
    }
}

/** A page over the tabs: a round back button at its top left, the NovaPlay logo in the middle, an optional action right. */
@Composable
fun Pushed(nav: Nav, brand: Boolean = true, right: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(P.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Box(Modifier.fillMaxSize().padding(top = 62.dp)) { content() }
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).height(46.dp)) {
            RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, onClick = { nav.pop() }, modifier = Modifier.align(Alignment.CenterStart))
            if (brand) Brand(Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.CenterEnd)) { right() }
        }
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
    var tab by remember { mutableStateOf("fav") }
    val prefix = App.repo.channelKeyPrefix()
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopBar(nav) { LiveButtons(nav) }
        Chips(listOf("fav" to "Favourites", "recent" to "Watch History", "all" to "All Categories"), tab, { tab = it })
        if (tab == "all") {
            // Every channel group, top to bottom, with how many channels it holds; a group opens on its own page.
            val groups = remember(st.lib, idx) {
                st.lib.liveCategories.map { Triple(it.id, it.name, idx?.channelsByCat?.get(it.id)?.size ?: 0) }.filter { it.third > 0 }
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(groups, key = { it.first }) { (id, name, n) ->
                    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).tap { nav.push(Route.ChannelGroup(id, name)) }
                        .padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, style = P.head.copy(fontSize = 17.sp), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$n", style = P.small)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, size = 22.dp, tint = P.dim)
                    }
                }
            }
            return@Column
        }
        val list = remember(idx, tab, App.store.favourites, App.store.recentChannels) {
            if (tab == "fav") idx?.channelsFor(App.store.favourites.filter { it.startsWith(prefix) })?.sortedBy { it.number }.orEmpty()
            else idx?.channelsFor(App.store.recentChannels.filter { it.startsWith(prefix) }).orEmpty()
        }
        if (list.isEmpty()) {
            Empty(Icons.Rounded.Tv, if (tab == "fav") "No favourite channels" else "Nothing watched yet",
                if (tab == "fav") "Open a channel and tap the heart to add it." else "Channels you watch show up here.")
            return@Column
        }
        ChannelList(list)
    }
}

/** A channel group's own page: its channels, top to bottom. */
@Composable
fun ChannelGroupPage(nav: Nav, id: String, name: String) {
    val st by App.repo.state.collectAsState()
    val list = remember(st.index, id) { st.index?.channelsByCat?.get(id).orEmpty() }
    Pushed(nav, right = { LiveButtons(nav) }) {
        Column {
            PageTitle(name)
            Text("${list.size} channels", style = P.small, modifier = Modifier.padding(horizontal = 16.dp))
            ChannelList(list)
        }
    }
}

@Composable
private fun LiveButtons(nav: Nav) = Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    RoundIcon(Icons.Rounded.Search, onClick = { nav.push(Route.Find("live")) })
    LayoutButton(View.liveGrid) { View.liveGrid = it }
}

/** Channels in the chosen order, as a list of rows or a grid of logos. */
@Composable
private fun ChannelList(channels: List<Channel>) {
    val list = channels
    if (View.liveGrid) {
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list.size, key = { list[it].id }) { i ->
                val c = list[i]
                Column(Modifier.glass(RoundedCornerShape(18.dp), strength = 0.7f).tap { playChannel(list, i) }.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ChannelLogo(c.logo, c.name, Modifier.fillMaxWidth().height(56.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(c.name, style = P.small.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list.size, key = { list[it].id }) { i -> PhoneChannelRow(list[i]) { playChannel(list, i) } }
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
