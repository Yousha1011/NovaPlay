package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.StandingsRow

/**
 * The place in a table as a small round chip, tinted by its zone (Champions League, relegation) when the league
 * sends one, else gold, silver and bronze for the top three.
 */
@Composable
internal fun RankChip(row: StandingsRow, size: Dp = 24.dp) {
    val zone = row.zone?.let { Color(it.color) }
    val medal = when (row.pos) { 1 -> Color(0xFFF5C400); 2 -> Color(0xFFB8C0CC); 3 -> Color(0xFFCD7F4A); else -> null }
    val tint = zone ?: medal
    Box(Modifier.size(size).clip(CircleShape).background(tint?.copy(alpha = 0.22f) ?: Color(0x10FFFFFF))
        .border(1.dp, tint?.copy(alpha = 0.85f) ?: Color(0x22FFFFFF), CircleShape), contentAlignment = Alignment.Center) {
        Text("${row.pos}", style = T.tiny.copy(color = if (tint != null) Color.White else SportsLook.Dim, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
    }
}

/** One line of a short table: rank chip, crest, name and figures, the last (points) in a pill. */
@Composable
internal fun TableLine(row: StandingsRow, figures: List<String>, highlighted: Boolean, zebra: Boolean, numWidth: Dp) {
    val shape = RoundedCornerShape(8.dp)
    val back = when {
        highlighted -> Brush.horizontalGradient(listOf(SportsLook.Blue.copy(alpha = 0.26f), Color(0x08FFFFFF)))
        zebra -> Brush.horizontalGradient(listOf(Color(0x0AFFFFFF), Color(0x0AFFFFFF)))
        else -> Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    Row(Modifier.fillMaxWidth().height(34.dp).clip(shape).background(back)
        .border(1.dp, if (highlighted) SportsLook.Blue.copy(alpha = 0.65f) else Color.Transparent, shape)
        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RankChip(row, 22.dp)
        Spacer(Modifier.width(8.dp))
        if (row.logo != null) { AsyncImage(row.logo, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        Text(row.name, style = T.small.copy(color = Color.White, fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium),
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        figures.forEachIndexed { i, f ->
            if (i == figures.lastIndex) PointsPill(f, numWidth)
            else Text(f, style = T.small.copy(color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center), modifier = Modifier.width(numWidth))
        }
    }
}

/** Points, the figure that matters, in a soft pill. */
@Composable
internal fun PointsPill(text: String, width: Dp) {
    Box(Modifier.width(width), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(text, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center))
        }
    }
}
