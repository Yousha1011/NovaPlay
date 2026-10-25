package io.github.novatv.play.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Programme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val HALF_HOUR = 30 * 60_000L
private const val WINDOW = 3 * 3600_000L

@Composable
fun GuideScreen(nav: Nav) {
    val st by App.repo.state.collectAsStateWithLifecycle()
    val groups by androidx.compose.runtime.produceState(emptyList<Group>(), st.lib) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { liveGroups(st.lib).filter { it.channels.isNotEmpty() } }
    }
    var sel by remember { mutableStateOf(if (groups.any { it.id == "fav" }) "fav" else "all") }
    val group = groups.firstOrNull { it.id == sel } ?: groups.firstOrNull()
    var start by remember { mutableLongStateOf(System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) }
    val t = now()

    Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 30.dp, end = 30.dp)) {
        Header("TV guide", "${dayLabel(start)} · ${clock(start)} – ${clock(start + WINDOW)}")
        SourceStatus()
        if (!App.repo.epg.hasData && st.epgStatus == null) {
            Text("This playlist has no TV guide. Add one in Settings (XMLTV link) to fill the grid.", style = T.small, modifier = Modifier.padding(bottom = 10.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
            items(groups, key = { it.id }) { g -> Pill(g.name, onClick = { sel = g.id }, selected = g.id == group?.id) }
        }
        if (group == null) return@Column
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val chW = 230.dp
            val gridW = maxWidth - chW
            Column {
                // Time ruler.
                Row(Modifier.padding(start = chW).height(28.dp)) {
                    for (i in 0 until 6) Text(clock(start + i * HALF_HOUR), style = T.small, modifier = Modifier.width(gridW / 6))
                }
                Box {
                    LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(group.channels, key = { _, c -> c.id }) { i, c ->
                            GuideRow(c, group.channels, i, start, chW, gridW, st.epgVersion,
                                // Left pages back only as far as the current half hour; from there it reaches the channel column.
                                onEarlier = { if (start > System.currentTimeMillis() / HALF_HOUR * HALF_HOUR) { start -= HALF_HOUR; true } else false },
                                onLater = { if (start + HALF_HOUR < System.currentTimeMillis() + 4 * 86_400_000L) start += HALF_HOUR })
                        }
                    }
                    // The "now" line.
                    val x = ((t - start).toFloat() / WINDOW)
                    if (x in 0f..1f) Box(Modifier.offset(x = chW + gridW * x).width(2.dp).fillMaxHeight().background(C.live.copy(alpha = 0.8f)))
                }
            }
        }
    }
}

@Composable
private fun GuideRow(c: Channel, list: List<Channel>, index: Int, start: Long, chW: Dp, gridW: Dp, epgVersion: Int,
                     onEarlier: () -> Boolean, onLater: () -> Unit) {
    val ctx = LocalContext.current
    val end = start + WINDOW
    val progs by produceState(emptyList<Programme>(), c.id, start, epgVersion) {
        value = withContext(Dispatchers.IO) { App.repo.epg.key(c)?.let { App.repo.epg.programmes(it, start, end) } ?: emptyList() }
    }
    Row(Modifier.height(62.dp), verticalAlignment = Alignment.CenterVertically) {
        Tile({ playChannel(ctx, list, index) }, Modifier.width(chW - 8.dp).fillMaxHeight(), shape = RoundedCornerShape(10.dp), scale = 1.02f) {
            Row(Modifier.fillMaxSize().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Logo(c.logo, c.name, Modifier.size(54.dp, 38.dp))
                Spacer(Modifier.width(10.dp))
                Text(c.name, style = T.small.copy(color = C.text), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(gridW).fillMaxHeight()) {
            if (progs.isEmpty()) {
                GuideCell("No information", null, Modifier.fillMaxSize(), first = true, last = true, onEarlier, onLater) { playChannel(ctx, list, index) }
            } else progs.forEachIndexed { i, p ->
                val s = maxOf(p.start, start); val e = minOf(p.stop, end)
                val x = gridW * ((s - start).toFloat() / WINDOW)
                val w = (gridW * ((e - s).toFloat() / WINDOW) - 4.dp).coerceAtLeast(6.dp)
                GuideCell(p.title, p, Modifier.offset(x = x).width(w).fillMaxHeight(), i == 0, i == progs.lastIndex, onEarlier, onLater) {
                    if (p.isLive || p.start <= System.currentTimeMillis()) playChannel(ctx, list, index)
                    else Toast.makeText(ctx, "${p.title} starts ${dayLabel(p.start)} at ${clock(p.start)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, p: Programme?, modifier: Modifier, first: Boolean, last: Boolean,
                      onEarlier: () -> Boolean, onLater: () -> Unit, onClick: () -> Unit) {
    val live = p?.isLive == true
    Tile(onClick, modifier.onPreviewKeyEvent { k ->
        if (k.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            k.key == Key.DirectionLeft && first -> onEarlier()
            k.key == Key.DirectionRight && last -> { onLater(); true }
            else -> false
        }
    }, shape = RoundedCornerShape(8.dp), scale = 1.0f, color = if (live) C.accent.copy(alpha = 0.22f) else C.panel) {
        Column(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
            Text(title, style = T.small.copy(color = C.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p != null) Text("${clock(p.start)} – ${clock(p.stop)}", style = T.tiny, maxLines = 1)
        }
    }
}
