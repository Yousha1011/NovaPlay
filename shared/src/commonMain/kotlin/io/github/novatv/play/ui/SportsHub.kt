package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.Route
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.PagePreview
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.Time
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.Teams
import io.github.novatv.play.sports.TeamPage
import io.github.novatv.play.sports.Follow
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.SportsCatalog
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.StandingsRepository
import io.github.novatv.play.sports.State
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.bandColor
import io.github.novatv.play.sports.teamBand
import io.github.novatv.play.sports.teamShades
import io.github.novatv.play.sports.parseTeamColor
import io.github.novatv.play.sports.sportsFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private enum class HubView { MINE, ALL, TABLES, NETWORKS }

/**
 * Rebuilt Sports section replicating NuvioPlus:
 * - Top header tabs: ★ My Sports, All Events · X live, 🏆 Tables, 📡 League Networks, and ✎ Follow Teams & Leagues
 * - Dedicated embedded 16:9 mini-player window in top right
 * - My Sports: Split-color HeroCard carousel, Live & Upcoming row, followed team rows
 * - All Events: Day selector, live scores scoreboard list, right-hand scoreboard card + latest scoring + watching on
 * - Tables: Qualification-colored standings + team summary card + legend card
 * - League Networks: 2-column grid of 24/7 networks by sport + video window
 */
@Composable
fun SportsScreen(nav: Nav) {
    val sports by App.repo.sports.collectAsState()
    val st by App.repo.state.collectAsState()
    var view by remember { mutableStateOf(HubView.MINE) }
    var popup by remember { mutableStateOf<SportEvent?>(null) }
    // The match open on its own page (head to head, channels, table), or null for the tabs.
    var match by remember { mutableStateOf<SportEvent?>(null) }
    var tableLeague by remember { mutableStateOf<String?>(null) }
    // The TV window shares Live TV's and the guide's preview: what plays keeps playing as you move between them.
    val previewChannel = sharedChannel()
    LaunchedEffect(nav.tab) { if (nav.tab == io.github.novatv.play.Tab.SPORTS) App.preview.surface = "sports" }
    val watch: (Channel) -> Unit = { App.preview.select(it, TV_PREVIEW) }

    LaunchedEffect(st.epgVersion, st.lib.channels.size) {
        withContext(IO) { App.repo.matcher() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            App.repo.refreshSports(55_000L)
            delay(60_000L)
        }
    }

    val liveCount = sports.events.count { it.state == State.LIVE && Follow.shows(it) }

    // Reusable 16:9 Video Slot for the top-right mini player
    val videoSlot: @Composable () -> Unit = {
        val ch = previewChannel
        if (ch != null) {
            PreviewBox(
                c = ch,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)),
                owner = "sports"
            )
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF0C0E14))
                    .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    Text("▶", style = T.h1.copy(fontSize = 28.sp, color = SportsLook.Dim))
                    Spacer(Modifier.height(6.dp))
                    Text("Pick a game to watch here", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White))
                    Text("Hold OK on a game, or open it and pick a channel", style = T.tiny.copy(color = SportsLook.Dim))
                }
            }
        }
    }

    match?.let { m ->
        Box(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp, bottom = 14.dp)) {
            MatchPage(m, videoSlot, onClose = { match = null }, onWatch = watch,
                onFullTable = { tableLeague = m.league.id; view = HubView.TABLES; match = null })
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp, bottom = 14.dp)) {
        // ---- Top Header Row ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Sports", style = T.h1.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold))
            Spacer(Modifier.width(20.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).rememberFocusInList()) {
                item { Pill("★ My Sports", onClick = { view = HubView.MINE }, selected = view == HubView.MINE) }
                item {
                    val label = if (liveCount > 0) "All Events · $liveCount live" else "All Events"
                    Pill(label, onClick = { view = HubView.ALL }, selected = view == HubView.ALL)
                }
                item { Pill("🏆 Tables", onClick = { view = HubView.TABLES }, selected = view == HubView.TABLES) }
            }
            // Far right: Follow Teams & Leagues button
            Pill("✎ Follow Teams & Leagues", onClick = { nav.push(Route.Leagues) })
        }

        // ---- Active Tab View ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (view) {
                HubView.MINE -> MySportsView(
                    events = sports.events,
                    loading = sports.loading,
                    onOpen = { match = it },
                    onWatchInWindow = watch,
                    videoSlot = videoSlot
                )
                HubView.ALL -> AllEventsView(
                    events = sports.events,
                    onOpen = { match = it },
                    onWatchInWindow = watch,
                    videoSlot = videoSlot
                )
                HubView.TABLES -> androidx.compose.runtime.key(tableLeague) { SportsTablesView(initialLeagueId = tableLeague, videoSlot = videoSlot) }
                HubView.NETWORKS -> {}
            }
        }
    }

    popup?.let { e -> ChannelsPopup(e, st.epgVersion) { popup = null } }
}

