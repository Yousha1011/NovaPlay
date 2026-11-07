package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.Time
import io.github.novatv.play.sports.Follow
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class HubView { MINE, ALL, TABLES, NETWORKS }

/**
 * The Sports hub: My Sports (a big card of the next game in the teams' colours with the video beside it, then Live &
 * Upcoming and a row per team you follow), All Events (every match by day and sport) and Follow Teams & Leagues.
 */
@Composable
fun SportsScreen(nav: Nav) {
    val sports by App.repo.sports.collectAsState()
    val st by App.repo.state.collectAsState()
    var view by remember { mutableStateOf(HubView.MINE) }
    var popup by remember { mutableStateOf<SportEvent?>(null) }
    LaunchedEffect(st.epgVersion, st.lib.channels.size) { withContext(IO) { App.repo.matcher() } }
    LaunchedEffect(Unit) { while (true) { App.repo.refreshSports(55_000L); delay(60_000L) } }
    val liveCount = sports.events.count { it.state == State.LIVE && Follow.shows(it) }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 24.dp, end = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 14.dp)) {
            Text("Sports", style = T.h1.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(24.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f).rememberFocusInList()) {
                item { Pill("★ My Sports", onClick = { view = HubView.MINE }, selected = view == HubView.MINE) }
                item { Pill("All Events" + if (liveCount > 0) " · $liveCount live" else "", onClick = { view = HubView.ALL }, selected = view == HubView.ALL) }
                item { Pill("🏆 Tables", onClick = { view = HubView.TABLES }, selected = view == HubView.TABLES) }
                item { Pill("📡 Networks", onClick = { view = HubView.NETWORKS }, selected = view == HubView.NETWORKS) }
                item { Pill("Follow Teams & Leagues", onClick = { nav.push(Route.Leagues) }) }
                item { Pill("Refresh", onClick = { App.repo.refreshSports(0) }) }
            }
            Text(if (sports.loading && sports.events.isEmpty()) "Loading games…" else if (sports.updatedAt > 0) "Updated ${clock(sports.updatedAt)}" else "",
                style = T.small.copy(color = C.dim))
        }
        when (view) {
            HubView.ALL -> AllEvents(nav)
            HubView.MINE -> MySports(sports.events, sports.loading) { popup = it }
            HubView.TABLES -> SportsTablesView()
            HubView.NETWORKS -> SportsNetworksView()
        }
    }
    popup?.let { e -> ChannelsPopup(e, st.epgVersion) { popup = null } }
}

@Composable
private fun MySports(events: List<SportEvent>, loading: Boolean, open: (SportEvent) -> Unit) {
    val t = now(60_000L)
    val shown = remember(events) { events.filter { Follow.shows(it) } }
    // Games of teams you follow first; with none followed, everything live and today's.
    val mine = remember(shown, t) { shown.filter { e -> e.teams.any { isFavTeam(e, it) } } }
    val upcoming = remember(shown, mine, t) {
        (mine.ifEmpty { shown }).filter { it.state == State.LIVE || (it.state == State.PRE && it.start < t + 7 * 86_400_000L) }
            .sortedWith(compareBy({ it.state != State.LIVE }, { it.start }))
    }
    val heroes = remember(upcoming, shown) { upcoming.take(8).ifEmpty { shown.filter { it.state == State.LIVE }.take(8) } }
    var hero by remember { mutableIntStateOf(0) }
    if (loading && events.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }; return }
    if (heroes.isEmpty()) { Message("No games to show", "Follow teams and leagues to fill My Sports, or open All Events."); return }
    val h = heroes[hero.coerceIn(0, heroes.lastIndex)]
    // A row per team you follow, with its coming games.
    val teamRows = remember(mine) {
        mine.flatMap { e -> e.teams.filter { isFavTeam(e, it) }.map { it.name to e } }.groupBy({ it.first }, { it.second })
            .map { (name, list) -> name to list.filter { it.state != State.POST }.sortedBy { it.start } }.filter { it.second.isNotEmpty() }
    }

    LazyColumn(Modifier.fillMaxSize().rememberFocusInList(), verticalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(bottom = 50.dp)) {
        item(key = "hero") {
            Row(Modifier.fillMaxWidth().height(300.dp)) {
                HeroCard(h, hero, heroes.size, Modifier.weight(1f).fillMaxHeight(),
                    onFlip = { d -> hero = (hero + d).mod(heroes.size) }) { open(h) }
                Spacer(Modifier.width(18.dp))
                HeroVideo(h, Modifier.fillMaxHeight().aspectRatio(16f / 9f))
            }
        }
        item(key = "upcoming") { GameRow("● Live & Upcoming", if (mine.isEmpty()) "Today's games, soonest first" else "Everything you follow, soonest first", upcoming, open) }
        teamRows.forEach { (name, list) ->
            item(key = "team-$name") { GameRow(name, "${list.size} games ahead", list, open) }
        }
    }
}

