package io.github.novatv.play.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.sports.GameInfo
import kotlinx.coroutines.launch
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.parseTeamColor

/**
 * A side's formation on a pitch, keeper at the bottom: a shirt per player in the team's colour with the number, the
 * surname under it, and its marks (yellow or red card, a ball per goal, an arrow for going off). Drawn, not loaded:
 * a few shapes, nothing to download.
 */
@Composable
fun FormationPitch(l: GameInfo.Lineup, teamColor: String?, modifier: Modifier = Modifier, small: Boolean = false, photos: Map<String, String> = emptyMap()) {
    val shirt = parseTeamColor(l.color ?: teamColor)
    val ink = if (shirt.luminanceApprox() > 0.6f) Color(0xFF14171E) else Color.White
    BoxWithConstraints(modifier.aspectRatio(0.78f).clip(RoundedCornerShape(14.dp))) {
        val w = maxWidth; val h = maxHeight
        PitchLines()
        // Keeper near the bottom line, forwards near the top: rows spread over the pitch's height.
        val rows = l.rows.coerceAtLeast(2)
        for (p in l.players) {
            val y = 0.90f - p.row * (0.80f / (rows - 1))
            val cx = w * p.x; val cy = h * y
            val sw = if (small) 22.dp else 34.dp
            Column(Modifier.offset(cx - 36.dp, cy - sw / 2).width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(contentAlignment = Alignment.Center) {
                    val photo = photos[p.fullName]
                    if (photo != null) {
                        // The player's face on a disc of the team's colour, the number on a small badge.
                        val face = if (small) 26.dp else 40.dp
                        Box(Modifier.size(face).clip(CircleShape).background(if (p.row == 0) Color(0xFF3A3F4A) else shirt).border(1.5.dp, Color(0xCCFFFFFF), CircleShape)) {
                            coil3.compose.AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                alignment = Alignment.TopCenter)
                        }
                        Box(Modifier.offset(face / 2 - 4.dp, face / 2 - 6.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xE014171E)).padding(horizontal = 3.dp)) {
                            Text(p.number, style = T.tiny.copy(fontSize = if (small) 7.sp else 9.sp, color = Color.White, fontWeight = FontWeight.Bold))
                        }
                    } else Box(Modifier.size(sw).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 6.dp, bottomEnd = 6.dp))
                        .background(if (p.row == 0) Color(0xFF3A3F4A) else shirt).border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 6.dp, bottomEnd = 6.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(p.number, style = T.small.copy(color = if (p.row == 0) Color.White else ink, fontWeight = FontWeight.Bold, fontSize = if (small) 10.sp else 14.sp))
                    }
                    // Marks round the shirt: cards top left, goals top right, off bottom right.
                    if (p.yellow || p.red) Box(Modifier.offset(-(sw / 2), -(sw / 2) + 3.dp).size(8.dp, 11.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (p.red) Color(0xFFE53935) else Color(0xFFFFD31A)))
                    if (p.goals > 0) Text("⚽" + (if (p.goals > 1) "×${p.goals}" else ""), style = T.tiny.copy(fontSize = 10.sp, color = Color.White),
                        modifier = Modifier.offset(sw / 2 + 3.dp, -(sw / 2) + 3.dp))
                    if (p.off) Box(Modifier.offset(sw / 2, sw / 2 - 4.dp).size(14.dp).clip(CircleShape).background(Color(0xFFE53935)), contentAlignment = Alignment.Center) {
                        Text("↓", style = T.tiny.copy(fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(p.name, style = T.tiny.copy(fontSize = if (small) 8.sp else 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x66000000)).padding(horizontal = 4.dp, vertical = 1.dp))
            }
        }
    }
}

/** The pitch: grass in bands, touchlines, halfway line, centre circle, both boxes. */
@Composable
private fun PitchLines() {
    Canvas(Modifier.fillMaxSize()) {
        val bands = 8
        for (i in 0 until bands) drawRect(if (i % 2 == 0) Color(0xFF2E7D45) else Color(0xFF2A7440),
            topLeft = Offset(0f, size.height * i / bands), size = Size(size.width, size.height / bands + 1))
        val line = Color(0x99FFFFFF); val s = Stroke(width = 2f)
        val m = 10f
        drawRect(line, topLeft = Offset(m, m), size = Size(size.width - 2 * m, size.height - 2 * m), style = s)
        drawLine(line, Offset(m, size.height / 2), Offset(size.width - m, size.height / 2), strokeWidth = 2f)
        drawCircle(line, radius = size.width * 0.14f, center = Offset(size.width / 2, size.height / 2), style = s)
        val boxW = size.width * 0.56f; val boxH = size.height * 0.14f
        drawRect(line, topLeft = Offset((size.width - boxW) / 2, m), size = Size(boxW, boxH), style = s)
        drawRect(line, topLeft = Offset((size.width - boxW) / 2, size.height - m - boxH), size = Size(boxW, boxH), style = s)
        val goalW = size.width * 0.26f; val goalH = size.height * 0.05f
        drawRect(line, topLeft = Offset((size.width - goalW) / 2, m), size = Size(goalW, goalH), style = s)
        drawRect(line, topLeft = Offset((size.width - goalW) / 2, size.height - m - goalH), size = Size(goalW, goalH), style = s)
    }
}

