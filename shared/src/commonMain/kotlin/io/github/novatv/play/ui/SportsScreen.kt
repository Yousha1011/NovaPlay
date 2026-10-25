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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.sports.Airing
import io.github.novatv.play.sports.Broadcast
import io.github.novatv.play.sports.Broadcasters
import io.github.novatv.play.sports.Follow
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.Time

private enum class SportsTab(val label: String) { LIVE("Live now"), TODAY("Today"), UPCOMING("Upcoming"), MINE("My teams"), CHANNELS("On your channels") }

fun teamKey(e: SportEvent, s: Side) = "${e.league.sport.name}|${s.id}|${s.name}"
fun isFavTeam(e: SportEvent, s: Side): Boolean = Follow.teamPicked(e, s)

@Composable
fun SportsScreen(nav: Nav) {
    val sports by App.repo.sports.collectAsState()
    val st by App.repo.state.collectAsState()
    var tab by remember { mutableStateOf(SportsTab.LIVE) }
    var sport by remember { mutableStateOf<Sport?>(null) }
    var selected by remember { mutableStateOf<SportEvent?>(null) }
    /** The match whose channels are open in a pop-up (OK on a card). */
    var popup by remember { mutableStateOf<SportEvent?>(null) }
    var favVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(st.epgVersion, st.lib.channels.size) { withContext(IO) { App.repo.matcher() } }
    // Live scores refresh every minute while the Sports centre is open.
    LaunchedEffect(Unit) { while (true) { App.repo.refreshSports(55_000L); delay(60_000L) } }

    val t = now(60_000L)
    val dayEnd = remember(t) { Time.dayStart(t) + 24 * 3600_000L - 60_000L }
    val list = remember(sports, tab, sport, favVersion, t) {
        sports.events.asSequence().filter { Follow.shows(it) }.filter { sport == null || it.league.sport == sport }.filter { e ->
            when (tab) {
                SportsTab.LIVE -> e.state == State.LIVE
                SportsTab.TODAY -> e.start in (t - 12 * 3600_000L)..dayEnd && (e.state != State.POST || e.start > t - 6 * 3600_000L)
                SportsTab.UPCOMING -> e.state == State.PRE && e.start > t
                SportsTab.MINE -> e.teams.any { isFavTeam(e, it) } && e.start > t - 24 * 3600_000L
                SportsTab.CHANNELS -> false
            }
        }.toList()
    }
    // Lead with what has started, then soonest first.
    val shown = remember(list, tab) { if (tab == SportsTab.UPCOMING) list else list.sortedWith(compareBy({ it.state != State.LIVE }, { it.start })) }
    val present = remember(sports) { sports.events.filter { Follow.shows(it) }.map { it.league.sport }.toSet() }

    Row(Modifier.fillMaxSize().padding(start = 28.dp, top = 30.dp, end = 30.dp)) {
        Column(Modifier.weight(1f)) {
            Header("Sports", if (sports.loading && sports.events.isEmpty()) "Loading matches…"
                else "${sports.events.count { it.state == State.LIVE }} live · updated ${if (sports.updatedAt > 0) clock(sports.updatedAt) else "–"}") {
                Pill("Choose sports", onClick = { nav.push(Route.Leagues) }, icon = Icons.Rounded.Tune)
                Pill("Refresh", onClick = { App.repo.refreshSports(0) }, icon = Icons.Rounded.Refresh)
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                items(SportsTab.entries.toList(), key = { it.name }) { s -> Pill(s.label, onClick = { tab = s }, selected = s == tab) }
            }
            if (tab != SportsTab.CHANNELS) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                item { Pill("Choose sports", onClick = { nav.push(Route.Leagues) }, icon = Icons.Rounded.Tune) }
                item { Pill("All sports", onClick = { sport = null }, selected = sport == null) }
                items(Sport.entries.filter { it in present }, key = { it.name }) { s -> Pill(s.label, onClick = { sport = s }, selected = sport == s) }
            }
            when {
                tab == SportsTab.CHANNELS -> GuideSports(st.epgVersion)
                sports.loading && sports.events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
                shown.isEmpty() -> Message(
                    when (tab) { SportsTab.LIVE -> "Nothing live right now"; SportsTab.MINE -> "No matches for your teams"; else -> "No matches" },
                    when (tab) { SportsTab.MINE -> "Open a match and star a team to follow it."; SportsTab.LIVE -> "Try Today or Upcoming."; else -> null })
                else -> LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(bottom = 50.dp, end = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(shown, key = { it.id }) { e ->
                        EventCard(e, onClick = { selected = e; popup = e }, fill = true, favourite = e.teams.any { isFavTeam(e, it) },
                            modifier = Modifier.onFocusChanged { if (it.isFocused) selected = e })
                    }
                }
            }
        }
        if (tab != SportsTab.CHANNELS) {
            Spacer(Modifier.width(22.dp))
            EventPanel(selected?.let { s -> sports.events.firstOrNull { it.id == s.id } ?: s }, st.epgVersion) { favVersion++ }
        }
    }
    popup?.let { e -> ChannelsPopup(e, st.epgVersion) { popup = null } }
}

