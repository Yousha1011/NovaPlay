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
import io.github.novatv.play.ui.arrowKeysMoveFocus
import androidx.compose.ui.focus.onFocusChanged
import io.github.novatv.play.ui.Text
import kotlinx.coroutines.delay

/** What the window shows over the app: the player, and a short message. */
object Desktop : Host {
    override val device = Device.DESKTOP
    /** 96 MB: a screen of logos and posters fits easily, and old ones are decoded again from the disk cache. */
    override val imageCacheBytes: Long = 96L * 1024 * 1024
    var playing by mutableStateOf<PlayItem?>(null)
    var message by mutableStateOf<Pair<String, Long>?>(null)
    override fun play(item: PlayItem) { playing = item }
    override fun toast(msg: String) { message = msg to System.nanoTime() }
    override fun openUrl(url: String) { runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) } }
    override val updateAsset = Regex("""NovaPlay-Setup-[\d.]+\.msi""")
    /** Recording while NovaPlay is open (see DesktopRecorder). */
    override val recorder: io.github.novatv.play.data.Recorder get() = DesktopRecorder
    /** Live TV's preview window (see DesktopPreviewEngine). */
    override val previewEngine: io.github.novatv.play.data.PreviewEngine get() = DesktopPreviewEngine
    /** Starts the setup (it replaces this version and keeps the settings in %APPDATA%), then quits so it can. */
    override fun installUpdate(file: String) {
        try {
            ProcessBuilder("msiexec", "/i", file).start()
        } catch (e: Exception) { toast("Couldn't start the setup: ${e.message}"); return }
        Vlc.release()
        kotlin.system.exitProcess(0)
    }
}

/** Raised by a second launch of the app: the open window comes to the front instead of a new one opening. */
private var wake by mutableStateOf(0)

/**
 * Only one NovaPlay runs at a time. The first holds a local port; a later launch finds it taken, tells the first one
 * to show itself, and quits.
 */
private fun firstInstance(): Boolean {
    val port = 47123
    return try {
        val server = java.net.ServerSocket(port, 5, java.net.InetAddress.getLoopbackAddress())
        Thread({ while (true) runCatching { server.accept().use { wake++ } } }, "single-instance").apply { isDaemon = true }.start()
        true
    } catch (e: java.io.IOException) {
        runCatching { java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port).close() }
        false
    }
}

fun main() {
    // A test run (development only) runs beside an open NovaPlay with its own data, and never signals it.
    if (AutoTest.scenario == null && !firstInstance()) return
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { t, e ->
        io.github.novatv.play.data.CrashLog.record(e, "Windows, ${t.name}")
        // The short record above (shown in Settings › About) keeps 12 lines per cause; the whole trace goes beside it
        // and to the console, so a crash deep in Compose still shows which screen it came from.
        runCatching {
            val full = "NovaPlay ${io.github.novatv.play.data.Updates.CURRENT} · Windows, ${t.name} · ${java.time.LocalDateTime.now().withNano(0)}\n${e.stackTraceToString()}"
            java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "NovaPlay/cache/last-crash-full.txt")
                .apply { parentFile?.mkdirs() }.writeText(full)
            System.err.println(full)
        }
        previous?.uncaughtException(t, e)
    }
    App.start(Desktop)
    AutoTest.installHooks()
    // VLC's engine takes most of a second to load: done now, off the window's thread, not on the first channel.
    Thread({ runCatching { Vlc.factory } }, "vlc-warmup").apply { isDaemon = true }.start()
    // Recordings scheduled before: armed again, or started / marked missed (Windows can't wake NovaPlay to record).
    Thread({ runCatching { DesktopRecorder.rearm() } }, "record-rearm").apply { isDaemon = true }.start()
    application {
        val window = rememberWindowState(size = DpSize(1440.dp, 860.dp))
        var contentFocused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        Window(
            onCloseRequest = { App.preview.stop(); Vlc.release(); exitApplication() },
            title = "NovaPlay",
            icon = painterResource("icon.png"),
            state = window,
            onPreviewKeyEvent = { e ->
                if (AutoTest.scenario != null && e.type == KeyEventType.KeyDown) AutoTest.received++
                when {
                    e.type != KeyEventType.KeyDown -> false
                    // F11 for full screen, as in a browser.
                    e.key == Key.F11 -> { window.placement = if (window.placement == WindowPlacement.Fullscreen) WindowPlacement.Floating else WindowPlacement.Fullscreen; true }
                    e.key == Key.Escape && Desktop.playing == null -> BackKeys.back()
                    // At launch nothing has focus, so the arrows have nothing to move from: the first one puts focus on
                    // the menu, as Esc does.
                    !contentFocused && Desktop.playing == null &&
                        e.key in listOf(Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight) -> BackKeys.back()
                    else -> false
                }
            },
        ) {
            val frame = this@Window.window
            // Development only: a test scenario when the dev build is started with NOVAPLAY_AUTOTEST (see AutoTest).
            if (AutoTest.scenario != null) LaunchedEffect(frame) { AutoTest.start(frame) }
            // Back to NovaPlay's window: playlist servers that sleep when idle are woken (see Repo.wakeServers).
            androidx.compose.runtime.DisposableEffect(frame) {
                val l = object : java.awt.event.WindowAdapter() {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent) { App.repo.wakeServers() }
                }
                frame.addWindowFocusListener(l)
                onDispose { frame.removeWindowFocusListener(l) }
            }
            // The app is out of sight behind the player (or minimised): its pages stop their preview, as on Android TV.
            LaunchedEffect(Desktop.playing != null, window.isMinimized) {
                App.away = Desktop.playing != null || window.isMinimized
                if (window.isMinimized) App.preview.stop()
                if (Desktop.playing == null) DesktopPreviewEngine.fullScreenClosed()
            }
            LaunchedEffect(wake) {
                if (wake == 0) return@LaunchedEffect
                if (window.isMinimized) window.isMinimized = false
                frame.toFront(); frame.requestFocus()
            }
            val fm = androidx.compose.ui.platform.LocalFocusManager.current
            Box(Modifier.fillMaxSize().background(C.bg).arrowKeysMoveFocus(fm) { Desktop.playing == null }
                .onFocusChanged { contentFocused = it.hasFocus; if (AutoTest.scenario != null) AutoTest.focusInside = it.hasFocus }) {
                NovaTheme {
                    Root()
                    // Full screen grows out of the TV window at the top right, and shrinks back into it on Back.
                    val lastItem = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<io.github.novatv.play.data.PlayItem?>(null) }
                    Desktop.playing?.let { if (lastItem.value !== it) lastItem.value = it }
                    val toWindow = androidx.compose.ui.graphics.TransformOrigin(0.97f, 0.12f)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = Desktop.playing != null,
                        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(280), initialScale = 0.22f, transformOrigin = toWindow),
                        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(240)) +
                            androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(280), targetScale = 0.22f, transformOrigin = toWindow),
                    ) {
                        lastItem.value?.let { item ->
                            androidx.compose.runtime.key(item) {
                                PlayerScreen(item, window, onClose = {
                                    if (Desktop.playing == null) return@PlayerScreen
                                    Desktop.playing = null
                                    App.playerClosed++
                                    if (window.placement == WindowPlacement.Fullscreen) window.placement = WindowPlacement.Floating
                                })
                            }
                        }
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
