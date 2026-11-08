package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONArray
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.nowMs

/**
 * League tables ("standings"): ESPN's public standings feeds for leagues with tables
 * (Football/Soccer, NFL, NBA, NHL, MLB, and F1 / NASCAR driver standings).
 * Cached for 1 hour.
 */

data class StandingsColumn(val key: String, val label: String)

data class StandingsZone(val label: String, val color: Long)

data class StandingsRow(
    val id: String,
    val name: String,
    val shortName: String,
    val abbr: String,
    val logo: String?,
    val pos: Int,
    val values: Map<String, String>,
    val form: String? = null,
    val zone: StandingsZone? = null,
    val clincher: String? = null,
    val clincherText: String? = null,
    val seed: Int? = null,
    val change: Int? = null,
    val record: String? = null,
    val home: String? = null,
    val road: String? = null,
    val streak: String? = null,
    val sortValue: Double = 0.0,
    val athlete: Boolean = false,
    val color: Long? = null,
    val altColor: Long? = null
)

data class StandingsGroup(val name: String, val parent: String?, val rows: List<StandingsRow>)

enum class StandingsView { DIVISION, CONFERENCE, LEAGUE }

enum class StandingsKind {
    TABLE,
    RECORD,
    HOCKEY,
    RACING
}

data class Standings(
    val leagueId: String,
    val season: String?,
    val kind: StandingsKind,
    val columns: List<StandingsColumn>,
    val groups: List<StandingsGroup>,
    val source: String,
    val fetchedAt: Long
) {
    val conferences: List<String> get() = groups.mapNotNull { it.parent }.distinct()
    val hasDivisions: Boolean get() = conferences.isNotEmpty()

    fun view(view: StandingsView, conference: String?): List<StandingsGroup> {
        if (!hasDivisions) return groups
        val conf = conference?.takeIf { it in conferences } ?: conferences.firstOrNull() ?: return groups
        return when (view) {
            StandingsView.DIVISION -> groups.filter { it.parent == conf }
            StandingsView.CONFERENCE -> listOf(merged(conf, groups.filter { it.parent == conf }.flatMap { it.rows }, bySeed = true))
            StandingsView.LEAGUE -> listOf(merged(Catalog.league(leagueId)?.name ?: "League", groups.flatMap { it.rows }, bySeed = false))
        }
    }

    private fun merged(name: String, rows: List<StandingsRow>, bySeed: Boolean): StandingsGroup {
        val sorted = if (bySeed && rows.all { it.seed != null }) rows.sortedBy { it.seed }
        else rows.sortedByDescending { it.sortValue }
        return StandingsGroup(name, null, sorted.mapIndexed { i, r -> r.copy(pos = i + 1) })
    }
}

object StandingsRepository {
    private val cache = SafeMap<String, Pair<Long, Standings>>()
    private const val TTL = 3600_000L

    fun forLeague(league: League): Standings? {
        val now = nowMs()
        cache[league.id]?.let { if (now - it.first < TTL) return it.second }
        val url = espnUrl(league) ?: return null
        return try {
            val raw = Http.get(url)
            val parsed = parseEspn(league, raw, now)
            if (parsed != null) cache[league.id] = now to parsed
            parsed
        } catch (e: Exception) {
            null
        }
    }

    /** Returns ordinal placement of a team if standings are in cache (e.g., "3rd", "13th"). */
    fun cachedPlace(teamName: String, leagueId: String): String? {
        val table = cache[leagueId]?.second ?: return null
        for (group in table.groups) {
            val row = group.rows.firstOrNull { 
                it.name.contains(teamName, ignoreCase = true) || teamName.contains(it.name, ignoreCase = true)
            }
            if (row != null) {
                val rank = row.pos
                val suffix = when {
                    rank % 100 in 11..13 -> "th"
                    rank % 10 == 1 -> "st"
                    rank % 10 == 2 -> "nd"
                    rank % 10 == 3 -> "rd"
                    else -> "th"
                }
                return "$rank$suffix"
            }
        }
        return null
    }

