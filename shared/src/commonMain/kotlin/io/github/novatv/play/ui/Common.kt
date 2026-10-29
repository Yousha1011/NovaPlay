package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.data.Programme
import io.github.novatv.play.play
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import io.github.novatv.play.platform.nowMs
import io.github.novatv.play.platform.IO

/** Plays [list] starting at [index], remembering the channel in Recent. */
fun playChannel(list: List<Channel>, index: Int) {
    if (list.isEmpty()) return
    val c = list[index.coerceIn(0, list.lastIndex)]
    App.store.addRecentChannel(App.repo.channelKey(c))
    App.store.lastChannel = App.repo.channelKey(c)
    play(PlayItem.Live(list, index.coerceIn(0, list.lastIndex)))
}

/** Now/next for [c], read off the main thread and refreshed when the guide changes. */
@Composable
fun nowNext(c: Channel): List<Programme> {
    // Observed, so rows drawn before the guide finished importing fill in when it does.
    val v = App.repo.state.collectAsState().value.epgVersion
    val r by produceState(emptyList<Programme>(), c.id, v) { value = withContext(IO) { App.repo.nowNext(c) } }
    return r
}

/** A ticking clock, so live progress bars move. */
@Composable
fun now(periodMs: Long = 30_000L): Long {
    val t by produceState(nowMs()) { while (true) { delay(periodMs); value = nowMs() } }
    return t
}

/** Asks for focus once the target is laid out (requestFocus throws before that). */
@Composable
fun AutoFocus(r: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { repeat(5) { try { r.requestFocus(); return@LaunchedEffect } catch (e: Exception) { delay(60) } } }
}

/**
 * A channel tile: the logo on a plate, a LIVE mark while the guide has a programme on now, the channel's number and
 * name, and the programme with how far through it is. Without a guide it says so rather than guessing.
 */
@Composable
fun ChannelCard(c: Channel, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    val p = nowNext(c).firstOrNull()
    val t = now()
    val on = p != null && t in p.start until p.stop
    Tile(onClick, modifier.width(240.dp).height(160.dp), onLongClick = onLongClick, shape = RoundedCornerShape(10.dp)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(78.dp).background(Color(0x0DFFFFFF))) {
                Logo(c.logo, c.name, Modifier.align(Alignment.Center).size(96.dp, 54.dp))
                if (on) LiveMark(Modifier.align(Alignment.TopEnd).padding(8.dp))
            }
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (c.number > 0) { Text("${c.number}", style = T.small.copy(color = C.faint)); Spacer(Modifier.width(8.dp)) }
                    Text(c.name, style = T.body.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(if (p != null) p.title else "No guide data", style = T.small.copy(color = if (p != null) C.dim else C.faint),
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.weight(1f))
                if (p != null && on) Bar(((t - p.start).toFloat() / (p.stop - p.start)).coerceIn(0f, 1f), Modifier.fillMaxWidth())
            }
        }
    }
}

/** A small red "● LIVE" mark. */
@Composable
fun LiveMark(modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xCC1A0A0C)).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Dot(C.live, 6.dp); Spacer(Modifier.width(5.dp))
        Text("LIVE", style = T.tiny.copy(color = C.live, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
    }
}

/** A match card: competition, sides with logos and scores, the time or live minute. */
@Composable
fun EventCard(e: SportEvent, onClick: () -> Unit, modifier: Modifier = Modifier, fill: Boolean = false, favourite: Boolean = false) {
    Tile(onClick, modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier.width(330.dp)).height(150.dp)) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(e.league.name, e.subtitle?.takeIf { it != e.league.name }).joinToString(" · "), style = T.tiny,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (favourite) { Text("★", style = T.small.copy(color = C.gold)); Spacer(Modifier.width(8.dp)) }
                when (e.state) {
                    State.LIVE -> Badge(if (e.detail.isBlank() || e.detail.equals("live", true)) "LIVE" else "LIVE · ${e.detail}")
                    State.POST -> Badge(e.detail.ifBlank { "Final" }, C.panelHi)
                    State.PRE -> Text("${dayLabel(e.start)} ${clock(e.start)}", style = T.small.copy(color = C.accent))
                }
            }
            Spacer(Modifier.height(10.dp))
            if (e.home != null && e.away != null) {
                SideRow(e.home.name, e.home.logo, e.home.score, e.state)
                Spacer(Modifier.height(8.dp))
                SideRow(e.away.name, e.away.logo, e.away.score, e.state)
            } else {
                Text(e.title, style = T.h2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                e.venue?.let { Text(it, style = T.small, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun SideRow(name: String, logo: String?, score: String?, state: State) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0x10FFFFFF)), contentAlignment = Alignment.Center) {
            if (logo != null) AsyncImage(logo, null, Modifier.size(24.dp)) else Text(initials(name), style = T.tiny)
        }
        Spacer(Modifier.width(10.dp))
        Text(name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        // Cricket scores ("113 & 34/2 (15 ov)") are long: smaller, so the side's name keeps its room.
        if (state != State.PRE && score != null) Text(score, style = if (score.length > 5) T.small.copy(color = C.text) else T.h2, maxLines = 1)
    }
}

@Composable
fun Header(title: String, sub: String? = null, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().padding(bottom = 18.dp)) {
        if (maxWidth < NARROW) {
            // A phone held upright: the title on its own line, the buttons under it.
            Column {
                Text(title, style = T.hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
            }
        } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = T.hero, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (sub != null) Text(sub, style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
        }
    }
}

/** Below this width (a phone, upright or turned) screens stack what sits side by side on a TV. */
val NARROW = 760.dp
/** Below this width side panels (a match's channels) give way to the main list. */
val WIDE = 1000.dp

/** A loading line or error from the active source, shown at the top of the main screens. */
@Composable
fun SourceStatus() {
    val st by App.repo.state.collectAsState()
    val msg = st.error?.let { "Could not load ${st.source?.name}: $it" } ?: st.status.ifBlank { null } ?: st.epgStatus
    if (msg == null) return
    Row(Modifier.padding(bottom = 14.dp).clip(RoundedCornerShape(10.dp)).background(if (st.error != null) C.live.copy(alpha = 0.18f) else C.panel)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (st.error == null) { Spinner(size = 16.dp); Spacer(Modifier.width(10.dp)) }
        Text(msg, style = T.small.copy(color = C.text))
    }
}

/** Remembers a value across recompositions without a key (a plain mutable state). */
@Composable
fun <T> rememberState(v: T) = remember { mutableStateOf(v) }