private fun Color.luminanceApprox() = 0.299f * red + 0.587f * green + 0.114f * blue

/** The column that matters most in a table, in a pill as points are in the standings: runs, wickets, partnership runs. */
private fun keyColumn(s: GameInfo.Section): Int = s.head.indexOfFirst { it == "R" && s.head.firstOrNull() == "Batter" }.takeIf { it >= 0 }
    ?: s.head.indexOf("W").takeIf { s.head.firstOrNull() == "Bowler" }
    ?: s.head.indexOf("Runs").takeIf { it >= 0 } ?: -1

/**
 * A Game info table in the standings' design: a card, a shaded header strip, rows in rounded lines with every other
 * one shaded, the names column first and bold, the key figure in a pill. [narrow] (a phone) scrolls wide tables
 * sideways instead of squeezing them.
 */
@Composable
fun InfoTable(s: GameInfo.Section, narrow: Boolean = false, photos: Map<String, String> = emptyMap(), faces: Map<String, String> = emptyMap()) {
    if (s.kind == "part") return PartnershipBars(s, narrow, faces)
    val n = s.head.size
    val short0 = s.head[0] in setOf("#", "Time", "Wicket")
    val main = if (short0) (s.head.indexOf("Player").takeIf { it > 0 } ?: s.head.indexOfFirst { it in setOf("Batters", "Event") }.takeIf { it > 0 } ?: 0) else 0
    val key = keyColumn(s)
    val textCols = setOf("How out", "Batters", "Player", "Position", "Event", "Team")
    // A phone scrolls a wide table sideways only when it doesn't fit (upright); turned sideways (or a tablet) the table
    // fills the width, the names and "How out" sharing the room, so nothing is cut short.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth()) {
    val need = (if (s.photos != null) 180 else 150) + (1 until n).sumOf { i -> if (s.head[i] in textCols) 110.toInt() else 44.toInt() } + 40
    val scrolls = narrow && n > 4 && maxWidth < need.dp
    fun width(i: Int): Dp? = when {
        !scrolls && (i == main || (s.head[i] in textCols && narrow)) -> null
        i == main -> if (s.photos != null) 180.dp else 150.dp
        i == 0 && short0 -> if (narrow) 40.dp else 64.dp
        s.head[i] in textCols -> if (narrow) 110.dp else 140.dp
        else -> if (narrow) 44.dp else 60.dp
    }
    fun cellMod(i: Int, rowScope: androidx.compose.foundation.layout.RowScope): Modifier =
        with(rowScope) { width(i)?.let { Modifier.width(it) } ?: Modifier.weight(if (i == main) 1.4f else 1f) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportsLook.Card).border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp))
        .padding(horizontal = 10.dp, vertical = 10.dp)) {
        Column(if (scrolls) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth()) {
            Row(Modifier.then(if (scrolls) Modifier else Modifier.fillMaxWidth()).clip(RoundedCornerShape(6.dp)).background(Color(0x14FFFFFF))
                .padding(vertical = 4.dp, horizontal = 6.dp)) {
                for (i in 0 until n) Text(s.head[i].uppercase(), style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold,
                    textAlign = if (i == 0 || i == main || s.head[i] in textCols) TextAlign.Start else TextAlign.Center),
                    modifier = cellMod(i, this), maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            for ((r, row) in s.rows.withIndex()) Row(Modifier.then(if (scrolls) Modifier else Modifier.fillMaxWidth()).height(if (s.photos != null) (if (narrow) 36.dp else 40.dp) else if (narrow) 32.dp else 34.dp)
                .clip(RoundedCornerShape(8.dp)).background(if (r % 2 == 1) Color(0x0AFFFFFF) else Color.Transparent).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until n) {
                    val v = row.getOrElse(i) { "" }
                    if (i == key && v.isNotBlank()) Box(cellMod(i, this), contentAlignment = Alignment.Center) {
                        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x1FFFFFFF)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                            Text(v, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center), maxLines = 1)
                        }
                    } else if (i == main && s.photos != null) Row(cellMod(i, this), verticalAlignment = Alignment.CenterVertically) {
                        // Two players in one cell ("Scorer 🎯 · Assister 👟", "↑ On · ↓ Off"): each with their own face.
                        val parts = if (s.photos2 != null) v.split(" · ", limit = 2) else listOf(v)
                        val faceSize = if (narrow) 24.dp else 28.dp
                        parts.forEachIndexed { pi, part ->
                            if (pi > 0) Text("  ·  ", style = T.small.copy(color = SportsLook.Dim))
                            val key = if (pi == 0) s.photos.getOrNull(r) else s.photos2?.getOrNull(r)
                            PlayerFace(key?.let { photos[it] }, part.replace("🎯", "").replace("👟", "").trim('↑', '↓', ' '), faceSize)
                            Spacer(Modifier.width(6.dp))
                            Text(part, style = T.small.copy(color = if (pi == 0) Color.White else Color.White.copy(alpha = 0.85f), fontWeight = if (pi == 0) FontWeight.Bold else FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                    } else Text(v, style = T.small.copy(color = if (i == main) Color.White else Color.White.copy(alpha = 0.78f),
                        fontWeight = if (i == main) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (i == 0 || i == main || s.head[i] in textCols) TextAlign.Start else TextAlign.Center),
                        modifier = cellMod(i, this), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        s.note?.let { Text(it, style = T.small.copy(color = SportsLook.Dim), modifier = Modifier.padding(top = 6.dp, start = 6.dp)) }
    }
    }
}

/**
 * The whole Game info body, shared by the TV's window and the phone's page: the formation(s) on a pitch (both sides
 * next to each other, so they compare at a glance), then the tables, both sides' match figures, and the
 * substitutes as short lists.
 */
@Composable
fun GameInfoBody(info: GameInfo.Info, colors: Map<String, String?>, narrow: Boolean, sideStep: Pair<Int, Int> = 0 to 0) {
    // The players' photos, each shown as it's found (those seen before at once, from the device).
    val photos by androidx.compose.runtime.produceState(emptyMap<String, String>(), info) {
        val names = (info.lineups.flatMap { l -> l.players.map { it.fullName } } + info.sections.flatMap { it.photos.orEmpty().filterNotNull() + it.photos2.orEmpty().filterNotNull() })
            .filter { it.isNotBlank() }
        if (names.isEmpty()) return@produceState
        // Looked up in the background; the screen takes what's found every half second (on its own thread).
        val got = io.github.novatv.play.platform.SafeMap<String, String>()
        val job = launch(io.github.novatv.play.platform.IO) {
            io.github.novatv.play.sports.PlayerPhotos.fetch(names) { n, url -> got[n] = url }
        }
        while (true) {
            val now = got.keys().associateWith { got[it]!! }
            if (now.size != value.size) value = now
            if (job.isCompleted) break
            kotlinx.coroutines.delay(500)
        }
        value = got.keys().associateWith { got[it]!! }
    }
    if (info.lineups.isNotEmpty()) {
        Text("FORMATIONS & LINE-UPS", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(8.dp))
        // Both teams side by side; one team's pitch centred at its own size.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (narrow) 8.dp else 16.dp, Alignment.CenterHorizontally)) {
            for (l in info.lineups) Column(if (info.lineups.size > 1) Modifier.weight(1f) else Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l.team, style = T.body.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                l.formation?.let { Text(it, style = T.small.copy(color = SportsLook.Blue, fontWeight = FontWeight.Bold)) }
                Spacer(Modifier.height(6.dp))
                FormationPitch(l, colors[l.team], Modifier.widthIn(max = 440.dp).fillMaxWidth(), small = narrow && info.lineups.size > 1, photos = photos)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    // Cricket: a tab per innings (1-2, or 1-4 for a Test), opening on the one being played (one team's: the one it
    // batted latest). An innings not played yet says who is yet to bat (or, in one team's view, to bowl).
    val plan = info.cricket
    var inningsTab by androidx.compose.runtime.remember(info) { androidx.compose.runtime.mutableIntStateOf(plan?.open ?: 1) }
    // The TV remote's left and right: the innings before or after (the window passes each press).
    androidx.compose.runtime.LaunchedEffect(sideStep.first) {
        if (plan != null && sideStep.first > 0) inningsTab = (inningsTab + sideStep.second).coerceIn(1, plan.batting.size)
    }
    if (plan != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (n in 1..plan.batting.size) Pill("Innings $n", onClick = { inningsTab = n }, selected = inningsTab == n)
        }
        Spacer(Modifier.height(12.dp))
    }
    val shown = if (plan == null) info.sections else GameInfo.inningsSections(info.sections, plan, inningsTab)
    if (plan != null && shown.isEmpty()) {
        Text(GameInfo.notYet(plan, inningsTab), style = T.body.copy(color = SportsLook.Dim))
        Spacer(Modifier.height(16.dp))
    }
    // Batters' faces by the name the scorecard gives them, for the partnerships (which name them the same way).
    val faces = shown.filter { it.kind == "bat" }.flatMap { b -> b.rows.mapIndexedNotNull { i, r -> b.photos?.getOrNull(i)?.let { photos[it] }?.let { r[0] to it } } }.toMap()
    for (s in shown) {
        Text(s.title.uppercase(), style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        InfoTable(s, narrow, photos, faces)
        Spacer(Modifier.height(16.dp))
    }
    info.figures?.let { f ->
        Text(f.title.uppercase(), style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        FigureBars(f, narrow)
        Spacer(Modifier.height(16.dp))
    }
    if (info.benches.isNotEmpty()) {
        Text("SUBSTITUTES", style = T.tiny.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (b in info.benches) Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(SportsLook.Card)
                .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(b.team, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1)
                Spacer(Modifier.height(4.dp))
                // "21 Radeljic · 18 Demirovic ↑ ..." in a line or two: the ones who came on marked.
                Text(b.players.joinToString("  ·  ") { (n, name, on) -> "$n ${name.substringAfterLast(' ')}" + (if (on) " ↑" else "") },
                    style = T.small.copy(color = Color.White.copy(alpha = 0.8f)))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/**
 * Starts a football match's Game info early, when its page opens: the line-ups, the players' photos (looked up and
 * downloaded into the picture cache), so Game info opens with the faces already there. Nothing for other sports.
 */
@Composable
fun PrefetchGameInfo(e: io.github.novatv.play.sports.SportEvent) {
    val ctx = coil3.compose.LocalPlatformContext.current
    androidx.compose.runtime.LaunchedEffect(e.id) {
        if (e.league.sport != io.github.novatv.play.sports.Sport.FOOTBALL && e.league.sport != io.github.novatv.play.sports.Sport.CRICKET) return@LaunchedEffect
        kotlinx.coroutines.withContext(io.github.novatv.play.platform.IO) {
            runCatching {
                // Without the ball-by-ball (Game info reads that itself, when opened): just line-ups and names.
                val info = GameInfo.forEvent(e, deep = false)
                val names = (info.lineups.flatMap { l -> l.players.map { it.fullName } } + info.sections.flatMap { it.photos.orEmpty().filterNotNull() + it.photos2.orEmpty().filterNotNull() }).filter { it.isNotBlank() }
                if (names.isEmpty()) return@runCatching
                val loader = coil3.SingletonImageLoader.get(ctx)
                io.github.novatv.play.sports.PlayerPhotos.fetch(names) { _, url ->
                    loader.enqueue(coil3.request.ImageRequest.Builder(ctx).data(url).build())
                }
            }
        }
    }
}

/** A player's face in a circle, else their initials on a plain disc. */
@Composable
fun PlayerFace(url: String?, name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(Color(0x26FFFFFF)), contentAlignment = Alignment.Center) {
        // Initials only while there's no photo (none found, still loading, or it failed): a cut-out photo has a
        // see-through background, and letters behind it showed through.
        var shown by androidx.compose.runtime.remember(url) { androidx.compose.runtime.mutableStateOf(false) }
        if (!shown) {
            val parts = name.split(' ').filter { it.isNotBlank() }
            val initials = (parts.firstOrNull()?.take(1).orEmpty() + (if (parts.size > 1) parts.last().take(1) else "")).uppercase()
            Text(initials, style = T.tiny.copy(color = Color.White.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, fontSize = 10.sp))
        }
        if (url != null) coil3.compose.AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.TopCenter,
            onSuccess = { shown = true }, onError = { shown = false })
    }
}

/**
 * Cricket partnerships, one line per wicket: the first batter (face, name) and their runs in a bar growing towards
 * the middle, the partnership in the middle (its runs, then balls and overs), the second batter's runs in a bar
 * growing out of the middle, then their name and face. Bars are measured against the biggest single share, so the
 * big contributions stand out. (ESPN gives each batter's runs in a partnership, not their balls.)
 */
@Composable
private fun PartnershipBars(s: GameInfo.Section, narrow: Boolean, faces: Map<String, String>) {
    val top = s.rows.maxOfOrNull { r -> maxOf(r.getOrNull(2)?.toIntOrNull() ?: 0, r.getOrNull(4)?.toIntOrNull() ?: 0) }?.coerceAtLeast(1) ?: 1
    val face = if (narrow) 26.dp else 32.dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportsLook.Card).border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp))
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (r in s.rows) {
            val wkt = r.getOrElse(0) { "" }; val p1 = r.getOrElse(1) { "" }; val r1 = r.getOrElse(2) { "" }
            val p2 = r.getOrElse(3) { "" }; val r2 = r.getOrElse(4) { "" }
            val total = r.getOrElse(5) { "" }; val overs = r.getOrElse(6) { "" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(wkt, style = T.small.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold), maxLines = 1, modifier = Modifier.width(if (narrow) 30.dp else 40.dp))
                PlayerFace(faces[p1], p1, face)
                Spacer(Modifier.width(8.dp))
                Text(p1, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.9f))
                ShareBar(r1, (r1.toIntOrNull() ?: 0).toFloat() / top, SportsLook.Blue, towardsEnd = true, modifier = Modifier.weight(1f))
                Row(Modifier.width(if (narrow) 78.dp else 104.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text(total, style = T.body.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1)
                    if (overs.isNotBlank()) Text(" ($overs)", style = T.small.copy(color = Color(0xFF9AA0AC)), maxLines = 1)
                }
                ShareBar(r2, (r2.toIntOrNull() ?: 0).toFloat() / top, Color(0xFF2EBD5F), towardsEnd = false, modifier = Modifier.weight(1f))
                Text(p2, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.End), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(0.9f))
                Spacer(Modifier.width(8.dp))
                PlayerFace(faces[p2], p2, face)
            }
        }
    }
}

