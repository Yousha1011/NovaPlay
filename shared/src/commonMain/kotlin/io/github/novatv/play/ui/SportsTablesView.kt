package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import io.github.novatv.play.platform.IO
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.League
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.Standings
import io.github.novatv.play.sports.StandingsColumn
import io.github.novatv.play.sports.StandingsKind
import io.github.novatv.play.sports.StandingsRepository
import io.github.novatv.play.sports.StandingsRow
import io.github.novatv.play.sports.bandColor
import io.github.novatv.play.sports.parseTeamColor
import io.github.novatv.play.sports.sportsFrame
import kotlinx.coroutines.withContext

private val StandingsRow.gamesPlayed: String get() = values["gamesPlayed"] ?: values["gp"] ?: "-"
private val StandingsRow.winsCount: String get() = values["wins"] ?: values["w"] ?: "0"
private val StandingsRow.tiesCount: String get() = values["ties"] ?: values["t"] ?: values["d"] ?: "0"
private val StandingsRow.lossesCount: String get() = values["losses"] ?: values["l"] ?: "0"
private val StandingsRow.pointsCount: String get() = values["points"] ?: values["pts"] ?: "0"
private val StandingsRow.diffCount: String get() = values["pointDifferential"] ?: values["diff"] ?: "0"

/**
 * 🏆 Tables & Standings view (matching Screenshot 3):
 * - League selector chips row across top
 * - Subtitle: "Season 2026-27 [League] · from ESPN"
 * - Left column: Standings table with qualification colored strips, team colors, Last 5 form squares
 * - Right column: Mini video player + Selected Team Summary card + Qualification Legend card
 * - Footer: "OK on a team: its card · ◄ ► on the leagues: another table"
 */
