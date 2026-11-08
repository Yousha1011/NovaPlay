package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.SafeMap
import kotlinx.coroutines.withContext

/** A sports team or competitor in a league. */
data class TeamInfo(
    val leagueId: String,
    val id: String,
    val name: String,
    val fullName: String,
    val abbr: String,
    val location: String?,
    val color: String?,
    val altColor: String?,
    val logo: String?,
    val record: String? = null
) {
    val key: String get() = "$leagueId:$id"
}

val Sport.icon: String
    get() = when (this) {
        Sport.AMERICAN_FOOTBALL -> "🏈"
        Sport.BASKETBALL -> "🏀"
        Sport.BASEBALL -> "⚾"
        Sport.HOCKEY -> "🏒"
        Sport.FOOTBALL -> "⚽"
        Sport.RUGBY -> "🏉"
        Sport.MMA -> "🥊"
        Sport.MOTORSPORT -> "🏁"
        Sport.GOLF -> "⛳"
        Sport.TENNIS -> "🎾"
        Sport.CRICKET -> "🏏"
    }

object SportsCatalog {
    private const val ESPN_BASE = "https://site.api.espn.com/apis/site/v2/sports"
    private val teamCache = SafeMap<String, List<TeamInfo>>()

    fun leaguesForSport(sport: Sport): List<League> =
        Catalog.leagues.filter { it.sport == sport }

    /** Fetches teams for a league from ESPN with local memory caching. */
    suspend fun teams(league: League): List<TeamInfo> = withContext(IO) {
        val cached = teamCache[league.id]
        if (cached != null && cached.isNotEmpty()) return@withContext cached

        val path = league.path ?: return@withContext fallbackTeams(league)
        try {
            val raw = Http.get("$ESPN_BASE/$path/teams?limit=1000")
            val parsed = parseTeamsJson(league.id, raw)
            if (parsed.isNotEmpty()) {
                teamCache[league.id] = parsed
                return@withContext parsed
            }
        } catch (_: Exception) {}

        fallbackTeams(league).also { teamCache[league.id] = it }
    }

    private fun parseTeamsJson(leagueId: String, jsonStr: String): List<TeamInfo> {
        val out = mutableListOf<TeamInfo>()
        val root = JSONObject(jsonStr)
        val sports = root.optJSONArray("sports") ?: return out
        for (i in 0 until sports.length()) {
            val s = sports.optJSONObject(i) ?: continue
            val leagues = s.optJSONArray("leagues") ?: continue
            for (j in 0 until leagues.length()) {
                val l = leagues.optJSONObject(j) ?: continue
                val teams = l.optJSONArray("teams") ?: continue
                for (k in 0 until teams.length()) {
                    val t = teams.optJSONObject(k)?.optJSONObject("team") ?: continue
                    val id = t.optString("id")
                    val name = t.optString("name").ifBlank { t.optString("displayName") }
                    val fullName = t.optString("displayName").ifBlank { name }
                    val abbr = t.optString("abbreviation")
                    val location = t.optString("location").ifBlank { null }
                    val color = t.optString("color").ifBlank { null }
                    val altColor = t.optString("alternateColor").ifBlank { null }
                    val logo = t.optJSONArray("logos")?.optJSONObject(0)?.optString("href")
                        ?: "https://a.espncdn.com/i/teamlogos/${leagueId.substringBefore('/')}/500/$id.png"

                    out.add(
                        TeamInfo(
                            leagueId = leagueId,
                            id = id,
                            name = name,
                            fullName = fullName,
                            abbr = abbr,
                            location = location,
                            color = color,
                            altColor = altColor,
                            logo = logo,
                            record = null
                        )
                    )
                }
            }
        }
        return out
    }