    fun espnUrl(league: League): String? {
        val path = league.path ?: return null
        val divisions = path in setOf("football/nfl", "basketball/nba", "hockey/nhl", "baseball/mlb")
        return "https://site.api.espn.com/apis/v2/sports/$path/standings" + if (divisions) "?level=3" else ""
    }

    fun parseEspn(league: League, raw: String, now: Long = nowMs()): Standings? {
        val root = JSONObject(raw)
        val leaves = ArrayList<Triple<String, String?, JSONObject>>()
        var season: String? = null

        fun walk(node: JSONObject, parent: String?) {
            val st = node.optJSONObject("standings")
            val entries = st?.optJSONArray("entries")
            if (st != null && entries != null && entries.length() > 0) {
                if (season == null) season = st.optString("seasonDisplayName").ifBlank { null } ?: st.opt("season")?.toString()
                leaves += Triple(node.optString("name").ifBlank { node.optString("abbreviation").ifBlank { league.name } }, parent, node)
            }
            val kids = node.optJSONArray("children") ?: return
            val name = node.optString("name").ifBlank { null }
            for (i in 0 until kids.length()) kids.optJSONObject(i)?.let { walk(it, if (node === root) null else name) }
        }

        walk(root, null)
        if (leaves.isEmpty()) return null

        val firstEntry = leaves.first().third.optJSONObject("standings")?.optJSONArray("entries")?.optJSONObject(0)
        val athletes = firstEntry?.has("athlete") == true
        val used = if (athletes) leaves.take(1) else leaves
        val statNames = HashSet<String>()

        for ((_, _, node) in used) {
            val e = node.optJSONObject("standings")?.optJSONArray("entries")?.optJSONObject(0) ?: continue
            val stats = e.optJSONArray("stats") ?: continue
            for (sIdx in 0 until stats.length()) {
                stats.optJSONObject(sIdx)?.optString("name")?.let { statNames += it }
            }
        }

        val kind = when {
            athletes -> StandingsKind.RACING
            "otLosses" in statNames -> StandingsKind.HOCKEY
            league.sport == Sport.FOOTBALL || ("points" in statNames && "ties" in statNames && "winPercent" !in statNames) -> StandingsKind.TABLE
            else -> StandingsKind.RECORD
        }

        val groups = used.map { (name, parent, node) ->
            val entriesArr = node.optJSONObject("standings")!!.optJSONArray("entries") ?: JSONArray()
            val entriesList = (0 until entriesArr.length()).mapNotNull { entriesArr.optJSONObject(it) }
            StandingsGroup(name, parent, parseEntries(entriesList, kind))
        }

        val anyTies = groups.any { g -> g.rows.any { (it.values["ties"] ?: "0").let { t -> t != "0" && t.isNotBlank() } } }
        val columns = when (kind) {
            StandingsKind.TABLE -> listOf(c("gamesPlayed", "P"), c("wins", "W"), c("ties", "D"), c("losses", "L"), c("pointDifferential", "GD"), c("points", "PTS"))
            StandingsKind.HOCKEY -> listOf(c("gamesPlayed", "GP"), c("wins", "W"), c("losses", "L"), c("otLosses", "OTL"), c("points", "PTS"), c("streak", "STRK"))
            StandingsKind.RACING -> listOf(c("points", "PTS"), c("behind", "BEHIND"))
            StandingsKind.RECORD -> listOfNotNull(c("wins", "W"), c("losses", "L"), if (anyTies) c("ties", "T") else null, c("winPercent", "PCT"), c("gamesBehind", "GB"), c("streak", "STRK"))
        }

        var filled = columns.filter { col -> col.key == "behind" || groups.any { g -> g.rows.any { !it.values[col.key].isNullOrBlank() } } }
        if (filled.size < 2 && !athletes) {
            val first = used.first().third.optJSONObject("standings")?.optJSONArray("entries")?.optJSONObject(0)
            val stats = first?.optJSONArray("stats")
            if (stats != null) {
                val own = (0 until stats.length()).mapNotNull { stats.optJSONObject(it) }.mapNotNull { st ->
                    val name = st.optString("name")
                    if (name.isBlank() || name in setOf("rank", "rankChange", "clincher", "playoffSeed") || st.optString("displayValue").isBlank()) return@mapNotNull null
                    c(name, (st.optString("abbreviation").ifBlank { st.optString("shortDisplayName").ifBlank { name } }).uppercase().take(5))
                }.distinctBy { it.key }.take(7)
                if (own.size >= 2) filled = own
            }
        }
        return Standings(league.id, season, kind, filled, groups, "ESPN", now)
    }

