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
import android.os.IBinder
import android.os.PowerManager
import io.github.novatv.play.data.Refresh
import io.github.novatv.play.platform.Time
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The overnight update: an alarm once a night, inside a two-hour window, wakes [SyncService]. */
object Overnight {
    private const val WINDOW = 2 * 3600_000L

    fun schedule(ctx: Context, enabled: Boolean, hour: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarm(ctx)
        am.cancel(pi)
        if (!enabled) return
        // Inexact on purpose: the system can fold it in with other work while the device sleeps.
        am.setWindow(AlarmManager.RTC_WAKEUP, Refresh.nextOvernight(System.currentTimeMillis(), hour), WINDOW, pi)
    }

    private fun alarm(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 0x5f1c,
        Intent(ctx, OvernightAlarm::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

class OvernightAlarm : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = SyncService.start(ctx)
}

/**
 * Reads every playlist and the guide again while the app is closed, then arms the next night. It runs in the
 * foreground (with a quiet notification) so Android lets it finish.
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wake: PowerManager.WakeLock? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        foreground()
        if (running) return START_NOT_STICKY
        running = true
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NovaPlay:sync").apply { acquire(60 * 60_000L) }
        scope.launch {
            val started = System.currentTimeMillis()
            try { App.repo.syncAll { App.store.overnightLast = "${Time.dateTime(started)} · $it" } }
            catch (e: Exception) { App.store.overnightLast = "${Time.dateTime(started)} · Failed: ${e.message ?: e::class.simpleName}" }
            Overnight.schedule(this@SyncService, App.store.overnightSync, App.store.overnightHour)
            wake?.let { if (it.isHeld) it.release() }
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("sync", "Playlist updates", NotificationManager.IMPORTANCE_MIN))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "sync") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Updating playlists and the TV guide")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(9, n)
    }

    override fun onDestroy() {
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }
}
