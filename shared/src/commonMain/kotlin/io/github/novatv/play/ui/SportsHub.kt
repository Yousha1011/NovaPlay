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
    var previewChannel by remember { mutableStateOf<Channel?>(null) }

    val follower = remember { PagePreview(App.preview, "sports") }
    LaunchedEffect(previewChannel) {
        follower.update(previewChannel, previewChannel, previewChannel != null)
    }
    DisposableEffect(Unit) {
        onDispose { App.preview.stop("sports") }
    }

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

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 20.dp, end = 28.dp, bottom = 14.dp)) {
        // ---- Top Header Row ----
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text("Sports", style = T.h1.copy(fontSize = 32.sp, fontWeight = FontWeight.Black))
            Spacer(Modifier.width(20.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).rememberFocusInList()) {
                item { Pill("★ My Sports", onClick = { view = HubView.MINE }, selected = view == HubView.MINE) }
                item {
                    val label = if (liveCount > 0) "All Events · $liveCount live" else "All Events"
                    Pill(label, onClick = { view = HubView.ALL }, selected = view == HubView.ALL)
                }
                item { Pill("🏆 Tables", onClick = { view = HubView.TABLES }, selected = view == HubView.TABLES) }
                item { Pill("📡 League Networks", onClick = { view = HubView.NETWORKS }, selected = view == HubView.NETWORKS) }
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
                    onOpen = { popup = it },
                    onWatchInWindow = { previewChannel = it },
                    videoSlot = videoSlot
                )
                HubView.ALL -> AllEventsView(
                    events = sports.events,
                    onOpen = { popup = it },
                    onWatchInWindow = { previewChannel = it },
                    videoSlot = videoSlot
                )
                HubView.TABLES -> SportsTablesView(videoSlot = videoSlot)
                HubView.NETWORKS -> SportsNetworksView(
                    onPlayInWindow = { previewChannel = it },
                    videoSlot = videoSlot
                )
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
        (mine.ifEmpty { shown }).filter { it.state == State.LIVE || (it.state == State.PRE && it.start < t + 7 * 86_400_000L) }
            .sortedWith(compareBy({ it.state != State.LIVE }, { it.start }))
    }
    val heroes = remember(upcoming, shown) { upcoming.take(8).ifEmpty { shown.filter { it.state == State.LIVE }.take(8) } }
    var heroIdx by remember { mutableIntStateOf(0) }

    if (loading && events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
        return
    }

    val teamRows = remember(mine) {
        mine.flatMap { e -> e.teams.filter { isFavTeam(e, it) }.map { it.name to e } }
            .groupBy({ it.first }, { it.second })
            .map { (name, list) -> name to list.filter { it.state != State.POST }.sortedBy { it.start } }
            .filter { it.second.isNotEmpty() }
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
                        HeroCardRebuilt(
                            event = h,
                            index = heroIdx,
                            count = heroes.size,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onStep = { d -> heroIdx = (heroIdx + d + heroes.size) % heroes.size },
                            onClick = { onOpen(h) },
                            onHoldWatch = {
                                val match = App.repo.matcher().airings(h).firstOrNull()?.channel
                                if (match != null) onWatchInWindow(match)
                            }
                        )
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
            teamRows.forEach { (name, list) ->
                item(key = "team_$name") {
                    val firstEv = list.firstOrNull()
                    val leagueName = firstEv?.league?.name ?: "League"
                    val standingPlace = StandingsRepository.cachedPlace(name, firstEv?.league?.id ?: "")
                    val subTitle = if (standingPlace != null) "$standingPlace in $leagueName · ${list.size} games ahead" else "${list.size} games ahead"
                    GameRowHeader(
                        title = name,
                        subtitle = subTitle,
                        events = list,
                        onOpen = onOpen,
                        onHoldWatch = onWatchInWindow,
                        favTeamName = name
                    )
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
    val (homeColor, awayColor) = teamShades(event.home, event.away)
    val homeBand = bandColor(homeColor)
    val awayBand = bandColor(awayColor)
    // Cricket-style scores ("356 & 7/0 (4 ov)") read badly as "386 - 356 & 7/0": each team shows its own innings.
    val inningsScores = event.league.sport == Sport.CRICKET || listOfNotNull(event.home?.score, event.away?.score).any { '&' in it || '/' in it }
    val showScore = event.state != State.PRE && !App.store.hideScores

    Box(
        modifier = modifier
            .sportsFrame(focused, accent = SportsLook.Gold, radius = 16.dp)
            .background(
                Brush.horizontalGradient(
                    0f to homeBand.copy(alpha = 0.95f),
                    0.50f to homeBand.copy(alpha = 0.60f),
                    0.50f to awayBand.copy(alpha = 0.60f),
                    1f to awayBand.copy(alpha = 0.95f)
                )
            )
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

            // Center Matchup: Team 1 Logo + Name, "VS", Team 2 Name + Logo
            if (event.home != null && event.away != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // Home Team
                    if (event.home.logo != null) {
                        LogoChip(event.home.logo, 44.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(event.home.name, style = T.h2.copy(fontWeight = FontWeight.Black, color = Color.White, fontSize = 20.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (inningsScores && showScore) InningsLine(event.home.score, Alignment.Start)
                    }

                    // VS or Score
                    Box(
                        Modifier.clip(RoundedCornerShape(12.dp))
                            .background(Color(0x77000000))
                            .padding(horizontal = 14.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (inningsScores && showScore) {
                            Text(event.detail.substringAfterLast(" · ").ifBlank { "LIVE" }, style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White))
                        } else if (showScore) {
                            Text("${event.home.score ?: "0"} – ${event.away.score ?: "0"}", style = T.body.copy(fontWeight = FontWeight.Black, color = Color.White))
                        } else {
                            Text("VS", style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White))
                        }
                    }

                    // Away Team
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(event.away.name, style = T.h2.copy(fontWeight = FontWeight.Black, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.End), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (inningsScores && showScore) InningsLine(event.away.score, Alignment.End)
                    }
                    if (event.away.logo != null) {
                        Spacer(Modifier.width(10.dp))
                        LogoChip(event.away.logo, 44.dp)
                    }
                }
            } else {
                Text(event.title, style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White))
            }

            Spacer(Modifier.weight(1f))

            // Bottom line: Subtitle + Pagination Dots
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val whenStr = if (event.state == State.LIVE) event.detail else "${dayLabel(event.start)} ${clock(event.start)}"
                val broadcastStr = event.broadcasts.firstOrNull()?.let { "US: $it" } ?: ""
                val info = listOfNotNull(event.summary, event.league.name.takeIf { event.summary == null }, whenStr, event.venue, broadcastStr.ifBlank { null }).joinToString(" · ")
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
    favTeamName: String? = null
) {
    if (events.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
private fun MatchCardItem(
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
                        Text(h.score ?: "", style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
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
                        Text(a.score ?: "", style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
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
                    Text(headerTitle, style = T.eyebrow.copy(color = if (liveNow.isNotEmpty() && dayOffset == 0) SportsLook.Live else SportsLook.Dim, fontWeight = FontWeight.ExtraBold))
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
                            Text(ev.home?.name ?: "Home", style = T.h2.copy(fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White), modifier = Modifier.weight(1f))
                            Text(ev.home?.score ?: "0", style = T.h2.copy(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White))
                        }
                        Spacer(Modifier.height(8.dp))
                        // Away Team
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ev.away?.logo != null) LogoChip(ev.away.logo, 24.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(ev.away?.name ?: "Away", style = T.h2.copy(fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White), modifier = Modifier.weight(1f))
                            Text(ev.away?.score ?: "0", style = T.h2.copy(fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White))
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
                            Text("GAME STATUS", style = T.eyebrow.copy(color = SportsLook.Dim, fontWeight = FontWeight.ExtraBold))
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
                            Text("WATCHING ON", style = T.eyebrow.copy(color = SportsLook.Dim, fontWeight = FontWeight.ExtraBold))
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
                        Text(event.home?.score ?: "0", style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Team 2
                Row(Modifier.fillMaxWidth().teamBand(awayShade), verticalAlignment = Alignment.CenterVertically) {
                    if (event.away?.logo != null) LogoChip(event.away.logo, 20.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(event.away?.name ?: "Away", style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (event.state != State.PRE && !App.store.hideScores) {
                        Text(event.away?.score ?: "0", style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 6.dp).widthIn(max = 150.dp))
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
private fun LogoChip(url: String?, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.88f)).padding(size * 0.12f),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/** A cricket side's innings: "356 & 7/0 (4 ov)" -> "356  ·  7/0 (4 ov)", first innings first. */
@Composable
private fun InningsLine(score: String?, align: Alignment.Horizontal) {
    val parts = score?.split("&")?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
    if (parts.isEmpty()) { Text("Yet to bat", style = T.tiny.copy(color = Color.White.copy(alpha = 0.7f))); return }
    Row(verticalAlignment = Alignment.CenterVertically) {
        parts.forEachIndexed { i, p ->
            if (i > 0) Text("  &  ", style = T.small.copy(color = Color.White.copy(alpha = 0.6f)))
            Text(p, style = if (i == parts.lastIndex) T.h2.copy(fontWeight = FontWeight.Black, color = Color.White, fontSize = 18.sp) else T.body.copy(fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f)))
        }
    }
}
