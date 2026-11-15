package io.github.novatv.play.sports

import io.github.novatv.play.data.Channel
import io.github.novatv.play.platform.Time

/** One followed competition on My Sports: its name and logo, and its matches still to come (soonest first). */
data class CompetitionSection(val league: League, val logo: String?, val games: List<SportEvent>)

/** A guide programme of a followed competition that names no match ("UEFA Champions League" at 20:00 on a channel). */
data class UnidentifiedGame(val league: League, val start: Long, val stop: Long, val title: String, val channels: List<Channel>) {
    /** As a match card shows it: the sides unknown ("? Team 1", "? Team 2"). */
    fun asEvent(now: Long): SportEvent = SportEvent(
        id = "guide:${league.id}:$start", league = league, subtitle = null, title = title, start = start,
        state = if (now in start until stop) State.LIVE else State.PRE, detail = "", broadcasts = channels.take(3).map { it.name }, venue = null,
        home = Side("", "? Team 1", "? Team 1", "?", null, null), away = Side("", "? Team 2", "? Team 2", "?", null, null))
}

/** My Sports: what is listed under Live & Upcoming and how a match's day and time read. */
object MySports {
    /** How far ahead matches are listed. */
    const val AHEAD_MS = 14 * 86_400_000L

    /**
     * A section per followed competition ([leagues], A to Z), each with its live and coming matches, soonest first;
     * only its picked teams' matches when [picks] gives any for it. Competitions with nothing to come are left out.
     */
    fun sections(events: List<SportEvent>, leagues: List<League>, picks: (String) -> List<Pair<String, String>>, logo: (League) -> String?,
                 now: Long): List<CompetitionSection> = leagues.sortedBy { it.name.lowercase() }.mapNotNull { l ->
        val p = picks(l.id)
        val games = events.filter { e ->
            Follow.inCompetition(e, l.id, p) && (e.state == State.LIVE || (e.state == State.PRE && e.start < now + AHEAD_MS))
        }.distinctBy { it.id }.sortedBy { it.start }
        if (games.isEmpty()) null else CompetitionSection(l, logo(l), games)
    }

    /** A match counts as Live from an hour before kick-off. */
    const val SOON_MS = 3_600_000L

    /**
     * The order of every list: kick-off first, then the sport in the Follow screen's order (Football, Cricket,
     * Basketball…), then the competition's name.
     */
    val order: Comparator<SportEvent> = compareBy<SportEvent>({ it.start }, { it.league.sport.ordinal }, { (it.subtitle ?: it.league.name).lowercase() })

    /** The Live tab: matches on now, and those starting within the hour. */
    fun live(events: List<SportEvent>, now: Long): List<SportEvent> =
        events.filter { it.state == State.LIVE || (it.state == State.PRE && it.start - now <= SOON_MS) }.distinctBy { it.id }.sortedWith(order)

    /** The Upcoming tab: every later match, up to [AHEAD_MS] ahead. */
    fun upcoming(events: List<SportEvent>, now: Long): List<SportEvent> =
        events.filter { it.state == State.PRE && it.start - now > SOON_MS && it.start < now + AHEAD_MS }.distinctBy { it.id }.sortedWith(order)

    /** The tag on a match: LIVE (red), TOMORROW (yellow), else its date. */
    enum class Tag { LIVE, TOMORROW, DATE, FINAL }

    fun tag(e: SportEvent, now: Long): Tag = when {
        e.state == State.LIVE -> Tag.LIVE
        e.state == State.POST -> Tag.FINAL
        dayOffset(e.start, now) == 1L -> Tag.TOMORROW
        else -> Tag.DATE
    }

    /** "TOMORROW", "LIVE", "FINAL" or the date: "SAT 15 NOV". */
    fun tagText(e: SportEvent, now: Long): String = when (tag(e, now)) {
        Tag.LIVE -> "● LIVE"; Tag.FINAL -> "FINAL"; Tag.TOMORROW -> "TOMORROW"
        Tag.DATE -> Time.dayMonth(e.start).uppercase()
    }

    /** Kick-off time: "2:05 PM" (local) from tomorrow on; today's as the app's clock is set. */
    fun timeText(e: SportEvent, now: Long): String = if (dayOffset(e.start, now) >= 1) Time.hm12(e.start) else Time.hm(e.start)

    /** Days from [now]'s local day to [t]'s: 0 today, 1 tomorrow. */
    fun dayOffset(t: Long, now: Long): Long = (Time.dayStart(t) - Time.dayStart(now) + 3_600_000L).floorDiv(86_400_000L)
}
