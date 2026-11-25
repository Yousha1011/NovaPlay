package io.github.novatv.play.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsSoccer
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.platform.IO
import io.github.novatv.play.sports.Airing
import io.github.novatv.play.sports.Broadcast
import io.github.novatv.play.sports.Broadcasters
import io.github.novatv.play.sports.Follow
import io.github.novatv.play.sports.icon
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import io.github.novatv.play.ui.Icon
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.clock
import io.github.novatv.play.ui.dayLabel
import io.github.novatv.play.ui.initials
import io.github.novatv.play.ui.isFavTeam
import io.github.novatv.play.ui.now
import io.github.novatv.play.ui.playChannel
import io.github.novatv.play.ui.teamKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn

private enum class When(val label: String) { LIVE("Live"), UPCOMING("Upcoming"), UNCATEGORIZED("Uncategorized"), TABLES("Tables") }

/**
 * Sports, in the iPhone's style. Live (on now, or starting within the hour) and Upcoming (later), each in sections: a
 * sport (its icon, title between lines, folded away with a tap) and under it each competition (its logo, a handshake
 * for friendlies) with its matches. What is listed: the competitions followed (narrowed to their picked teams), with
 * the games the TV guide names only by competition among them; with nothing followed, every match.
 */
@Composable
fun PhoneSports(nav: Nav) {
    val sports by App.repo.sports.collectAsState()
    val st by App.repo.state.collectAsState()
    var whenTab by remember { mutableStateOf(When.LIVE) }
    var sport by remember { mutableStateOf<Sport?>(null) }
    var query by remember { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(emptySet<String>()) }
    var foldedComps by remember { mutableStateOf(io.github.novatv.play.App.store.foldedComps.toSet()) }
    LaunchedEffect(Unit) { while (true) { App.repo.refreshSports(55_000L); delay(60_000L) } }
    // The addons' streams of the live matches, checked ahead, so a match's page lists them at once.
    LaunchedEffect(sports.events.count { it.state == State.LIVE }) {
        val live = sports.events.filter { it.state == State.LIVE }
        withContext(IO) { runCatching { io.github.novatv.play.data.Addons.prefetch(live) } }
    }
    // The channel index is built ahead, so a match's channels open at once.
    LaunchedEffect(st.epgVersion, st.lib.channels.size) { withContext(IO) { App.repo.matcher(); io.github.novatv.play.data.ChannelVersions.prepare(st.lib.channels) } }
    val t = now(60_000L)
    val store = App.store
    val pool = remember(sports, store.sportsLeagues, store.sportsComps, store.sportsLeagueTeams) {
        val all = sports.events
        if (Follow.followedCompetitions().isEmpty()) all.filter { Follow.shows(it) } else all.filter { Follow.followed(it) }
    }
    // Checked again as soon as the guide has (re)loaded, and every 10 minutes. Not after each part while it loads:
    // each check searches the guide for every followed competition, which held up Sports' channel lookups on a TV.
    val guideState = App.repo.state.collectAsState().value
    val guideVersion = if (guideState.epgStatus == null) guideState.epgVersion else -1
    val unidentified by produceState(emptyList<io.github.novatv.play.sports.UnidentifiedGame>(), sports.events.size, store.sportsLeagues, store.sportsComps, guideVersion, t / 600_000L) {
        value = withContext(IO) {
            try {
                val m = App.repo.matcher()
                m.unidentifiedAll(Follow.competitions(), sports.events, t - 3 * 3600_000L, t + 7 * 86_400_000L)
            } catch (x: Exception) { emptyList() }
        }
    }
    // Live and Upcoming list identified matches only; the guide's unnamed games are in Uncategorized.
    val unknown = remember(unidentified, t) { unidentified.map { it.asEvent(t) } }
    val all = remember(pool, unknown) { pool + unknown }
    val list = remember(pool, unknown, whenTab, sport, t, query) {
        val l = when (whenTab) {
            When.LIVE -> io.github.novatv.play.sports.MySports.live(pool, t)
            When.UNCATEGORIZED -> io.github.novatv.play.sports.MySports.uncategorized(unknown, t)
            else -> io.github.novatv.play.sports.MySports.upcoming(pool, t)
        }
        l.filter { (sport == null || it.league.sport == sport) && io.github.novatv.play.ui.matchesSearch(it, query) }
    }
    val groups = remember(list) { io.github.novatv.play.sports.MySports.grouped(list) }
    val present = remember(all) { all.map { it.league.sport }.toSet() }
    /** OK on a match: its page; a game the guide only names by competition has none, so its channel plays. */
    fun open(e: SportEvent) {
        if (e.id.startsWith("guide:")) unidentified.firstOrNull { "guide:${it.league.id}:${it.start}" == e.id }?.channels?.firstOrNull()?.let { playChannel(listOf(it), 0) }
        else nav.push(Route.Match(e))
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopBar(nav) { RoundIcon(Icons.Rounded.Tune, onClick = { nav.push(Route.Leagues) }) }
        Chips(When.entries.map { w -> w.name to w.label + when (w) {
            When.LIVE -> " · ${io.github.novatv.play.sports.MySports.live(pool, t).size}"
            When.UPCOMING -> " · ${io.github.novatv.play.sports.MySports.upcoming(pool, t).size}"
            When.UNCATEGORIZED -> " · ${io.github.novatv.play.sports.MySports.uncategorized(unknown, t).size}"
            else -> ""
        } }, whenTab.name, { whenTab = When.valueOf(it) })
        // Search: a competition (or tour) or a team, in the list shown.
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(P.card)
            .border(1.dp, P.line, RoundedCornerShape(22.dp)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, size = 20.dp, tint = P.dim)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search competition or team", style = P.body.copy(color = P.dim))
                androidx.compose.foundation.text.BasicTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, textStyle = P.body,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(P.blue))
            }
            if (query.isNotEmpty()) Icon(Icons.Rounded.Close, size = 20.dp, tint = P.dim, modifier = Modifier.tap { query = "" })
        }
        Chips(listOf("ALL" to "All sports") + Sport.entries.filter { it in present }.map { it.name to "${it.icon} ${it.label}" }, sport?.name ?: "ALL",
            { sport = if (it == "ALL") null else Sport.valueOf(it) })
        when (whenTab) {
            When.TABLES -> Box(Modifier.fillMaxSize().padding(horizontal = 16.dp)) { io.github.novatv.play.ui.SportsTablesView() }
            else -> when {
                sports.loading && sports.events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { io.github.novatv.play.ui.Spinner() }
                list.isEmpty() && query.isNotBlank() -> Empty(Icons.Rounded.Search, "No competition or team matches \"${query.trim()}\"", null)
                list.isEmpty() -> Empty(Icons.Rounded.SportsSoccer, when (whenTab) { When.LIVE -> "Nothing live or about to start"
                        When.UNCATEGORIZED -> "No uncategorized games"; else -> "Nothing coming up" },
                    if (Follow.followedCompetitions().isEmpty()) "Follow competitions (top right) to list only theirs." else null)
                else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (sg in groups) {
                        val folded = sg.sport.name in collapsed
                        item(key = "s:${sg.sport.name}") {
                            SportHeader(sg, folded) { collapsed = if (folded) collapsed - sg.sport.name else collapsed + sg.sport.name }
                        }
                        if (!folded) for (cg in sg.comps) {
                            val byDefault = whenTab != When.LIVE
                            val foldKey = io.github.novatv.play.ui.compFoldKey(sg.sport.name, cg.name, byDefault)
                            val compFolded = io.github.novatv.play.ui.isCompFolded(foldKey, foldedComps, byDefault)
                            item(key = "c:${sg.sport.name}:${cg.name}") {
                                CompHeader(cg, compFolded) {
                                    foldedComps = if (foldKey in foldedComps) foldedComps - foldKey else foldedComps + foldKey
                                    io.github.novatv.play.App.store.foldedComps = foldedComps.toList()
                                }
                            }
                            if (!compFolded) items(cg.games, key = { it.id }) { e -> MatchCard(e) { open(e) } }
                        }
                    }
                }
            }
        }
    }
}

