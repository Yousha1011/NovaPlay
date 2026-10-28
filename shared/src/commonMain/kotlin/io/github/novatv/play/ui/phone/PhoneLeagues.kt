package io.github.novatv.play.ui.phone

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.Cricket
import io.github.novatv.play.sports.Follow
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.ui.Text
import io.github.novatv.play.ui.teamKey

/** Choose sports, in the iPhone's style: a sport at a time, switched on or off, then its competitions, series and clubs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneLeagues(nav: Nav) {
    var v by remember { mutableIntStateOf(0) }
    var sport by remember { mutableStateOf(Sport.FOOTBALL) }
    val store = App.store
    val changed = { v++ }
    val events by produceState<List<SportEvent>?>(null, sport) { value = App.repo.sportCatalog(sport) }
    val leagues = remember(sport) { Catalog.leagues.filter { it.sport == sport && it.header == null } }
    val byCountry = remember(leagues) { leagues.groupBy { it.country } }
    val comps = remember(events) {
        events.orEmpty().filter { it.league.header != null || it.league.sport == Sport.TENNIS }.mapNotNull { it.subtitle }.distinct().sorted()
    }
    val clubs = remember(events) {
        events.orEmpty().flatMap { e -> e.teams.map { (e.subtitle ?: e.league.name) to (e to it) } }
            .groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.distinctBy { it.second.name }.sortedBy { it.second.name } }
            .entries.sortedBy { it.key }.associate { it.key to it.value }
    }
    val cricket = remember(events, sport) {
        if (sport != Sport.CRICKET) emptyList() else {
            val ev = events.orEmpty()
            val sides = ev.flatMap { it.teams }.mapNotNull { Cricket.nation(it.name) }.toSet()
            val homes = ev.mapNotNull { e -> e.subtitle?.let { c -> Cricket.leagueHome(c, e.teams.map { it.name })?.let { it to c } } }
                .distinct().groupBy({ it.first }, { it.second }).mapValues { (_, l) -> l.sorted() }
            val rest = (sides + homes.keys).filter { it !in Cricket.major && it != "Other" }.sorted()
            (Cricket.major + rest + listOf("Other").filter { it in homes }).map { Triple(it, it != "Other", homes[it].orEmpty()) }
        }
    }
    fun toggleComp(k: String, on: Boolean) { store.sportsComps = if (on) store.sportsComps - k else store.sportsComps + k; changed() }

    Pushed(nav) {
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Choose sports", style = P.large, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            // The sports, each marked when it is on.
            item {
                val on = remember(v) { Sport.entries.filter { Follow.sportOn(it) }.map { it.name }.toSet() }
                Chips(Sport.entries.map { it.name to ((if (it.name in on) "● " else "") + it.label) }, sport.name, { sport = Sport.valueOf(it) })
            }
            // This sport: on or off, and its picks cleared in one go.
            item {
                val on = remember(v, sport) { Follow.sportOn(sport) }
                val picks = remember(v, sport) { Follow.hasPicks(sport) }
                Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(sport.label, style = P.title)
                            Text(if (!on) "Off: no ${sport.label.lowercase()} in Sports" else if (picks) "Your picks only" else "Every match",
                                style = P.small)
                        }
                        Box(Modifier.tap {
                            val all = Sport.entries.map { it.name }.toSet()
                            val now = store.sportsEnabled.ifEmpty { all }
                            val next = if (sport.name in now) now - sport.name else now + sport.name
                            // Every sport ticked is stored as "all", so sports added later are on too.
                            store.sportsEnabled = if (next == all) emptySet() else next
                            changed()
                        }) { Toggle(on) }
                    }
                    if (picks) {
                        Spacer(Modifier.height(12.dp))
                        Text("Clear picks", style = P.head.copy(color = P.red), modifier = Modifier.tap {
                            store.sportsLeagues = store.sportsLeagues - leagues.map { it.id }.toSet()
                            store.sportsComps = store.sportsComps.filterNot { it.startsWith(sport.name + "|") }.toSet()
                            store.favouriteTeams = store.favouriteTeams.filterNot { it.startsWith(sport.name + "|") }.toSet()
                            changed()
                        })
                    }
                }
            }
            item {
                Text("Pick nothing to follow every match, or pick competitions, countries or clubs to narrow it.", style = P.small,
                    modifier = Modifier.padding(horizontal = 20.dp))
            }

            if (leagues.isNotEmpty()) {
                item { Section("COMPETITIONS") }
                for ((country, ls) in byCountry) item(key = "c:$sport:$country") {
                    Group(country) {
                        val all = remember(v) { ls.all { it.id in store.sportsLeagues } }
                        if (byCountry.size > 1) Pick("All of $country", all) {
                            val ids = ls.map { it.id }.toSet()
                            store.sportsLeagues = if (all) store.sportsLeagues - ids else store.sportsLeagues + ids
                            changed()
                        }
                        for (l in ls) {
                            val on = remember(v) { l.id in store.sportsLeagues }
                            Pick(l.name, on) { store.sportsLeagues = if (on) store.sportsLeagues - l.id else store.sportsLeagues + l.id; changed() }
                        }
                    }
                }
            }
            if (events == null) item { Text("Loading clubs and series…", style = P.small, modifier = Modifier.padding(horizontal = 20.dp)) }

            if (sport == Sport.CRICKET) {
                item { Section("INTERNATIONAL TOURNAMENTS") }
                item {
                    Group(null) {
                        val on = remember(v) { Cricket.ALL_INTERNATIONAL in store.sportsComps }
                        Pick("All international tournaments", on) { toggleComp(Cricket.ALL_INTERNATIONAL, on) }
                    }
                }
                val groups = Cricket.tournaments.map { it.group to it.name }.distinct().groupBy({ it.first }, { it.second })
                for ((group, names) in groups) item(key = "intl:$group") {
                    Group(group) {
                        for (n in names) {
                            val k = Cricket.tournamentKey(n)
                            val on = remember(v) { k in store.sportsComps }
                            Pick(n, on) { toggleComp(k, on) }
                        }
                    }
                }
                if (cricket.isNotEmpty()) item { Section("COUNTRIES") }
                for ((country, national, comps2) in cricket) item(key = "cr:$country") {
                    Group(country) {
                        val nKey = Cricket.nationKey(country)
                        val lKey = Cricket.leaguesKey(country)
                        if (national) {
                            val both = remember(v) { nKey in store.sportsComps && lKey in store.sportsComps }
                            Pick("All of $country", both) {
                                store.sportsComps = if (both) store.sportsComps - nKey - lKey else store.sportsComps + nKey + lKey
                                changed()
                            }
                            val n = remember(v) { nKey in store.sportsComps }
                            Pick("National team", n) { toggleComp(nKey, n) }
                        }
                        val l = remember(v) { lKey in store.sportsComps }
                        Pick("All leagues", l) { toggleComp(lKey, l) }
                        for (c in comps2) {
                            val k = "${sport.name}|$c"
                            val on = remember(v) { k in store.sportsComps }
                            Pick(c, on) { toggleComp(k, on) }
                        }
                    }
                }
            }

            if (sport != Sport.CRICKET && comps.isNotEmpty()) {
                item { Section(if (sport == Sport.TENNIS) "TOURNAMENTS" else "SERIES AND TOURNAMENTS") }
                item {
                    Group(null) {
                        for (c in comps) {
                            val k = "${sport.name}|$c"
                            val on = remember(v) { k in store.sportsComps }
                            Pick(c, on) { toggleComp(k, on) }
                        }
                    }
                }
            }
            if (sport != Sport.CRICKET && clubs.isNotEmpty()) {
                item { Section(if (sport == Sport.TENNIS) "PLAYERS" else "CLUBS AND TEAMS · NEXT TWO WEEKS") }
                for ((comp, list) in clubs) item(key = "t:$sport:$comp") {
                    Group(comp) {
                        for ((e, side) in list) {
                            val on = remember(v) { Follow.teamPicked(e, side) }
                            Pick(side.name, on, star = true) {
                                store.favouriteTeams = if (on) store.favouriteTeams.filterNot { it.endsWith("|${side.name}") || it == teamKey(e, side) }.toSet()
                                    else store.favouriteTeams + teamKey(e, side)
                                changed()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) = Text(title, style = P.caps, modifier = Modifier.padding(start = 20.dp, top = 22.dp))

/** A titled card holding picks that wrap onto as many lines as they need. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Group(title: String?, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().glass(RoundedCornerShape(20.dp), strength = 0.6f).padding(14.dp)) {
        if (title != null) { Text(title, style = P.head); Spacer(Modifier.height(10.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** One pick: accent-filled with a tick (or a star for a team) when chosen. */
@Composable
private fun Pick(label: String, on: Boolean, star: Boolean = false, onClick: () -> Unit) {
    Box((if (on) Modifier.clip(RoundedCornerShape(50)).background(P.blue) else Modifier.glass(RoundedCornerShape(50)))
        .tap(onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text((if (on) (if (star) "★ " else "✓ ") else "") + label, style = P.head.copy(fontSize = 14.sp, color = if (on) Color.Black else Color.White), maxLines = 1)
    }
}

@Composable
private fun Toggle(on: Boolean) {
    Box(Modifier.size(52.dp, 32.dp).clip(RoundedCornerShape(16.dp)).background(if (on) Color(0xFF30D158) else P.card2).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.size(26.dp).clip(RoundedCornerShape(13.dp)).background(Color.White))
    }
}

