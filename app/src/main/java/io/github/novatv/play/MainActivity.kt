package io.github.novatv.play

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.novatv.play.ui.NovaTheme
import io.github.novatv.play.ui.phone.PhoneRoot

class MainActivity : ComponentActivity() {
    companion object {
        /** The app's screen is started (on screen, or under the TV's see-through full screen). */
        @Volatile var started = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PHONE) {
            // The phone app: the iPhone's screens, drawn under the status and navigation bars.
            enableEdgeToEdge()
            setContent { PhoneRoot() }
            // Game alerts need Android 13's notification permission: asked once, when either alert is switched on.
            if (android.os.Build.VERSION.SDK_INT >= 33 && (App.store.alertsBefore || App.store.alertsStart) &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        } else setContent { NovaTheme { Root() } }
    }

    /** The remote's play/pause button pauses the TV window's channel, or plays it on (full screen has its own). */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        if (!PHONE && (k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE || k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            && App.preview.state.value is io.github.novatv.play.data.PreviewController.State.Playing) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                val paused = App.preview.paused
                if (k == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || (k == android.view.KeyEvent.KEYCODE_MEDIA_PAUSE) != paused) App.preview.togglePause()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        started = true
        App.away = false
        // Back in the app: the open playlist and the guide are read again if their interval has passed.
        App.repo.refreshIfDue()
    }

    /**
     * The full-screen player opened over the app, or it went to the background (Home, another app, the screen off):
     * previews let their player go, and stay stopped until the user moves on.
     */
    override fun onStop() {
        super.onStop()
        started = false
        App.away = true
        // Not while the preview's player is passing to full screen: stopping it now would cut the stream it is taking.
        // (Once full screen has it, the preview's stop leaves that player alone: see ExoPreviewEngine.release.)
        if (App.host.previewEngine != null && !App.handoverActive) App.preview.stop()
    }
}

/** True in the phone app, false in the Fire TV app. */
val PHONE = BuildConfig.FLAVOR == "phone"
