package io.github.novatv.play.data

import io.github.novatv.play.App

/** Multi-view, on every platform: up to [MAX] live channels at once, each reconnecting on its own when it drops. */
object MultiView {
    const val MAX = 4

    /** How long a screen may sit buffering before its stream is opened again. */
    const val STALL_MS = 12_000L

    /** Wait before reconnecting after the [n]th failure in a row: 2, 4, 6... up to 10 seconds. */
    fun retryDelay(n: Int): Long = (2_000L * n.coerceAtLeast(1)).coerceAtMost(10_000L)

    /** Failures in a row before a screen's trouble is put down to the playlist's limit (and said once). */
    const val WARN_AFTER = 3

    /**
     * Why a screen keeps stopping, when the playlist may be the reason: Xtream accounts say how many streams they allow,
     * and with more screens than that the server may cut one off. Only asked once a screen has failed [WARN_AFTER]
     * times in a row: many servers report a lower limit than they enforce, so the number alone proves nothing.
     * Blocking; null when it fits or the limit is unknown.
     */
    fun limitWarning(screens: Int): String? {
        val max = try { App.repo.provider?.maxConnections() } catch (e: Exception) { null } ?: return null
        return if (screens > max) "A screen keeps stopping. Your playlist says it allows $max stream${if (max == 1) "" else "s"} at once, " +
            "which may be why: try fewer screens, or ask your provider for more connections." else null
    }

    /** The grid for [n] screens: columns × rows (2 side by side; 3 and 4 in two rows). */
    fun grid(n: Int): Pair<Int, Int> = when { n <= 1 -> 1 to 1; n == 2 -> 2 to 1; else -> 2 to 2 }
}
