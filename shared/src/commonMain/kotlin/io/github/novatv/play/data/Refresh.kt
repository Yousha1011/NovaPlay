package io.github.novatv.play.data

import io.github.novatv.play.platform.Time

/**
 * When playlists and the guide are read again: every few hours ([hours]; 0 means only when the user asks), and
 * optionally once a night in the background, at [hour] o'clock.
 */
object Refresh {
    private const val HOUR = 3600_000L

    /** The intervals Settings offers, in hours; 0 is "only when I ask". */
    val INTERVALS = listOf(6, 12, 24, 48, 0)

    /** Whether something last updated at [updatedAt] (null: never) is due again, refreshing every [hours]. */
    fun due(updatedAt: Long?, hours: Int, now: Long): Boolean =
        updatedAt == null || (hours > 0 && now - updatedAt >= hours * HOUR)

    /** The cache age allowed by [hours], for code that takes an age. */
    fun maxAgeMs(hours: Int): Long = if (hours <= 0) Long.MAX_VALUE else hours * HOUR

    /** The next [hour]:00 local time after [now], where the overnight update starts; [dayStart] gives local midnight. */
    fun nextOvernight(now: Long, hour: Int, dayStart: (Long) -> Long = { Time.dayStart(it) }): Long {
        val today = dayStart(now) + hour * HOUR
        return if (today > now) today else dayStart(now + 24 * HOUR) + hour * HOUR
    }

    /** "every 12 hours", "every day", "every 2 days", "only when you ask". */
    fun intervalLabel(hours: Int): String = when {
        hours <= 0 -> "Only when I ask"
        hours == 24 -> "Every day"
        hours % 24 == 0 -> "Every ${hours / 24} days"
        else -> "Every $hours hours"
    }

    /** "just now", "12 min ago", "3 h ago", "2 days ago"; "never" for null. */
    fun ago(t: Long?, now: Long): String {
        if (t == null) return "never"
        val m = (now - t).coerceAtLeast(0) / 60_000
        return when {
            m < 1 -> "just now"
            m < 60 -> "$m min ago"
            m < 48 * 60 -> "${m / 60} h ago"
            else -> "${m / (24 * 60)} days ago"
        }
    }
}
