package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import io.github.novatv.play.data.Channel
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.Time
import io.github.novatv.play.sports.Airing
import io.github.novatv.play.sports.Broadcasters
import io.github.novatv.play.sports.Side
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.Standings
import io.github.novatv.play.sports.StandingsRepository
import io.github.novatv.play.sports.StandingsRow
import io.github.novatv.play.sports.State
import io.github.novatv.play.sports.TeamPage
import io.github.novatv.play.sports.Teams
import io.github.novatv.play.sports.bandColor
import io.github.novatv.play.sports.teamShades
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.flow.flowOn

/**
 * A match, head to head: both sides with their record, place and last five; where it airs on your channels; their
 * form and the match details; and on the right the TV window, when it's on and the league table around both teams.
 * OK on a side opens its team card. Back returns to Sports.
 */
@Composable
fun MatchPage(e: SportEvent, videoSlot: @Composable () -> Unit, onClose: () -> Unit, onWatch: (Channel) -> Unit, onFullTable: () -> Unit) {
    BackHandler(true) { onClose() }
    val home = e.home; val away = e.away
    val homePage by produceState<TeamPage?>(null, e.id) { value = home?.let { withContext(IO) { Teams.info(e.league, it.id) } } }
    val awayPage by produceState<TeamPage?>(null, e.id) { value = away?.let { withContext(IO) { Teams.info(e.league, it.id) } } }
    val table by produceState<Standings?>(null, e.id) { value = withContext(IO) { runCatching { StandingsRepository.forEvent(e) }.getOrNull() } }
    // Your channels from the guide and channel names first (at once, offline), then the broadcasters found online
    // added; looked up again when the guide (re)loads, so a game opened while it was loading still finds them.
    val guideVersion = App.repo.state.collectAsState().value.epgVersion
    val airings by produceState<List<Airing>?>(null, e.id, guideVersion) {
        val local = withContext(IO) { try { App.repo.matcher().airings(e, limit = 40) } catch (x: Exception) { emptyList() } }
        value = local
        val online = withContext(IO) {
            try {
                kotlinx.coroutines.withTimeoutOrNull(15_000L) { App.repo.matcher().airings(e, Broadcasters.forEvent(e), limit = 40) }.orEmpty()
            } catch (x: Exception) { emptyList() }
        }
        if (online.isNotEmpty()) value = (local + online).distinctBy { it.channel.id }
    }
    // The user's Stremio addons: their streams of this match, after your channels (read off the network, so later).
    val fromAddons by produceState(io.github.novatv.play.data.Addons.known(e).orEmpty(), e.id) {
        // Listed as each stream's check passes (the streams themselves are stored by the background update).
        try {
            kotlinx.coroutines.withTimeoutOrNull(60_000L) {
                io.github.novatv.play.data.Addons.airingsFlow(e).flowOn(IO).collect { l -> value = l }
            }
        } catch (x: Exception) { }
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
    // The broadcasters' own apps, for matches only they show (DRM-protected, so no stream here plays them).
    val apps by produceState(emptyList<io.github.novatv.play.sports.OfficialApps.Service>(), e.id) {
        value = withContext(IO) { io.github.novatv.play.sports.OfficialApps.forEvent(e) }
    }
    var card by remember { mutableStateOf<Side?>(null) }
    var gameInfo by remember { mutableStateOf(false) }
    // Game info's line-ups and faces loaded while the page is read, so it opens ready.
    PrefetchGameInfo(e)
    // Opened from a team's card: that team only; from the page: the whole match.
    var infoSide by remember { mutableStateOf<Side?>(null) }
    val testInfo by App.testGameInfo.collectAsState()
    androidx.compose.runtime.LaunchedEffect(testInfo) { gameInfo = testInfo }
    // Focus starts on the page (the first side, else the first button), never left in the menu.
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(e.id) {
        repeat(10) { try { first.requestFocus(); return@LaunchedEffect } catch (x: Exception) { kotlinx.coroutines.delay(80) } }
    }
    var picking by remember { mutableStateOf(false) }
    var follows by remember { mutableIntStateOf(0) }
    val rows = remember(table) { table?.groups?.flatMap { it.rows }.orEmpty() }
    fun rowOf(s: Side?) = s?.let { side -> rows.firstOrNull { it.id == side.id } ?: rows.firstOrNull { it.name.equals(side.name, true) } }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            // Windows has no remote Back: a button. The TV keeps the line (its remote's Back closes the page).
            if (App.device == io.github.novatv.play.platform.Device.DESKTOP) androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Pill("Back", onClick = onClose, icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack)
                Spacer(Modifier.width(14.dp))
                Text(listOfNotNull(e.subtitle ?: e.league.name, e.venue).joinToString(" · "),
                    style = T.small.copy(color = SportsLook.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else Text("‹ Sports    " + listOfNotNull(e.subtitle ?: e.league.name, e.venue).joinToString(" · "),
                style = T.small.copy(color = SportsLook.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Scoreboard(e, homePage, awayPage, rowOf(home), rowOf(away), first) { card = it }
            Spacer(Modifier.height(18.dp))
            Text("WATCH ON YOUR CHANNELS", style = T.eyebrow.copy(color = SportsLook.Dim))
            Spacer(Modifier.height(8.dp))
            val shown = listed
            when {
                shown == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Spinner(size = 24.dp)
                    // A TV guide loading takes most of a TV's power: the lookup waits on it, so say so.
                    App.repo.state.collectAsState().value.epgStatus?.let {
                        Spacer(Modifier.width(10.dp)); Text("Finding channels · $it", style = T.small.copy(color = SportsLook.Dim))
                    }
                }
                shown.isEmpty() -> Text("Not found in your channels' guide or names. Pick a channel to remember one for this game.", style = T.small)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // OK plays it in the TV window; hold OK opens it full screen. Every version, a row each.
                    // The first 10 drawn (each row a logo and a guide line; all of a big playlist's versions at once
                    // left a Fire TV seconds behind each key and out of memory); the rest on request.
                    var all by androidx.compose.runtime.remember(e.id) { androidx.compose.runtime.mutableStateOf(false) }
                    val chans = shown.map { it.channel }
                    (if (all) shown else shown.take(10)).forEach { a ->
                        AiringRow(a, onLongClick = { fullScreen(a.channel, chans, onWatch) }) { watchOrFull(a.channel, chans, onWatch) }
                    }
                    if (!all && shown.size > 10) Pill("Show ${shown.size - 10} more", onClick = { all = true })
                }
            }
            if (fromAddons.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("FROM YOUR ADDONS", style = T.eyebrow.copy(color = SportsLook.Dim))
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    fromAddons.forEachIndexed { i, a ->
                        AiringRow(a, onLongClick = { fullScreen(a.channel, fromAddons.map { it.channel }, onWatch) }) { watchOrFull(a.channel, fromAddons.map { it.channel }, onWatch) }
                    }
                }
            }
            if (apps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("WATCH IN THE OFFICIAL APP", style = T.eyebrow.copy(color = SportsLook.Dim))
                Spacer(Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (svc in apps) item(key = "app_${svc.name}") { Pill(svc.name, onClick = { io.github.novatv.play.sports.OfficialApps.open(svc) }) }
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item(key = "gameinfo") { Pill("Game info", onClick = { gameInfo = true }) }
                for (s in e.teams) item(key = "f_${s.id}_$follows") {
                    val key = teamKey(e, s)
                    val on = isFavTeam(e, s)
                    Pill((if (on) "★ " else "☆ ") + s.short, onClick = {
                        App.store.favouriteTeams = if (on) App.store.favouriteTeams.filterNot { it == key || it.endsWith("|${s.name}") }.toSet()
                            else App.store.favouriteTeams + key
                        follows++
                    }, selected = on)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Panel("FORM · LAST FIVE", Modifier.weight(1f)) {
                    for ((s, p) in listOf(home to homePage, away to awayPage)) if (s != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(s.short, style = T.body.copy(fontWeight = FontWeight.Bold, color = Color.White), modifier = Modifier.weight(1f), maxLines = 1)
                        FormBoxes(p?.form.orEmpty(), lettered = true)
                    }
                    if (homePage == null && awayPage == null) Text("No recent results for this competition.", style = T.tiny.copy(color = SportsLook.Dim))
                }
                Panel("MATCH INFO", Modifier.weight(1f)) {
                    for (line in listOfNotNull(e.venue, e.subtitle ?: e.league.name, e.detail.takeIf { e.state != State.PRE && it.isNotBlank() }, e.summary))
                        Text(line, style = T.small.copy(color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(0.82f).fillMaxHeight().verticalScroll(rememberScrollState())) {
            videoSlot()
            Spacer(Modifier.height(10.dp))
            Panel(null, Modifier.fillMaxWidth()) {
                Text("${Time.dayMonth(e.start)} · ${clock(e.start)}", style = T.body.copy(fontWeight = FontWeight.Bold, color = Color.White))
                e.broadcasts.takeIf { it.isNotEmpty() }?.let { Text("On TV: ${it.take(3).joinToString(", ")}", style = T.small.copy(color = SportsLook.Dim), maxLines = 1) }
            }
            Spacer(Modifier.height(10.dp))
            val t = table
            if (t != null && rows.isNotEmpty()) StandingsExcerpt(t, setOfNotNull(rowOf(home)?.id, rowOf(away)?.id), onFullTable)
        }
    }

    card?.let { s ->
        val page = if (s.id == home?.id) homePage else awayPage
        TeamCard(s, page, rowOf(s), if (e.league.sport == io.github.novatv.play.sports.Sport.CRICKET) (e.subtitle ?: e.league.name) else e.league.name, e,
            onClose = { card = null }, onGameInfo = { infoSide = s; card = null; gameInfo = true })
    }
    // From a team's card, Back (the button or the remote's) returns to that card; Close shuts both.
    if (gameInfo) GameInfoDialog(e, infoSide, onBack = infoSide?.let { s -> { gameInfo = false; infoSide = null; card = s } }) { gameInfo = false; infoSide = null }
    if (picking) ChannelsPopup(e, 0) { picking = false }
}

/** Both sides in their colours: logo, name, record and home/away; place and last five; time or score on the right. */
@Composable
private fun Scoreboard(e: SportEvent, hp: TeamPage?, ap: TeamPage?, hr: StandingsRow?, ar: StandingsRow?, first: androidx.compose.ui.focus.FocusRequester, onTeam: (Side) -> Unit) {
    val (hc, ac) = teamShades(e.home, e.away)
    Row(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(SportsLook.Card)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Golf, racing, a fight night: no two sides, so the event's name; the page still takes focus here.
            if (e.home == null || e.away == null) Tile({}, Modifier.fillMaxSize().focusRequester(first), shape = RoundedCornerShape(0.dp), scale = 1f, color = Color.Transparent) {
                Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
                    Text(e.title, style = T.h1.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(e.subtitle ?: e.league.name, style = T.small.copy(color = SportsLook.Dim))
                }
            }
            for ((s, info) in listOf(Triple(e.home, hp, hr) to hc, Triple(e.away, ap, ar) to ac)) {
                val (side, page, row) = s
                if (side == null) continue
                val tone = rememberLogoTone(side.logo) ?: info
                Tile({ onTeam(side) }, Modifier.weight(1f).fillMaxWidth().then(if (side == e.home) Modifier.focusRequester(first) else Modifier), shape = RoundedCornerShape(0.dp), scale = 1f,
                    color = Color.Transparent) {
                    Row(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bandColor(tone), 1f to lerp(bandColor(tone), Color.Black, 0.6f)))
                        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LogoChip(side.logo, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(side.name, style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(page?.record, if (side == e.home) "Home" else "Away").joinToString(" · "),
                                style = T.small.copy(color = Color.White.copy(alpha = 0.8f)), maxLines = 1)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            row?.let { Text(ordinal(it.pos), style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White)) }
                            FormBoxes(page?.form.orEmpty(), lettered = false)
                        }
                        if (e.state != State.PRE && !App.store.hideScores) {
                            Spacer(Modifier.width(14.dp))
                            // Cricket's long scores ("217 & 360 (130.3 ov)") smaller and kept to half the band, so the name shows.
                            val sc = side.score ?: "–"
                            Text(sc, style = (if (sc.length > 9) T.h2 else T.h1).copy(fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.End),
                                maxLines = 2, modifier = Modifier.widthIn(max = 190.dp))
                        }
                    }
                }
            }
        }
        Column(Modifier.width(190.dp).fillMaxHeight().background(Color(0x22FFFFFF)).padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            when (e.state) {
                State.PRE -> {
                    Text(Time.dayMonth(e.start).uppercase(), style = T.tiny.copy(color = SportsLook.Dim))
                    Text(clock(e.start), style = T.h1.copy(fontWeight = FontWeight.Bold, color = Color.White))
                    Text(untilText(e.start - io.github.novatv.play.platform.nowMs()), style = T.tiny.copy(color = SportsLook.Dim))
                }
                else -> {
                    Text(if (e.state == State.LIVE) "● LIVE" else "FINAL", style = T.small.copy(fontWeight = FontWeight.Bold, color = if (e.state == State.LIVE) SportsLook.Live else Color.White))
                    Text(e.detail, style = T.small.copy(color = Color.White, textAlign = TextAlign.Center), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            e.broadcasts.firstOrNull()?.let {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).padding(horizontal = 10.dp, vertical = 2.dp)) {
                    Text(it, style = T.tiny.copy(color = Color.White), maxLines = 1)
                }
            }
        }
    }
}

/** A team's card: place, points, goal difference and record; its last three results and its next match. */
@Composable
private fun TeamCard(s: Side, page: TeamPage?, row: StandingsRow?, league: String, e: SportEvent, onClose: () -> Unit, onGameInfo: () -> Unit) {
    val follow = remember { mutableIntStateOf(0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(0.62f).heightIn(max = 620.dp).clip(RoundedCornerShape(20.dp)).background(C.panel)
            .border(1.dp, SportsLook.Border, RoundedCornerShape(20.dp)).verticalScroll(rememberScrollState()).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoChip(s.logo, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.name, style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White))
                    Text(if (e.league.sport == io.github.novatv.play.sports.Sport.CRICKET) league else row?.let { "${ordinal(it.pos)} in $league" } ?: page?.standing ?: league, style = T.small.copy(color = SportsLook.Dim))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Cricket: the ICC rankings' place, rating and matches (no league points or goal difference).
                for ((label, value) in if (e.league.sport == io.github.novatv.play.sports.Sport.CRICKET) listOf(
                    "ICC RANK" to row?.let { ordinal(it.pos) },
                    "RATING" to row?.values?.get("points"),
                    "MATCHES" to row?.values?.get("gamesPlayed"),
                ) else listOf(
                    "PLACE" to row?.let { ordinal(it.pos) },
                    "POINTS" to row?.values?.get("points"),
                    "GOAL DIFF" to row?.values?.get("pointDifferential"),
                    "RECORD" to page?.record,
                )) Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).border(1.dp, SportsLook.Border, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text(label, style = T.tiny.copy(color = SportsLook.Dim), maxLines = 1)
                    Text(value ?: "–", style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1)
                }
            }
            Spacer(Modifier.height(14.dp))
            if (page == null) Text("No team page for this competition.", style = T.small.copy(color = SportsLook.Dim))
            page?.let { p ->
                Text("LAST 3", style = T.tiny.copy(color = SportsLook.Dim))
                for (g in p.past.take(3)) ResultLine(p, g)
                p.next.firstOrNull()?.let { n ->
                    Spacer(Modifier.height(8.dp))
                    Text("NEXT", style = T.tiny.copy(color = SportsLook.Dim))
                    ResultLine(p, n)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val key = page?.let { "${it.league.sport.name}|${s.id}|${s.name}" }
                val on = follow.intValue >= 0 && App.store.favouriteTeams.any { it.endsWith("|${s.name}") || it == key }
                if (key != null) Pill(if (on) "★ Following" else "☆ Follow", onClick = {
                    App.store.favouriteTeams = if (on) App.store.favouriteTeams.filterNot { it.endsWith("|${s.name}") || it == key }.toSet() else App.store.favouriteTeams + key
                    follow.intValue++
                }, selected = on)
                Pill("Game info", onClick = onGameInfo, primary = true)
            }
        }
    }
}

/** "W  @ Celtic · Thu 5 Nov   2–3", or for a match to come "v Genclerbirligi · Tomorrow 1:00 PM". */
@Composable
private fun ResultLine(p: TeamPage, g: SportEvent) {
    val me = g.teams.firstOrNull { it.id == p.id }
    val them = g.teams.firstOrNull { it.id != p.id }
    val r = p.result(g)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (r != null) Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(formColor(r)), contentAlignment = Alignment.Center) {
            Text(r.toString(), style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold))
        } else Spacer(Modifier.width(22.dp))
        Spacer(Modifier.width(10.dp))
        val at = if (me == g.home) "v" else "@"
        val whenText = if (g.state == State.POST) Time.dayMonth(g.start) else "${dayLabel(g.start)} ${clock(g.start)}"
        Text("$at ${them?.name ?: g.title}  ·  $whenText", style = T.small.copy(color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (g.state == State.POST) Text("${me?.score ?: "–"}–${them?.score ?: "–"}", style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End),
            modifier = Modifier.width(64.dp))
    }
}

