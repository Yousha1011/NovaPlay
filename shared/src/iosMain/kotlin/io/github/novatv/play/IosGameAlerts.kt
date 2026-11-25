package io.github.novatv.play

import io.github.novatv.play.data.GameAlert
import io.github.novatv.play.data.GameAlerter
import io.github.novatv.play.platform.nowMs
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter

/**
 * The iPhone's game notifications: scheduled with the system ahead of time, so they show with NovaPlay closed. The
 * system keeps at most 64; [io.github.novatv.play.data.GameAlerts] schedules no more than 60. Permission is asked the
 * first time alerts are scheduled.
 */
object IosGameAlerter : GameAlerter {
    private const val PREFIX = "game:"
    private var asked = false

    override fun replace(alerts: List<GameAlert>) {
        val center = UNUserNotificationCenter.currentNotificationCenter()
        if (!asked && alerts.isNotEmpty()) {
            asked = true
            center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
        }
        center.getPendingNotificationRequestsWithCompletionHandler { pending ->
            val old = pending.orEmpty().mapNotNull { (it as? UNNotificationRequest)?.identifier }.filter { it.startsWith(PREFIX) }
            center.removePendingNotificationRequestsWithIdentifiers(old)
            val now = nowMs()
            for (a in alerts) {
                val secs = (a.at - now) / 1000.0
                if (secs < 1) continue
                val content = UNMutableNotificationContent().apply {
                    setTitle(a.title)
                    setBody(a.body)
                    setSound(UNNotificationSound.defaultSound)
                }
                val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(secs, repeats = false)
                center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(PREFIX + a.id, content, trigger)) { _ -> }
            }
        }
    }
}
