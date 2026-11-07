package io.github.novatv.play.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.novatv.play.App
import io.github.novatv.play.sports.LeagueNetworks
import io.github.novatv.play.sports.NetworkTile
import io.github.novatv.play.toast

/**
 * 📡 Dedicated League Networks view: 24/7 official sports league channels
 * (NFL Network, NFL RedZone, NBA TV, MLB Network, NHL Network, Golf Channel, Tennis Channel, LaLiga TV).
 */
@Composable
fun SportsNetworksView() {
    val st by App.repo.state.collectAsState()
    val tiles = remember(st.lib.channels) {
        LeagueNetworks.resolve(st.lib.channels)
    }

    if (st.lib.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Message("No channels", "Add an IPTV playlist to access 24/7 league networks.")
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 340.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 40.dp),
        modifier = Modifier.fillMaxSize().rememberFocusInList()
    ) {
        items(tiles, key = { it.network.id }) { tile ->
            NetworkCard(tile)
        }
    }
}

@Composable
private fun NetworkCard(tile: NetworkTile) {
    val net = tile.network
    val lead = tile.lead

    Tile(
        onClick = {
            if (lead != null) {
                playChannel(listOf(lead), 0)
            } else {
                toast("${net.name} was not found in your playlist.")
            }
        },
        Modifier.fillMaxWidth().height(140.dp),
        shape = RoundedCornerShape(16.dp),
        scale = 1.03f,
        color = C.panel
    ) {
        Row(
            Modifier.fillMaxSize().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Brand Logo / Monogram
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Color(net.color)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    net.mark,
                    style = T.h2.copy(color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                )
            }
            Spacer(Modifier.width(16.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    net.name,
                    style = T.h2.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                if (lead != null) {
                    Text(
                        lead.name,
                        style = T.body.copy(color = C.dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFF22C55E).copy(alpha = 0.2f)).padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("AVAILABLE", style = T.tiny.copy(color = Color(0xFF22C55E), fontWeight = FontWeight.Bold))
                        }
                        if (tile.others.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text("+${tile.others.size} copies", style = T.tiny.copy(color = C.dim))
                        }
                    }
                } else {
                    Text("Not in playlist", style = T.body.copy(color = Color(0xFFEF4444)))
                }
            }

            if (lead != null) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(C.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, size = 24.dp, tint = Color.White)
                }
            }
        }
    }
}