/**
 * The top of the league table, the two teams highlighted: their own group when the league has groups (each group's
 * places start at 1), the first [EXCERPT_ROWS] places; the rest is in the full table.
 */
@Composable
internal fun StandingsExcerpt(t: Standings, mark: Set<String>, onFullTable: () -> Unit) {
    val group = t.groups.firstOrNull { g -> g.rows.any { it.id in mark } } ?: t.groups.firstOrNull() ?: return
    // The ICC rankings have no Tables page: they open out here instead.
    val inPlace = t.leagueId.startsWith("icc.") || t.leagueId.startsWith("fifa.")
    var all by remember(t.leagueId) { mutableStateOf(false) }
    val sorted = group.rows.distinctBy { it.id }.sortedBy { it.pos }
    val keep = if (all) sorted else sorted.take(EXCERPT_ROWS)
    // Played, goal (or points) difference and points where the league has them, as on a football table.
    val wanted = listOf("gamesPlayed", "pointDifferential", "points").mapNotNull { k -> t.columns.firstOrNull { it.key == k } }
    val cols = if (wanted.size == 3) wanted else t.columns.takeLast(3)
    Panel(null, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text((t.title ?: "${(io.github.novatv.play.sports.Catalog.leagues.firstOrNull { it.id == t.leagueId }?.name ?: "League").uppercase()} STANDINGS") +
                    (group.name.takeIf { it.isNotBlank() && t.groups.size > 1 }?.let { " · ${it.uppercase()}" } ?: ""),
                style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            if (!inPlace || sorted.size > EXCERPT_ROWS) Tile({ if (inPlace) all = !all else onFullTable() }, shape = RoundedCornerShape(8.dp), scale = 1f, color = Color.Transparent) {
                Text(if (inPlace) (if (all) "TOP $EXCERPT_ROWS ‹" else "ALL ${sorted.size} ›") else "FULL TABLE ›", style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Row(Modifier.padding(top = 8.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF)).padding(vertical = 4.dp, horizontal = 6.dp)) {
            Text("#", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.width(30.dp))
            Text("TEAM", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            for (c in cols) Text(c.label, style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), modifier = Modifier.width(48.dp))
        }
        for ((i, r) in keep.withIndex()) {
            TableLine(r, cols.map { r.values[it.key] ?: "–" }, highlighted = r.id in mark, zebra = i % 2 == 1, numWidth = 48.dp)
        }
    }
}

@Composable
private fun Panel(title: String?, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(SportsLook.Card).border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp)).padding(14.dp)) {
        if (title != null) { Text(title, style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold)); Spacer(Modifier.height(8.dp)) }
        content()
    }
}