/** Every channel showing [e], over the Sports centre: Up/Down to pick, OK to play, Back to close. */
@Composable
private fun ChannelsPopup(e: SportEvent, epgVersion: Int, onDismiss: () -> Unit) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    val close = remember { androidx.compose.ui.focus.FocusRequester() }
    val airings by produceState<List<Airing>?>(null, e.id, epgVersion) {
        value = withContext(IO) { App.repo.matcher().airings(e, limit = 60) }
        val more = withContext(IO) { App.repo.matcher().airings(e, Broadcasters.forEvent(e), limit = 60) }
        // Keep what is already listed in place, so the row under the cursor doesn't move.
        value = (value.orEmpty() + more).distinctBy { it.channel.id }
    }
    var focused by remember { mutableStateOf(false) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(760.dp).fillMaxHeight(0.86f).clip(RoundedCornerShape(22.dp)).background(C.panel).padding(28.dp)) {
            Text(listOfNotNull(e.league.name, e.subtitle).distinct().joinToString(" · "), style = T.tiny)
            Spacer(Modifier.height(6.dp))
            Text(e.title, style = T.h1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(if (e.state == State.LIVE) e.detail else "${dayLabel(e.start)} ${clock(e.start)}",
                style = T.small.copy(color = if (e.state == State.LIVE) C.live else C.dim))
            Spacer(Modifier.height(18.dp))
            SectionTitle("Watch on your channels", trailing = airings?.size?.takeIf { it > 0 }?.let { "$it found" })
            when {
                airings == null -> Spinner(size = 28.dp)
                airings!!.isEmpty() -> {
                    Text("Not found in your playlist's guide or channel names.", style = T.small)
                    Spacer(Modifier.height(16.dp))
                    Pill("Close", onClick = onDismiss, modifier = Modifier.focusRequester(close))
                    LaunchedEffect(Unit) { repeat(5) { try { close.requestFocus(); return@LaunchedEffect } catch (ex: Exception) { delay(60) } } }
                }
                else -> {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(airings!!.size) { i ->
                            AiringRow(airings!![i], if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                onDismiss(); playChannel(airings!!.map { it.channel }, i)
                            }
                        }
                    }
                    LaunchedEffect(Unit) { if (!focused) repeat(5) { try { first.requestFocus(); focused = true; return@LaunchedEffect } catch (ex: Exception) { delay(60) } } }
                }
            }
        }
    }
}

