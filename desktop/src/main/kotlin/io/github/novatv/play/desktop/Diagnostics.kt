package io.github.novatv.play.desktop

import io.github.novatv.play.data.Diag
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Windows side of problem reports ([Diag]): the PC and its memory, and a watch on the screen's thread (a freeze of
 * 5 s or more is reported with what it was doing). Test runs (AutoTest) report nothing.
 */
object Diagnostics {
    private const val FREEZE_MS = 5_000L

    fun start() {
        if (AutoTest.scenario != null) { Diag.model = "test"; return }
        val os = System.getProperty("os.name") + " " + System.getProperty("os.version")
        Diag.model = "$os, ${Runtime.getRuntime().availableProcessors()} cores"
        Diag.facts = {
            val rt = Runtime.getRuntime()
            val pc = runCatching {
                val b = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
                ", PC ${b.freeMemorySize / 1_048_576} of ${b.totalMemorySize / 1_048_576} MB free"
            }.getOrDefault("")
            "${Diag.model}\nmemory: app ${(rt.totalMemory() - rt.freeMemory()) / 1_048_576} of ${rt.maxMemory() / 1_048_576} MB$pc"
        }
        Thread({
            var reported = false
            while (true) {
                val answered = AtomicBoolean(false)
                val t0 = System.currentTimeMillis()
                EventQueue.invokeLater { answered.set(true) }
                Thread.sleep(FREEZE_MS)
                if (!answered.get()) {
                    if (!reported) {
                        reported = true
                        val edt = Thread.getAllStackTraces().entries.firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
                        val stack = edt?.value?.take(40)?.joinToString("\n") { "  at $it" }.orEmpty()
                        Diag.report("freeze", "The window stopped responding for over ${FREEZE_MS / 1000} s\n$stack")
                    }
                    while (!answered.get()) Thread.sleep(250)
                    Diag.note("Window froze for ${(System.currentTimeMillis() - t0) / 1000.0} s")
                } else reported = false
            }
        }, "nova-watchdog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}