/** Last five as coloured squares, oldest first; with letters in the form panel, plain on the scoreboard. */
@Composable
internal fun FormBoxes(form: List<Char>, lettered: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (f in form) Box(Modifier.size(if (lettered) 22.dp else 11.dp).clip(RoundedCornerShape(if (lettered) 5.dp else 3.dp)).background(formColor(f)),
            contentAlignment = Alignment.Center) {
            if (lettered) Text(f.toString(), style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp))
        }
    }
}

private fun formColor(r: Char?) = when (r) { 'W' -> Color(0xFF2EAD5B); 'L' -> Color(0xFFD64541); 'D' -> Color(0xFF6B7280); else -> Color(0xFF3A3F4A) }

private fun ordinal(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

/** "in 18 days 15 h", "in 3 h 20 min", "starting". */
private fun untilText(ms: Long): String {
    if (ms <= 0) return "starting"
    val min = ms / 60_000; val h = min / 60; val d = h / 24
    return when { d > 0 -> "in $d days ${h % 24} h"; h > 0 -> "in $h h ${min % 60} min"; else -> "in $min min" }
}

/**
 * A click (or OK) on a channel of the match, as in Live TV: it plays in the TV window at once; on the one already in
 * the window it goes full screen in that same player, and Back brings it back to the window still playing.
 */
internal fun watchOrFull(c: Channel, list: List<Channel>, watch: (Channel) -> Unit) {
    when (io.github.novatv.play.data.okAction(c, App.preview.state.value, App.preview.available)) {
        io.github.novatv.play.data.OkAction.WATCH -> if (App.preview.available) playFromPreview(c, list) else playChannel(list, list.indexOfFirst { it.id == c.id }.coerceAtLeast(0), switchOnFailure = true)
        io.github.novatv.play.data.OkAction.PREVIEW -> watch(c)
    }
}

/** Full screen straight away (a hold, or double click): through the window, so Back returns to it still playing. */
internal fun fullScreen(c: Channel, list: List<Channel>, watch: (Channel) -> Unit) {
    if (App.preview.available && App.preview.channel?.id != c.id) watch(c)
    if (App.preview.available) playFromPreview(c, list) else playChannel(list, list.indexOfFirst { it.id == c.id }.coerceAtLeast(0), switchOnFailure = true)
}
internal const val EXCERPT_ROWS = 10

/** A team's figures in this game, the other side's beside each when there is one. */
@Composable
internal fun GameStats(lines: List<io.github.novatv.play.sports.MatchStats.Line>, mine: String, other: String?) {
    val paired = lines.any { it.other != null }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).border(1.dp, SportsLook.Border, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        if (paired) Row(Modifier.padding(bottom = 2.dp)) {
            Spacer(Modifier.weight(1f))
            Text(mine, style = T.tiny.copy(color = SportsLook.Blue, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), modifier = Modifier.width(70.dp), maxLines = 1)
            Text(other.orEmpty(), style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), modifier = Modifier.width(70.dp), maxLines = 1)
        }
        for (l in lines) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(l.label, style = T.small.copy(color = SportsLook.Dim), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(l.value, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = if (paired) TextAlign.Center else TextAlign.End),
                modifier = if (paired) Modifier.width(70.dp) else Modifier, maxLines = 1)
            if (paired) Text(l.other ?: "–", style = T.small.copy(color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center), modifier = Modifier.width(70.dp), maxLines = 1)
        }
    }
}

