package io.github.novatv.play.data

import io.github.novatv.play.App

/** Multi-view, on every platform: up to [MAX] live channels at once, each reconnecting on its own when it drops. */
object MultiView {
    const val MAX = 4

    /** How long a screen may sit buffering before its stream is opened again. */
    const val STALL_MS = 12_000L

    /** Wait before reconnecting after the [n]th failure in a row: 2, 4, 6... up to 10 seconds. */
    fun retryDelay(n: Int): Long = (2_000L * n.coerceAtLeast(1)).coerceAtMost(10_000L)

    /**
     * A warning when the playlist allows fewer streams than [screens] (Xtream accounts say how many): the server then
     * cuts one of them off, which looks like a screen stopping. Blocking; null when it fits or the limit is unknown.
     */
    fun limitWarning(screens: Int): String? {
        val max = try { App.repo.provider?.maxConnections() } catch (e: Exception) { null } ?: return null
        return if (screens > max) "Your playlist allows $max stream${if (max == 1) "" else "s"} at once, so ${screens - max} screen" +
            "${if (screens - max == 1) "" else "s"} may keep stopping. Ask your provider for more connections." else null
    }

    /** The grid for [n] screens: columns × rows (2 side by side; 3 and 4 in two rows). */
    fun grid(n: Int): Pair<Int, Int> = when { n <= 1 -> 1 to 1; n == 2 -> 2 to 1; else -> 2 to 2 }
}
