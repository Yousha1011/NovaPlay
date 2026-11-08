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
    val table by produceState<Standings?>(null, e.league.id) { value = withContext(IO) { runCatching { StandingsRepository.forLeague(e.league) }.getOrNull() } }
    val airings by produceState<List<Airing>?>(null, e.id) {
        value = withContext(IO) {
            val m = App.repo.matcher()
            (m.airings(e, limit = 40) + m.airings(e, Broadcasters.forEvent(e), limit = 40)).distinctBy { it.channel.id }
        }
    }
    var card by remember { mutableStateOf<Side?>(null) }
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
            Text("‹ Sports    " + listOfNotNull(e.subtitle ?: e.league.name, e.venue).joinToString(" · "),
                style = T.small.copy(color = SportsLook.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            Scoreboard(e, homePage, awayPage, rowOf(home), rowOf(away), first) { card = it }
            Spacer(Modifier.height(18.dp))
            Text("WATCH ON YOUR CHANNELS", style = T.eyebrow.copy(color = SportsLook.Dim))
            Spacer(Modifier.height(8.dp))
            when {
                airings == null -> Spinner(size = 24.dp)
                airings!!.isEmpty() -> Text("Not found in your channels' guide or names. Pick a channel to remember one for this game.", style = T.small)
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // OK plays it in the TV window; hold OK opens it full screen.
                    airings!!.take(3).forEachIndexed { i, a ->
                        AiringRow(a, onLongClick = { playChannel(airings!!.map { it.channel }, i, switchOnFailure = true) }) { onWatch(a.channel) }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in e.teams) item(key = "f_${s.id}_$follows") {
                    val key = teamKey(e, s)
                    val on = isFavTeam(e, s)
                    Pill((if (on) "★ " else "☆ ") + s.short, onClick = {
                        App.store.favouriteTeams = if (on) App.store.favouriteTeams.filterNot { it == key || it.endsWith("|${s.name}") }.toSet()
                            else App.store.favouriteTeams + key
                        follows++
                    }, selected = on)
                }
                item { Pill("Pick a channel", onClick = { picking = true }) }
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
        TeamCard(s, page, rowOf(s), e.league.name, onClose = { card = null })
    }
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
                            Text(side.score ?: "–", style = T.h1.copy(fontWeight = FontWeight.Bold, color = Color.White), maxLines = 1)
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
private fun TeamCard(s: Side, page: TeamPage?, row: StandingsRow?, league: String, onClose: () -> Unit) {
    val follow = remember { mutableIntStateOf(0) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(0.62f).clip(RoundedCornerShape(20.dp)).background(C.panel)
            .border(1.dp, SportsLook.Border, RoundedCornerShape(20.dp)).padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoChip(s.logo, 52.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(s.name, style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White))
                    Text(row?.let { "${ordinal(it.pos)} in $league" } ?: page?.standing ?: league, style = T.small.copy(color = SportsLook.Dim))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, value) in listOf(
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
                Pill("Game info", onClick = onClose, primary = true)
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

/** The league table around both teams: the top three, each team and its neighbours, the two teams highlighted. */
@Composable
private fun StandingsExcerpt(t: Standings, mark: Set<String>, onFullTable: () -> Unit) {
    val rows = t.groups.flatMap { it.rows }.distinctBy { it.id }.sortedBy { it.pos }
    val keep = rows.filter { r -> r.pos <= 3 || mark.any { id -> rows.firstOrNull { it.id == id }?.let { kotlin.math.abs(it.pos - r.pos) <= 1 } == true } }.take(10)
    // Played, goal (or points) difference and points where the league has them, as on a football table.
    val wanted = listOf("gamesPlayed", "pointDifferential", "points").mapNotNull { k -> t.columns.firstOrNull { it.key == k } }
    val cols = if (wanted.size == 3) wanted else t.columns.takeLast(3)
    Panel(null, Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${(io.github.novatv.play.sports.Catalog.leagues.firstOrNull { it.id == t.leagueId }?.name ?: "League").uppercase()} STANDINGS",
                style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            Tile(onFullTable, shape = RoundedCornerShape(8.dp), scale = 1f, color = Color.Transparent) {
                Text("FULL TABLE ›", style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Row(Modifier.padding(top = 6.dp)) {
            Spacer(Modifier.weight(1f))
            for (c in cols) Text(c.label, style = T.tiny.copy(color = SportsLook.Dim, textAlign = TextAlign.Center), modifier = Modifier.width(48.dp))
        }
        for (r in keep) {
            val on = r.id in mark
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(if (on) Color(0x22FFFFFF) else Color.Transparent).padding(vertical = 3.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val style = T.small.copy(color = Color.White, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                Text("${r.pos}", style = style.copy(color = SportsLook.Dim), modifier = Modifier.width(30.dp))
                Text(r.name, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                for (c in cols) Text(r.values[c.key] ?: "–", style = style.copy(textAlign = TextAlign.Center), modifier = Modifier.width(48.dp))
            }
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
private fun FormBoxes(form: List<Char>, lettered: Boolean) {
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
