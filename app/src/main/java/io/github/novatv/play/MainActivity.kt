package io.github.novatv.play

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.novatv.play.ui.NovaTheme
import io.github.novatv.play.ui.phone.PhoneRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PHONE) {
            // The phone app: the iPhone's screens, drawn under the status and navigation bars.
            enableEdgeToEdge()
            setContent { PhoneRoot() }
        } else setContent { NovaTheme { Root() } }
    }

    override fun onStart() {
        super.onStart()
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
        App.away = true
        if (App.host.previewEngine != null) App.preview.stop()
    }
}

/** True in the phone app, false in the Fire TV app. */
val PHONE = BuildConfig.FLAVOR == "phone"
