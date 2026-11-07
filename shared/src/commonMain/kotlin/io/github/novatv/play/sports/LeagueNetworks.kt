package io.github.novatv.play.sports

import io.github.novatv.play.data.Channel
import io.github.novatv.play.data.Epg

/**
 * A major league's or sport's 24/7 dedicated network channel (NFL Network, NBA TV, Golf Channel...).
 */
data class LeagueNetwork(
    val id: String,
    val sport: String,
    val name: String,
    val mark: String,
    val color: Long,
    val words: List<String>,
    val not: List<String> = emptyList()
)

data class NetworkTile(
    val network: LeagueNetwork,
    val lead: Channel?,
    val others: List<Channel>,
    val picked: Boolean
)

object LeagueNetworks {
    val all: List<LeagueNetwork> = listOf(
        LeagueNetwork("nfl", "FOOTBALL", "NFL Network", "NFL", 0xFF1C4F9CL, listOf("nfl network"), listOf("redzone", "red zone")),
        LeagueNetwork("redzone", "FOOTBALL", "NFL RedZone", "RZ", 0xFFA8322DL, listOf("redzone", "red zone")),
        LeagueNetwork("nbatv", "BASKETBALL", "NBA TV", "NBA", 0xFF8A3B1FL, listOf("nba tv"), listOf("canada")),
        LeagueNetwork("nbatvca", "BASKETBALL", "NBA TV Canada", "NBA", 0xFF5A3A8FL, listOf("nba tv canada")),
        LeagueNetwork("mlb", "BASEBALL", "MLB Network", "MLB", 0xFF1D6B5AL, listOf("mlb network"), listOf("strike zone")),
        LeagueNetwork("strikezone", "BASEBALL", "MLB Strike Zone", "SZ", 0xFF4B5870L, listOf("strike zone")),
        LeagueNetwork("nhl", "HOCKEY", "NHL Network", "NHL", 0xFF2F5D8AL, listOf("nhl network")),
        LeagueNetwork("golf", "GOLF", "Golf Channel", "GC", 0xFF2E7D4FL, listOf("golf channel")),
        LeagueNetwork("tennis", "TENNIS", "Tennis Channel", "TC", 0xFF7A8A22L, listOf("tennis channel")),
        LeagueNetwork("laliga", "SOCCER", "LaLiga TV", "LL", 0xFFB5372EL, listOf("laliga tv", "la liga tv")),
        LeagueNetwork("ligue1", "SOCCER", "Ligue 1+", "L1", 0xFF2A3F8FL, listOf("ligue 1 plus"))
    )

    private val NOT_THE_CHANNEL = listOf("replay", "replays", "24 7", "classic", "vault", "encore", "highlights")

    fun resolve(channels: List<Channel>): List<NetworkTile> {
        val nonHeaders = channels.filterNot { it.name.trimStart().startsWith("##") }
        val names = nonHeaders.map { it to Epg.norm(it.name) }

        return all.map { net ->
            val matching = names.filter { (_, norm) ->
                net.words.any { w -> norm.contains(Epg.norm(w)) } &&
                net.not.none { n -> norm.contains(Epg.norm(n)) } &&
                NOT_THE_CHANNEL.none { norm.contains(it) }
            }.map { it.first }

            // Quality weighting: 4K/UHD > FHD > HD > SD
            val sorted = matching.sortedBy { c ->
                val n = c.name.lowercase()
                when {
                    n.contains("4k") || n.contains("uhd") -> 0
                    n.contains("fhd") || n.contains("1080") -> 1
                    n.contains("hd") || n.contains("720") -> 2
                    else -> 3
                }
            }
            NetworkTile(
                network = net,
                lead = sorted.firstOrNull(),
                others = sorted.drop(1),
                picked = false
            )
        }
    }
}