/** A sport's heading: its icon and name, with its match count, centred between fine lines; a tap folds it away. */
@Composable
private fun SportHeader(sg: io.github.novatv.play.sports.MySports.SportGroup, folded: Boolean, onToggle: () -> Unit) {
    val blue = Color(0xFF4DA3FF)
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).tap(onToggle), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, blue.copy(alpha = 0.7f)))))
        Row(Modifier.padding(horizontal = 10.dp).clip(RoundedCornerShape(50)).background(Color(0xFF0E1015))
            .border(1.dp, blue.copy(alpha = 0.55f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(sg.sport.icon, style = P.small.copy(fontSize = 16.sp))
            Spacer(Modifier.width(6.dp))
            Text(sg.sport.label.uppercase(), style = P.head.copy(fontSize = 14.sp, letterSpacing = 1.4.sp))
            Spacer(Modifier.width(8.dp))
            Text("${sg.count}", style = P.small.copy(color = blue))
            Spacer(Modifier.width(6.dp))
            Text(if (folded) "▾" else "▴", style = P.small.copy(color = blue))
        }
        Box(Modifier.weight(1f).height(1.dp).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(blue.copy(alpha = 0.7f), Color.Transparent))))
    }
}

/** A competition's heading: its logo (a handshake for friendlies, the sport's icon with no logo), name and count. */
@Composable
private fun CompHeader(cg: io.github.novatv.play.sports.MySports.CompGroup, folded: Boolean, onToggle: () -> Unit) {
    val logo = if (cg.friendly) null else io.github.novatv.play.sports.Espn.leagueLogo(cg.league.id)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(Color(0x0DFFFFFF)).tap(onToggle)
        .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(0x22FFFFFF)), contentAlignment = Alignment.Center) {
            when {
                cg.friendly -> Text("\uD83E\uDD1D", style = P.small.copy(fontSize = 15.sp))
                logo != null -> AsyncImage(logo, cg.name, Modifier.size(20.dp), contentScale = ContentScale.Fit)
                else -> Text(cg.league.sport.icon, style = P.small.copy(fontSize = 14.sp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(cg.name, style = P.head.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text("${cg.games.size}", style = P.small)
        Spacer(Modifier.width(8.dp))
        Text(if (folded) "▾" else "▴", style = P.small.copy(color = Color(0xFF4DA3FF)))
    }
}

/** A match: its competition and time or minute on top, then each side with its logo and score. */
@Composable
private fun MatchCard(e: SportEvent, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), strength = 0.75f).tap(onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((e.subtitle ?: e.league.name).uppercase(), style = P.caps,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (e.teams.any { isFavTeam(e, it) }) { Icon(Icons.Rounded.Star, size = 16.dp, tint = P.gold); Spacer(Modifier.width(6.dp)) }
            StateTag(e)
        }
        Spacer(Modifier.height(12.dp))
        if (e.home != null && e.away != null) {
            SideLine(e.home, e.state)
            Spacer(Modifier.height(8.dp))
            SideLine(e.away, e.state)
        } else {
            Text(e.title, style = P.title.copy(fontSize = 19.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            e.venue?.let { Text(it, style = P.small, maxLines = 1) }
        }
    }
}

@Composable
private fun StateTag(e: SportEvent) {
    when (e.state) {
        State.LIVE -> Text(if (e.detail.isBlank() || e.detail.equals("live", true)) "LIVE" else "LIVE · ${e.detail}", style = P.small.copy(color = Color.White, fontSize = 12.sp),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFF3B4E)).padding(horizontal = 8.dp, vertical = 3.dp))
        State.POST -> Text(e.detail.ifBlank { "Final" }, style = P.small)
        // TOMORROW in yellow (as LIVE is red), otherwise the date; times from tomorrow on as AM/PM.
        State.PRE -> {
            val t = now(60_000L)
            val tag = io.github.novatv.play.sports.MySports.tag(e, t)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tag == io.github.novatv.play.sports.MySports.Tag.TOMORROW)
                    Text("TOMORROW", style = P.small.copy(color = Color(0xFF14171E), fontSize = 12.sp),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFFFFD31A)).padding(horizontal = 8.dp, vertical = 3.dp))
                else if (tag == io.github.novatv.play.sports.MySports.Tag.TODAY)
                    Text("TODAY", style = P.small.copy(color = Color.White, fontSize = 12.sp),
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2EBD5F)).padding(horizontal = 8.dp, vertical = 3.dp))
                else Text(io.github.novatv.play.sports.MySports.tagText(e, t), style = P.small.copy(color = P.blue))
                Spacer(Modifier.width(6.dp))
                Text(io.github.novatv.play.sports.MySports.timeText(e, t), style = P.small.copy(color = P.blue))
            }
        }
    }
}

