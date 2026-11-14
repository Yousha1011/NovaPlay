package io.github.novatv.play.desktop

import io.github.novatv.play.App
import io.github.novatv.play.data.Recorder
import io.github.novatv.play.data.Recording
import io.github.novatv.play.data.Recordings
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** How early a recording starts and how late it runs (Settings › Recording). */
private val PAD_BEFORE get() = App.store.recordBeforeMin * 60_000L
private val PAD_AFTER get() = App.store.recordAfterMin * 60_000L
/** Below this much free space a recording stops rather than fill the disk. */
private const val MIN_FREE = 300L * 1024 * 1024

/**
 * Windows' recorder, the same rules as Android's: the channel's MPEG-TS stream is saved as it arrives (no converter),
 * from a little before the programme to a little after; it reconnects when the stream drops and stops early when the
 * disk is nearly full. HLS-only channels are refused with a reason.
 *
 * Windows can't wake NovaPlay, so recordings only run while it is open: on the next start, one whose time has passed
 * is marked missed and one still on starts at once.
 */
object DesktopRecorder : Recorder {
    /** Videos\NovaPlay Recordings (a test run sets its own folder, so a real one is never touched). */
    val folder: File get() = (System.getenv("NOVAPLAY_RECORD_DIR")?.let(::File)
        ?: File(System.getProperty("user.home"), "Videos${File.separator}NovaPlay Recordings")).apply { mkdirs() }

    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "record-timer").apply { isDaemon = true } }
    private val armed = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val jobs = ConcurrentHashMap<String, Thread>()

    /** Recordings running now (for the test driver). */
    val running: Set<String> get() = jobs.keys

    init {
        // Closing NovaPlay: running recordings stop and keep what they saved.
        Runtime.getRuntime().addShutdownHook(Thread {
            val list = jobs.values.toList()
            list.forEach { it.interrupt() }
            list.forEach { runCatching { it.join(3000) } }
        })
    }

    override fun schedule(r: Recording) {
        val at = r.start - PAD_BEFORE
        val wait = at - System.currentTimeMillis()
        if (wait <= 0) { begin(r.id); return }
        armed.remove(r.id)?.cancel(false)
        armed[r.id] = timer.schedule({ armed.remove(r.id); begin(r.id) }, wait, TimeUnit.MILLISECONDS)
    }

    override fun cancel(id: String) {
        armed.remove(id)?.cancel(false)
        jobs[id]?.interrupt()
    }

    override fun deleteFile(path: String) { runCatching { toFile(path).delete() } }

    override fun freeBytes(): Long = runCatching { folder.usableSpace }.getOrDefault(0L)

    /** At start-up: arms every scheduled recording again, starts ones still on, and marks ones missed while closed. */
    fun rearm() {
        val now = System.currentTimeMillis()
        for (r in Recordings.all) when (r.status) {
            Recording.Status.SCHEDULED -> if (r.stop + PAD_AFTER > now) schedule(r)
                else Recordings.update(r.id) { it.copy(status = Recording.Status.FAILED, error = "Missed: NovaPlay wasn't open") }
            Recording.Status.RECORDING -> if (!jobs.containsKey(r.id)) {
                if (r.stop + PAD_AFTER > now) begin(r.id)
                else Recordings.update(r.id) { it.copy(status = if (r.bytes > 0) Recording.Status.DONE else Recording.Status.FAILED,
                    error = if (r.bytes > 0) "Stopped early: NovaPlay was closed" else "NovaPlay was closed") }
            }
            else -> {}
        }
    }

    private fun begin(id: String) {
        if (jobs.containsKey(id)) return
        val r = Recordings.get(id) ?: return
        val t = Thread({ try { record(r) } finally { jobs.remove(id) } }, "record-$id").apply { isDaemon = true }
        if (jobs.putIfAbsent(id, t) == null) t.start()
    }

    private class RecordFailure(msg: String) : Exception(msg)

    private fun record(r0: Recording) {
        val safe = r0.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifBlank { "Recording" }
        val file = File(folder, "${safe}_${r0.id}.ts")
        val uri = file.toPath().toUri().toASCIIString()
        Recordings.update(r0.id) { it.copy(status = Recording.Status.RECORDING, file = uri, error = null) }
        testLog("RECORD ${r0.id} started")
        val end = r0.stop + PAD_AFTER
        var error: String? = null
        var stoppedByUser = false
        try {
            val url = streamUrl(r0) ?: throw RecordFailure("This channel only offers an HLS stream, which can't be recorded yet")
            var failures = 0
            FileOutputStream(file, true).use { out ->
                while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(AutoTest.stream(url)).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15_000; readTimeout = 20_000
                            setRequestProperty("User-Agent", io.github.novatv.play.data.Http.UA)
                        }
                        val code = conn.responseCode
                        if (code !in 200..299) throw RecordFailure("The server refused the stream (HTTP $code)")
                        if (conn.contentType.orEmpty().contains("mpegurl", true)) throw RecordFailure("This channel only offers an HLS stream, which can't be recorded yet")
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            var sinceCheck = 0L
                            while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n)
                                sinceCheck += n
                                failures = 0
                                if (sinceCheck > 4L * 1024 * 1024) {
                                    sinceCheck = 0
                                    Recordings.update(r0.id) { it.copy(bytes = file.length()) }
                                    if (folder.usableSpace < MIN_FREE) throw RecordFailure("Stopped early: the disk is almost full")
                                }
                            }
                        }
                    } catch (e: RecordFailure) { throw e } catch (e: Exception) {
                        if (Thread.currentThread().isInterrupted) break
                        // Dropped: try again a few times, then give up.
                        if (++failures > 8) throw RecordFailure("The stream kept dropping")
                        testLog("RECORD ${r0.id} dropped (${e.javaClass.simpleName}), reconnecting ($failures)")
                        try { Thread.sleep(3000) } catch (ie: InterruptedException) { break }
                    } finally { conn?.disconnect() }
                }
                stoppedByUser = Thread.currentThread().isInterrupted
            }
        } catch (e: RecordFailure) { error = e.message } catch (e: InterruptedException) { stoppedByUser = true } catch (e: Exception) { error = "Recording failed (${e.message})" }
        val size = file.length()
        Recordings.update(r0.id) { it.copy(status = if (size > 0) Recording.Status.DONE else Recording.Status.FAILED, bytes = size,
            error = error ?: if (size == 0L) "Nothing was received" else null) }
        if (size == 0L) file.delete()
        testLog("RECORD ${r0.id} finished: ${size / 1024} KB, error=$error, stopped=$stoppedByUser")
    }

    /** The channel's stream as MPEG-TS: freshly resolved when the playlist is loaded, else the address saved at scheduling. */
    private fun streamUrl(r: Recording): String? {
        val fresh = runCatching {
            val idx = App.repo.state.value.index
            val ch = idx?.channelsFor(listOf(r.channelKey))?.firstOrNull()
            if (ch != null) App.repo.provider?.liveUrl(ch) else null
        }.getOrNull()
        val u = fresh ?: r.url
        val path = u.substringBefore('?')
        // Xtream panels serve every channel as .ts as well as .m3u8.
        if (path.endsWith(".m3u8", true)) return if (path.contains("/live/")) u.replaceFirst(".m3u8", ".ts") else null
        return u
    }

    private fun toFile(path: String): File = if (path.startsWith("file:")) File(URI(path)) else File(path)
}

/** Development only: what the test driver reads; nothing is written outside a test run. */
private fun testLog(msg: String) { if (AutoTest.scenario != null) AutoTest.log(msg) }
