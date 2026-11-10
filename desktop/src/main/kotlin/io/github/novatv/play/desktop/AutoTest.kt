package io.github.novatv.play.desktop

import io.github.novatv.play.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.event.KeyEvent
import java.io.File

/**
 * Development only: runs a test scenario inside the app, so the Windows build can be checked without anyone at the
 * keyboard. Off unless the dev build is started with NOVAPLAY_AUTOTEST=<scenario>; releases never set it.
 *
 * Keys go into NovaPlay's own window from inside the app (its AWT event queue), never through Windows, so no other
 * program can receive them, whichever window is in front. Everything it does goes to %TEMP%\novaplay-autotest.log.
 */
object AutoTest {
    val scenario: String? = System.getenv("NOVAPLAY_AUTOTEST")?.trim()?.lowercase()?.ifEmpty { null }
    private val file = File(System.getProperty("java.io.tmpdir"), "novaplay-autotest.log")
    private var window: java.awt.Window? = null
    private var failures = 0
    // ---- probes (read only; set by the player and multi-view while a scenario runs) ----
    data class ScreenState(val channel: String, val muted: Boolean, val buffering: Boolean, val error: String?, val playing: Boolean, val hd: Boolean)
    data class MultiState(val active: Int, val picking: Int?, val screens: List<ScreenState>)
    data class FullState(val channel: String, val playing: Boolean, val buffering: Boolean, val error: String?)
    @Volatile var multi: (() -> MultiState)? = null
    @Volatile var full: (() -> FullState)? = null
    /** VLC players made and released, by the full-screen player and multi-view. */
    val vlcCreated = java.util.concurrent.atomic.AtomicInteger()
    val vlcReleased = java.util.concurrent.atomic.AtomicInteger()
    fun vlcLive() = vlcCreated.get() - vlcReleased.get()

    /** Scenarios that cut the network route every stream through [relay]; others leave stream addresses alone. */
    val relay: StreamRelay? by lazy { if (scenario?.startsWith("net") == true) StreamRelay(io.github.novatv.play.data.Http.UA) { log(it) } else null }
    /** A player's stream address: through the relay while a network scenario runs, otherwise unchanged. */
    @JvmStatic fun stream(url: String): String = relay?.wrap(url) ?: url

    suspend fun multiNow(): MultiState? = onUi { multi?.invoke() }
    suspend fun fullNow(): FullState? = onUi { full?.invoke() }

    /** Key presses Compose has seen in the window (counted in Main's key handler while a scenario runs). */
    @Volatile var received = 0

    fun log(msg: String) {
        val line = "${java.time.LocalTime.now().withNano(0)} $msg"
        runCatching { file.appendText(line + "\n") }
        println("[autotest] $line")
    }