@Composable
private fun SideLine(s: Side, state: State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(s, 30.dp)
        Spacer(Modifier.width(12.dp))
        Text(s.name, style = P.head.copy(fontSize = 17.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (state != State.PRE && s.score != null) Text(s.score, style = if (s.score.length > 5) P.small.copy(color = Color.White) else P.title.copy(fontSize = 22.sp), maxLines = 1)
    }
}

@Composable
private fun TeamLogo(s: Side, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0x14FFFFFF)), contentAlignment = Alignment.Center) {
        // The initials until the logo arrives, and for good when there is none or it will not load.
        var loaded by remember(s.logo) { mutableStateOf(false) }
        if (!loaded) Text(initials(s.name), style = if (size > 40.dp) P.head else P.small.copy(fontSize = 11.sp, color = Color.White))
        if (s.logo != null) AsyncImage(s.logo, s.name, Modifier.size(size * 0.82f), contentScale = ContentScale.Fit,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

/** A match's own page: the score, who shows it, and every channel in the playlist showing it. */
@Composable
fun MatchPage(nav: Nav, start: SportEvent) {
    val sports by App.repo.sports.collectAsState()
    val e = sports.events.firstOrNull { it.id == start.id } ?: start
    val st by App.repo.state.collectAsState()
    var favs by remember { mutableIntStateOf(0) }
    val tv by produceState<List<Broadcast>?>(null, e.id) { value = withContext(IO) { Broadcasters.forEvent(e) } }
    val apps by produceState(emptyList<io.github.novatv.play.sports.OfficialApps.Service>(), e.id) {
        value = withContext(IO) { io.github.novatv.play.sports.OfficialApps.forEvent(e) }
    }
    // What is known locally first, then the online broadcasters added below it.
    // The user's Stremio addons: their streams of this match, in their own section, each listed as its check passes
    // (the streams themselves are stored by the background update), without waiting for your channels.
    val fromAddons by produceState(io.github.novatv.play.data.Addons.known(e).orEmpty(), e.id) {
        try {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                io.github.novatv.play.data.Addons.airingsFlow(e).flowOn(IO).collect { l: List<Airing> -> value = l }
            }
        } catch (x: Exception) { }
    }
    // Both sides' form and the league table around them, as on the TV's match page.
    val homePage by produceState<io.github.novatv.play.sports.TeamPage?>(null, e.id) {
        value = e.home?.let { s -> withContext(IO) { runCatching { io.github.novatv.play.sports.Teams.info(e.league, s.id) }.getOrNull() } }
    }
    val awayPage by produceState<io.github.novatv.play.sports.TeamPage?>(null, e.id) {
        value = e.away?.let { s -> withContext(IO) { runCatching { io.github.novatv.play.sports.Teams.info(e.league, s.id) }.getOrNull() } }
    }
    val table by produceState<io.github.novatv.play.sports.Standings?>(null, e.id) {
        value = withContext(IO) { runCatching { io.github.novatv.play.sports.StandingsRepository.forEvent(e) }.getOrNull() }
    }
    val rows = remember(table) { table?.groups?.flatMap { it.rows }.orEmpty() }
    // A tap on a side: that team in this game (its figures beside the other side's).
    var teamOpen by remember(e.id) { mutableStateOf<Side?>(null) }
    // Game info's line-ups and faces loaded while the page is read, so it opens ready.
    io.github.novatv.play.ui.PrefetchGameInfo(e)
    fun rowOf(s: Side?) = s?.let { side -> rows.firstOrNull { it.id == side.id } ?: rows.firstOrNull { it.name.equals(side.name, true) } }
    val airings by produceState<List<Airing>?>(null, e.id, st.epgVersion) {
        value = withContext(IO) { App.repo.matcher().airings(e, limit = 150) }
        val more = withContext(IO) { App.repo.matcher().airings(e, Broadcasters.forEvent(e), limit = 150) }
        value = (value.orEmpty() + more).distinctBy { it.channel.id }
    }
    // Your channels as listed: the found ones, those addons name, and every version (SD, HD, FHD, 4K) of each.
    // Worked out again only when the addon streams' names change (not at each stream check).
    val addonNames = androidx.compose.runtime.remember(fromAddons) { fromAddons.map { it.channel.name.substringBefore(" · ") }.toSet() }
    val listed by produceState<List<Airing>?>(null, airings, addonNames) {
        val found = airings ?: run { value = null; return@produceState }
        value = found
        value = withContext(IO) {
            runCatching { io.github.novatv.play.data.ChannelVersions.forMatch(found, fromAddons, App.repo.state.value.lib.channels) }.getOrDefault(found)
        }
    }
    Pushed(nav) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOfNotNull(e.league.name, e.subtitle?.takeIf { it != e.league.name }).joinToString(" · ").uppercase(), style = P.caps, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (e.home != null && e.away != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Short scores sit together in the middle; long ones (cricket's 168/9 (79.4 ov)) go under each side.
                            val short = (e.home.score?.length ?: 1) <= 4 && (e.away.score?.length ?: 1) <= 4
                            val apart = e.state != State.PRE && !short
                            BigSide(e.home, Modifier.weight(1f).tap { teamOpen = if (teamOpen?.id == e.home.id) null else e.home }, if (apart) e.home.score else null)
                            Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                val centred = Modifier.fillMaxWidth()
                                if (e.state == State.PRE) Text(clock(e.start), style = P.large.copy(fontSize = 30.sp), textAlign = TextAlign.Center, modifier = centred)
                                else if (apart) Text("vs", style = P.title.copy(color = P.dim), textAlign = TextAlign.Center, modifier = centred)
                                else Text("${e.home.score ?: "-"} : ${e.away.score ?: "-"}", style = P.large.copy(fontSize = 30.sp), maxLines = 1,
                                    textAlign = TextAlign.Center, modifier = centred)
                                Text(if (e.state == State.PRE) dayLabel(e.start) else e.detail, style = P.small.copy(color = if (e.state == State.LIVE) Color(0xFFFF3B4E) else P.dim),
                                    maxLines = 2, textAlign = TextAlign.Center)
                            }
                            BigSide(e.away, Modifier.weight(1f).tap { teamOpen = if (teamOpen?.id == e.away.id) null else e.away }, if (apart) e.away.score else null)
                        }
                    } else {
                        Text(e.title, style = P.title, textAlign = TextAlign.Center)
                        Text("${dayLabel(e.start)} ${clock(e.start)}  ${e.detail}", style = P.small)
                    }
                    e.venue?.let { Spacer(Modifier.height(10.dp)); Text(it, style = P.small, textAlign = TextAlign.Center) }
                    tv?.takeIf { it.isNotEmpty() }?.let { b ->
                        Spacer(Modifier.height(6.dp))
                        Text("TV: " + b.take(5).joinToString(", ") { it.channel + (it.country?.let { c -> " ($c)" } ?: "") }, style = P.small,
                            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (e.teams.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Game info", style = P.head.copy(fontSize = 14.sp), maxLines = 1,
                                modifier = Modifier.glass(RoundedCornerShape(50)).tap { nav.push(Route.GameInfo(e)) }.padding(horizontal = 14.dp, vertical = 8.dp))
                            for (s in e.teams) {
                                val fav = favs.let { isFavTeam(e, s) }
                                Row(Modifier.glass(RoundedCornerShape(50)).tap {
                                    val k = teamKey(e, s)
                                    val on = isFavTeam(e, s)
                                    App.store.favouriteTeams = if (on) App.store.favouriteTeams.filterNot { it.endsWith("|${s.name}") || it == k }.toSet() else App.store.favouriteTeams + k
                                    favs++
                                }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (fav) Icons.Rounded.Star else Icons.Rounded.StarBorder, size = 18.dp, tint = if (fav) P.gold else Color.White)
                                    Spacer(Modifier.width(6.dp))
                                    Text(s.short, style = P.head.copy(fontSize = 14.sp), maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
            teamOpen?.let { s -> item(key = "team:${s.id}") {
                // The team's standing, as on the TV's team card; its game info (only this team) one tap away.
                val row = rowOf(s)
                val page = if (s.id == e.home?.id) homePage else awayPage
                val cricket = e.league.sport == io.github.novatv.play.sports.Sport.CRICKET
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, style = P.head, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Rounded.Close, size = 20.dp, tint = P.dim, modifier = Modifier.tap { teamOpen = null })
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((label, value) in if (cricket) listOf("ICC rank" to row?.pos?.toString(), "Rating" to row?.values?.get("points"), "Matches" to row?.values?.get("gamesPlayed"))
                            else listOf("Place" to row?.pos?.toString(), "Points" to row?.values?.get("points"), "Record" to page?.record))
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(P.card).padding(10.dp)) {
                                Text(label, style = P.small.copy(fontSize = 12.sp), maxLines = 1)
                                Text(value ?: "–", style = P.head, maxLines = 1)
                            }
                    }
                    page?.form?.takeIf { it.isNotEmpty() }?.let { f ->
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Last five", style = P.small, modifier = Modifier.weight(1f))
                            io.github.novatv.play.ui.FormBoxes(f, lettered = true)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("${s.short} game info ›", style = P.body.copy(color = P.blue), modifier = Modifier.tap { nav.push(Route.GameInfo(e, s)) })
                }
            } }
            if (apps.isNotEmpty()) item {
                Column(Modifier.padding(top = 14.dp)) {
                    Text("Watch in the official app", style = P.title)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (svc in apps) Text(svc.name, style = P.head.copy(fontSize = 14.sp), maxLines = 1,
                            modifier = Modifier.glass(RoundedCornerShape(50)).tap { io.github.novatv.play.sports.OfficialApps.open(svc) }.padding(horizontal = 16.dp, vertical = 9.dp))
                    }
                }
            }
            item {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Watch on your channels", style = P.title, modifier = Modifier.weight(1f))
                    listed?.takeIf { it.isNotEmpty() }?.let { Text("${it.size} found", style = P.small) }
                }
            }
            val a = listed
            when {
                a == null -> item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { io.github.novatv.play.ui.Spinner() } }
                a.isEmpty() -> item { Empty(Icons.Rounded.SportsSoccer, "No channels found", "Not in your playlist's guide or channel names.") }
                else -> items(a.size, key = { a[it].channel.id }) { i -> AiringCard(a, i) }
            }
            if (fromAddons.isNotEmpty()) {
                item {
                    Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                        Text("From your addons", style = P.title, modifier = Modifier.weight(1f))
                        Text("${fromAddons.size} found", style = P.small)
                    }
                }
                items(fromAddons.size, key = { "addon:" + fromAddons[it].channel.id }) { i -> AiringCard(fromAddons, i) }
            }
            if (e.home != null && e.away != null) item {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).padding(16.dp)) {
                    Text("Form · last five", style = P.head)
                    Spacer(Modifier.height(8.dp))
                    for ((s, p) in listOf(e.home to homePage, e.away to awayPage)) Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(s.short + (rowOf(s)?.let { "  · ${it.pos}." } ?: ""), style = P.body, modifier = Modifier.weight(1f), maxLines = 1)
                        io.github.novatv.play.ui.FormBoxes(p?.form.orEmpty(), lettered = true)
                    }
                    if (homePage == null && awayPage == null) Text("No recent results for this competition.", style = P.small)
                }
            }
            val info = listOfNotNull(e.venue, e.subtitle ?: e.league.name, e.detail.takeIf { e.state != State.PRE && it.isNotBlank() }, e.summary)
            if (info.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).padding(16.dp)) {
                    Text("Match info", style = P.head)
                    Spacer(Modifier.height(6.dp))
                    for (line in info) Text(line, style = P.small, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
            val t = table
            if (t != null && rows.isNotEmpty()) item {
                io.github.novatv.play.ui.StandingsExcerpt(t, setOfNotNull(rowOf(e.home)?.id, rowOf(e.away)?.id)) { nav.push(Route.Table(e.league.id)) }
            }
        }
    }
}

