package io.github.novatv.play.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Recording
import io.github.novatv.play.data.Recordings

/**
 * Recordings: what is recording now, what is scheduled and what has been recorded, with the space left.
 * OK plays a recording (or asks to cancel a scheduled one); hold OK deletes.
 */
@Composable
fun RecordingsScreen(nav: Nav) {
    val v = Recordings.version
    val t = now()
    val all = remember(v, t / 60_000) { Recordings.all }
    val free = remember(v) { App.host.recorder?.freeBytes() ?: 0L }
    var ask by remember { mutableStateOf<Recording?>(null) }
    ask?.let { r ->
        RemoveDialog(r.title, if (r.status == Recording.Status.SCHEDULED) "Scheduled recordings" else "Recordings",
            onRemove = { if (r.status == Recording.Status.SCHEDULED) Recordings.cancel(r.id) else Recordings.delete(r.id) }, onDismiss = { ask = null })
    }
    val now = all.filter { it.status == Recording.Status.RECORDING }
    val scheduled = all.filter { it.status == Recording.Status.SCHEDULED }.sortedBy { it.start }
    val done = all.filter { it.status == Recording.Status.DONE || it.status == Recording.Status.FAILED }.sortedByDescending { it.start }
    Column(Modifier.fillMaxSize().padding(start = S.gutter, top = 30.dp, end = S.gutter)) {
        Header("Recordings", "${size(all.sumOf { it.bytes })} used · ${size(free)} free on this device")
        if (all.isEmpty()) {
            Message("No recordings yet", "Hold OK on a programme in the TV guide, or use Record in the player, to record it.")
            return@Column
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (now.isNotEmpty()) item { SectionTitle("Recording now", Modifier.padding(top = 6.dp)) }
            items(now, key = { it.id }) { r -> RecRow(r, t, onClick = { Recordings.play(r) }, onLong = { ask = r }) }
            if (scheduled.isNotEmpty()) item { SectionTitle("Scheduled", Modifier.padding(top = 14.dp)) }
            items(scheduled, key = { it.id }) { r -> RecRow(r, t, onClick = { ask = r }, onLong = { ask = r }) }
            if (done.isNotEmpty()) item { SectionTitle("Recorded", Modifier.padding(top = 14.dp)) }
            items(done, key = { it.id }) { r -> RecRow(r, t, onClick = { if (r.file != null) Recordings.play(r) else ask = r }, onLong = { ask = r }) }
        }
    }
}

@Composable
private fun RecRow(r: Recording, t: Long, onClick: () -> Unit, onLong: () -> Unit) {
    Tile(onClick, Modifier.fillMaxWidth().height(64.dp), onLongClick = onLong, shape = RoundedCornerShape(8.dp), scale = 1.0f) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (r.status == Recording.Status.RECORDING) { Dot(C.live, 10.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(r.title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${r.channelName} · ${dayLabel(r.start)} ${clock(r.start)} – ${clock(r.stop)}", style = T.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(16.dp))
            Text(when (r.status) {
                Recording.Status.RECORDING -> "${size(r.bytes)} · ends ${clock(r.stop)}"
                Recording.Status.SCHEDULED -> if (r.start > t) "Starts ${dayLabel(r.start)} ${clock(r.start)}" else "Starting…"
                Recording.Status.DONE -> size(r.bytes) + (r.error?.let { " · $it" } ?: "")
                Recording.Status.FAILED -> r.error ?: "Failed"
            }, style = T.small.copy(color = if (r.status == Recording.Status.FAILED) C.live else C.dim), maxLines = 1)
        }
    }
}

private fun size(b: Long): String = when {
    b >= 1L shl 30 -> "${(b * 10 / (1L shl 30)) / 10.0} GB"
    b >= 1L shl 20 -> "${b / (1L shl 20)} MB"
    else -> "0 MB"
}
