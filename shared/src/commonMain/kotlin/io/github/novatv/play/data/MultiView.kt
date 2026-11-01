package io.github.novatv.play.data

import io.github.novatv.play.App

/** Multi-view, on every platform: up to [MAX] live channels at once, each reconnecting on its own when it drops. */
object MultiView {
    const val MAX = 4

    /**
     * How many screens a device with [ramMb] of memory can play without running out: each one holds seconds of video,
     * and a Fire TV Stick (under 2 GB) ground to a halt with four. Below 2 GB: 2; below 3 GB: 3; otherwise [MAX].
     */
    fun maxScreens(ramMb: Long): Int = when { ramMb in 1 until 2048 -> 2; ramMb in 2048 until 3072 -> 3; else -> MAX }

    /** How long a screen may sit buffering before its stream is opened again. */
    const val STALL_MS = 12_000L

    /**
     * A screen that had been playing at least this long when its stream ended reconnects at once, keeping its last
     * picture, with no message: servers that allow fewer streams than screens cut the extra ones off every half minute
     * or so, and a quick reconnect makes that a blink instead of a stop.
     */
    const val SEAMLESS_AFTER_MS = 10_000L

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
