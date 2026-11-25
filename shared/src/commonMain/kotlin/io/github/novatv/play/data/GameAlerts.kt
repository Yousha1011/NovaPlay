package io.github.novatv.play.data

import io.github.novatv.play.App
import io.github.novatv.play.sports.Follow
import io.github.novatv.play.sports.SportEvent
import io.github.novatv.play.sports.State
import kotlinx.coroutines.launch

/** One notification for a game, at a set time: "Starts in 30 minutes", "Starting now". */
data class GameAlert(val id: String, val title: String, val body: String, val at: Long, val matchId: String)

/**
 * Shows notifications at set times, where the platform can (the phone apps: Android's alarms, the iPhone's scheduled
 * notifications). [replace] drops everything scheduled before and schedules [alerts] instead.
 */
interface GameAlerter {
    fun replace(alerts: List<GameAlert>)
}

/**
 * Game notifications for the games you follow (the competitions and teams picked in Sports): 30 minutes before kick-off
 * and when it starts, each switchable in Settings. Planned on the phone itself whenever Sports refreshes, so they need
 * no server; score changes and the final whistle come from the server (Android).
 */
object GameAlerts {
    /** How far ahead games are scheduled (the iPhone keeps at most 64 pending notifications). */
    const val AHEAD_MS = 3 * 86_400_000L
    const val MAX = 60
    const val BEFORE_MS = 30 * 60_000L

    /** The games you follow among [events]: in a followed competition (its picked teams, when any), or a favourite team's. */
    fun followed(events: List<SportEvent>): List<SportEvent> {
        val comps = Follow.competitions()
        return events.filter { e ->
            !e.id.startsWith("guide:") && (
                comps.any { l -> Follow.inCompetition(e, l.id) } || e.teams.any { side -> Follow.teamPicked(e, side) })
        }.distinctBy { it.id }
    }

    /** The notifications for [events] from [now], as switched on ([before]: 30 minutes ahead, [start]: kick-off). */
    fun plan(events: List<SportEvent>, now: Long, before: Boolean, start: Boolean): List<GameAlert> {
        val out = ArrayList<GameAlert>()
        for (e in events.filter { it.state == State.PRE && it.start > now && it.start < now + AHEAD_MS }.sortedBy { it.start }) {
            val what = e.subtitle?.let { "${e.league.name} · $it" } ?: e.league.name
            if (before && e.start - BEFORE_MS > now)
                out += GameAlert("before:${e.id}", e.title, "Starts in 30 minutes · $what", e.start - BEFORE_MS, e.id)
            if (start) out += GameAlert("start:${e.id}", e.title, "Starting now · $what", e.start, e.id)
        }
        return out.sortedBy { it.at }.take(MAX)
    }

    /** Schedules the notifications for the latest Sports list (none when both switches are off). */
    fun update(events: List<SportEvent> = App.repo.sports.value.events) {
        val alerter = App.host.alerter ?: return
        val s = App.store
        val mine = followed(events)
        val alerts = if (!s.alertsBefore && !s.alertsStart) emptyList()
        else plan(mine, io.github.novatv.play.platform.nowMs(), s.alertsBefore, s.alertsStart)
        runCatching { alerter.replace(alerts) }.onFailure { io.github.novatv.play.platform.logW("game alerts", it) }
        App.host.pushToken?.let { token -> sendToServer(token, mine) }
    }

    /** The NovaPlay server's address, from its addon link (".../k/<key>/manifest.json"), or null without one. */
    fun serverBase(): String? = Addons.list().map { it.base }.firstOrNull { Regex("""/k/[^/]+$""").containsMatchIn(it) }

    /** The ESPN feed path and id the server reads a game's score from ("soccer/eng.1", "12345"), or null. */
    internal fun feedOf(e: SportEvent): Pair<String, String>? {
        val id = e.id.substringAfterLast(':').ifBlank { return null }
        val path = e.espnLeague?.let { "cricket/$it" } ?: e.league.path ?: return null
        return path to id
    }

    /**
     * Tells the server this device's address and the games to watch (the followed ones in the next 3 days, and any
     * on now), with which alerts are on: it sends score changes and final results as they happen. In the background.
     */
    private fun sendToServer(token: String, games: List<SportEvent>) {
        val base = serverBase() ?: return
        val s = App.store
        val now = io.github.novatv.play.platform.nowMs()
        val list = io.github.novatv.play.json.JSONArray()
        games.filter { it.state != State.POST && it.start < now + AHEAD_MS }.take(100).forEach { e ->
            val (path, id) = feedOf(e) ?: return@forEach
            list.put(io.github.novatv.play.json.JSONObject().put("path", path).put("id", id).put("title", e.title)
                .put("sport", e.league.sport.name).put("start", e.start).put("match", e.id))
        }
        val body = io.github.novatv.play.json.JSONObject().put("token", token).put("score", s.alertsScore).put("end", s.alertsEnd).put("games", list).toString()
        kotlinx.coroutines.CoroutineScope(io.github.novatv.play.platform.IO).launch {
            runCatching { Http.send("POST", "$base/watch", emptyMap(), body) }
                .onFailure { io.github.novatv.play.platform.logW("game alerts: server", it) }
        }
    }
}
