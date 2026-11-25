package io.github.novatv.play

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.novatv.play.data.GameAlert
import io.github.novatv.play.data.GameAlerter

/**
 * The phone's game notifications: an alarm per notification wakes [GameAlertReceiver], which shows it. The alarms
 * scheduled last time are remembered, so a new list replaces them.
 */
class AndroidGameAlerter(private val ctx: Context) : GameAlerter {
    private val sp get() = ctx.getSharedPreferences("gameAlerts", Context.MODE_PRIVATE)

    override fun replace(alerts: List<GameAlert>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        sp.getStringSet("ids", emptySet()).orEmpty().forEach { id -> am.cancel(pending(ctx, id, null)) }
        for (a in alerts) {
            val pi = pending(ctx, a.id, a)
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, a.at, pi)
        }
        sp.edit().putStringSet("ids", alerts.map { it.id }.toSet()).apply()
    }

    companion object {
        const val CHANNEL = "games"

        fun pending(ctx: Context, id: String, a: GameAlert?): PendingIntent {
            val i = Intent(ctx, GameAlertReceiver::class.java).setAction("game:$id")
            if (a != null) i.putExtra("title", a.title).putExtra("body", a.body).putExtra("match", a.matchId)
            return PendingIntent.getBroadcast(ctx, id.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        /** Shows a game notification now (an alarm's, or one the server sent); tapping it opens the app. */
        fun show(ctx: Context, key: String, title: String, body: String) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Game alerts", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(ctx, key.hashCode(),
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, CHANNEL) else android.app.Notification.Builder(ctx)
            val n = b.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(title).setContentText(body)
                .setContentIntent(open).setAutoCancel(true).build()
            runCatching { nm.notify(key.hashCode(), n) }  // no permission granted: nothing shown
        }
    }
}

class GameAlertReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.action?.removePrefix("game:") ?: return
        AndroidGameAlerter.show(ctx, id, intent.getStringExtra("title") ?: "NovaPlay", intent.getStringExtra("body") ?: "")
    }
}