    /** Built-in initial teams for instant rendering if offline or while ESPN loads. */
    private fun fallbackTeams(league: League): List<TeamInfo> = when (league.id) {
        "nfl" -> listOf(
            TeamInfo("nfl", "22", "Cardinals", "Arizona Cardinals", "ARI", "Arizona", "a40227", "ffffff", "https://a.espncdn.com/i/teamlogos/nfl/500/ari.png", "4-4"),
            TeamInfo("nfl", "1", "Falcons", "Atlanta Falcons", "ATL", "Atlanta", "a71930", "000000", "https://a.espncdn.com/i/teamlogos/nfl/500/atl.png", "5-3"),
            TeamInfo("nfl", "33", "Ravens", "Baltimore Ravens", "BAL", "Baltimore", "241773", "000000", "https://a.espncdn.com/i/teamlogos/nfl/500/bal.png", "5-3"),
            TeamInfo("nfl", "2", "Bills", "Buffalo Bills", "BUF", "Buffalo", "00338d", "c60c30", "https://a.espncdn.com/i/teamlogos/nfl/500/buf.png", "7-2"),
            TeamInfo("nfl", "29", "Panthers", "Carolina Panthers", "CAR", "Carolina", "0085ca", "101820", "https://a.espncdn.com/i/teamlogos/nfl/500/car.png", "2-7"),
            TeamInfo("nfl", "3", "Bears", "Chicago Bears", "CHI", "Chicago", "0b162a", "c83803", "https://a.espncdn.com/i/teamlogos/nfl/500/chi.png", "4-4"),
            TeamInfo("nfl", "4", "Bengals", "Cincinnati Bengals", "CIN", "Cincinnati", "fb4f14", "000000", "https://a.espncdn.com/i/teamlogos/nfl/500/cin.png", "4-5"),
            TeamInfo("nfl", "5", "Browns", "Cleveland Browns", "CLE", "Cleveland", "ff3c00", "311d00", "https://a.espncdn.com/i/teamlogos/nfl/500/cle.png", "2-7"),
            TeamInfo("nfl", "6", "Cowboys", "Dallas Cowboys", "DAL", "Dallas", "003594", "041e42", "https://a.espncdn.com/i/teamlogos/nfl/500/dal.png", "3-5"),
            TeamInfo("nfl", "7", "Broncos", "Denver Broncos", "DEN", "Denver", "fb4f14", "002244", "https://a.espncdn.com/i/teamlogos/nfl/500/den.png", "5-4"),
            TeamInfo("nfl", "8", "Lions", "Detroit Lions", "DET", "Detroit", "0076b6", "b0b7bc", "https://a.espncdn.com/i/teamlogos/nfl/500/det.png", "7-1"),
            TeamInfo("nfl", "9", "Packers", "Green Bay Packers", "GB", "Green Bay", "203731", "ffb612", "https://a.espncdn.com/i/teamlogos/nfl/500/gb.png", "6-3"),
            TeamInfo("nfl", "34", "Texans", "Houston Texans", "HOU", "Houston", "03202f", "a71930", "https://a.espncdn.com/i/teamlogos/nfl/500/hou.png", "6-3"),
            TeamInfo("nfl", "11", "Colts", "Indianapolis Colts", "IND", "Indianapolis", "002c5f", "a2aaad", "https://a.espncdn.com/i/teamlogos/nfl/500/ind.png", "4-5"),
            TeamInfo("nfl", "30", "Jaguars", "Jacksonville Jaguars", "JAX", "Jacksonville", "006778", "d7a22a", "https://a.espncdn.com/i/teamlogos/nfl/500/jax.png", "2-7"),
            TeamInfo("nfl", "12", "Chiefs", "Kansas City Chiefs", "KC", "Kansas City", "e31837", "ffb81c", "https://a.espncdn.com/i/teamlogos/nfl/500/kc.png", "8-0"),
            TeamInfo("nfl", "13", "Raiders", "Las Vegas Raiders", "LV", "Las Vegas", "000000", "a5acaf", "https://a.espncdn.com/i/teamlogos/nfl/500/lv.png", "2-7"),
            TeamInfo("nfl", "24", "Chargers", "Los Angeles Chargers", "LAC", "LA", "0080c6", "ffc20e", "https://a.espncdn.com/i/teamlogos/nfl/500/lac.png", "5-3"),
            TeamInfo("nfl", "14", "Rams", "Los Angeles Rams", "LAR", "LA", "003594", "ffa300", "https://a.espncdn.com/i/teamlogos/nfl/500/lar.png", "4-4"),
            TeamInfo("nfl", "15", "Dolphins", "Miami Dolphins", "MIA", "Miami", "008e97", "fc4c02", "https://a.espncdn.com/i/teamlogos/nfl/500/mia.png", "2-6"),
            TeamInfo("nfl", "16", "Vikings", "Minnesota Vikings", "MIN", "Minnesota", "4f2683", "ffc62f", "https://a.espncdn.com/i/teamlogos/nfl/500/min.png", "6-2"),
            TeamInfo("nfl", "17", "Patriots", "New England Patriots", "NE", "New England", "002244", "c60c30", "https://a.espncdn.com/i/teamlogos/nfl/500/ne.png", "2-7"),
            TeamInfo("nfl", "18", "Saints", "New Orleans Saints", "NO", "New Orleans", "d3bc8d", "101820", "https://a.espncdn.com/i/teamlogos/nfl/500/no.png", "2-7"),
            TeamInfo("nfl", "19", "Giants", "New York Giants", "NYG", "New York", "0b2265", "a71930", "https://a.espncdn.com/i/teamlogos/nfl/500/nyg.png", "2-7"),
            TeamInfo("nfl", "20", "Jets", "New York Jets", "NYJ", "New York", "125740", "000000", "https://a.espncdn.com/i/teamlogos/nfl/500/nyj.png", "3-6"),
            TeamInfo("nfl", "21", "Eagles", "Philadelphia Eagles", "PHI", "Philadelphia", "004c54", "a5acaf", "https://a.espncdn.com/i/teamlogos/nfl/500/phi.png", "6-2"),
            TeamInfo("nfl", "23", "Steelers", "Pittsburgh Steelers", "PIT", "Pittsburgh", "ffb612", "101820", "https://a.espncdn.com/i/teamlogos/nfl/500/pit.png", "6-2"),
            TeamInfo("nfl", "25", "49ers", "San Francisco 49ers", "SF", "San Francisco", "aa0000", "b3995d", "https://a.espncdn.com/i/teamlogos/nfl/500/sf.png", "4-4"),
            TeamInfo("nfl", "26", "Seahawks", "Seattle Seahawks", "SEA", "Seattle", "002244", "69be28", "https://a.espncdn.com/i/teamlogos/nfl/500/sea.png", "4-5"),
            TeamInfo("nfl", "27", "Buccaneers", "Tampa Bay Buccaneers", "TB", "Tampa Bay", "d50a0a", "0a0a08", "https://a.espncdn.com/i/teamlogos/nfl/500/tb.png", "4-5"),
            TeamInfo("nfl", "10", "Titans", "Tennessee Titans", "TEN", "Tennessee", "0c2340", "4b92db", "https://a.espncdn.com/i/teamlogos/nfl/500/ten.png", "2-6"),
            TeamInfo("nfl", "28", "Commanders", "Washington Commanders", "WSH", "Washington", "5a1414", "ffb612", "https://a.espncdn.com/i/teamlogos/nfl/500/wsh.png", "7-2"),
        )
        "eng.1" -> listOf(
            TeamInfo("eng.1", "359", "Arsenal", "Arsenal", "ARS", "London", "EF0107", "063672", "https://a.espncdn.com/i/teamlogos/soccer/500/359.png"),
            TeamInfo("eng.1", "362", "Aston Villa", "Aston Villa", "AVL", "Birmingham", "95BFE5", "670E36", "https://a.espncdn.com/i/teamlogos/soccer/500/362.png"),
            TeamInfo("eng.1", "337", "Brentford", "Brentford", "BRE", "Brentford", "E30613", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/337.png"),
            TeamInfo("eng.1", "331", "Brighton", "Brighton & Hove Albion", "BHA", "Brighton", "0057B8", "FFCD00", "https://a.espncdn.com/i/teamlogos/soccer/500/331.png"),
            TeamInfo("eng.1", "363", "Chelsea", "Chelsea", "CHE", "London", "034694", "EE242C", "https://a.espncdn.com/i/teamlogos/soccer/500/363.png"),
            TeamInfo("eng.1", "384", "Crystal Palace", "Crystal Palace", "CRY", "London", "1B458F", "C4122E", "https://a.espncdn.com/i/teamlogos/soccer/500/384.png"),
            TeamInfo("eng.1", "368", "Everton", "Everton", "EVE", "Liverpool", "003399", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/368.png"),
            TeamInfo("eng.1", "370", "Fulham", "Fulham", "FUL", "London", "CC0000", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/370.png"),
            TeamInfo("eng.1", "364", "Liverpool", "Liverpool", "LIV", "Liverpool", "C8102E", "00B2A9", "https://a.espncdn.com/i/teamlogos/soccer/500/364.png"),
            TeamInfo("eng.1", "382", "Man City", "Manchester City", "MCI", "Manchester", "6CABDD", "1C2C5B", "https://a.espncdn.com/i/teamlogos/soccer/500/382.png"),
            TeamInfo("eng.1", "360", "Man United", "Manchester United", "MUN", "Manchester", "DA291C", "FBE122", "https://a.espncdn.com/i/teamlogos/soccer/500/360.png"),
            TeamInfo("eng.1", "361", "Newcastle", "Newcastle United", "NEW", "Newcastle", "241F20", "41B6E6", "https://a.espncdn.com/i/teamlogos/soccer/500/361.png"),
            TeamInfo("eng.1", "393", "Nottingham Forest", "Nottingham Forest", "NFO", "Nottingham", "DD0000", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/393.png"),
            TeamInfo("eng.1", "367", "Tottenham", "Tottenham Hotspur", "TOT", "London", "132257", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/367.png"),
            TeamInfo("eng.1", "371", "West Ham", "West Ham United", "WHU", "London", "7A263A", "1BB1E7", "https://a.espncdn.com/i/teamlogos/soccer/500/371.png"),
            TeamInfo("eng.1", "380", "Wolves", "Wolverhampton Wanderers", "WOL", "Wolverhampton", "FDB913", "231F20", "https://a.espncdn.com/i/teamlogos/soccer/500/380.png")
        )
        "ita.1" -> listOf(
            TeamInfo("ita.1", "114", "Napoli", "Napoli", "NAP", "Naples", "0088CC", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/114.png"),
            TeamInfo("ita.1", "110", "Lazio", "Lazio", "LAZ", "Rome", "87D8F7", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/110.png"),
            TeamInfo("ita.1", "103", "Milan", "AC Milan", "MIL", "Milan", "FB090B", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/103.png"),
            TeamInfo("ita.1", "111", "Inter", "Inter Milan", "INT", "Milan", "010E80", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/111.png"),
            TeamInfo("ita.1", "112", "Juventus", "Juventus", "JUV", "Turin", "000000", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/112.png"),
            TeamInfo("ita.1", "104", "Roma", "AS Roma", "ROM", "Rome", "8E1F2F", "F0BC42", "https://a.espncdn.com/i/teamlogos/soccer/500/104.png"),
            TeamInfo("ita.1", "125", "Atalanta", "Atalanta", "ATA", "Bergamo", "1E71B8", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/125.png"),
            TeamInfo("ita.1", "3263", "Monza", "Monza", "MON", "Monza", "E4002B", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/3263.png"),
            TeamInfo("ita.1", "107", "Genoa", "Genoa", "GEN", "Genoa", "990000", "002B49", "https://a.espncdn.com/i/teamlogos/soccer/500/107.png"),
        )
        "esp.1" -> listOf(
            TeamInfo("esp.1", "83", "Barcelona", "Barcelona", "BAR", "Barcelona", "004D98", "A50044", "https://a.espncdn.com/i/teamlogos/soccer/500/83.png"),
            TeamInfo("esp.1", "86", "Real Madrid", "Real Madrid", "RMA", "Madrid", "FEBE10", "00529F", "https://a.espncdn.com/i/teamlogos/soccer/500/86.png"),
            TeamInfo("esp.1", "1068", "Atlético Madrid", "Atlético Madrid", "ATM", "Madrid", "CB3524", "272E61", "https://a.espncdn.com/i/teamlogos/soccer/500/1068.png"),
            TeamInfo("esp.1", "244", "Real Betis", "Real Betis", "BET", "Seville", "00954C", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/244.png"),
            TeamInfo("esp.1", "3751", "Elche", "Elche", "ELC", "Elche", "006837", "FFFFFF", "https://a.espncdn.com/i/teamlogos/soccer/500/3751.png"),
        )
        "ger.1" -> listOf(
            TeamInfo("ger.1", "132", "Bayern", "Bayern Munich", "BAY", "Munich", "DC052D", "0066B2", "https://a.espncdn.com/i/teamlogos/soccer/500/132.png"),
            TeamInfo("ger.1", "124", "Dortmund", "Borussia Dortmund", "BVB", "Dortmund", "FDE100", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/124.png"),
            TeamInfo("ger.1", "131", "Leverkusen", "Bayer Leverkusen", "LEV", "Leverkusen", "E32221", "000000", "https://a.espncdn.com/i/teamlogos/soccer/500/131.png"),
            TeamInfo("ger.1", "11420", "RB Leipzig", "RB Leipzig", "RBL", "Leipzig", "DA0812", "001E46", "https://a.espncdn.com/i/teamlogos/soccer/500/11420.png"),
        )
        "nba" -> listOf(
            TeamInfo("nba", "2", "Celtics", "Boston Celtics", "BOS", "Boston", "007A33", "BA9653", "https://a.espncdn.com/i/teamlogos/nba/500/bos.png"),
            TeamInfo("nba", "17", "Lakers", "Los Angeles Lakers", "LAL", "Los Angeles", "552583", "FDB927", "https://a.espncdn.com/i/teamlogos/nba/500/lal.png"),
            TeamInfo("nba", "9", "Warriors", "Golden State Warriors", "GSW", "Golden State", "1D428A", "FFC72C", "https://a.espncdn.com/i/teamlogos/nba/500/gsw.png"),
            TeamInfo("nba", "4", "Bulls", "Chicago Bulls", "CHI", "Chicago", "CE1141", "000000", "https://a.espncdn.com/i/teamlogos/nba/500/chi.png"),
            TeamInfo("nba", "14", "Heat", "Miami Heat", "MIA", "Miami", "98002E", "F9A01B", "https://a.espncdn.com/i/teamlogos/nba/500/mia.png"),
            TeamInfo("nba", "18", "Knicks", "New York Knicks", "NYK", "New York", "006BB6", "F58426", "https://a.espncdn.com/i/teamlogos/nba/500/nyk.png")
        )
        else -> emptyList()
    }
}
