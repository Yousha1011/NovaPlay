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

    /** The app went to the background (Home, another app, the screen off): the Live TV preview lets its player go. */
    override fun onStop() {
        super.onStop()
        if (App.host.previewEngine != null) App.preview.stop()
    }
}

/** True in the phone app, false in the Fire TV app. */
val PHONE = BuildConfig.FLAVOR == "phone"
