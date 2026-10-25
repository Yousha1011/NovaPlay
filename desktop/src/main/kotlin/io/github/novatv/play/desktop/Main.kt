package io.github.novatv.play.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.novatv.play.App
import io.github.novatv.play.Root
import io.github.novatv.play.data.PlayItem
import io.github.novatv.play.platform.Device
import io.github.novatv.play.platform.Host
import io.github.novatv.play.ui.BackKeys
import io.github.novatv.play.ui.C
import io.github.novatv.play.ui.NovaTheme
import io.github.novatv.play.ui.T
import io.github.novatv.play.ui.Text
import kotlinx.coroutines.delay

/** What the window shows over the app: the player, and a short message. */
object Desktop : Host {
    override val device = Device.DESKTOP
    var playing by mutableStateOf<PlayItem?>(null)
    var message by mutableStateOf<Pair<String, Long>?>(null)
    override fun play(item: PlayItem) { playing = item }
    override fun toast(msg: String) { message = msg to System.nanoTime() }
}

fun main() {
    App.start(Desktop)
    application {
        val window = rememberWindowState(size = DpSize(1440.dp, 860.dp))
        Window(
            onCloseRequest = { Vlc.release(); exitApplication() },
            title = "NovaPlay",
            icon = painterResource("icon.png"),
            state = window,
            onPreviewKeyEvent = { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    // F11 for full screen, as in a browser.
                    e.key == Key.F11 -> { window.placement = if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen; true }
                    e.key == Key.Escape && Desktop.playing == null -> BackKeys.back()
                    else -> false
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(C.bg)) {
                NovaTheme {
                    Root()
                    Desktop.playing?.let { item ->
                        PlayerScreen(item, window, onClose = {
                            Desktop.playing = null
                            if (window.placement == WindowPlacement.Fullscreen) window.placement = WindowPlacement.Floating
                        })
                    }
                    Desktop.message?.let { (msg, at) ->
                        LaunchedEffect(at) { delay(2500); if (Desktop.message?.second == at) Desktop.message = null }
                        Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
                            Text(msg, style = T.body, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE0141A28)).padding(horizontal = 20.dp, vertical = 12.dp))
                        }
                    }
                }
            }
        }
    }
}