// -------------------------------------------------------------------------
// Tab 1: ★ My Sports
// -------------------------------------------------------------------------

@Composable
private fun MySportsView(
    events: List<SportEvent>,
    loading: Boolean,
    onOpen: (SportEvent) -> Unit,
    onWatchInWindow: (Channel) -> Unit,
    videoSlot: @Composable () -> Unit
) {
    val t = now(60_000L)
    val shown = remember(events) { events.filter { Follow.shows(it) } }
    val mine = remember(shown, t) { shown.filter { e -> e.teams.any { isFavTeam(e, it) } } }
    val upcoming = remember(shown, mine, t) {
        val soon = { e: SportEvent -> e.state == State.LIVE || (e.state == State.PRE && e.start < t + 7 * 86_400_000L) }
        mine.filter(soon).ifEmpty { shown.filter(soon) }
            .sortedWith(compareBy({ it.state != State.LIVE }, { it.start }))
    }
    val heroes = remember(upcoming, shown) { upcoming.take(8).ifEmpty { shown.filter { it.state == State.LIVE }.take(8) } }
    var heroIdx by remember { mutableIntStateOf(0) }
    LaunchedEffect(heroIdx, heroes.size) {
        if (heroes.size > 1) { kotlinx.coroutines.delay(5_000); heroIdx = (heroIdx + 1) % heroes.size }
    }

    if (loading && events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        return
    }

    // A row for each followed team, from its own schedule (weeks ahead, every competition), its live match taken
    // from the scoreboards so the score is current.
    val store = App.store
    val followedTeams = store.favouriteTeams
    val teamInfos by produceState(emptyList<TeamPage>(), followedTeams, events.size > 0) {
        value = withContext(IO) { followedTeams.mapNotNull { k -> try { Teams.followed(k, events) } catch (x: Exception) { null } } }
    }
    val teamRows = remember(teamInfos, events) {
        teamInfos.map { ti ->
            val live = events.filter { e -> e.state != State.POST && e.teams.any { it.id == ti.id && it.name == ti.name } }
            val list = (live + ti.next).distinctBy { it.id }.distinctBy { it.title to it.start / 3_600_000L }.sortedWith(compareBy({ it.state != State.LIVE }, { it.start }))
            ti to list
        }.filter { it.second.isNotEmpty() }
    }
    // Then a row for each followed league, competition or sport, followed leagues first, soonest first.
    val compRows = remember(shown) {
        shown.filter { it.state != State.POST }
            .groupBy { it.subtitle ?: it.league.name }
            .map { (name, list) -> name to list.sortedWith(compareBy({ it.state != State.LIVE }, { it.start })) }
            .sortedWith(compareBy({ (_, l) -> l.first().league.id !in store.sportsLeagues && Follow.compKey(l.first()) !in store.sportsComps }, { it.second.first().start }))
            .take(14)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).rememberFocusInList(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            // Hero card + Video Window top row (168dp height)
            if (heroes.isNotEmpty()) {
                val h = heroes[heroIdx.coerceIn(0, heroes.lastIndex)]
                item(key = "hero_row") {
                    Row(Modifier.fillMaxWidth().height(168.dp)) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = h,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            transitionSpec = {
                                (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(450)) { it / 4 } +
                                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(450))) togetherWith
                                    (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(450)) { -it / 4 } +
                                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(300)))
                            },
                            contentKey = { it.id },
                            label = "hero"
                        ) { h ->
                        HeroCardRebuilt(
                            event = h,
                            index = heroIdx,
                            count = heroes.size,
                            modifier = Modifier.fillMaxSize(),
                            onStep = { d -> heroIdx = (heroIdx + d + heroes.size) % heroes.size },
                            onClick = { onOpen(h) },
                            onHoldWatch = {
                                val match = App.repo.matcher().airings(h).firstOrNull()?.channel
                                if (match != null) onWatchInWindow(match)
                            }
                        )
                        }
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
                            videoSlot()
                        }
                    }
                }
            }

            // Row 1: ● Live & Upcoming
            item(key = "live_upcoming") {
                GameRowHeader(
                    title = "● Live & Upcoming",
                    subtitle = if (mine.isEmpty()) "Today's games, soonest first" else "Everything you follow, soonest first",
                    events = upcoming,
                    onOpen = onOpen,
                    onHoldWatch = onWatchInWindow
                )
            }

            // Followed Team Rows (e.g. Arsenal · 3rd in Champions League · 9 games ahead)
            teamRows.forEach { (ti, list) ->
                item(key = "team_${ti.league.id}_${ti.id}") {
                    val place = listOfNotNull(ti.standing, "${list.size} ${if (list.size == 1) "game" else "games"} ahead").joinToString(" · ")
                    GameRowHeader(title = ti.name, subtitle = place, events = list, onOpen = onOpen, onHoldWatch = onWatchInWindow,
                        favTeamName = ti.name, logo = ti.logo)
                }
            }
            compRows.forEach { (name, list) ->
                item(key = "comp_$name") {
                    GameRowHeader(title = name, subtitle = "${list.size} ${if (list.size == 1) "game" else "games"} ahead", events = list, onOpen = onOpen,
                        onHoldWatch = onWatchInWindow)
                }
            }
        }

        // Bottom hint line
        Text(
            "OK: game info · Hold OK: watch it in the window · ◄ ► through a row",
            style = T.tiny.copy(color = SportsLook.Faint),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Hero match card matching Screenshot 1 (Napoli vs Lazio style) */
@Composable
private fun HeroCardRebuilt(
    event: SportEvent,
    index: Int,
    count: Int,
    modifier: Modifier,
    onStep: (Int) -> Unit,
    onClick: () -> Unit,
    onHoldWatch: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    // Each side in the main colour of its own logo (ESPN's team colour until the logo has loaded).
    val (espnHome, espnAway) = teamShades(event.home, event.away)
    val homeTone = rememberLogoTone(event.home?.logo) ?: espnHome
    val awayTone = rememberLogoTone(event.away?.logo) ?: espnAway
    val homeBand = bandColor(homeTone)
    val awayBand = bandColor(awayTone)
    val showScore = event.state != State.PRE && !App.store.hideScores

    Box(
        modifier = modifier
            .sportsFrame(focused, accent = SportsLook.Gold, radius = 16.dp)
            .background(
                Brush.horizontalGradient(
                    0f to homeBand,
                    0.30f to lerp(homeBand, Color.Black, 0.25f),
                    0.50f to lerp(lerp(homeBand, awayBand, 0.5f), Color.Black, 0.45f),
                    0.70f to lerp(awayBand, Color.Black, 0.25f),
                    1f to awayBand
                )
            )
            // A soft light from the top and shade at the bottom, so the colours read as a surface, not flat paint.
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f)))
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { k ->
                if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (k.key) {
                    Key.DirectionLeft -> { if (count > 1) { onStep(-1); true } else false }
                    Key.DirectionRight -> { if (count > 1) { onStep(1); true } else false }
                    else -> false
                }
            }
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Top Row: Badge on left, League logo on right
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val tagText = when (event.state) {
                    State.LIVE -> "● LIVE NOW"
                    State.POST -> "FINAL"
                    else -> "COMING UP · " + dayLabel(event.start).uppercase()
                }
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (event.state == State.LIVE) SportsLook.Live else Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(tagText, style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold))
                }
                Spacer(Modifier.weight(1f))
                Text(event.league.name, style = T.tiny.copy(color = Color.White.copy(alpha = 0.8f), fontWeight = FontWeight.Bold))
            }

            Spacer(Modifier.weight(1f))

            // Matchup: logo over name at each end, scores just inside them, the state of play in the middle.
            if (event.home != null && event.away != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    HeroTeam(event.home, Modifier.width(150.dp))
                    if (showScore) ScoreBlock(event.home.score, Alignment.Start, Modifier.width(150.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val stateText = when {
                            event.state == State.PRE -> "${dayLabel(event.start)} ${clock(event.start)}"
                            event.state == State.POST -> "Final"
                            else -> event.detail.substringAfterLast(" · ").ifBlank { "Live" }
                        }
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x66000000)).padding(horizontal = 12.dp, vertical = 3.dp)) {
                            Text(if (event.state == State.PRE) "VS" else stateText, style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White))
                        }
                        val lines = listOfNotNull(
                            event.summary.takeIf { showScore },
                            if (event.state == State.PRE) stateText else event.detail.substringBeforeLast(" · ", "").ifBlank { null }
                        )
                        lines.forEach {
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = T.small.copy(color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (showScore) ScoreBlock(event.away.score, Alignment.End, Modifier.width(150.dp))
                    HeroTeam(event.away, Modifier.width(150.dp))
                }
            } else {
                Text(event.title, style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White))
            }

            Spacer(Modifier.weight(1f))

            // Bottom line: Subtitle + Pagination Dots
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val info = listOfNotNull(event.subtitle ?: event.league.name, event.venue, event.broadcasts.firstOrNull()).joinToString(" · ")
                Text(
                    info,
                    style = T.tiny.copy(color = Color.White.copy(alpha = 0.85f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (count > 1) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until minOf(count, 8)) {
                            Box(
                                Modifier.size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (i == index) Color.White else Color(0x66FFFFFF))
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameRowHeader(
    title: String,
    subtitle: String,
    events: List<SportEvent>,
    onOpen: (SportEvent) -> Unit,
    onHoldWatch: (Channel) -> Unit,
    favTeamName: String? = null,
    logo: String? = null
) {
    if (events.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (logo != null) { LogoChip(logo, 30.dp); Spacer(Modifier.width(10.dp)) }
            Text(title, style = T.h2.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp), modifier = Modifier.weight(1f))
            Text(subtitle, style = T.tiny.copy(color = SportsLook.Dim))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(events.take(24), key = { it.id }) { ev ->
                MatchCardItem(
                    event = ev,
                    favTeamName = favTeamName,
                    onClick = { onOpen(ev) },
                    onHoldWatch = {
                        val ch = App.repo.matcher().airings(ev).firstOrNull()?.channel
                        if (ch != null) onHoldWatch(ch)
                    }
                )
            }
        }
    }
}

/** Individual match card in horizontal row (Screenshot 1) */
@Composable
internal fun MatchCardItem(
    event: SportEvent,
    favTeamName: String?,
    onClick: () -> Unit,
    onHoldWatch: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val (homeShade, awayShade) = teamShades(event.home, event.away)
    val band = Color(0xFF1B1F27)

    Box(
        modifier = Modifier
            .width(280.dp)
            .height(130.dp)
            .sportsFrame(focused, accent = SportsLook.Gold, radius = 12.dp)
            .background(
                Brush.horizontalGradient(
                    0f to band.copy(alpha = 0.85f),
                    0.40f to band.copy(alpha = 0.25f),
                    1f to Color(0xFF14171E).copy(alpha = 0.90f)
                )
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // League and Status
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(event.league.name, style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f), maxLines = 1)
                val statusText = when (event.state) {
                    State.LIVE -> "● LIVE"
                    State.POST -> "FINAL"
                    else -> dayLabel(event.start).uppercase()
                }
                Box(
                    Modifier.clip(RoundedCornerShape(4.dp))
                        .background(if (event.state == State.LIVE) SportsLook.Live else Color(0x33FFFFFF))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(statusText, style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp))
                }
            }

            Spacer(Modifier.height(6.dp))

            // Golf, racing, fight nights: no two sides, so the event's own name and stage.
            if (event.home == null) {
                Text(event.title, style = T.body.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                event.detail.ifBlank { null }?.let { Text(it, style = T.tiny.copy(color = SportsLook.Dim), maxLines = 1) }
            }

            // Team 1
            event.home?.let { h ->
                Row(Modifier.fillMaxWidth().teamBand(homeShade), verticalAlignment = Alignment.CenterVertically) {
                    if (h.logo != null) {
                        LogoChip(h.logo, 20.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    val isFav = favTeamName == h.name || isFavTeam(event, h)
                    Text(h.name + if (isFav) " ★" else "", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (event.state != State.PRE && !App.store.hideScores) {
                        Text(h.score ?: "", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            // Team 2
            event.away?.let { a ->
                Row(Modifier.fillMaxWidth().teamBand(awayShade), verticalAlignment = Alignment.CenterVertically) {
                    if (a.logo != null) {
                        LogoChip(a.logo, 20.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    val isFav = favTeamName == a.name || isFavTeam(event, a)
                    Text(a.name + if (isFav) " ★" else "", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (event.state != State.PRE && !App.store.hideScores) {
                        Text(a.score ?: "", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // Subtitle
            val sub = listOfNotNull(clock(event.start), event.broadcasts.firstOrNull()).joinToString(" · ")
            Text(sub, style = T.tiny.copy(color = SportsLook.Faint), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// -------------------------------------------------------------------------
// Tab 2: All Events (Screenshot 2)
// -------------------------------------------------------------------------

@Composable
private fun AllEventsView(
    events: List<SportEvent>,
    onOpen: (SportEvent) -> Unit,
    onWatchInWindow: (Channel) -> Unit,
    videoSlot: @Composable () -> Unit
) {
    val store = App.store
    var dayOffset by remember { mutableIntStateOf(0) }
    val nowMs = now(60_000L)

    val days = remember {
        listOf(-1, 0, 1, 2, 3, 4, 5)
    }

    val dayEvents = remember(events, dayOffset, nowMs) {
        val targetDayStart = Time.dayStart(nowMs + dayOffset * 86_400_000L)
        val targetDayEnd = targetDayStart + 86_400_000L
        events.filter { it.start in targetDayStart until targetDayEnd || (dayOffset == 0 && it.state == State.LIVE) }
            .sortedWith(compareBy({ it.state != State.LIVE }, { it.start }))
    }

    var selectedEvent by remember(dayEvents) {
        mutableStateOf(dayEvents.firstOrNull { it.state == State.LIVE } ?: dayEvents.firstOrNull())
    }

    Column(Modifier.fillMaxSize()) {
        // Day selector row: Yesterday, Today, Tomorrow, Mon 9, Tue 10...
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            items(days) { offset ->
                val label = when (offset) {
                    -1 -> "Yesterday"
                    0 -> "Today"
                    1 -> "Tomorrow"
                    else -> Time.formatDayName(nowMs + offset * 86_400_000L)
                }
                Pill(label, onClick = { dayOffset = offset }, selected = dayOffset == offset)
            }
        }

        // Sub-filter row: "Showing 53 leagues" | "Choose Leagues ›"
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val totalLeagues = Catalog.leagues.size
            Text("Showing $totalLeagues leagues", style = T.tiny.copy(color = SportsLook.Dim))
            Spacer(Modifier.weight(1f))
            Text("Choose Leagues ›", style = T.tiny.copy(color = C.accent, fontWeight = FontWeight.Bold))
        }

        // Split screen: Left Match list (~480.dp), Right details & video
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Left Match List
            Column(Modifier.width(500.dp).fillMaxHeight()) {
                val liveNow = dayEvents.filter { it.state == State.LIVE }
                val headerTitle = if (dayOffset == 0 && liveNow.isNotEmpty()) "● LIVE NOW" else "GAMES"
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(headerTitle, style = T.eyebrow.copy(color = if (liveNow.isNotEmpty() && dayOffset == 0) SportsLook.Live else SportsLook.Dim, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.width(8.dp))
                    Text("${dayEvents.size} games", style = T.tiny.copy(color = SportsLook.Dim))
                }

                if (dayEvents.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No games scheduled for this day.", style = T.small.copy(color = SportsLook.Dim))
                    }
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize().rememberFocusInList(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(dayEvents, key = { it.id }) { ev ->
                            val isSelected = selectedEvent?.id == ev.id
                            ScoreboardCard(
                                event = ev,
                                isSelected = isSelected,
                                onFocus = {
                                    selectedEvent = ev
                                    val lead = App.repo.matcher().airings(ev).firstOrNull()?.channel
                                    if (lead != null) onWatchInWindow(lead)
                                },
                                onClick = { onOpen(ev) }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(18.dp))

            // Right Pane: Video + Scoreboard Strip + Scoring & Watching Cards
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // Top Video Slot
                videoSlot()

                Spacer(Modifier.height(10.dp))

                // Middle Detailed Scoreboard Card
                val ev = selectedEvent
                if (ev != null) {
                    val (homeColor, awayColor) = teamShades(ev.home, ev.away)
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.verticalGradient(
                                    0f to homeColor.copy(alpha = 0.85f),
                                    0.5f to homeColor.copy(alpha = 0.60f),
                                    0.5f to awayColor.copy(alpha = 0.60f),
                                    1f to awayColor.copy(alpha = 0.85f)
                                )
                            )
                            .padding(12.dp)
                    ) {
                        // Home Team
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ev.home?.logo != null) LogoChip(ev.home.logo, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(ev.home?.name ?: "Home", style = T.h2.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White), modifier = Modifier.weight(1f))
                            Text(ev.home?.score ?: "0", style = T.h2.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Away Team
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ev.away?.logo != null) LogoChip(ev.away.logo, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(ev.away?.name ?: "Away", style = T.h2.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White), modifier = Modifier.weight(1f))
                            Text(ev.away?.score ?: "0", style = T.h2.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White))
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ev.state == State.LIVE) {
                                Box(Modifier.clip(RoundedCornerShape(4.dp)).background(SportsLook.Live).padding(horizontal = 6.dp, vertical = 2.dp)) {
                                    Text("● LIVE", style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp))
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(ev.detail, style = T.tiny.copy(color = Color.White.copy(alpha = 0.85f)))
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Bottom Two Cards: LATEST SCORING + WATCHING ON
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        // LATEST SCORING Card
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SportsLook.Card)
                                .border(1.dp, SportsLook.Border, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text("GAME STATUS", style = T.eyebrow.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                when (ev.state) {
                                    State.LIVE -> ev.detail.ifBlank { "Live" }
                                    State.POST -> "Final · ${ev.home?.name} ${ev.home?.score}, ${ev.away?.name} ${ev.away?.score}"
                                    else -> "Starts ${clock(ev.start)}"
                                },
                                style = T.tiny.copy(color = Color.White),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // WATCHING ON Card
                        Column(
                            Modifier.weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SportsLook.Card)
                                .border(1.dp, SportsLook.Border, RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            Text("WATCHING ON", style = T.eyebrow.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold))
                            Spacer(Modifier.height(6.dp))
                            val airings = App.repo.matcher().airings(ev)
                            val lead = airings.firstOrNull()?.channel
                            if (lead != null) {
                                Text(lead.name, style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (airings.size > 1) {
                                    Text("Also on: ${airings[1].channel.name}", style = T.tiny.copy(color = SportsLook.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else {
                                val bc = ev.broadcasts.firstOrNull()
                                Text(bc ?: "Check playlist channels", style = T.tiny.copy(color = SportsLook.Dim))
                            }
                        }
                    }
                }
            }
        }

        // Bottom hint line
        Text(
            "OK: game info · Hold OK: watch it in the window",
            style = T.tiny.copy(color = SportsLook.Faint),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Scoreboard card item in All Events list (Screenshot 2) */
@Composable
private fun ScoreboardCard(
    event: SportEvent,
    isSelected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val (homeShade, awayShade) = teamShades(event.home, event.away)
    val band = Color(0xFF1B1F27)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(86.dp)
            .sportsFrame(focused, playing = isSelected && !focused, accent = SportsLook.Gold, radius = 12.dp)
            .background(
                Brush.horizontalGradient(
                    0f to band.copy(alpha = 0.85f),
                    0.35f to band.copy(alpha = 0.25f),
                    1f to Color(0xFF14171E).copy(alpha = 0.90f)
                )
            )
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // Teams & Scores column
            Column(Modifier.weight(1f)) {
                // Team 1
                Row(Modifier.fillMaxWidth().teamBand(homeShade), verticalAlignment = Alignment.CenterVertically) {
                    if (event.home?.logo != null) LogoChip(event.home.logo, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(event.home?.name ?: "Home", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (event.state != State.PRE && !App.store.hideScores) {
                        Text(event.home?.score ?: "0", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Team 2
                Row(Modifier.fillMaxWidth().teamBand(awayShade), verticalAlignment = Alignment.CenterVertically) {
                    if (event.away?.logo != null) LogoChip(event.away.logo, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(event.away?.name ?: "Away", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (event.state != State.PRE && !App.store.hideScores) {
                        Text(event.away?.score ?: "0", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
                    }
                }
            }

            Spacer(Modifier.width(14.dp))

            // Right Status Badge & Detail
            Column(horizontalAlignment = Alignment.End) {
                if (event.state == State.LIVE) {
                    Box(
                        Modifier.clip(RoundedCornerShape(4.dp))
                            .background(SportsLook.Live)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("● LIVE", style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(event.detail, style = T.tiny.copy(color = SportsLook.Dim), maxLines = 1)
                } else {
                    Text(clock(event.start), style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold))
                    Spacer(Modifier.height(2.dp))
                    Text(dayLabel(event.start), style = T.tiny.copy(color = SportsLook.Dim))
                }
            }
        }
    }
}

/** A team logo on a soft light disc, so a red crest still reads on a red team band. */
@Composable
internal fun LogoChip(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.88f)).padding(size * 0.12f),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** A side in the hero: its logo, its name underneath. */
@Composable
private fun HeroTeam(side: Side, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (side.logo != null) LogoChip(side.logo, 52.dp) else Spacer(Modifier.height(52.dp))
        Spacer(Modifier.height(6.dp))
        Text(side.name, style = T.body.copy(fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A side's score beside it: one figure, or a cricket side's innings stacked ("356" over "7/0 (4 ov)"), latest largest. */
@Composable
private fun ScoreBlock(score: String?, align: Alignment.Horizontal, modifier: Modifier) {
    val parts = score?.split("&")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    val textAlign = TextAlign.Center
    Column(modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (parts.isEmpty()) {
            Text("–", style = T.h1.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.6f)))
        } else {
            parts.dropLast(1).forEach { Text(it, style = T.body.copy(color = Color.White.copy(alpha = 0.75f), textAlign = textAlign), maxLines = 1) }
            val last = parts.last()
            val big = last.substringBefore(" (")
            val extra = last.substringAfter(" (", "").removeSuffix(")").ifBlank { null }
            Text(big, style = T.h1.copy(fontWeight = FontWeight.Bold, color = Color.White, textAlign = textAlign), maxLines = 1)
            if (extra != null) Text(extra, style = T.tiny.copy(color = Color.White.copy(alpha = 0.8f), textAlign = textAlign), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
