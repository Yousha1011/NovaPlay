package io.github.novatv.play.ui

import io.github.novatv.play.sports.StandingsColumn

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.platform.IO
import io.github.novatv.play.sports.Catalog
import io.github.novatv.play.sports.League
import io.github.novatv.play.sports.Sport
import io.github.novatv.play.sports.Standings
import io.github.novatv.play.sports.StandingsKind
import io.github.novatv.play.sports.StandingsRepository
import io.github.novatv.play.sports.StandingsRow
import kotlinx.coroutines.withContext

/**
 * 🏆 Tables & Standings view: ESPN standings with points, form, goal difference,
 * conference/division groupings, and team badges.
 */
@Composable
fun SportsTablesView(initialLeagueId: String? = null) {
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

    LaunchedEffect(selLeague) {
        val l = selLeague ?: return@LaunchedEffect
        loading = true
        standings = withContext(IO) { StandingsRepository.forLeague(l) }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        // League selection row
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp).rememberFocusInList()
        ) {
            items(leaguesWithTables) { l ->
                Pill(
                    l.name,
                    onClick = { selLeague = l },
                    selected = selLeague?.id == l.id
                )
            }
        }

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

        LazyColumn(
            Modifier.fillMaxSize().rememberFocusInList(),
            contentPadding = PaddingValues(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            s.groups.forEach { group ->
                item(key = group.name) {
                    GroupTable(group.name, s.columns, group.rows, s.kind)
                }
            }
        }
    }
}

@Composable
private fun GroupTable(
    title: String,
    columns: List<StandingsColumn>,
    rows: List<StandingsRow>,
    kind: StandingsKind
) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(C.panel).padding(16.dp)
    ) {
        if (title.isNotBlank()) {
            Text(title, style = T.h2.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 12.dp))
        }

        // Table Header
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("#", style = T.small.copy(color = C.dim, fontWeight = FontWeight.Bold), modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)
            Text("Team / Club", style = T.small.copy(color = C.dim, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
            columns.forEach { col -> val lbl = col.label
                Text(lbl, style = T.small.copy(color = C.dim, fontWeight = FontWeight.Bold), modifier = Modifier.width(42.dp), textAlign = TextAlign.Center)
            }
        }

        // Table Rows
        rows.forEachIndexed { idx, row ->
            val bg = if (idx % 2 == 1) Color(0x15FFFFFF) else Color.Transparent
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pos
                val posColor = when (row.pos) {
                    1 -> Color(0xFFFFD700)
                    2 -> Color(0xFFC0C0C0)
                    3 -> Color(0xFFCD7F32)
                    in 4..5 -> if (kind == StandingsKind.TABLE) Color(0xFF4A90E2) else Color.White
                    else -> Color.White
                }
                Text("${row.pos}", style = T.body.copy(fontWeight = FontWeight.Bold, color = posColor), modifier = Modifier.width(36.dp), textAlign = TextAlign.Center)

                // Logo
                if (row.logo != null) {
                    AsyncImage(row.logo, null, Modifier.size(24.dp).padding(end = 4.dp))
                } else {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Color(0x33FFFFFF)), contentAlignment = Alignment.Center) {
                        Text(row.abbr.take(2), style = T.tiny.copy(fontWeight = FontWeight.Bold))
                    }
                }
                Spacer(Modifier.width(8.dp))

                // Name
                Text(
                    row.name + (row.clincher?.let { " ($it)" } ?: ""),
                    style = T.body.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Values
                val vals = row.values
                val keys = columns.map { it.key }
                keys.forEach { k ->
                    val v = vals[k] ?: "-"
                    val isBold = k == "points" || k == "winPercent"
                    Text(
                        v,
                        style = if (isBold) T.body.copy(fontWeight = FontWeight.Bold, color = Color(0xFF4ADE80)) else T.body,
                        modifier = Modifier.width(42.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