/**
 * The whole match: cricket's scorecards, football's key moments and line-ups, other sports' box scores, then both
 * sides' figures. Scrolls (the remote's up and down move through it); Back closes it.
 */
@Composable
private fun GameInfoDialog(e: SportEvent, side: Side?, onBack: (() -> Unit)?, onClose: () -> Unit) {
    val info by produceState<io.github.novatv.play.sports.GameInfo.Info?>(null, e.id, side?.id) {
        value = withContext(IO) { runCatching { io.github.novatv.play.sports.GameInfo.forEvent(e, side) }.getOrDefault(io.github.novatv.play.sports.GameInfo.Info(emptyList(), emptyList())) }
    }
    val scroll = rememberScrollState()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Left and right on the remote: (how many presses, which way), for the innings tabs.
    var sideStep by remember { mutableStateOf(0 to 0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onBack ?: onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.runtime.LaunchedEffect(Unit) { repeat(10) { try { focus.requestFocus(); return@LaunchedEffect } catch (x: Exception) { kotlinx.coroutines.delay(80) } } }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        // One team's: narrower, so its single pitch and tables aren't lost in empty space.
        Column(Modifier.widthIn(max = if (side != null) 760.dp else 1100.dp).fillMaxWidth(0.86f).heightIn(max = 640.dp).clip(RoundedCornerShape(20.dp)).background(C.panel)
            .border(1.dp, SportsLook.Border, RoundedCornerShape(20.dp))
            // The remote moves the page: up and down scroll it; left and right go to the innings before or after.
            .focusRequester(focus)
            .scrollKeys(onSide = { right -> sideStep = (sideStep.first + 1) to (if (right) 1 else -1) }) { up -> scope.launch { scroll.animateScrollBy(if (up) -260f else 260f) } }
            .focusable()
            .verticalScroll(scroll).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                onBack?.let { back -> Pill("Back", onClick = back, icon = androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack); Spacer(Modifier.width(14.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(if (side != null) "${side.name} · ${e.title}" else e.title, style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(e.subtitle ?: e.league.name, e.detail.ifBlank { null }, e.summary).joinToString(" · "), style = T.small.copy(color = SportsLook.Dim), maxLines = 2)
                }
                Pill("Close", onClick = onClose)
            }
            Spacer(Modifier.height(14.dp))
            val i = info
            when {
                i == null -> Spinner(size = 28.dp)
                i.isEmpty -> Text(if (e.state == State.PRE) "Line-ups and figures appear once the game is close to starting." else "No details for this game.",
                    style = T.small.copy(color = SportsLook.Dim))
                else -> GameInfoBody(i, e.teams.associate { it.name to it.color }, narrow = false, sideStep = sideStep)
            }
        }
    }
}


/** Up and down (key presses, not releases) handed to [move], true for up; left and right to [onSide], true for right. */
private fun Modifier.scrollKeys(onSide: (Boolean) -> Unit = {}, move: (Boolean) -> Unit): Modifier = this.then(Modifier.onPreviewKeyEvent { e ->
    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (e.key) {
        androidx.compose.ui.input.key.Key.DirectionUp -> { move(true); true }
        androidx.compose.ui.input.key.Key.DirectionDown -> { move(false); true }
        androidx.compose.ui.input.key.Key.DirectionLeft -> { onSide(false); true }
        androidx.compose.ui.input.key.Key.DirectionRight -> { onSide(true); true }
        else -> false
    }
})