/** The big card: the two sides in their colours, the score (or kick-off), and competition, time, ground and where it's on. */
@Composable
private fun HeroCard(e: SportEvent, index: Int, count: Int, modifier: Modifier, onFlip: (Int) -> Unit, onClick: () -> Unit) {
    Tile(onClick, modifier.onPreviewKeyEvent { k ->
        if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (k.key) { Key.DirectionLeft -> { if (index > 0) { onFlip(-1); true } else false }; Key.DirectionRight -> { if (index < count - 1) { onFlip(1); true } else false }; else -> false }
    }, shape = RoundedCornerShape(22.dp), scale = 1.0f, color = C.panel) {
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to teamColor(e.home, 0.85f), 0.5f to teamColor(e.home, 0.85f), 0.5f to teamColor(e.away, 0.85f), 1f to teamColor(e.away, 0.85f)))) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color(0x99000000))))
            Column(Modifier.fillMaxSize().padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (e.state == State.LIVE) Box(Modifier.clip(RoundedCornerShape(6.dp)).background(C.live).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text("● LIVE NOW", style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    } else Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x66000000)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                        Text(dayLabel(e.start).uppercase(), style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (e.home != null && e.away != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    // Cricket and the like ("386 & 7/0 (4 ov)"): each score under its side, "v" in the middle.
                    val long = (e.home.score?.length ?: 0) > 4 || (e.away.score?.length ?: 0) > 4
                    val scored = e.state != State.PRE
                    TeamLogo(e.home, 84); Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(e.home.short, style = T.h1.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (long && scored) Text(e.home.score ?: "", style = T.h2.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x99000000)).padding(horizontal = 26.dp, vertical = 8.dp)) {
                        Text(when { !scored -> clock(e.start); long -> "v"; else -> "${e.home.score ?: "-"} – ${e.away.score ?: "-"}" },
                            style = T.h1.copy(color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                        Text(e.away.short, style = T.h1.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (long && scored) Text(e.away.score ?: "", style = T.h2.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(18.dp)); TeamLogo(e.away, 84)
                } else Text(e.title, style = T.h1.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 2)
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull(e.league.name, if (e.state == State.LIVE) e.detail else "${dayLabel(e.start)} ${clock(e.start)}", e.venue).joinToString("  ·  "),
                        style = T.body.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (count > 1) {
                        Text("◀ ▶  ", style = T.small.copy(color = Color(0xCCFFFFFF)))
                        for (i in 0 until count) { Dot(if (i == index) Color.White else Color(0x66FFFFFF), 8.dp); Spacer(Modifier.width(6.dp)) }
                    }
                }
            }
        }
    }
}

