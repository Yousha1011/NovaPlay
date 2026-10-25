package io.github.novatv.play.platform

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/** Dates and times: what Java's SimpleDateFormat and Calendar did, the same on every platform. */
object Time {
    private fun local(t: Long): LocalDateTime = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.currentSystemDefault())
    private fun utc(t: Long): LocalDateTime = Instant.fromEpochMilliseconds(t).toLocalDateTime(TimeZone.UTC)

    private val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val days = mapOf(DayOfWeek.MONDAY to "Mon", DayOfWeek.TUESDAY to "Tue", DayOfWeek.WEDNESDAY to "Wed", DayOfWeek.THURSDAY to "Thu",
        DayOfWeek.FRIDAY to "Fri", DayOfWeek.SATURDAY to "Sat", DayOfWeek.SUNDAY to "Sun")

    /** "14:05" in local time. */
    fun hm(t: Long): String = local(t).let { "${pad2(it.hour)}:${pad2(it.minute)}" }

    /** "Sun 25 Oct". */
    fun dayMonth(t: Long): String = local(t).let { "${days[it.dayOfWeek]} ${it.dayOfMonth} ${months[it.monthNumber - 1]}" }

    /** "25 Oct 14:05". */
    fun dateTime(t: Long): String = local(t).let { "${it.dayOfMonth} ${months[it.monthNumber - 1]} ${pad2(it.hour)}:${pad2(it.minute)}" }

    fun hourOfDay(t: Long = nowMs()): Int = local(t).hour

    /** Local midnight at the start of [t]'s day. */
    fun dayStart(t: Long = nowMs()): Long {
        val tz = TimeZone.currentSystemDefault()
        return local(t).date.atStartOfDayIn(tz).toEpochMilliseconds()
    }

    /** "2026-10-25" (UTC). */
    fun isoDay(t: Long): String = utc(t).let { "${it.year}-${pad2(it.monthNumber)}-${pad2(it.dayOfMonth)}" }

    /** "20261025" (UTC). */
    fun compactDay(t: Long): String = utc(t).let { "${it.year}${pad2(it.monthNumber)}${pad2(it.dayOfMonth)}" }

    private val isoRe = Regex("""(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*(Z|[+-]\d{2}:?\d{2})?""")

    /** ISO 8601 date-times ("2026-10-25T14:00Z", "...14:00:00+01:00"); no zone means UTC. 0 when unreadable. */
    fun parseIso(s: String): Long {
        val m = isoRe.find(s) ?: return 0L
        val g = m.groupValues
        val base = epoch(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0) ?: return 0L
        return base - offsetMs(g[7])
    }

    /** XMLTV times: "20261025140000 +0100" (no zone: UTC). 0 when unreadable. */
    fun parseXmltv(v: String?): Long {
        if (v.isNullOrBlank()) return 0L
        val t = v.trim()
        if (t.length < 12) return 0L
        fun n(a: Int, b: Int) = t.substring(a, b).toIntOrNull()
        val base = epoch(n(0, 4) ?: return 0L, n(4, 6) ?: return 0L, n(6, 8) ?: return 0L, n(8, 10) ?: return 0L, n(10, 12) ?: return 0L,
            if (t.length >= 14) n(12, 14) ?: 0 else 0) ?: return 0L
        return base - offsetMs(t.substring(minOf(14, t.length)).trim())
    }

    /** "2026-10-25 14:00" or "25-10-2026 14:00", UTC. */
    fun parseDayTime(day: String, time: String): Long? {
        val d = day.split('-').mapNotNull { it.toIntOrNull() }
        val hm = time.split(':').mapNotNull { it.trim().take(2).toIntOrNull() }
        if (d.size != 3 || hm.size < 2) return null
        return if (day[4] == '-') epoch(d[0], d[1], d[2], hm[0], hm[1], 0) else epoch(d[2], d[1], d[0], hm[0], hm[1], 0)
    }

    private fun epoch(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long? = try {
        LocalDateTime(y, mo, d, h, mi, s).toInstant(TimeZone.UTC).toEpochMilliseconds()
    } catch (e: Exception) { null }

    /** "+01:00", "+0100", "-0500", "Z" -> milliseconds east of UTC. */
    private fun offsetMs(z: String?): Long {
        if (z.isNullOrBlank() || z == "Z") return 0L
        val sign = if (z[0] == '-') -1 else 1
        val digits = z.drop(1).replace(":", "")
        if (digits.length < 4) return 0L
        val h = digits.take(2).toIntOrNull() ?: return 0L
        val m = digits.substring(2, 4).toIntOrNull() ?: return 0L
        return sign * (h * 3600_000L + m * 60_000L)
    }
}
