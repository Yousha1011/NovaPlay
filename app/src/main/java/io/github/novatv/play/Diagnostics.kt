package io.github.novatv.play

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.novatv.play.data.Diag

/**
 * The Android side of problem reports ([Diag]): the device and its memory, a watch on the main thread (the screen
 * freezing for 5 s or more is reported with what it was doing), and on Android 11 and later why the app last closed
 * when Android closed it (out of memory, not responding, a native crash), which no error handler sees.
 */
object Diagnostics {
    private const val FREEZE_MS = 5_000L

    fun start(ctx: Context) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        Diag.model = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})"
        Diag.facts = {
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val rt = Runtime.getRuntime()
            "${Diag.model}\nmemory: device ${mi.availMem / 1_048_576} of ${mi.totalMem / 1_048_576} MB free" +
                (if (mi.lowMemory) " (low)" else "") + ", app heap ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB" +
                ", app limit ${am.memoryClass} MB (large ${am.largeMemoryClass} MB)"
        }
        watchMainThread()
        if (Build.VERSION.SDK_INT >= 30) Thread { runCatching { lastExits(ctx, am) } }.start()
    }

    private fun watchMainThread() {
        val main = Handler(Looper.getMainLooper())
        Thread({
            var reported = false
            while (true) {
                val answered = java.util.concurrent.atomic.AtomicBoolean(false)
                val t0 = System.currentTimeMillis()
                main.post { answered.set(true) }
                Thread.sleep(FREEZE_MS)
                if (!answered.get()) {
                    if (!reported) {
                        reported = true
                        val stack = Looper.getMainLooper().thread.stackTrace.take(40).joinToString("\n") { "  at $it" }
                        Diag.report("freeze", "The screen stopped responding for over ${FREEZE_MS / 1000} s\n$stack")
                    }
                    while (!answered.get()) Thread.sleep(250)
                    Diag.note("Screen froze for ${(System.currentTimeMillis() - t0) / 1000.0} s")
                } else reported = false
            }
        }, "nova-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    /** Android's reasons for the app's last closes since the last start that read them. */
    @androidx.annotation.RequiresApi(30)
    private fun lastExits(ctx: Context, am: ActivityManager) {
        val sp = ctx.getSharedPreferences("diag", Context.MODE_PRIVATE)
        val seen = sp.getLong("exitSeen", 0L)
        val exits = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10).filter { it.timestamp > seen }
        if (exits.isEmpty()) return
        sp.edit().putLong("exitSeen", exits.maxOf { it.timestamp }).apply()
        if (seen == 0L) return // the first start with this version: older closes aren't news
        val bad = setOf(ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_INITIALIZATION_FAILURE)
        for (e in exits.filter { it.reason in bad || (it.reason == ApplicationExitInfo.REASON_SIGNALED && it.importance <= 125) }) {
            val why = when (e.reason) {
                ApplicationExitInfo.REASON_LOW_MEMORY -> "closed by Android: out of memory"
                ApplicationExitInfo.REASON_ANR -> "closed by Android: not responding"
                ApplicationExitInfo.REASON_CRASH -> "crashed"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crashed in native code (player or graphics)"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "closed by Android: too much memory or CPU"
                ApplicationExitInfo.REASON_SIGNALED -> "killed while on screen (signal ${e.status})"
                else -> "failed to start"
            }
            val trace = if (e.reason == ApplicationExitInfo.REASON_ANR || e.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
                runCatching { e.traceInputStream?.bufferedReader()?.use { r -> r.readText().take(20_000) } }.getOrNull().orEmpty() else ""
            Diag.report("exit", "The app $why at ${java.util.Date(e.timestamp)}\n" +
                "memory then: ${e.pss / 1024} MB (pss), ${e.rss / 1024} MB (rss); ${e.description.orEmpty()}\n$trace")
        }
    }
}