/** The video beside the big card: the best channel found for the game, previewed muted. */
@Composable
private fun HeroVideo(e: SportEvent, modifier: Modifier) {
    val st by App.repo.state.collectAsState()
    val channel by produceState<io.github.novatv.play.data.Channel?>(null, e.id, st.epgVersion) {
        delay(400); value = withContext(IO) { App.repo.matcher().airings(e, limit = 5).firstOrNull()?.channel }
    }
    val follower = remember { io.github.novatv.play.data.PagePreview(App.preview, "sports") }
    LaunchedEffect(channel) { follower.update(channel, channel, channel != null && e.state == State.LIVE) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { App.preview.stop("sports") } }
    val c = channel
    if (c != null) PreviewBox(c, modifier.clip(RoundedCornerShape(22.dp)), "sports", if (e.state == State.LIVE) null else "Starts ${dayLabel(e.start)} ${clock(e.start)}")
    else Box(modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xFF07090C)), contentAlignment = Alignment.Center) {
        Text("Finding a channel…", style = T.small.copy(color = C.dim))
    }
}

/** A titled row of game cards. */
@Composable
private fun GameRow(title: String, note: String, list: List<SportEvent>, open: (SportEvent) -> Unit) {
    if (list.isEmpty()) return
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = T.h2.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            Text(note, style = T.small.copy(color = C.dim))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.rememberFocusInList()) {
            items(list.take(30), key = { it.id }) { e -> GameCard(e) { open(e) } }
        }
    }
}

/** A game: competition and status on top, each side on a band of its colour with its score, then time and ground. */
@Composable
private fun GameCard(e: SportEvent, onClick: () -> Unit) {
    Tile(onClick, Modifier.width(380.dp).height(168.dp), shape = RoundedCornerShape(16.dp), scale = 1.03f, color = C.panel) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.league.name, style = T.small.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val tag = when {
                    e.state == State.LIVE -> "● LIVE"
                    e.state == State.POST -> "FT"
                    Time.dayStart(e.start) == Time.dayStart(now()) -> clock(e.start)
                    else -> dayLabel(e.start).uppercase()
                }
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(if (e.state == State.LIVE) C.live else Color(0x33FFFFFF)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(tag, style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
            }
            if (e.home != null && e.away != null) {
                SideBand(e, e.home); SideBand(e, e.away)
            } else Text(e.title, style = T.body.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, modifier = Modifier.padding(horizontal = 14.dp).weight(1f))
            Spacer(Modifier.weight(1f))
            Text(listOfNotNull(if (e.state == State.LIVE) e.detail else "${dayLabel(e.start)} ${clock(e.start)}", e.venue).joinToString(" · "),
                style = T.tiny.copy(color = C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun SideBand(e: SportEvent, s: Side) {
    Row(Modifier.fillMaxWidth().height(40.dp).background(Brush.horizontalGradient(0f to teamColor(s, 0.75f), 1f to teamColor(s, 0.15f))).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        TeamLogo(s, 26); Spacer(Modifier.width(10.dp))
        Text(s.short + if (isFavTeam(e, s)) " ★" else "", style = T.body.copy(color = Color.White, fontWeight = FontWeight.SemiBold), maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (e.state != State.PRE) Text(s.score ?: "", style = T.body.copy(color = Color.White, fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun TeamLogo(s: Side, size: Int) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        var loaded by remember(s.logo) { mutableStateOf(false) }
        if (!loaded) Text(initials(s.name), style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold))
        if (s.logo != null) AsyncImage(s.logo, null, Modifier.size(size.dp), onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

/** A side's colour (ESPN's), darkened a little so white text reads on it; a neutral slate when ESPN has none. */
private fun teamColor(s: Side?, alpha: Float): Color {
    val hex = s?.color ?: return Color(0xFF2A3340).copy(alpha = alpha)
    val v = hex.toLongOrNull(16) ?: return Color(0xFF2A3340).copy(alpha = alpha)
    val r = ((v shr 16) and 0xFF) * 0.72f; val g = ((v shr 8) and 0xFF) * 0.72f; val b = (v and 0xFF) * 0.72f
    return Color(r / 255f, g / 255f, b / 255f, alpha)
}
