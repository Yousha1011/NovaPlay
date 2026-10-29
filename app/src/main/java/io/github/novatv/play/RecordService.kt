package io.github.novatv.play

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import io.github.novatv.play.data.Http
import io.github.novatv.play.data.Recorder
import io.github.novatv.play.data.Recording
import io.github.novatv.play.data.Recordings
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Recordings start this early and end this late, since guide times are often a little off. */
private const val PAD_BEFORE = 60_000L
private const val PAD_AFTER = 120_000L
/** Below this much free space a recording stops rather than fill the device. */
private const val MIN_FREE = 300L * 1024 * 1024

/**
 * Android's recorder: alarms wake [RecordAlarm] at each start, which starts [RecordService] in the foreground.
 * Files go to the app's own folder on the device (no storage permission; removed with the app).
 */
class AndroidRecorder(private val ctx: Context) : Recorder {
    override fun schedule(r: Recording) {
        val at = r.start - PAD_BEFORE
        if (at <= System.currentTimeMillis()) { RecordService.start(ctx, r.id); return }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarm(ctx, r.id)
        // Exact alarms need the user's permission on Android 12+; without it the start may drift by a few minutes,
        // which the padding mostly absorbs.
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    override fun cancel(id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarm(ctx, id))
        RecordService.stop(ctx, id)
    }

    override fun deleteFile(path: String) { File(path.removePrefix("file://")).delete() }

    override fun freeBytes(): Long = runCatching { StatFs(folder(ctx).path).availableBytes }.getOrDefault(0L)

    /** Alarms don't survive a restart: arms every scheduled recording again, and marks ones cut off by it as failed. */
    fun rearm() {
        for (r in Recordings.all) when (r.status) {
            Recording.Status.SCHEDULED -> if (r.stop > System.currentTimeMillis()) schedule(r)
                else Recordings.update(r.id) { it.copy(status = Recording.Status.FAILED, error = "Missed: the device was off or asleep") }
            Recording.Status.RECORDING -> if (!RecordService.running(r.id)) {
                if (r.stop > System.currentTimeMillis()) RecordService.start(ctx, r.id)
                else Recordings.update(r.id) { it.copy(status = if (r.bytes > 0) Recording.Status.DONE else Recording.Status.FAILED,
                    error = if (r.bytes > 0) "Stopped early: the app was closed" else "The app was closed") }
            }
            else -> {}
        }
    }

    companion object {
        fun folder(ctx: Context): File = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: ctx.filesDir, "Recordings").apply { mkdirs() }
        fun alarm(ctx: Context, id: String): PendingIntent = PendingIntent.getBroadcast(ctx, id.hashCode(),
            Intent(ctx, RecordAlarm::class.java).putExtra("id", id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Wakes at a recording's start and hands it to the service; after a restart, arms the scheduled ones again. */
class RecordAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) { (App.host.recorder as? AndroidRecorder)?.rearm(); return }
        intent.getStringExtra("id")?.let { RecordService.start(ctx, it) }
    }
}

/**
 * Records live channels by saving the stream as it arrives (MPEG-TS streams can be saved as they are: no FFmpeg).
 * HLS-only channels are refused with a reason. Runs in the foreground with a notification while anything records,
 * holds a partial wake lock, reconnects when the stream drops, and stops early when space runs low.
 */
class RecordService : Service() {
    private val jobs = ConcurrentHashMap<String, Thread>()
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        val id = intent?.getStringExtra("id")
        when (intent?.action) {
            "stop" -> id?.let { jobs.remove(it)?.interrupt() }
            else -> if (id != null && !jobs.containsKey(id)) begin(id)
        }
        if (jobs.isEmpty()) finishIfIdle()
        return START_NOT_STICKY
    }

    private fun begin(id: String) {
        val r = Recordings.get(id) ?: return
        val t = Thread({ record(r) }, "record-$id")
        jobs[id] = t
        live.add(id)
        if (wake == null) wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaPlay:record").apply { acquire(12 * 3600_000L) }
        t.start()
        foreground()
    }

    private fun record(r0: Recording) {
        val safe = r0.title.replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().take(60).ifBlank { "Recording" }
        val file = File(AndroidRecorder.folder(this), "${safe}_${r0.id}.ts")
        Recordings.update(r0.id) { it.copy(status = Recording.Status.RECORDING, file = file.path, error = null) }
        val end = r0.stop + PAD_AFTER
        var error: String? = null
        try {
            val url = streamUrl(r0) ?: throw RecordFailure("This channel only offers an HLS stream, which can't be recorded yet")
            var failures = 0
            FileOutputStream(file, true).use { out ->
                while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted) {
                    try {
                        TvHttp.client.newCall(Request.Builder().url(url).header("User-Agent", Http.UA).build()).execute().use { resp ->
                            if (!resp.isSuccessful) throw RecordFailure("The server refused the stream (HTTP ${resp.code})")
                            val type = resp.header("Content-Type").orEmpty()
                            if (type.contains("mpegurl", true)) throw RecordFailure("This channel only offers an HLS stream, which can't be recorded yet")
                            val input = resp.body!!.byteStream()
                            val buf = ByteArray(64 * 1024)
                            var sinceCheck = 0L
                            while (System.currentTimeMillis() < end && !Thread.currentThread().isInterrupted) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n)
                                sinceCheck += n
                                if (sinceCheck > 16L * 1024 * 1024) {
                                    sinceCheck = 0
                                    Recordings.update(r0.id) { it.copy(bytes = file.length()) }
                                    if (StatFs(file.parent).availableBytes < MIN_FREE) throw RecordFailure("Stopped early: the device is almost full")
                                }
                                failures = 0
                            }
                        }
                    } catch (e: RecordFailure) { throw e } catch (e: Exception) {
                        if (Thread.currentThread().isInterrupted) break
                        // Dropped: try again a few times, then give up.
                        if (++failures > 8) throw RecordFailure("The stream kept dropping")
                        Thread.sleep(3000)
                    }
                }
            }
        } catch (e: RecordFailure) { error = e.message } catch (e: InterruptedException) { } catch (e: Exception) { error = "Recording failed (${e.message})" }
        val size = file.length()
        Recordings.update(r0.id) { it.copy(status = if (size > 0) Recording.Status.DONE else Recording.Status.FAILED, bytes = size,
            error = error ?: if (size == 0L) "Nothing was received" else null) }
        if (size == 0L) file.delete()
        jobs.remove(r0.id); live.remove(r0.id)
        finishIfIdle()
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

    private fun finishIfIdle() {
        if (jobs.isNotEmpty()) { foreground(); return }
        wake?.let { if (it.isHeld) it.release() }; wake = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("rec", "Recordings", NotificationManager.IMPORTANCE_LOW))
        val names = jobs.keys.mapNotNull { Recordings.get(it)?.title }
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "rec") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (names.size > 1) "Recording ${names.size} programmes" else "Recording")
            .setContentText(names.joinToString(", ").ifBlank { "Starting…" })
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(7, n)
    }

    override fun onDestroy() {
        jobs.values.forEach { it.interrupt() }
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private class RecordFailure(msg: String) : Exception(msg)

    companion object {
        private val live = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        fun running(id: String) = id in live
        fun start(ctx: Context, id: String) {
            val i = Intent(ctx, RecordService::class.java).putExtra("id", id)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context, id: String) {
            if (!running(id)) return
            ctx.startService(Intent(ctx, RecordService::class.java).setAction("stop").putExtra("id", id))
        }
    }
}