@Composable
fun SportsTablesView(
    initialLeagueId: String? = null,
    videoSlot: (@Composable () -> Unit)? = null
) {
    val store = App.store
    val leaguesWithTables = remember {
        Catalog.leagues.filter { l ->
            l.path != null && (l.sport == Sport.FOOTBALL || l.sport == Sport.BASKETBALL ||
                    l.sport == Sport.AMERICAN_FOOTBALL || l.sport == Sport.HOCKEY ||
                    l.sport == Sport.BASEBALL || l.sport == Sport.MOTORSPORT)
        }
    }
    var selLeague by remember {
        mutableStateOf(leaguesWithTables.firstOrNull { it.id == initialLeagueId } ?: leaguesWithTables.firstOrNull())
    }
    var standings by remember { mutableStateOf<Standings?>(null) }
    var loading by remember { mutableStateOf(false) }
    var focusedRow by remember { mutableStateOf<StandingsRow?>(null) }

    LaunchedEffect(selLeague) {
        val l = selLeague ?: return@LaunchedEffect
        loading = true
        standings = withContext(IO) { StandingsRepository.forLeague(l) }
        focusedRow = standings?.groups?.firstOrNull()?.rows?.firstOrNull()
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        // League selection row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).rememberFocusInList()
        ) {
            items(leaguesWithTables) { l ->
                Pill(
                    l.name,
                    onClick = { selLeague = l },
                    selected = selLeague?.id == l.id
                )
            }
        }

        // Subtitle line
        Text(
            listOfNotNull(standings?.season ?: selLeague?.name, "from ESPN").joinToString(" · "),
            style = T.small.copy(color = SportsLook.Dim),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (loading && standings == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            return@Column
        }

        val s = standings
        if (s == null || s.groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Message("No standings available", "Live standings could not be loaded for ${selLeague?.name ?: "this league"}.")
            }
            return@Column
        }

        val allRows = remember(s) { s.groups.flatMap { it.rows } }
        val hasForm = remember(s) { allRows.any { !it.form.isNullOrBlank() } }
        val activeRow = focusedRow ?: allRows.firstOrNull()

        // Two-column split layout: Left table, Right preview and summaries
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Left Table Pane
            Column(Modifier.width(520.dp).fillMaxHeight()) {
                // Table Column Header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("#", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.width(26.dp))
                    Text("TEAM", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    s.columns.forEach { col ->
                        Text(col.label, style = T.tiny.copy(color = SportsLook.Dim, fontWeight = if (col.key == "points") FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center), modifier = Modifier.width(colWidth(col)))
                    }
                    if (hasForm) Text("LAST 5", style = T.tiny.copy(color = SportsLook.Dim, textAlign = TextAlign.Center), modifier = Modifier.width(72.dp))
                }

                LazyColumn(
                    Modifier.fillMaxSize().rememberFocusInList(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    s.groups.forEach { group ->
                        if (group.name.isNotBlank() && s.groups.size > 1) {
                            item(key = "h_${group.name}") {
                                Text(
                                    group.name,
                                    style = T.small.copy(fontWeight = FontWeight.Bold, color = SportsLook.Dim),
                                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 6.dp)
                                )
                            }
                        }
                        items(group.rows, key = { it.id }) { row ->
                            val isFollowed = store.favouriteTeams.any { it.endsWith("|${row.name}") }
                            val isFocused = activeRow?.id == row.id
                            StandingsRowItem(
                                row = row,
                                columns = s.columns,
                                hasForm = hasForm,
                                totalRows = allRows.size,
                                isFollowed = isFollowed,
                                isFocused = isFocused,
                                onFocus = { focusedRow = row }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.width(18.dp))

            // Right Pane: Video + Team Detail + Legend
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (videoSlot != null) {
                    videoSlot()
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(180.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0C0E14))
                            .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("▶", style = T.h1.copy(fontSize = 26.sp, color = SportsLook.Dim))
                            Spacer(Modifier.height(6.dp))
                            Text("Pick a game to watch here", style = T.small.copy(fontWeight = FontWeight.Bold))
                            Text("Hold OK on a game, or open it and pick a channel", style = T.tiny.copy(color = SportsLook.Dim))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Team Summary Card
                activeRow?.let { row ->
                    val isFollowed = store.favouriteTeams.any { it.endsWith("|${row.name}") }
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SportsLook.Card)
                            .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            "${row.name.uppercase()}${if (isFollowed) " ★" else ""} · ${row.pos} IN ${selLeague?.name?.uppercase() ?: ""}",
                            style = T.eyebrow.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold),
                            maxLines = 1
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            s.columns.mapNotNull { c -> row.values[c.key]?.let { "${c.label} $it" } }.joinToString(" · "),
                            style = T.small.copy(color = Color.White)
                        )
                        Spacer(Modifier.height(4.dp))
                        row.zone?.let { Text(it.label, style = T.tiny.copy(color = Color(it.color))) }
                        Spacer(Modifier.height(6.dp))
                        Text("OK on a team: its card", style = T.tiny.copy(color = SportsLook.Faint))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Legend Card: ESPN's own zones for this league, hidden when it sends none
                val zones = remember(s) { allRows.mapNotNull { it.zone }.distinctBy { it.label } }
                if (zones.isNotEmpty()) Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SportsLook.Card)
                        .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    zones.forEach { z ->
                        LegendItem(Color(z.color), z.label)
                    }
                }
            }
        }

        // Bottom hint line
        Text(
            "OK on a team: its card · ◄ ► on the leagues: another table",
            style = T.tiny.copy(color = SportsLook.Faint),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun StandingsRowItem(
    row: StandingsRow,
    columns: List<StandingsColumn>,
    hasForm: Boolean,
    totalRows: Int,
    isFollowed: Boolean,
    isFocused: Boolean,
    onFocus: () -> Unit
) {
    val stripColor = row.zone?.let { Color(it.color) } ?: Color.Transparent

    val teamBase = parseTeamColor(row.color)
    val band = bandColor(teamBase)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SportsLook.Card)
            .background(
                Brush.horizontalGradient(
                    0f to band.copy(alpha = if (isFocused) 0.90f else 0.65f),
                    0.55f to band.copy(alpha = 0.20f),
                    1f to Color.Transparent
                )
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = if (isFocused) SportsLook.Gold else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .onFocusChanged { if (it.isFocused) onFocus() }
            .clickable { onFocus() }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left qualification color strip
        Box(
            Modifier.width(3.dp).height(24.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(stripColor)
        )
        Spacer(Modifier.width(6.dp))

        // Rank
        Text("${row.pos}", style = T.small.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), modifier = Modifier.width(22.dp))

        // Logo
        if (row.logo != null) {
            AsyncImage(model = row.logo, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
        }

        // Name
        Text(
            row.name + if (isFollowed) " ★" else "",
            style = T.small.copy(fontWeight = FontWeight.Bold, color = Color.White),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        // Numbers: the league's own columns, so hockey, US leagues and racing show their real figures
        columns.forEach { col ->
            val bold = col.key == "points" || col.key == "winPercent"
            Text(row.values[col.key] ?: "–", style = T.small.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal, color = if (bold) Color.White else Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center), modifier = Modifier.width(colWidth(col)))
        }

        // Last 5: only when ESPN sends a form guide
        if (hasForm) Row(Modifier.width(72.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            (row.form ?: "").takeLast(5).forEach { f ->
                val c = when (f.uppercaseChar()) {
                    'W' -> Color(0xFF00C853)
                    'D' -> Color(0xFF78909C)
                    'L' -> Color(0xFFE53935)
                    else -> Color(0xFF546E7A)
                }
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(c))
            }
        }
    }
}

private fun colWidth(col: StandingsColumn) = when (col.key) {
    "pointDifferential", "points", "streak", "gamesBehind", "behind" -> 40.dp
    "winPercent" -> 46.dp
    else -> 30.dp
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(4.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(label, style = T.tiny.copy(color = SportsLook.Dim))
    }
}