    /** Called once the window exists. */
    fun start(w: java.awt.Window) {
        val s = scenario ?: return
        window = w
        runCatching { file.delete() }
        log("scenario '$s' starting")
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (s) {
                    "spike" -> spike()
                    "probes" -> probes()
                    "netdrop" -> netDrop()
                    else -> { log("unknown scenario"); failures++ }
                }
            } catch (e: Throwable) { log("ABORTED: ${e::class.simpleName}: ${e.message}"); failures++ }
            log(if (failures == 0) "RESULT PASS" else "RESULT FAIL ($failures)")
            if (System.getenv("NOVAPLAY_AUTOTEST_STAY") == null) { delay(1500); Vlc.release(); kotlin.system.exitProcess(if (failures == 0) 0 else 3) }
        }
    }

    // ---- checks -------------------------------------------------------------------------------------------------

    fun check(what: String, ok: Boolean) { log((if (ok) "PASS " else "FAIL ") + what); if (!ok) failures++ }

    /** Waits up to [ms] for [cond]; true if it came true. */
    suspend fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (onUi(cond)) return true; delay(200) }
        return onUi(cond)
    }

    private suspend fun <T> onUi(f: () -> T): T = withContext(Dispatchers.Main) { f() }

    /** The playlist has loaded its channels. */
    suspend fun waitForLibrary(): Boolean = waitFor(240_000) { App.repo.state.value.lib.channels.isNotEmpty() && !App.repo.state.value.loading }

    // ---- keys ---------------------------------------------------------------------------------------------------

    /** Presses [key] (a java.awt.event.KeyEvent.VK_ code) in NovaPlay's window, then waits [after] ms. */
    suspend fun press(key: Int, after: Long = 700, name: String = KeyEvent.getKeyText(key)) {
        val target = target() ?: run { log("no window to send keys to"); failures++; return }
        val now = System.currentTimeMillis()
        EventQueue.invokeAndWait {
            target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, key, KeyEvent.CHAR_UNDEFINED))
            target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now + 30, 0, key, KeyEvent.CHAR_UNDEFINED))
        }
        delay(150)
        log("key $name → tab ${App.openTab}${if (App.menuFocused) " (menu)" else ""} (keys received $received)")
        delay(after)
    }

    suspend fun press(key: Int, times: Int, after: Long = 500) = repeat(times) { press(key, after) }

    /** The component Compose listens to for keys: the window's focus owner, or its drawing surface. */
    private fun target(): Component? {
        val w = window ?: return null
        return w.mostRecentFocusOwner ?: deepest(w)
    }

    private fun deepest(c: Component): Component =
        if (c is Container && c.componentCount > 0) deepest(c.getComponent(0)) else c

    private val tabOrder = listOf("HOME", "LIVE", "GUIDE", "SPORTS", "MOVIES", "SERIES", "RECORDINGS", "SEARCH", "SETTINGS")

    /** Goes to [tab] through the menu with arrow keys, then Right into the page. True once it is open there. */
    suspend fun goTab(tab: String): Boolean {
        // At launch nothing has focus and the arrows have nowhere to start from; Esc (Back) puts focus on the menu.
        if (!App.menuFocused) press(KeyEvent.VK_ESCAPE, after = 800)
        repeat(4) { if (!App.menuFocused) press(KeyEvent.VK_LEFT, after = 600) }
        if (!App.menuFocused) { log("menu never took focus"); return false }
        repeat(10) {
            if (App.openTab == tab) return@repeat
            val up = tabOrder.indexOf(App.openTab).let { it >= 0 && it > tabOrder.indexOf(tab) }
            press(if (up) KeyEvent.VK_UP else KeyEvent.VK_DOWN, after = 900)
        }
        if (App.openTab != tab) { log("menu stayed on ${App.openTab}"); return false }
        press(KeyEvent.VK_RIGHT, after = 2000)
        return App.openTab == tab && !App.menuFocused
    }

    // ---- scenarios ----------------------------------------------------------------------------------------------

    /** Step 1: do keys sent from inside the app reach Compose, even with the window in the background? */
    private suspend fun spike() {
        val focused = onUi { window?.isFocused == true }
        log("window focused at start: $focused")
        check("playlist loaded", waitForLibrary())
        delay(3000)
        check("reached Live TV", goTab("LIVE"))
        // Into the channel list and play.
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        check("Enter opened the player (keys reach Compose)", waitFor(8000) { Desktop.playing != null })
        log("playing: ${onUi { Desktop.playing?.title }}")
        delay(6000)
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("Esc closed the player", waitFor(5000) { Desktop.playing == null })
        log("window focused at end: ${onUi { window?.isFocused == true }}")
    }

    /** Steps 2 and 4: the probes report the player, multi-view's screens and sound, and VLC players made and freed. */
    private suspend fun probes() {
        check("playlist loaded", waitForLibrary()); delay(3000)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        check("player opened", waitFor(8000) { Desktop.playing != null })
        check("full screen is playing", waitFor(20_000) { full?.invoke()?.playing == true })
        log("full: ${fullNow()}  vlc live=${vlcLive()} (made ${vlcCreated.get()}, freed ${vlcReleased.get()})")
        check("one VLC player while full screen", vlcLive() == 1)

        press(KeyEvent.VK_M, after = 0)
        check("multi-view opened", waitFor(6000) { multi != null })
        log("multi: ${multiNow()}")
        // It opens with the list up to pick screen 2's channel; Esc keeps the next channel.
        if (multiNow()?.picking != null) press(KeyEvent.VK_ESCAPE, after = 1500)
        check("picker closed", multiNow()?.picking == null)
        check("both screens playing", waitFor(30_000) { multi?.invoke()?.screens?.count { it.playing } == 2 })
        var m = multiNow(); log("multi: $m  vlc live=${vlcLive()}")
        fun soundOk(st: MultiState?) = st != null && st.screens.count { !it.muted } == 1 && !st.screens[st.active].muted
        for (t in 0 until 5) { m = multiNow(); log("t+${t * 2}s active=${m?.active} muted=${m?.screens?.map { it.muted }}"); if (soundOk(m)) break; delay(2000) }
        check("sound only from the selected screen (${m?.active})", soundOk(m))
        press(KeyEvent.VK_RIGHT, after = 1000)
        m = multiNow(); log("after Right: active=${m?.active} muted=${m?.screens?.map { it.muted }}")
        check("Right moved the sound to screen 1", m?.active == 1 && soundOk(m))

        val beforeExit = vlcLive()
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("multi-view closed", waitFor(6000) { multi == null })
        check("back in full screen, playing", waitFor(20_000) { full?.invoke()?.playing == true })
        delay(2000)
        log("after multi-view: full=${fullNow()} vlc live=${vlcLive()} (was $beforeExit in multi-view)")
        check("multi-view's players all freed (one left)", vlcLive() == 1)

        press(KeyEvent.VK_ESCAPE, after = 0)
        check("player closed", waitFor(5000) { Desktop.playing == null })
        delay(2000)
        log("end: vlc made ${vlcCreated.get()}, freed ${vlcReleased.get()}")
        check("every VLC player freed", vlcLive() == 0)
    }

    /** Opens a Live TV channel full screen, then multi-view with two screens (the picker closed). */
    private suspend fun intoMultiView(): Boolean {
        if (!waitForLibrary()) return false
        delay(3000)
        if (!goTab("LIVE")) return false
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        if (!waitFor(20_000) { full?.invoke()?.playing == true }) { log("full screen never played"); return false }
        press(KeyEvent.VK_M, after = 0)
        if (!waitFor(6000) { multi != null }) return false
        if (multiNow()?.picking != null) press(KeyEvent.VK_ESCAPE, after = 1500)
        return waitFor(30_000) { multi?.invoke()?.let { m -> m.picking == null && m.screens.all { it.playing } } == true }
    }

    private fun screens(m: MultiState?) = m?.screens?.joinToString(" | ") { "${it.channel.take(18)}: " +
        (it.error ?: if (it.buffering) "buffering" else if (it.playing) "playing" else "starting") } ?: "no multi-view"

    /** Step 8: the connection drops for 30 s with multi-view open, then comes back. */
    private suspend fun netDrop() {
        val r = relay ?: run { check("relay running", false); return }
        check("into multi-view, both screens playing", intoMultiView())
        log("streams through the relay: ${r.streams()} (served ${r.served})")
        check("streams go through the relay", r.served > 0)
        delay(5000)
        log("before: ${screens(multiNow())}")
        log("CUT: dropping every stream for 30 s")
        r.cutAll()
        val cutAt = System.currentTimeMillis()
        var sawTrouble = false
        while (System.currentTimeMillis() - cutAt < 30_000) {
            delay(3000); val m = multiNow()
            if (m?.screens?.any { it.error != null || it.buffering || !it.playing } == true) sawTrouble = true
            log("cut +${(System.currentTimeMillis() - cutAt) / 1000}s: ${screens(m)}  (refused ${r.refused})")
        }
        check("the screens noticed the drop", sawTrouble)
        r.restore()
        val backAt = System.currentTimeMillis()
        log("RESTORED")
        val back = waitFor(120_000) { multi?.invoke()?.screens?.all { it.playing && it.error == null && !it.buffering } == true }
        log("after ${(System.currentTimeMillis() - backAt) / 1000}s: ${screens(multiNow())}")
        check("both screens came back by themselves", back)
        delay(10_000)
        log("10 s later: ${screens(multiNow())}  vlc live=${vlcLive()}")
        check("still playing 10 s later", multiNow()?.screens?.all { it.playing && it.error == null } == true)
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("multi-view closed", waitFor(6000) { multi == null })
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("player closed", waitFor(6000) { Desktop.playing == null })
        delay(3000)
        log("end: vlc made ${vlcCreated.get()}, freed ${vlcReleased.get()}")
        check("every VLC player freed", vlcLive() == 0)
        r.close()
    }
}
