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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Sort
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.League
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportsCatalog
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.TeamInfo
import io.github.novatv.play.sports.bandColor
import io.github.novatv.play.sports.icon
import io.github.novatv.play.sports.parseTeamColor
import io.github.novatv.play.sports.sportsFrame
import io.github.novatv.play.toast

/**
 * Follow Teams & Leagues screen (matching NuvioPlus design):
 * - Left column: Sport categories with icons and followed counts
 * - Top: Horizontal Followed pills row ("FOLLOWING · X")
 * - Center: Horizontal league selector, Follow League toggle, and alphabetical team cards grid with team colors
 */
@Composable
fun SportsPickerScreen(nav: Nav) {
    val store = App.store
    var version by remember { mutableIntStateOf(0) }
    var selectedSport by remember { mutableStateOf(Sport.AMERICAN_FOOTBALL) }
    val leagues = remember(selectedSport) { SportsCatalog.leaguesForSport(selectedSport) }
    var selectedLeague by remember(selectedSport) { mutableStateOf(leagues.firstOrNull()) }
    var searchQuery by remember { mutableStateOf("") }

    val rawTeams by produceState<List<TeamInfo>>(emptyList(), selectedLeague, version) {
        val l = selectedLeague
        value = if (l != null) SportsCatalog.teams(l) else emptyList()
    }

    val displayTeams = remember(rawTeams, searchQuery) {
        if (searchQuery.isBlank()) rawTeams
        else rawTeams.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.fullName.contains(searchQuery, ignoreCase = true) ||
            (it.location != null && it.location.contains(searchQuery, ignoreCase = true))
        }
    }

    val followedTeams = remember(version) { store.favouriteTeams }
    val followedLeagues = remember(version) { store.sportsLeagues }

    Box(Modifier.fillMaxSize().background(Color(0xFF0F1116))) {
        Column(Modifier.fillMaxSize().padding(start = 36.dp, end = 36.dp, top = 20.dp, bottom = 20.dp)) {
            // ---- Top Header Row ----
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Pill("‹ Sports", onClick = { nav.pop() })
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Follow Teams & Leagues", style = T.h1.copy(fontSize = 24.sp, fontWeight = FontWeight.Black))
                    Text(
                        "Teams: their games, reminders and scoring alerts. Leagues: their schedule on My Sports.",
                        style = T.small.copy(color = SportsLook.Dim)
                    )
                }
                Box(Modifier.width(260.dp)) {
                    InputField(
                        value = searchQuery,
                        onChange = { searchQuery = it },
                        hint = "Search teams and leagues",
                        modifier = Modifier.fillMaxWidth().height(42.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Pill(": Arrange", onClick = { toast("Arrange view") })
                Spacer(Modifier.width(8.dp))
                Pill("✓ Done", onClick = { nav.pop() }, selected = true)
            }

            Spacer(Modifier.height(14.dp))

            // ---- Following Bar ----
            val totalFollows = followedTeams.size + followedLeagues.size
            Column(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(SportsLook.Card)
                        .border(1.dp, SportsLook.Border, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("FOLLOWING · $totalFollows", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.ExtraBold))
                    Spacer(Modifier.width(12.dp))
                    if (totalFollows == 0) {
                        Text("Nothing yet: pick a team or league below.", style = T.small.copy(color = SportsLook.Faint))
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                            items(followedTeams.toList(), key = { "t_$it" }) { item ->
                                val parts = item.split("|")
                                val tName = parts.lastOrNull() ?: item
                                FollowedChip(tName) {
                                    store.favouriteTeams = store.favouriteTeams - item
                                    version++
                                }
                            }
                            items(followedLeagues.toList(), key = { "l_$it" }) { lId ->
                                val l = Catalog.league(lId)
                                FollowedChip(l?.name ?: lId) {
                                    store.sportsLeagues = store.sportsLeagues - lId
                                    version++
                                }
                            }
                        }
                    }
                }
                if (totalFollows > 0) {
                    Text("OK on one to stop following it", style = T.tiny.copy(color = SportsLook.Faint), modifier = Modifier.padding(start = 14.dp, top = 3.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            // ---- Main 2-Pane Picker ----
            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Left Sports Column
                LazyColumn(
                    Modifier.width(200.dp).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(Sport.entries.toList(), key = { it.name }) { sp ->
                        val count = followedTeams.count { it.startsWith("${sp.name}|") } +
                                followedLeagues.count { Catalog.league(it)?.sport == sp }
                        SportSidebarItem(
                            sport = sp,
                            selected = sp == selectedSport && searchQuery.isBlank(),
                            count = count,
                            onClick = {
                                selectedSport = sp
                                selectedLeague = SportsCatalog.leaguesForSport(sp).firstOrNull()
                                searchQuery = ""
                            }
                        )
                    }
                }

                Spacer(Modifier.width(18.dp))

                // Right Content Area: League tabs + Alphabetical teams grid
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // League horizontal chips
                    if (leagues.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            items(leagues, key = { it.id }) { l ->
                                val sel = l.id == selectedLeague?.id
                                val isPinned = l.id in followedLeagues
                                Pill(
                                    text = l.name + if (isPinned) " ★" else "",
                                    selected = sel,
                                    onClick = { selectedLeague = l }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    selectedLeague?.let { league ->
                        val isFollowed = league.id in followedLeagues
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Pill(
                                text = if (isFollowed) "★ Following ${league.name}" else "☆ Follow ${league.name}",
                                selected = isFollowed,
                                onClick = {
                                    val now = store.sportsLeagues
                                    store.sportsLeagues = if (isFollowed) now - league.id else now + league.id
                                    version++
                                }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }

                    // Alphabetical teams grid
                    if (displayTeams.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Loading teams or none found...", style = T.body.copy(color = SportsLook.Dim))
                        }
                    } else {
                        val grouped = remember(displayTeams) {
                            displayTeams.groupBy { team ->
                                (team.location ?: team.name).trim().firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#'
                            }.entries.sortedBy { it.key }.associate { it.key to it.value }
                        }

                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = 30.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            grouped.forEach { (letter, teamsInGroup) ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Text(
                                        letter.toString(),
                                        style = T.h2.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = SportsLook.Dim),
                                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                                    )
                                }
                                items(teamsInGroup, key = { it.key }) { t ->
                                    val teamFavKey = "${selectedSport.name}|${t.id}|${t.name}"
                                    val followed = teamFavKey in followedTeams || store.favouriteTeams.any { it.endsWith("|${t.name}") }
                                    TeamPickerCard(
                                        team = t,
                                        followed = followed,
                                        onClick = {
                                            val cur = store.favouriteTeams.toMutableSet()
                                            if (followed) {
                                                cur.removeAll { it.endsWith("|${t.name}") || it == teamFavKey }
                                            } else {
                                                cur.add(teamFavKey)
                                            }
                                            store.favouriteTeams = cur
                                            version++
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowedChip(title: String, onRemove: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(SportsLook.CardRaised)
            .border(1.dp, if (focused) SportsLook.Gold else SportsLook.Border, RoundedCornerShape(50))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onRemove)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(title, style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White))
        Spacer(Modifier.width(6.dp))
        Text("✕", style = T.tiny.copy(color = SportsLook.Dim))
    }
}

@Composable
private fun SportSidebarItem(sport: Sport, selected: Boolean, count: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(SportsLook.SmallShape)
            .background(if (selected) C.accent.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                1.5.dp,
                if (focused) SportsLook.Gold else if (selected) C.accent else Color.Transparent,
                SportsLook.SmallShape
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(SportsLook.Card)
                .border(1.dp, SportsLook.Border, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(sport.icon, style = T.tiny.copy(fontSize = 13.sp))
        }
        Spacer(Modifier.width(10.dp))
        Text(sport.label, style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White), modifier = Modifier.weight(1f))
        if (count > 0) {
            Box(
                Modifier.clip(CircleShape).background(C.accent).padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("$count", style = T.tiny.copy(color = Color.Black, fontWeight = FontWeight.Bold))
            }
        }
    }
}

@Composable
private fun TeamPickerCard(team: TeamInfo, followed: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val baseColor = parseTeamColor(team.color)
    val band = bandColor(baseColor)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .sportsFrame(focused, playing = followed && !focused, accent = SportsLook.Gold, radius = 12.dp)
            .background(
                Brush.horizontalGradient(
                    0f to band.copy(alpha = 0.95f),
                    0.75f to band.copy(alpha = 0.30f),
                    1f to Color(0xFF14171E).copy(alpha = 0.85f)
                )
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!team.logo.isNullOrBlank()) {
                AsyncImage(
                    model = team.logo,
                    contentDescription = team.name,
                    modifier = Modifier.size(36.dp)
                )
            } else {
                Box(Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF222632)), contentAlignment = Alignment.Center) {
                    Text(team.abbr.take(3), style = T.tiny.copy(fontWeight = FontWeight.Bold))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(team.name, style = T.small.copy(fontWeight = FontWeight.Black, color = Color.White), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(team.location, team.record).joinToString(" · ")
                if (sub.isNotBlank()) {
                    Text(sub, style = T.tiny.copy(color = Color.White.copy(alpha = 0.65f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (followed) {
                Box(
                    Modifier.size(20.dp).clip(CircleShape).background(SportsLook.Gold),
                    contentAlignment = Alignment.Center
                ) {
                    Text("★", style = T.tiny.copy(fontSize = 11.sp, color = Color.Black, fontWeight = FontWeight.Black))
                }
            }
        }

        if (focused) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.White).padding(vertical = 1.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (followed) "FOLLOWING · OK TO UNFOLLOW" else "OK TO FOLLOW",
                    style = T.tiny.copy(fontSize = 8.sp, color = Color.Black, fontWeight = FontWeight.Black)
                )
            }
        }
    }
}
