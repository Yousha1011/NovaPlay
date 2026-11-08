package io.github.novatv.play.sports

import io.github.novatv.play.data.Http
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.SafeMap
import io.github.novatv.play.platform.nowMs

/**
 * One team's page: where it stands, its record, its last results and its next matches, from ESPN's team and
 * team-schedule feeds. Kept 15 minutes. Cricket has no team feeds, so a cricket side has only what the scoreboards show.
 */
data class TeamPage(
    val id: String,
    val name: String,
    val logo: String?,
    val league: League,
    /** "1st in English Premier League". */
    val standing: String?,
    /** "9-0-1" (won-drawn-lost, or won-lost). */
    val record: String?,
    /** Played, most recent first. */
    val past: List<SportEvent>,
    /** To come (and on now), soonest first, across every competition the team plays in. */
    val next: List<SportEvent>,
) {
    /** "W", "D" or "L" for each of the last five, oldest first. */
    val form: List<Char> get() = past.take(5).reversed().mapNotNull { result(it) }

    /** This team's result in [e]: W, D or L; null if it hasn't finished or can't tell. */
    fun result(e: SportEvent): Char? {
        if (e.state != State.POST) return null
        val me = e.teams.firstOrNull { it.id == id } ?: return null
        val them = e.teams.firstOrNull { it.id != id } ?: return null
        me.winner?.let { w -> if (w) return 'W'; if (them.winner == true) return 'L' }
        val a = me.score?.toDoubleOrNull(); val b = them.score?.toDoubleOrNull()
        return when { a == null || b == null -> null; a > b -> 'W'; a < b -> 'L'; else -> 'D' }
    }
}

object Teams {
    private const val SITE = "https://site.api.espn.com/apis/site/v2/sports"
    private val cache = SafeMap<String, Pair<Long, TeamPage?>>()
    /** A followed team's league, found once by asking each league of its sport. */
    private val homes = SafeMap<String, String>()

    /** [teamId]'s page in [league]; null when the league has no team feeds (cricket) or the team isn't found. */
    fun info(league: League, teamId: String): TeamPage? {
        val path = league.path ?: return null
        val key = "${league.id}|$teamId"
        val now = nowMs()
        cache[key]?.let { if (now - it.first < 15 * 60_000L) return it.second }
        val found = try {
            val team = JSONObject(Http.get("$SITE/$path/teams/$teamId")).optJSONObject("team")
            if (team == null || team.optString("id").isBlank()) null else {
                val past = try { Espn.schedule(league, Http.get("$SITE/$path/teams/$teamId/schedule")) } catch (e: Exception) { emptyList() }
                // Soccer's "fixture" schedule lists every competition's coming matches; other sports' plain one has them too.
                val ahead = if (league.sport == Sport.FOOTBALL)
                    try { Espn.schedule(league, Http.get("$SITE/$path/teams/$teamId/schedule?fixture=true")) } catch (e: Exception) { emptyList() }
                else emptyList()
                // A club's cup and European matches are only in those competitions' feeds: their coming fixtures too.
                val cups = if (league.sport == Sport.FOOTBALL) Catalog.leagues.filter {
                    it.sport == Sport.FOOTBALL && it.path != null && it.id != league.id && (it.id.startsWith("uefa.") || (league.country.isNotBlank() && it.country == league.country))
                }.flatMap { c -> try { Espn.schedule(c, Http.get("$SITE/${c.path}/teams/$teamId/schedule?fixture=true")) } catch (e: Exception) { emptyList() } } else emptyList()
                val all = (past + ahead + cups).distinctBy { it.id }.distinctBy { it.title to it.start / 3_600_000L }
                TeamPage(
                    id = team.optString("id"),
                    name = team.optString("displayName").ifBlank { team.optString("name") },
                    logo = team.optJSONArray("logos")?.optJSONObject(0)?.optString("href")?.ifBlank { null },
                    league = league,
                    standing = team.optString("standingSummary").ifBlank { null },
                    record = team.optJSONObject("record")?.optJSONArray("items")?.optJSONObject(0)?.optString("summary")?.ifBlank { null },
                    past = all.filter { it.state == State.POST }.sortedByDescending { it.start },
                    next = all.filter { it.state != State.POST }.sortedBy { it.start },
                )
            }
        } catch (e: Exception) { null }
        cache[key] = now to found
        return found
    }

    /**
     * A followed team ("SPORT|id|name") with the league its page is in: from a match on the scoreboards when there is
     * one, else by trying the sport's leagues until one knows a team of that id and name.
     */
    fun followed(key: String, events: List<SportEvent>): TeamPage? {
        val parts = key.split('|')
        if (parts.size < 3) return null
        val sport = runCatching { Sport.valueOf(parts[0]) }.getOrNull() ?: return null
        val id = parts[1]; val name = parts.drop(2).joinToString("|")
        homes[key]?.let { lid -> Catalog.leagues.firstOrNull { it.id == lid }?.let { return info(it, id) } }
        // Leagues to ask: one a match on the scoreboards puts the team in, then the sport's others.
        val seen = events.firstOrNull { e -> e.league.sport == sport && e.league.path != null && e.teams.any { it.id == id && it.name == name } }?.league
        val tries = (listOfNotNull(seen) + Catalog.leagues.filter { it.sport == sport && it.path != null }).distinct().take(12)
        for (l in tries) {
            val t = info(l, id) ?: continue
            if (!(t.name.equals(name, ignoreCase = true) || name.contains(t.name, ignoreCase = true) || t.name.contains(name, ignoreCase = true))) continue
            // The team's own league, which "1st in German Bundesliga" names: a cup or the Champions League feed only
            // knows its matches in that competition, and soccer ids are the same in every feed.
            val own = t.standing?.let { s -> Catalog.leagues.filter { it.sport == sport && it.path != null && s.contains(it.name, ignoreCase = true) }
                .maxByOrNull { it.name.length } } ?: l
            homes[key] = own.id
            return if (own == l) t else info(own, id) ?: t
        }
        return null
    }
}
