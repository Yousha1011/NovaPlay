package io.github.novatv.play

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * The phone app's push messages (score changes and final whistles, sent by the NovaPlay server): Firebase set up from
 * the values built in from google-services.json, and this device's address for messages kept for [token].
 */
object Push {
    @Volatile var token: String? = null
        private set

    fun start(ctx: Context) {
        if (BuildConfig.FB_APP.isBlank()) return
        runCatching {
            if (FirebaseApp.getApps(ctx).isEmpty())
                FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder().setProjectId(BuildConfig.FB_PROJECT)
                    .setApplicationId(BuildConfig.FB_APP).setApiKey(BuildConfig.FB_KEY).setGcmSenderId(BuildConfig.FB_SENDER).build())
            token = ctx.getSharedPreferences("push", Context.MODE_PRIVATE).getString("token", null)
            FirebaseMessaging.getInstance().token.addOnSuccessListener { t -> remember(ctx, t) }
        }.onFailure { io.github.novatv.play.platform.logW("push start", it) }
    }

    fun remember(ctx: Context, t: String) {
        val changed = t != token
        token = t
        ctx.getSharedPreferences("push", Context.MODE_PRIVATE).edit().putString("token", t).apply()
        // The server learns the new address with the followed games.
        if (changed) runCatching { io.github.novatv.play.data.GameAlerts.update() }
    }
}

class PushService : FirebaseMessagingService() {
    override fun onNewToken(t: String) = Push.remember(this, t)

    /** With the app open, Android leaves showing the message to the app. */
    override fun onMessageReceived(m: RemoteMessage) {
        val n = m.notification
        val title = n?.title ?: m.data["title"] ?: return
        AndroidGameAlerter.show(this, m.data["key"] ?: m.messageId ?: title, title, n?.body ?: m.data["body"].orEmpty())
    }
}
