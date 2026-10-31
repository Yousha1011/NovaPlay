package io.github.novatv.play.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.novatv.play.App
import io.github.novatv.play.Nav
import io.github.novatv.play.data.Category
import io.github.novatv.play.data.Parental

/**
 * Parental controls: choosing what to block. Categories of one [kind] ("live", "movies", "series") with a lock each;
 * or "channels": live categories on the left, their channels on the right. OK locks or unlocks; BACK is done.
 * Lists the whole playlist, blocked items included.
 */
@Composable
fun BlocksScreen(nav: Nav, kind: String) {
    val st by App.repo.fullState.collectAsState()
    val source = st.source?.id ?: "-"
    var v by remember { mutableIntStateOf(0) }
    val save = { App.repo.parentalChanged(); v++ }
    // Read once per change rather than once per row.
    val blockedCats = remember(v) { App.store.blockedCategories }
    val blockedChans = remember(v) { App.store.blockedChannels }
    // Focus starts on the first list, so OK works straight away.
    val start = remember { androidx.compose.ui.focus.FocusRequester() }
    val title = when (kind) { "live" -> "Live TV categories"; "movies" -> "Film categories"; "series" -> "Series categories"; else -> "Channels" }
    Column(Modifier.fillMaxSize().background(C.bg).padding(start = 60.dp, end = 60.dp, top = 36.dp)) {
        Header("Block $title".replace("Block Channels", "Block channels"),
            "OK locks or unlocks. Blocked ones are hidden everywhere while parental controls are on. BACK when you're done.")
        if (kind != "channels") {
            val cats = when (kind) { "live" -> st.lib.liveCategories; "movies" -> st.lib.movieCategories; else -> st.lib.seriesCategories }
            val key = { c: Category -> when (kind) { "live" -> Parental.liveKey(source, c.id); "movies" -> Parental.movieKey(source, c.id); else -> Parental.seriesKey(source, c.id) } }
            if (cats.isEmpty()) Message("Nothing to block", "This playlist has no $title.".lowercase().replaceFirstChar { it.uppercase() })
            LazyColumn(Modifier.fillMaxSize().focusRequester(start).rememberFocusInList(), contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (v < 0) return@LazyColumn
                items(cats, key = { it.id }) { c ->
                    val k = key(c)
                    val adult = App.store.blockAdult && Parental.isAdultName(c.name)
                    LockRow(c.name, k in blockedCats || adult, if (adult) "Blocked as an adult category" else null) {
                        if (!adult) { App.store.blockedCategories = App.store.blockedCategories.let { if (k in it) it - k else it + k }; save() }
                    }
                }
            }
        } else {
            var cat by remember { mutableStateOf(st.lib.liveCategories.firstOrNull()?.id) }
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.width(360.dp).fillMaxHeight().focusRequester(start).rememberFocusInList(), contentPadding = PaddingValues(bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(st.lib.liveCategories, key = { it.id }) { c ->
                        val open = c.id == cat
                        Tile({ cat = c.id }, Modifier.fillMaxWidth().height(48.dp).onFocusChanged { if (it.isFocused) cat = c.id },
                            shape = RoundedCornerShape(8.dp), scale = 1.0f, color = if (open) C.accent.copy(alpha = 0.14f) else Color.Transparent) {
                            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
                                Text(c.name, style = T.body.copy(color = if (open) C.text else C.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(24.dp))
                val channels = remember(st.lib, cat) { st.lib.channels.filter { it.categoryId == cat } }
                androidx.compose.runtime.key(cat) {
                    LazyColumn(Modifier.weight(1f).fillMaxHeight().rememberFocusInList(), contentPadding = PaddingValues(bottom = 60.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (v < 0) return@LazyColumn
                        items(channels, key = { it.id }) { c ->
                            val k = App.repo.channelKey(c)
                            LockRow("${if (c.number > 0) "${c.number}  " else ""}${c.name}", k in blockedChans, null) {
                                App.store.blockedChannels = App.store.blockedChannels.let { if (k in it) it - k else it + k }; save()
                            }
                        }
                    }
                }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); try { start.requestFocus() } catch (e: Exception) { } }
}

@Composable
private fun LockRow(name: String, locked: Boolean, note: String?, toggle: () -> Unit) {
    Tile(toggle, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(10.dp), scale = 1.0f,
        color = if (locked) C.live.copy(alpha = 0.16f) else C.panel) {
        Row(Modifier.fillMaxSize().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(name, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                note?.let { Text(it, style = T.tiny) }
            }
            Text(if (locked) "Blocked" else "Allowed", style = T.body.copy(color = if (locked) C.live else C.dim))
        }
    }
}
