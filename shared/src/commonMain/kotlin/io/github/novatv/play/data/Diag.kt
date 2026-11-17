package io.github.novatv.play.data

import io.github.novatv.play.App
import io.github.novatv.play.json.JSONObject
import io.github.novatv.play.platform.IO
import io.github.novatv.play.platform.cacheDir
import io.github.novatv.play.platform.locked
import io.github.novatv.play.platform.nowMs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.buffer
import okio.use

/**
 * Problem reports: crashes, freezes, slow loading and failed loads go to the accounts server, which keeps them in the
 * private accounts repository (diagnostics/<day>/), so they can be read without reaching the device. Each report carries
 * the app's last steps ([note]) and its memory. Addresses lose their paths and queries, and logins are blanked, before
 * anything leaves the device. Settings › About can turn it off ([Store.problemReports]).
 */
object Diag {
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private val steps = ArrayDeque<String>()
    private val stepsLock = io.github.novatv.play.platform.Lock()
    private val sentLock = io.github.novatv.play.platform.Lock()
    private val sentAt = HashMap<String, Long>()
    private var sentToday = 0
    private var day = 0L

    /** Platform facts each report starts with (the device's model, memory): set by each platform's start-up. */
    var facts: () -> String = { "" }

    /** One of the app's steps ("Opening playlist X"), kept (the last 40) to show what led to a problem. */
    fun note(step: String) {
        val line = io.github.novatv.play.platform.Time.dateTime(nowMs()).takeLast(8) + " " + scrub(step).take(200)
        stepsLock.locked {
            if (previous == null) previous = runCatching { readSteps() }.getOrDefault("")
            steps.addLast(line); while (steps.size > 40) steps.removeFirst()
        }
        // Kept on disk too, so when Android closes the app the next start can report what it was doing.
        scope.launch { runCatching { val text = steps(); FileSystem.SYSTEM.write(stepsFile()) { writeUtf8(text) } } }
    }

    /** The steps of the run before this one (read before this run's first step replaced them on disk). */
    private var previous: String? = null
    fun previousSteps(): String = stepsLock.locked { previous.orEmpty() }
    private fun stepsFile() = cacheDir() / "last-steps.txt"
    private fun readSteps(): String = if (FileSystem.SYSTEM.exists(stepsFile())) FileSystem.SYSTEM.read(stepsFile()) { readUtf8() } else ""

    fun steps(): String = stepsLock.locked { steps.joinToString("\n") }

    /** Something took [ms]: reported when it's over [limitMs]. */
    fun slow(what: String, ms: Long, limitMs: Long) {
        note("$what took ${ms / 1000.0} s")
        if (ms > limitMs) report("slow", "$what took ${ms / 1000.0} s (over ${limitMs / 1000} s)")
    }

    /** A load that failed: its error and where it came from. */
    fun failed(what: String, e: Throwable) = report("error", "$what failed\n" + trace(e))

    /**
     * Sends a report ([kind]: crash, freeze, slow, error, exit). The same problem is sent once an hour at most, and 25
     * reports a day; nothing is sent when reports are off or the build has no server.
     */
    fun report(kind: String, text: String, earlierRun: Boolean = false) {
        val now = nowMs()
        val key = kind + ":" + text.lineSequence().firstOrNull().orEmpty().take(80)
        sentLock.locked {
            if (now / 86_400_000L != day) { day = now / 86_400_000L; sentToday = 0 }
            if ((sentAt[key] ?: 0L) > now - 3600_000L || sentToday >= 25) return
            sentAt[key] = now; sentToday++
        }
        val body = scrub(text) + "\n\n--- device\n" + runCatching { facts() }.getOrDefault("") +
            (if (earlierRun) "\n\n--- last steps before it closed\n" + previousSteps() + "\n\n--- this start\n" + steps()
             else "\n\n--- last steps\n" + steps())
        scope.launch { send(kind, body) }
    }

    /** Blocking send (the crash handler's, with the app about to close); true when the server kept it. */
    fun send(kind: String, text: String): Boolean {
        // Unit tests run without a platform (no model): they never report.
        if (model == "test" || model.isBlank() || !AccountSync.available || !runCatching { App.store.problemReports }.getOrDefault(true)) return false
        return try {
            val json = JSONObject().put("kind", kind).put("text", text.take(60_000)).put("version", Updates.CURRENT)
                .put("platform", runCatching { App.device.name.lowercase() }.getOrDefault("unknown"))
                .put("model", model).put("device", deviceId())
            Http.send("POST", AccountSync.serverUrl("/account/diag"), emptyMap(), json.toString()).first == 200
        } catch (e: Throwable) { false }
    }

    /** The device's model ("AFTMA08C15", "Windows 11"), set at start-up. */
    var model: String = ""

    /** A random name for this install, so reports from one device can be told apart (nothing else identifies it). */
    private fun deviceId(): String = runCatching {
        App.store.diagId.ifBlank { (1..8).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("").also { App.store.diagId = it } }
    }.getOrDefault("unknown")

    /**
     * At start-up: the last crash ([CrashLog]) goes up once, then is marked sent (it stays in Settings › About).
     */
    fun sendPendingCrash() = scope.launch {
        val fs = FileSystem.SYSTEM
        val flag = cacheDir() / "last-crash.sent"
        val crash = CrashLog.last() ?: return@launch
        val stamp = crash.hashCode().toString()
        val sent = runCatching { if (fs.exists(flag)) fs.source(flag).buffer().use { it.readUtf8() } else "" }.getOrDefault("")
        if (sent == stamp) return@launch
        if (send("crash", scrub(crash) + "\n\n--- device\n" + runCatching { facts() }.getOrDefault("")))
            runCatching { fs.sink(flag).buffer().use { it.writeUtf8(stamp) } }
    }

    fun trace(e: Throwable): String = buildString {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 4) {
            if (depth > 0) append("Caused by: ")
            append(t::class.simpleName).append(": ").append(t.message.orEmpty()).append('\n')
            t.stackTraceToString().lineSequence().drop(1).take(25).forEach { append("  ").append(it.trim()).append('\n') }
            t = t.cause.takeIf { it !== t }
            depth++
        }
    }

    /**
     * Strips what could identify a playlist or its login: addresses keep only their host, and user names, passwords,
     * tokens and MAC addresses are blanked.
     */
    fun scrub(s: String): String = s
        .replace(Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]*)://(?:[^\s/@"']*@)?([^\s/:?#"']+)(?::\d+)?[^\s"'<>)]*""")) { "${it.groupValues[1]}://${it.groupValues[2]}/…" }
        .replace(Regex("""(?i)\b(user(?:name)?|pass(?:word)?|token|key|auth|mac|sid|session)(["']?\s*[=:]\s*["']?)[^\s&"',;]+""")) { it.groupValues[1] + it.groupValues[2] + "***" }
        .replace(Regex("""(?i)\b[0-9a-f]{2}(?::[0-9a-f]{2}){5}\b"""), "**:**:**:**:**:**")
}
