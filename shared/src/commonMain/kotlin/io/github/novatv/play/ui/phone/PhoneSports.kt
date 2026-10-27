package io.github.novatv.play.ui.phone

import androidx.compose.foundation.background
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

private enum class When(val label: String) { LIVE("Live now"), TODAY("Today"), UPCOMING("Upcoming"), MINE("My teams") }

/** Sports, in the iPhone's style: when (live, today...), which sport, then the matches as cards. */
@Composable
fun PhoneSports(nav: Nav) {
    val sports by App.repo.sports.collectAsState()
    val st by App.repo.state.collectAsState()
    var whenTab by remember { mutableStateOf(When.LIVE) }
    var sport by remember { mutableStateOf<Sport?>(null) }
    LaunchedEffect(Unit) { while (true) { App.repo.refreshSports(55_000L); delay(60_000L) } }
    // The channel index is built ahead, so a match's channels open at once.
    LaunchedEffect(st.epgVersion, st.lib.channels.size) { withContext(IO) { App.repo.matcher() } }
    val t = now(60_000L)
    val dayEnd = io.github.novatv.play.platform.Time.dayStart(t) + 24 * 3600_000L - 60_000L
    val list = remember(sports, whenTab, sport, t) {
        sports.events.asSequence().filter { Follow.shows(it) }.filter { sport == null || it.league.sport == sport }.filter { e ->
            when (whenTab) {
                When.LIVE -> e.state == State.LIVE
                When.TODAY -> e.start in (t - 12 * 3600_000L)..dayEnd && (e.state != State.POST || e.start > t - 6 * 3600_000L)
                When.UPCOMING -> e.state == State.PRE && e.start > t
                When.MINE -> e.teams.any { isFavTeam(e, it) } && e.start > t - 24 * 3600_000L
            }
        }.sortedWith(compareBy({ it.state != State.LIVE }, { it.start })).toList()
    }
    val present = remember(sports) { sports.events.filter { Follow.shows(it) }.map { it.league.sport }.toSet() }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        TopBar(nav) { RoundIcon(Icons.Rounded.Tune, onClick = { nav.push(Route.Leagues) }) }
        Chips(When.entries.map { it.name to it.label }, whenTab.name, { whenTab = When.valueOf(it) })
        Spacer(Modifier.height(8.dp))
        Chips(listOf("ALL" to "All sports") + Sport.entries.filter { it in present }.map { it.name to it.label }, sport?.name ?: "ALL",
            { sport = if (it == "ALL") null else Sport.valueOf(it) })
        when {
            sports.loading && sports.events.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { io.github.novatv.play.ui.Spinner() }
            list.isEmpty() -> Empty(Icons.Rounded.SportsSoccer, when (whenTab) { When.LIVE -> "Nothing live right now"; When.MINE -> "No matches for your teams"; else -> "No matches" },
                when (whenTab) { When.MINE -> "Open a match and star a team to follow it."; When.LIVE -> "Try Today or Upcoming."; else -> null })
            else -> LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(list, key = { it.id }) { e -> MatchCard(e) { nav.push(Route.Match(e)) } }
            }
        }
    }
}

/** A match: its competition and time or minute on top, then each side with its logo and score. */
@Composable
private fun MatchCard(e: SportEvent, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp), strength = 0.75f).tap(onClick).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(e.league.name, e.subtitle?.takeIf { it != e.league.name }).joinToString(" · ").uppercase(), style = P.caps,
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
        State.PRE -> Text("${dayLabel(e.start)} ${clock(e.start)}", style = P.small.copy(color = P.blue))
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
        if (s.logo != null) AsyncImage(s.logo, s.name, Modifier.size(size * 0.82f), contentScale = ContentScale.Fit)
        else Text(initials(s.name), style = P.small)
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
    // What is known locally first, then the online broadcasters added below it.
    val airings by produceState<List<Airing>?>(null, e.id, st.epgVersion) {
        value = withContext(IO) { App.repo.matcher().airings(e, limit = 60) }
        val more = withContext(IO) { App.repo.matcher().airings(e, Broadcasters.forEvent(e), limit = 60) }
        value = (value.orEmpty() + more).distinctBy { it.channel.id }
    }
    Pushed(nav) {
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(listOfNotNull(e.league.name, e.subtitle?.takeIf { it != e.league.name }).joinToString(" · ").uppercase(), style = P.caps, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    if (e.home != null && e.away != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BigSide(e.home, Modifier.weight(1f))
                            Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                if (e.state == State.PRE) Text(clock(e.start), style = P.large.copy(fontSize = 30.sp))
                                else Text("${e.home.score ?: "-"} : ${e.away.score ?: "-"}", style = P.large.copy(fontSize = 30.sp), maxLines = 1)
                                Text(if (e.state == State.PRE) dayLabel(e.start) else e.detail, style = P.small.copy(color = if (e.state == State.LIVE) Color(0xFFFF3B4E) else P.dim),
                                    maxLines = 2, textAlign = TextAlign.Center)
                            }
                            BigSide(e.away, Modifier.weight(1f))
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
            item {
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Text("Watch on your channels", style = P.title, modifier = Modifier.weight(1f))
                    airings?.takeIf { it.isNotEmpty() }?.let { Text("${it.size} found", style = P.small) }
                }
            }
            val a = airings
            when {
                a == null -> item { Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { io.github.novatv.play.ui.Spinner() } }
                a.isEmpty() -> item { Empty(Icons.Rounded.SportsSoccer, "No channels found", "Not in your playlist's guide or channel names.") }
                else -> items(a.size, key = { a[it].channel.id }) { i ->
                    val air = a[i]
                    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), strength = 0.7f).tap { playChannel(a.map { it.channel }, i) }.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(air.channel.logo, air.channel.name, Modifier.size(72.dp, 50.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(air.channel.name, style = P.head, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(air.programme?.let { "${clock(it.start)}  ${it.title}" } ?: air.why, style = P.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BigSide(s: Side, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        TeamLogo(s, 64.dp)
        Spacer(Modifier.height(8.dp))
        Text(s.short, style = P.head, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
    }
}