/** The selected match: score, status and where to watch it in your playlist. */
@Composable
private fun EventPanel(e: SportEvent?, epgVersion: Int, modifier: Modifier = Modifier, onFavChanged: () -> Unit) {
    Column(modifier.width(470.dp).fillMaxHeight().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(C.panel.copy(alpha = 0.7f)).padding(24.dp)) {
        if (e == null) { Message("Pick a match", "Where it airs on your channels shows here."); return@Column }
        Text(listOfNotNull(e.league.name, e.subtitle).distinct().joinToString(" · "), style = T.tiny)
        Spacer(Modifier.height(14.dp))
        if (e.home != null && e.away != null) {
            val long = (e.home.score?.length ?: 0) > 4 || (e.away.score?.length ?: 0) > 4
            val scored = e.state != State.PRE
            Row(verticalAlignment = Alignment.CenterVertically) {
                Team(e.home, Modifier.weight(1f), if (scored && long) e.home.score else null)
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(110.dp)) {
                    when {
                        !scored -> Text(clock(e.start), style = T.h1)
                        long -> Text("vs", style = T.h2.copy(color = C.dim))
                        else -> Text("${e.home.score ?: "-"} : ${e.away.score ?: "-"}", style = T.h1)
                    }
                    Text(if (!scored) dayLabel(e.start) else e.detail, style = T.small.copy(color = if (e.state == State.LIVE) C.live else C.dim),
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Team(e.away, Modifier.weight(1f), if (scored && long) e.away.score else null)
            }
        } else {
            Text(e.title, style = T.h1)
            Text("${dayLabel(e.start)} ${clock(e.start)}  ${e.detail}", style = T.small)
        }
        e.venue?.let { Spacer(Modifier.height(8.dp)); Text(it, style = T.small, maxLines = 1) }
        // Who shows it: TheSportsDB and SportMonks (with your keys) and ESPN, your country first.
        val tv by produceState<List<Broadcast>?>(null, e.id) { delay(250); value = withContext(IO) { Broadcasters.forEvent(e) } }
        tv?.takeIf { it.isNotEmpty() }?.let { list ->
            Spacer(Modifier.height(6.dp))
            Text("TV: " + list.take(6).joinToString(", ") { b -> b.channel + (b.country?.let { " ($it)" } ?: "") }, style = T.small, maxLines = 3,
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(18.dp))
        var favs by remember { mutableIntStateOf(0) }
        if (e.teams.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (s in e.teams) {
                val fav = favs.let { isFavTeam(e, s) }
                Pill((if (fav) "★ " else "☆ ") + s.short, onClick = {
                    val k = teamKey(e, s)
                    val on = isFavTeam(e, s)
                    favs++
                    App.store.favouriteTeams = if (on) App.store.favouriteTeams.filterNot { it.endsWith("|${s.name}") || it == k }.toSet() else App.store.favouriteTeams + k
                    onFavChanged()
                }, selected = fav)
            }
        }
        Spacer(Modifier.height(18.dp))
        SectionTitle("Watch on your channels")
        val airings by produceState<List<Airing>?>(null, e.id, epgVersion, tv) {
            if (value == null) delay(250)
            if (value == null) value = withContext(IO) { App.repo.matcher().airings(e) }
            val b = tv ?: return@produceState
            value = withContext(IO) { App.repo.matcher().airings(e, b) }
        }
        when {
            airings == null -> Spinner(size = 24.dp)
            airings!!.isEmpty() -> Text(if (App.repo.epg.hasData) "Not found in your playlist's guide or channel names." else
                "Add a TV guide to your playlist to find matches on your channels.", style = T.small)
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(airings!!.size) { i -> AiringRow(airings!![i]) { playChannel(airings!!.map { it.channel }, i) } }
            }
        }
    }
}

@Composable
private fun Team(s: Side, modifier: Modifier, score: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
            if (s.logo != null) AsyncImage(s.logo, null, Modifier.size(60.dp)) else Text(initials(s.name), style = T.h1.copy(color = C.dim))
        }
        Spacer(Modifier.height(6.dp))
        Text(s.short, style = T.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (score != null) Text(score, style = T.h2, maxLines = 2)
    }
}