/** A channel (or addon stream) showing the match: a tap plays it, with the rest of [list] to zap through. */
@Composable
private fun AiringCard(list: List<Airing>, i: Int) {
    val air = list[i]
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).tap { playChannel(list.map { it.channel }, i) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        ChannelLogo(air.channel.logo, air.channel.name, Modifier.size(84.dp, 56.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(air.channel.name, style = P.head, maxLines = 2, overflow = TextOverflow.Ellipsis)
            // An addon stream listed straight away, still being checked (it drops out if it doesn't open).
            if (io.github.novatv.play.data.Addons.isChecking(air.channel.url)) Text("Checking…", style = P.small.copy(color = P.blue, fontSize = 12.sp))
            Text(air.programme?.let { "${clock(it.start)}  ${it.title}" } ?: air.why, style = P.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun BigSide(s: Side, modifier: Modifier, score: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(s, 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(s.short, style = P.head, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        if (score != null) Text(score, style = P.title.copy(fontSize = 20.sp), maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** A match's full information on the phone (only [side]'s when opened from a team): pitch, then the tables. */
@Composable
fun GameInfoPage(nav: Nav, e: SportEvent, side: Side? = null) {
    val info by produceState<io.github.novatv.play.sports.GameInfo.Info?>(null, e.id, side?.id) {
        value = withContext(IO) { runCatching { io.github.novatv.play.sports.GameInfo.forEvent(e, side) }.getOrDefault(io.github.novatv.play.sports.GameInfo.Info(emptyList(), emptyList())) }
    }
    Pushed(nav) {
        Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 60.dp)) {
            Text(if (side != null) "${side.name} · ${e.title}" else e.title, style = P.title)
            Text(listOfNotNull(e.subtitle ?: e.league.name, e.detail.ifBlank { null }, e.summary).joinToString(" · "), style = P.small)
            Spacer(Modifier.height(14.dp))
            val i = info
            when {
                i == null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { io.github.novatv.play.ui.Spinner() }
                i.isEmpty -> Empty(Icons.Rounded.SportsSoccer, "No details yet", if (e.state == State.PRE) "Line-ups and figures appear close to the start." else null)
                else -> io.github.novatv.play.ui.GameInfoBody(i, e.teams.associate { it.name to it.color }, narrow = true)
            }
        }
    }
}
