package io.github.novatv.play

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.github.novatv.play.data.Http
import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.Series
import io.github.novatv.play.platform.Device
import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.res.Res
import io.github.novatv.play.res.logo_mark
import io.github.novatv.play.ui.AddSourceScreen
import io.github.novatv.play.ui.Backdrop
import io.github.novatv.play.ui.BackHandler
import io.github.novatv.play.ui.C
import io.github.novatv.play.ui.DetailScreen
import io.github.novatv.play.ui.GuideScreen
import io.github.novatv.play.ui.HomeScreen
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.LeaguesScreen
import io.github.novatv.play.ui.LiveScreen
import io.github.novatv.play.ui.Pill
import io.github.novatv.play.ui.SearchScreen
import io.github.novatv.play.ui.SettingsScreen
import io.github.novatv.play.ui.SportsScreen
import io.github.novatv.play.ui.T
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.VodScreen
import io.github.novatv.play.ui.WelcomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home), LIVE("Live TV", Icons.Rounded.LiveTv), GUIDE("TV guide", Icons.Rounded.CalendarMonth),
    SPORTS("Sports", Icons.Rounded.SportsSoccer), MOVIES("Movies", Icons.Rounded.Movie), SERIES("Series", Icons.Rounded.VideoLibrary),
    SEARCH("Search", Icons.Rounded.Search), SETTINGS("Settings", Icons.Rounded.Settings),
}

/** Screens opened over a tab; Back closes them. */
sealed class Route {
    data class MovieDetail(val movie: Movie) : Route()
    data class SeriesDetail(val series: Series) : Route()
    data object AddSource : Route()
    data object Leagues : Route()
    // iPhone only.
    data class SeeAll(val title: String, val items: List<Any>) : Route()
    data object Menu : Route()
    data object Settings : Route()
    data object SearchSettings : Route()
    data object Appearance : Route()
    data class Match(val event: io.github.novatv.play.sports.SportEvent) : Route()
    data class ChannelGroup(val id: String, val name: String) : Route()
    data object Playlists : Route()
    data object EpgSources : Route()
    data object Configurations : Route()
}

/**
 * Navigation shared by the screens. Stable: its state is snapshot state, so screens taking it are skipped when nothing
 * they read changed (otherwise every focus move in the menu rebuilt every kept screen).
 */
@Stable
class Nav {
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Route>()
    val railFocus = FocusRequester()
    /** Each tab's page: Right from the menu goes to its first option, top left. */
    val pageFocus = HashMap<Tab, FocusRequester>()
    fun page(t: Tab) = pageFocus.getOrPut(t) { FocusRequester() }
    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean = if (stack.isEmpty()) false else { stack.removeAt(stack.lastIndex); true }
}