    private fun c(key: String, label: String) = StandingsColumn(key, label)

    private fun parseEntries(entries: List<JSONObject>, kind: StandingsKind): List<StandingsRow> {
        val rows = entries.mapNotNull { e -> runCatching { parseEntry(e, kind) }.getOrNull() }
        val ranked = if (rows.all { it.pos > 0 }) rows.sortedBy { it.pos } else rows
        val withPos = ranked.mapIndexed { i, r -> if (r.pos > 0) r else r.copy(pos = i + 1) }
        if (kind != StandingsKind.RACING || withPos.isEmpty()) return withPos
        val lead = withPos.first().values["points"]?.toDoubleOrNull() ?: return withPos
        return withPos.map { r ->
            val p = r.values["points"]?.toDoubleOrNull()
            val behind = if (p == null || r.pos == 1) "–" else "-" + (lead - p).toInt()
            r.copy(values = r.values + ("behind" to behind))
        }
    }

    private fun parseEntry(e: JSONObject, kind: StandingsKind): StandingsRow? {
        val team = e.optJSONObject("team")
        val athlete = e.optJSONObject("athlete")
        val who = team ?: athlete ?: return null
        val id = who.optString("id")
        val name = who.optString("displayName").ifBlank { who.optString("name") }
        val short = who.optString("shortDisplayName").ifBlank { name }
        val abbr = who.optString("abbreviation")
        val logo = who.optString("logo").ifBlank { null }
            ?: who.optJSONArray("logos")?.optJSONObject(0)?.optString("href")

        val values = LinkedHashMap<String, String>()
        var sortValue = 0.0
        var streak: String? = null
        var clincher: String? = null
        var clincherText: String? = null
        var seed: Int? = null
        var change: Int? = null
        var form: String? = null

        val stats = e.optJSONArray("stats")
        if (stats != null) {
            for (i in 0 until stats.length()) {
                val st = stats.optJSONObject(i) ?: continue
                val k = st.optString("name")
                val v = st.optString("displayValue").ifBlank { st.optString("value") }
                if (k.isNotBlank() && v.isNotBlank()) values[k] = v
                when (k) {
                    "points" -> if (kind == StandingsKind.TABLE || kind == StandingsKind.HOCKEY || kind == StandingsKind.RACING) sortValue = st.optDouble("value", 0.0)
                    "winPercent" -> if (kind == StandingsKind.RECORD) sortValue = st.optDouble("value", 0.0)
                    "streak" -> streak = v
                    "clincher" -> clincher = v
                    "playoffSeed" -> seed = st.optInt("value", 0).takeIf { it > 0 }
                    "rankChange" -> change = st.optInt("value", 0)
                }
            }
        }

        val note = e.optJSONObject("note")
        val zone = note?.let { n ->
            val hex = n.optString("color").removePrefix("#")
            val text = n.optString("description").ifBlank { n.optString("text") }
            if (hex.length == 6 && text.isNotBlank()) hex.toLongOrNull(16)?.let { StandingsZone(text, 0xFF000000 or it) } else null
        }
        if (note != null) {
            clincher = note.optString("rank").ifBlank { clincher }
            clincherText = note.optString("text").ifBlank { null }
        }

        val color = who.optString("color").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }
        val altColor = who.optString("alternateColor").takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000 or it }
        val rank = e.optInt("rank", 0)

        return StandingsRow(
            id = id,
            name = name,
            shortName = short,
            abbr = abbr,
            logo = logo,
            pos = rank,
            values = values,
            form = form,
            clincher = clincher,
            clincherText = clincherText,
            zone = zone,
            seed = seed,
            change = change,
            streak = streak,
            sortValue = sortValue,
            athlete = athlete != null,
            color = color,
            altColor = altColor
        )
    }
}