/** A batter's share of a partnership: a rounded bar [fraction] long, from the middle outwards, the runs inside. */
@Composable
private fun ShareBar(runs: String, fraction: Float, color: Color, towardsEnd: Boolean, modifier: Modifier) {
    Box(modifier.height(22.dp).padding(horizontal = 6.dp).clip(RoundedCornerShape(11.dp)).background(Color(0x14FFFFFF)),
        contentAlignment = if (towardsEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0.3f, 1f)).fillMaxHeight().clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center) {
            Text(runs, style = T.small.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1)
        }
    }
}

/** 1 -> "1st", 2 -> "2nd", 3 -> "3rd", 4 -> "4th". */
private fun ordinalWord(n: Int) = "$n" + when { n % 100 in 11..13 -> "th"; n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"; else -> "th" }

/**
 * Both sides' figures as bars, like the partnerships: the home side's value in a blue bar growing towards the
 * middle, the figure's name in the middle, the away side's in a green bar growing out of it; each bar's length is
 * that side's share of the two. Rates given as fractions ("0.3" on target) shown as percentages.
 */
@Composable
private fun FigureBars(s: GameInfo.Section, narrow: Boolean) {
    fun num(v: String) = v.trim().removeSuffix("%").toDoubleOrNull()
    fun shown(label: String, v: String): String {
        val n = num(v) ?: return v
        return if ("%" in label && n <= 1.0 && "." in v) "${(n * 1000).toLong() / 10.0}" else v
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SportsLook.Card).border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp))
        .padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The two sides, over their bars.
        Row(Modifier.fillMaxWidth()) {
            Text(s.head.getOrElse(1) { "" }, style = T.small.copy(color = SportsLook.Blue, fontWeight = FontWeight.Bold), maxLines = 1, modifier = Modifier.weight(1f))
            Text(s.head.getOrElse(2) { "" }, style = T.small.copy(color = Color(0xFF2EBD5F), fontWeight = FontWeight.Bold, textAlign = TextAlign.End), maxLines = 1,
                modifier = Modifier.weight(1f))
        }
        for (r in s.rows) {
            val label = r.getOrElse(0) { "" }
            val a = r.getOrElse(1) { "" }; val b = r.getOrElse(2) { "" }
            val x = num(a) ?: 0.0; val y = num(b) ?: 0.0
            val sum = (x + y).takeIf { it > 0 } ?: 1.0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ShareBar(shown(label, a), (x / sum).toFloat(), SportsLook.Blue, towardsEnd = true, modifier = Modifier.weight(1f))
                Text(label, style = T.small.copy(color = Color.White, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.width(if (narrow) 96.dp else 150.dp))
                ShareBar(shown(label, b), (y / sum).toFloat(), Color(0xFF2EBD5F), towardsEnd = false, modifier = Modifier.weight(1f))
            }
        }
    }
}