@Composable
private fun AiringRow(a: Airing, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Tile(onClick, modifier.fillMaxWidth().height(64.dp), shape = RoundedCornerShape(10.dp), scale = 1.02f, color = C.panelHi) {
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(a.channel.logo, a.channel.name, Modifier.size(64.dp, 42.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.channel.name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(a.programme?.let { "${clock(it.start)} ${it.title}" } ?: a.why, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (a.programme?.isLive == true) Badge("LIVE")
        }
    }
}

/** Sport on the user's channels now and in the next three hours, straight from the TV guide. */
@Composable
private fun GuideSports(epgVersion: Int) {
    val list by produceState<List<Airing>?>(null, epgVersion) {
        value = withContext(IO) { App.repo.matcher().guideSports(nowMs() - 3600_000L, nowMs() + 3 * 3600_000L) }
    }
    when {
        list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        list!!.isEmpty() -> Message("No sport in your guide right now", if (App.repo.epg.hasData) "Check back later." else "This playlist has no TV guide.")
        else -> LazyVerticalGrid(GridCells.Fixed(2), contentPadding = PaddingValues(bottom = 50.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list!!.size) { i -> AiringRow(list!![i]) { playChannel(list!!.map { it.channel }, i) } }
        }
    }
}

/**
 * Choose sports: tick the sports to list on the left; for the focused one, pick competitions (by country), cricket or
 * rugby series and clubs on the right. Nothing picked within a sport means every match of it.
 */
@Composable
fun LeaguesScreen(nav: Nav) {
    var v by remember { mutableIntStateOf(0) }
    var cur by remember { mutableStateOf(Sport.FOOTBALL) }
    val store = App.store
    // Opens on the first sport in the list.
    val firstSport = remember { androidx.compose.ui.focus.FocusRequester() }
    AutoFocus(firstSport)
    Row(Modifier.fillMaxSize().padding(start = 50.dp, top = 36.dp, end = 40.dp)) {
        Column(Modifier.width(330.dp)) {
            Header("Choose sports", "OK turns a sport on or off")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
                items(Sport.entries.toList(), key = { it.name }) { sp ->
                    val on = remember(v) { Follow.sportOn(sp) }
                    val picks = remember(v) { Follow.hasPicks(sp) }
                    Tile({
                        val all = Sport.entries.map { it.name }.toSet()
                        val now = store.sportsEnabled.ifEmpty { all }
                        val next = if (sp.name in now) now - sp.name else now + sp.name
                        // Every sport ticked is stored as "all", so sports added later are on too.
                        store.sportsEnabled = if (next == all) emptySet() else next
                        v++
                    }, Modifier.fillMaxWidth().height(58.dp).let { if (sp == Sport.entries.first()) it.focusRequester(firstSport) else it }
                        .onFocusChanged { if (it.isFocused) cur = sp },
                        shape = RoundedCornerShape(12.dp), scale = 1.02f, color = if (sp == cur) C.panelHi else C.panel) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (on) "✓" else "", style = T.h2.copy(color = C.accent), modifier = Modifier.width(30.dp))
                            Column(Modifier.weight(1f)) {
                                Text(sp.label, style = T.body.copy(color = if (on) C.text else C.faint))
                                Text(if (!on) "Off" else if (picks) "Your picks" else "Everything", style = T.small)
                            }
                        }
                    }
                }
                item {
                    Pill("Done", onClick = { App.repo.refreshSports(0); nav.pop() }, primary = true, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        Spacer(Modifier.width(30.dp))
        SportPicks(cur, v) { v++ }
    }
}

/** Competitions (by country), series and clubs of [sport] to pick. */
@Composable
private fun SportPicks(sport: Sport, v: Int, changed: () -> Unit) {
    val store = App.store
    val events by produceState<List<SportEvent>?>(null, sport) { value = App.repo.sportCatalog(sport) }
    val leagues = remember(sport) { Catalog.leagues.filter { it.sport == sport && it.header == null } }
    val byCountry = remember(leagues) { leagues.groupBy { it.country } }
    // Series and tournaments inside one feed: cricket, rugby, tennis.
    val comps = remember(events) {
        events.orEmpty().filter { it.league.header != null || it.league.sport == Sport.TENNIS }.mapNotNull { it.subtitle }.distinct().sorted()
    }
    // Clubs by competition, from the next two weeks of matches.
    val clubs = remember(events) {
        events.orEmpty().flatMap { e -> e.teams.map { (e.subtitle ?: e.league.name) to (e to it) } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.distinctBy { it.second.name }.sortedBy { it.second.name } }
            .entries.sortedBy { it.key }.associate { it.key to it.value }
    }
    // Cricket by country: the national side, then that country's own competitions.
    val cricket = remember(events, sport) {
        if (sport != Sport.CRICKET) emptyList() else {
            val ev = events.orEmpty()
            val sides = ev.flatMap { it.teams }.mapNotNull { io.github.novatv.play.sports.Cricket.nation(it.name) }.toSet()
            val homes = ev.mapNotNull { e -> e.subtitle?.let { c -> io.github.novatv.play.sports.Cricket.leagueHome(c, e.teams.map { it.name })?.let { it to c } } }
                .distinct().groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.sorted() }
            val rest = (sides + homes.keys).filter { it !in io.github.novatv.play.sports.Cricket.major && it != "Other" }.sorted()
            (io.github.novatv.play.sports.Cricket.major + rest + listOf("Other").filter { it in homes })
                .map { Triple(it, it != "Other", homes[it].orEmpty()) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Header(sport.label, if (!Follow.sportOn(sport)) "This sport is off. Turn it on with OK on the left."
            else "Nothing picked: every ${sport.label.lowercase()} match. Pick competitions, countries or clubs to narrow it.") {
            Pill("Clear picks", onClick = {
                store.sportsLeagues = store.sportsLeagues - leagues.map { it.id }.toSet()
                store.sportsComps = store.sportsComps.filterNot { it.startsWith(sport.name + "|") }.toSet()
                store.favouriteTeams = store.favouriteTeams.filterNot { it.startsWith(sport.name + "|") }.toSet()
                changed()
            })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 60.dp)) {
            if (leagues.isNotEmpty()) item { SectionTitle("Competitions") }
            for ((country, ls) in byCountry) {
                item(key = "c:$country") {
                    val all = remember(v) { ls.all { it.id in store.sportsLeagues } }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (byCountry.size > 1) item {
                            Pill((if (all) "✓ " else "") + "All of $country", selected = all, onClick = {
                                val ids = ls.map { it.id }.toSet()
                                store.sportsLeagues = if (all) store.sportsLeagues - ids else store.sportsLeagues + ids
                                changed()
                            })
                        }
                        items(ls, key = { it.id }) { l ->
                            val on = remember(v) { l.id in store.sportsLeagues }
                            Pill((if (on) "✓ " else "") + l.name, selected = on, onClick = {
                                store.sportsLeagues = if (on) store.sportsLeagues - l.id else store.sportsLeagues + l.id
                                changed()
                            })
                        }
                    }
                }
            }
            if (events == null) item {
                Row(verticalAlignment = Alignment.CenterVertically) { Spinner(size = 22.dp); Spacer(Modifier.width(10.dp)); Text("Loading clubs and series…", style = T.small) }
            }
            if (sport == Sport.CRICKET) {
                item { SectionTitle("International tournaments", Modifier.padding(top = 10.dp), trailing = "ICC events, cups and trophies") }
                item(key = "intl:all") {
                    val on = remember(v) { io.github.novatv.play.sports.Cricket.ALL_INTERNATIONAL in store.sportsComps }
                    Pill((if (on) "✓ " else "") + "All international tournaments", selected = on, onClick = {
                        val k = io.github.novatv.play.sports.Cricket.ALL_INTERNATIONAL
                        store.sportsComps = if (on) store.sportsComps - k else store.sportsComps + k
                        changed()
                    })
                }
                val groups = io.github.novatv.play.sports.Cricket.tournaments.map { it.group to it.name }.distinct().groupBy({ it.first }, { it.second })
                for ((group, names) in groups) item(key = "intl:$group") {
                    Column {
                        Text(group, style = T.body, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(names, key = { it }) { n ->
                                val k = io.github.novatv.play.sports.Cricket.tournamentKey(n)
                                val on = remember(v) { k in store.sportsComps }
                                Pill((if (on) "✓ " else "") + n, selected = on, onClick = {
                                    store.sportsComps = if (on) store.sportsComps - k else store.sportsComps + k
                                    changed()
                                })
                            }
                        }
                    }
                }
            }
            if (cricket.isNotEmpty()) {
                item { SectionTitle("Countries", Modifier.padding(top = 10.dp), trailing = "national team, then leagues") }
                for ((country, national, comps2) in cricket) item(key = "cr:$country") {
                    Column {
                        Text(country, style = T.body, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            val nKey = io.github.novatv.play.sports.Cricket.nationKey(country)
                            val lKey = io.github.novatv.play.sports.Cricket.leaguesKey(country)
                            // The whole country: its national side and all its leagues.
                            if (national) item {
                                val both = remember(v) { nKey in store.sportsComps && lKey in store.sportsComps }
                                Pill((if (both) "✓ " else "") + "All of $country", selected = both, onClick = {
                                    store.sportsComps = if (both) store.sportsComps - nKey - lKey else store.sportsComps + nKey + lKey
                                    changed()
                                })
                            }
                            if (national) item {
                                val on = remember(v) { nKey in store.sportsComps }
                                Pill((if (on) "✓ " else "") + "National team", selected = on, onClick = {
                                    store.sportsComps = if (on) store.sportsComps - nKey else store.sportsComps + nKey
                                    changed()
                                })
                            }
                            item {
                                val on = remember(v) { lKey in store.sportsComps }
                                Pill((if (on) "✓ " else "") + "All leagues", selected = on, onClick = {
                                    store.sportsComps = if (on) store.sportsComps - lKey else store.sportsComps + lKey
                                    changed()
                                })
                            }
                            items(comps2, key = { it }) { c ->
                                val key = "${sport.name}|$c"
                                val on = remember(v) { key in store.sportsComps }
                                Pill((if (on) "✓ " else "") + c, selected = on, onClick = {
                                    store.sportsComps = if (on) store.sportsComps - key else store.sportsComps + key
                                    changed()
                                })
                            }
                        }
                    }
                }
            }
            if (sport != Sport.CRICKET && comps.isNotEmpty()) {
                item { SectionTitle(if (sport == Sport.TENNIS) "Tournaments" else "Series and tournaments", Modifier.padding(top = 10.dp)) }
                items(comps.chunked(3)) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (c in row) {
                            val key = "${sport.name}|$c"
                            val on = remember(v) { key in store.sportsComps }
                            Pill((if (on) "✓ " else "") + c, selected = on, onClick = {
                                store.sportsComps = if (on) store.sportsComps - key else store.sportsComps + key
                                changed()
                            })
                        }
                    }
                }
            }
            if (sport != Sport.CRICKET && clubs.isNotEmpty()) item {
                SectionTitle(if (sport == Sport.TENNIS) "Players" else "Clubs and teams", Modifier.padding(top = 10.dp), trailing = "playing in the next two weeks")
            }
            if (sport != Sport.CRICKET) for ((comp, list) in clubs) {
                item(key = "t:$comp") { Text(comp, style = T.small, modifier = Modifier.padding(top = 6.dp)) }
                items(list.chunked(4), key = { r -> "t:$comp:" + r.first().second.name }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((e, side) in row) {
                            val on = remember(v) { Follow.teamPicked(e, side) }
                            Pill((if (on) "★ " else "") + side.name, selected = on, onClick = {
                                store.favouriteTeams = if (on) store.favouriteTeams.filterNot { it.endsWith("|${side.name}") || it == teamKey(e, side) }.toSet()
                                    else store.favouriteTeams + teamKey(e, side)
                                changed()
                            })
                        }
                    }
                }
            }
        }
    }
}