/** The whole app: the menu, the tabs and the screens opened over them. */
@Composable
fun Root() {
    setSingletonImageLoaderFactory { ctx ->
        // Logos and posters: a memory cache sized for a TV and a 250 MB disk cache.
        ImageLoader.Builder(ctx)
            .components { add(KtorNetworkFetcherFactory(httpClient = { Http.client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.20).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir() / "images").maxSizeBytes(250L * 1024 * 1024).build() }
            .build()
    }
    val nav = remember { Nav() }
    val state by App.repo.state.collectAsState()
    var railFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = nav.stack.isNotEmpty() || !railFocused) {
        if (!nav.pop()) try { nav.railFocus.requestFocus() } catch (e: Exception) { }
    }
    Box(Modifier.fillMaxSize()) {
        Backdrop()
        if (state.source == null && nav.stack.isEmpty()) {
            WelcomeScreen(nav)
            return@Box
        }
        val top = nav.stack.lastOrNull()
        if (top != null) {
            when (top) {
                is Route.MovieDetail -> DetailScreen(nav, movie = top.movie)
                is Route.SeriesDetail -> DetailScreen(nav, series = top.series)
                Route.AddSource -> AddSourceScreen(nav)
                Route.Leagues -> LeaguesScreen(nav)
                else -> {} // iPhone-only screens never open on a TV or PC
            }
            // A remote has Back; with a mouse or a finger the screen needs a button.
            if (App.device != Device.TV) Pill("Back", onClick = { nav.pop() }, icon = Icons.AutoMirrored.Rounded.ArrowBack,
                modifier = Modifier.align(Alignment.BottomStart).padding(24.dp))
            return@Box
        }
        // The rail floats over the screen: it widens over the content instead of pushing it, so opening it
        // doesn't lay the whole screen out again on every frame.
        Box(Modifier.fillMaxSize()) {
            // The last few tabs stay built, hidden, so going back to one is instant. Hidden ones stay measured (so they
            // show without laying out their rows again, which took ~90 ms) but are not placed: not drawn, not focusable;
            // only [KEEP_TABS] are kept, to stay well inside the Fire TV's memory.
            val visited = remember { mutableStateListOf<Tab>() }
            if (visited.lastOrNull() != nav.tab) {
                visited.remove(nav.tab); visited.add(nav.tab)
                while (visited.size > KEEP_TABS) visited.removeAt(0)
            }
            // Separate drawing layers for the page and the menu: the menu's focus animation then repaints only the menu.
            Box(Modifier.fillMaxSize().padding(start = 84.dp).graphicsLayer()) {
                for (t in visited) key(t) {
                    Box(if (t == nav.tab) Modifier.fillMaxSize().focusRequester(nav.page(t)) else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} }) {
                        when (t) {
                            Tab.HOME -> HomeScreen(nav)
                            Tab.LIVE -> LiveScreen(nav)
                            Tab.GUIDE -> GuideScreen(nav)
                            Tab.SPORTS -> SportsScreen(nav)
                            Tab.MOVIES -> VodScreen(nav, series = false)
                            Tab.SERIES -> VodScreen(nav, series = true)
                            Tab.SEARCH -> SearchScreen(nav)
                            Tab.SETTINGS -> SettingsScreen(nav)
                        }
                    }
                }
            }
            Rail(nav) { railFocused = it }
        }
    }
}

/** The left rail: icons only, widening to show labels while it has focus. Focusing an item opens its tab. */
@Composable
private fun Rail(nav: Nav, onFocus: (Boolean) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var pendingTab by remember { mutableStateOf<Tab?>(null) }
    val scope = rememberCoroutineScope()
    val w = if (focused) 210.dp else 84.dp
    Column(
        Modifier.graphicsLayer().width(w).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0xF0060910), if (focused) Color(0xE0060910) else Color.Transparent)))
            .onFocusChanged { if (focused != it.hasFocus) { focused = it.hasFocus; onFocus(it.hasFocus) } }
            // Right goes to the page's first option, not to whatever sits beside the menu item.
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown || k.key != Key.DirectionRight) false
                else try { nav.page(nav.tab).requestFocus(); true } catch (e: Exception) { false }
            }
            .padding(vertical = 28.dp, horizontal = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The logo, with the name beside it while the rail is open.
        Row(Modifier.padding(start = 2.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(Res.drawable.logo_mark), "NovaPlay", Modifier.size(52.dp))
            if (focused) { Spacer(Modifier.width(10.dp)); Text("NovaPlay", style = T.h2) }
        }
        for (t in Tab.entries) {
            val selected = nav.tab == t
            // A plain focusable row: no grow, border or glow animation, just an instant colour swap. The animated
            // focus effect cost 70-80 ms frames on a Fire TV every time focus moved.
            var itemFocused by remember { mutableStateOf(false) }
            val fg = when { itemFocused -> Color(0xFF0B0E14); selected -> C.accent; else -> C.dim }
            Row(
                Modifier.fillMaxWidth().height(50.dp)
                    .let { if (selected) it.focusRequester(nav.railFocus) else it }
                    .onFocusChanged {
                        itemFocused = it.isFocused
                        // Opens the tab once focus rests on it, so moving through the menu doesn't build every screen on the way.
                        if (it.isFocused) { pendingTab = t; scope.launch { delay(150); if (pendingTab == t && nav.tab != t) nav.tab = t } }
                    }
                    .clickable { nav.tab = t }
                    .background(when { itemFocused -> Color(0xF2FFFFFF); selected -> C.accent.copy(alpha = 0.22f); else -> Color.Transparent },
                        RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(t.icon, size = 24.dp, tint = fg)
                if (focused) {
                    Spacer(Modifier.width(14.dp))
                    Text(t.label, style = T.body.copy(color = if (itemFocused) fg else C.text), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(1.dp).clip(RoundedCornerShape(1.dp)))
    }
}

/** How many tabs stay built in memory (the one on screen included). */
private const val KEEP_TABS = 4
