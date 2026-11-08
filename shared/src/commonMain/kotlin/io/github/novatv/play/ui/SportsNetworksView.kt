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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.novatv.play.App
import io.github.novatv.play.data.Channel
import io.github.novatv.play.sports.LeagueNetworks
import io.github.novatv.play.sports.NetworkTile
import io.github.novatv.play.sports.SportsLook
import io.github.novatv.play.sports.sportsFrame
import io.github.novatv.play.toast

/**
 * 📡 Dedicated League Networks view (matching Screenshot 4):
 * - Subtitle: "Each league's own channel, found in your playlists by name."
 * - Left pane: 2-column grid grouped by sport headers (FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, etc.)
 * - Right pane: Mini-player window (16:9) playing the selected channel!
 * - Footer: "OK: play here, OK again: full screen · Hold OK: other copies, pick a channel"
 */
@Composable
fun SportsNetworksView(
    onPlayInWindow: (Channel) -> Unit,
    videoSlot: (@Composable () -> Unit)? = null
) {
    val st by App.repo.state.collectAsState()
    val tiles = remember(st.lib.channels) {
        LeagueNetworks.resolve(st.lib.channels)
    }

    var selectedTile by remember(tiles) { mutableStateOf(tiles.firstOrNull { it.lead != null } ?: tiles.firstOrNull()) }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Each league's own channel, found in your playlists by name.",
            style = T.tiny.copy(color = SportsLook.Dim),
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (st.lib.channels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Message("No channels", "Add an IPTV playlist to access 24/7 league networks.")
            }
            return@Column
        }

        val bySport = remember(tiles) { tiles.groupBy { it.network.sport } }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Left Pane: 2-column grid with sport section headers
            LazyColumn(
                modifier = Modifier.width(520.dp).fillMaxHeight().rememberFocusInList(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                bySport.forEach { (sport, sportTiles) ->
                    item(key = "h_$sport") {
                        Text(
                            sport,
                            style = T.eyebrow.copy(color = SportsLook.Dim, fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(top = 10.dp, bottom = 4.dp, start = 4.dp)
                        )
                    }

                    // 2 columns per row
                    val chunked = sportTiles.chunked(2)
                    items(chunked.size, key = { "r_${sport}_$it" }) { rowIdx ->
                        val rowItems = chunked[rowIdx]
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            rowItems.forEach { tile ->
                                val isSelected = selectedTile?.network?.id == tile.network.id
                                Box(Modifier.weight(1f)) {
                                    LeagueNetworkCard(
                                        tile = tile,
                                        isSelected = isSelected,
                                        onFocus = {
                                            selectedTile = tile
                                            tile.lead?.let { onPlayInWindow(it) }
                                        },
                                        onClick = {
                                            val lead = tile.lead
                                            if (lead != null) {
                                                if (isSelected) {
                                                    playChannel(listOf(lead), 0)
                                                } else {
                                                    selectedTile = tile
                                                    onPlayInWindow(lead)
                                                }
                                            } else {
                                                toast("Network channel not found in playlists")
                                            }
                                        }
                                    )
                                }
                            }
                            if (rowItems.size < 2) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.width(18.dp))

            // Right Pane: Video Window + Channel Info
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (videoSlot != null) {
                    videoSlot()
                } else {
                    Box(
                        Modifier.fillMaxWidth().height(220.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFF0C0E14))
                            .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("▶", style = T.h1.copy(fontSize = 32.sp, color = SportsLook.Dim))
                    }
                }

                Spacer(Modifier.height(12.dp))

                selectedTile?.let { tile ->
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SportsLook.Card)
                            .border(1.dp, SportsLook.Border, RoundedCornerShape(14.dp))
                            .padding(14.dp)
                    ) {
                        Text(
                            tile.network.name,
                            style = T.h2.copy(fontWeight = FontWeight.Bold, color = Color.White)
                        )
                        Spacer(Modifier.height(4.dp))
                        val lead = tile.lead
                        if (lead != null) {
                            Text("Best copy: ${lead.name}", style = T.small.copy(color = SportsLook.Dim))
                            if (tile.others.isNotEmpty()) {
                                Text("+${tile.others.size} alternate streams available in playlist", style = T.tiny.copy(color = SportsLook.Faint))
                            }
                        } else {
                            Text("Channel not found in current IPTV playlist.", style = T.small.copy(color = SportsLook.Dim))
                        }
                    }
                }
            }
        }

        // Bottom hint line
        Text(
            "OK: play here, OK again: full screen · Hold OK: other copies, pick a channel",
            style = T.tiny.copy(color = SportsLook.Faint),
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun LeagueNetworkCard(
    tile: NetworkTile,
    isSelected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val net = tile.network
    val lead = tile.lead

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .sportsFrame(focused, playing = isSelected && !focused, accent = SportsLook.Gold, radius = 12.dp)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocus()
            }
            .clickable(onClick = onClick)
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            // The network's own coloured badge: playlist logos are often the provider's, not the network's.
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(9.dp)).background(Color(net.color)),
                contentAlignment = Alignment.Center
            ) {
                Text(net.mark, style = T.tiny.copy(color = Color.White, fontWeight = FontWeight.Bold))
            }

            Spacer(Modifier.width(10.dp))

            Column(Modifier.weight(1f)) {
                Text(net.name, style = T.small.copy(fontWeight = FontWeight.Bold, color = if (lead != null) Color.White else SportsLook.Dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val onNow by androidx.compose.runtime.produceState<String?>(null, lead) {
                    value = kotlinx.coroutines.withContext(io.github.novatv.play.platform.IO) {
                        runCatching { lead?.let { io.github.novatv.play.App.repo.nowNext(it).firstOrNull()?.title } }.getOrNull()
                    }
                }
                val more = if (tile.others.isNotEmpty()) "+${tile.others.size} more" else null
                Text(
                    when {
                        lead == null -> "Not found · hold OK to pick"
                        onNow != null -> listOfNotNull(onNow, more).joinToString(" · ")
                        else -> more ?: lead.name
                    },
                    style = T.tiny.copy(color = SportsLook.Faint),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
