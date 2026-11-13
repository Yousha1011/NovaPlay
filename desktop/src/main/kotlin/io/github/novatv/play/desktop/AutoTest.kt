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
    /** The run's own log (the runner gives each run its own file and id, so no run can read another's result). */
    private val file = System.getenv("NOVAPLAY_AUTOTEST_LOG")?.let(::File) ?: File(System.getProperty("java.io.tmpdir"), "novaplay-autotest.log")
    private val runId: String = System.getenv("NOVAPLAY_AUTOTEST_RUN") ?: "local-${System.currentTimeMillis()}"
    private fun envInt(k: String, d: Int) = System.getenv(k)?.toIntOrNull() ?: d
    /** Stop conditions: more VLC players than this, more Java heap (MB) than this, or a run longer than this (s). */
    private val maxPlayers = envInt("NOVAPLAY_AUTOTEST_MAX_PLAYERS", 6)
    private val maxHeapMb = envInt("NOVAPLAY_AUTOTEST_MAX_HEAP_MB", 1500)
    private val timeoutS = envInt("NOVAPLAY_AUTOTEST_TIMEOUT_S", 900)
    /** Screens multi-view scenarios use (2 to 4; 2 while the framework is being verified). */
    private val mvScreens = envInt("NOVAPLAY_AUTOTEST_SCREENS", 2).coerceIn(2, 4)
    @Volatile private var ended = false
    private var window: java.awt.Window? = null
    private var failures = 0
    // ---- probes (read only; set by the player and multi-view while a scenario runs) ----
    data class ScreenState(val channel: String, val muted: Boolean, val buffering: Boolean, val error: String?, val playing: Boolean, val hd: Boolean, val detail: String = "")
    data class MultiState(val active: Int, val picking: Int?, val screens: List<ScreenState>)
    data class FullState(val channel: String, val playing: Boolean, val buffering: Boolean, val error: String?, val changedAt: Long = 0L, val detail: String = "")
    @Volatile var multi: (() -> MultiState)? = null
    /** Multi-view's own buttons (add a screen, change a screen's channel, remove one), for the driver to press. */
    class MultiActions(val add: () -> Unit, val changeNext: (Int) -> Unit, val remove: (Int) -> Unit)
    @Volatile var multiDo: MultiActions? = null
    suspend fun multiAction(name: String, f: (MultiActions) -> Unit) { log("button: $name"); onUi { multiDo?.let(f) ?: log("no multi-view") } }
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
    /** Something inside the window's content has keyboard focus (Main's content box, while a scenario runs). */
    @Volatile var focusInside = false

    /** Streams the full-screen player has started (its "PLAY" lines): first opens, channel changes and retries. */
    @Volatile var plays = 0
    /** Streams VLC reported as ended ("finished") in the full-screen player. */
    @Volatile var ends = 0
    fun log(msg: String) {
        if (msg.startsWith("PLAY idx=")) plays++
        if (msg.startsWith("VLC idx=") && msg.contains(" finished ")) ends++
        val line = "${java.time.LocalTime.now().withNano(0)} $msg"
        runCatching { file.appendText(line + "\n") }
        println("[autotest] $line")
    }

    /** Called once the window exists. */
    fun start(w: java.awt.Window) {
        val s = scenario ?: return
        window = w
        runCatching { file.delete() }
        log("BEGIN run=$runId scenario=$s pid=${ProcessHandle.current().pid()} maxPlayers=$maxPlayers maxHeapMb=$maxHeapMb timeoutS=$timeoutS screens=$mvScreens")
        // Stop conditions, checked every 2 s: too many players, too much heap, or too long.
        CoroutineScope(Dispatchers.Default).launch {
            val t0 = System.currentTimeMillis()
            while (!ended) {
                delay(2000)
                val why = when {
                    vlcLive() > maxPlayers -> "too many VLC players (${vlcLive()} > $maxPlayers)"
                    heap() > maxHeapMb -> run { System.gc(); if (heap() > maxHeapMb) "Java heap ${heap()} MB > $maxHeapMb" else null }
                    System.currentTimeMillis() - t0 > timeoutS * 1000L -> "scenario ran over ${timeoutS}s"
                    else -> null
                } ?: continue
                abort(why)
            }
        }
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (s) {
                    "spike" -> spike()
                    "probes" -> probes()
                    "netdrop" -> netDrop()
                    "multiview" -> multiViewFull()
                    "cycles" -> cycles()
                    "limits" -> limits()
                    "launch" -> launch()
                    "netfreeze" -> netFreeze()
                    "netsoak" -> netSoak()
                    "netmulti" -> netMulti()
                    "chancheck" -> chanCheck()
                    "fullscreen" -> fullScreen()
                    "netend" -> netEnd()
                    else -> { log("unknown scenario"); failures++ }
                }
            } catch (e: Throwable) { log("ABORTED: ${e::class.simpleName}: ${e.message}"); failures++ }
            if (ended) return@launch
            ended = true
            log(if (failures == 0) "RESULT PASS" else "RESULT FAIL ($failures)")
            log("END run=$runId status=${if (failures == 0) "PASS" else "FAIL"} failures=$failures vlcLive=${vlcLive()}")
            if (System.getenv("NOVAPLAY_AUTOTEST_STAY") == null) { delay(1500); Vlc.release(); kotlin.system.exitProcess(if (failures == 0) 0 else 3) }
        }
    }

    /** A stop condition was hit: the run ends now as ABORTED (never PASS), and the app quits. */
    private fun abort(why: String) {
        if (ended) return
        ended = true
        log("STOP $why")
        log("RESULT ABORTED")
        log("END run=$runId status=ABORTED failures=${failures + 1} vlcLive=${vlcLive()}")
        Runtime.getRuntime().halt(4)
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

    /** Right after launch, with no Esc or click first: do the arrow keys move focus straight away? */
    private suspend fun launch() {
        check("playlist loaded", waitForLibrary()); delay(3000)
        log("at launch: focus inside=$focusInside tab=${App.openTab} menu=${App.menuFocused}")
        val start = App.openTab
        press(KeyEvent.VK_DOWN, after = 900)
        check("first arrow key puts focus somewhere", focusInside)
        log("after Down: focus inside=$focusInside tab=${App.openTab} menu=${App.menuFocused}")
        press(KeyEvent.VK_DOWN, after = 900)
        check("arrows move through the menu from launch ($start -> ${App.openTab})", App.menuFocused && App.openTab != start)
        press(KeyEvent.VK_UP, after = 900)
        check("Up comes back", App.menuFocused)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        check("Enter plays a channel", waitFor(20_000) { full?.invoke()?.playing == true })
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("player closed", waitFor(6000) { Desktop.playing == null })
        delay(1500)
        check("focus back on the page after the player", focusInside && !App.menuFocused && App.openTab == "LIVE")
        check("reached Search", goTab("SEARCH"))
        log("search: focus inside=$focusInside")
        press(KeyEvent.VK_LEFT, after = 900)
        check("Left from Search reaches the menu", App.menuFocused)
        delay(1500)
        check("every VLC player freed", vlcLive() == 0)
    }

    /** Investigation: second by second through a 30 s cut, what each screen shows and what its players are doing. */
    private suspend fun netFreeze() {
        val r = relay ?: run { check("relay running", false); return }
        check("into multi-view, both screens playing", intoMultiView())
        delay(15_000)
        fun row(m: MultiState?) = m?.screens?.joinToString(" || ") { "${it.channel.take(14)} shows=" +
            (it.error ?: if (it.buffering) "spinner" else "picture") + " playing=${it.playing} ${it.detail}" } ?: "no multi-view"
        log("before: ${row(multiNow())}")
        r.cutAll(); log("CUT")
        val t0 = System.currentTimeMillis()
        var frozenSilent = 0
        while (System.currentTimeMillis() - t0 < 30_000) {
            delay(1000); val m = multiNow()
            m?.screens?.forEach { if (it.error == null && !it.buffering && Regex("still=([0-9]+)s").find(it.detail)?.groupValues?.get(1)?.toInt()?.let { a -> a >= 5 } == true) frozenSilent++ }
            log("cut +${(System.currentTimeMillis() - t0) / 1000}s: ${row(m)}")
        }
        r.restore(); log("RESTORED")
        val t1 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t1 < 25_000) { delay(1000); log("back +${(System.currentTimeMillis() - t1) / 1000}s: ${row(multiNow())}") }
        log("seconds a screen showed a picture unchanged 5 s+ with no spinner or message: $frozenSilent")
        check("no frozen picture without a status during the cut", frozenSilent == 0)
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { multi == null }
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { Desktop.playing == null }
        r.close()
    }

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
        if (!waitFor(45_000) { full?.invoke()?.playing == true }) { log("full screen never played"); return false }
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

    private fun soundOk(st: MultiState?) = st != null && st.screens.count { !it.muted } == 1 && !st.screens[st.active].muted
    private suspend fun allPlaying(n: Int, ms: Long = 40_000) =
        waitFor(ms) { multi?.invoke()?.let { m -> m.screens.size == n && m.screens.all { it.playing && it.error == null } } == true }
    private fun heap() = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() } / (1024 * 1024))

    /** Test 3 steps 1-7: four screens, sound, channels per screen, removing, back to full screen and Live TV. */
    private suspend fun multiViewFull() {
        check("1. multi-view from full screen, 2 screens playing", intoMultiView())
        multiAction("Add a screen") { it.add() }
        check("2. third screen added and playing", allPlaying(3))
        multiAction("Add a screen") { it.add() }
        check("2. fourth screen added and playing", allPlaying(4))
        multiAction("Add a screen (at the limit)") { it.add() }
        delay(1500)
        check("2. no fifth screen (limit 4)", multiNow()?.screens?.size == 4)
        log("4 screens: ${screens(multiNow())}  vlc live=${vlcLive()}")
        val at4 = vlcLive()
        for (k in 1..4) {
            press(KeyEvent.VK_RIGHT, after = 800)
            val m = multiNow()
            check("3. sound only from screen ${m?.active}", soundOk(m))
        }
        val before = multiNow()!!.screens.map { it.channel }
        multiAction("Change channel, screen 1") { it.changeNext(0) }
        multiAction("Change channel, screen 3") { it.changeNext(2) }
        val restarted = waitFor(4000) { multi?.invoke()?.screens?.let { it[0].channel != before[0] && it[2].channel != before[2] } == true }
        check("4. screens 1 and 3 switched channel", restarted)
        delay(1500)
        check("4. both changed screens playing again", allPlaying(4))
        val after = multiNow()!!.screens.map { it.channel }
        log("channels before $before")
        log("channels after  $after")
        check("4. screens 1 and 3 changed, 2 and 4 kept theirs", after[0] != before[0] && after[2] != before[2] && after[1] == before[1] && after[3] == before[3])
        multiAction("Remove screen 4") { it.remove(3) }
        multiAction("Remove screen 2") { it.remove(1) }
        check("5. two screens left, playing", allPlaying(2))
        delay(3000)
        log("after removing: ${screens(multiNow())}  vlc live=${vlcLive()} (was $at4 with 4)")
        // Each screen has its player and, for a moment after a stream drops, a standby: 1 full screen + up to 2 per screen.
        check("5. removed screens' players freed (at most 1 + 2 per screen left)", vlcLive() <= 1 + 2 * 2)
        check("5. sound still from one screen", soundOk(multiNow()))
        val chosen = multiNow()!!.let { it.screens[it.active].channel }
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("6. multi-view closed", waitFor(6000) { multi == null })
        check("6. full screen playing", waitFor(20_000) { full?.invoke()?.playing == true })
        check("6. full screen carries on with the selected screen ($chosen)", fullNow()?.channel == chosen)
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("7. player closed", waitFor(6000) { Desktop.playing == null })
        delay(1500)
        check("7. back on Live TV", App.openTab == "LIVE" && !App.menuFocused)
        press(KeyEvent.VK_DOWN, after = 800); press(KeyEvent.VK_ENTER, after = 0)
        check("7. arrows and Enter work: next channel plays", waitFor(20_000) { full?.invoke()?.playing == true })
        log("7. now playing ${fullNow()?.channel}")
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("7. player closed", waitFor(6000) { Desktop.playing == null })
        delay(3000)
        log("end: vlc made ${vlcCreated.get()}, freed ${vlcReleased.get()}")
        check("every VLC player freed", vlcLive() == 0)
    }

    /** Test 3 step 10: into multi-view and out again, ten times, then a quiet minute. */
    private suspend fun cycles() {
        check("playlist loaded", waitForLibrary()); delay(3000)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        check("full screen playing", waitFor(20_000) { full?.invoke()?.playing == true })
        delay(5000); System.gc(); delay(1000)
        log("baseline: vlc live=${vlcLive()} heap=${heap()} MB threads=${Thread.activeCount()}")
        val live = mutableListOf<Int>()
        for (c in 1..10) {
            press(KeyEvent.VK_M, after = 0)
            if (!waitFor(6000) { multi != null }) { check("cycle $c: multi-view opened", false); break }
            if (multiNow()?.picking != null) press(KeyEvent.VK_ESCAPE, after = 1000)
            val ok = allPlaying(2, 30_000)
            log("cycle $c: in multi-view, both playing=$ok vlc live=${vlcLive()}")
            delay(5000)
            press(KeyEvent.VK_ESCAPE, after = 0)
            if (!waitFor(6000) { multi == null }) { check("cycle $c: multi-view closed", false); break }
            waitFor(20_000) { full?.invoke()?.playing == true }
            delay(2000)
            live += vlcLive()
            log("cycle $c: out, vlc live=${vlcLive()} heap=${heap()} MB threads=${Thread.activeCount()}")
        }
        check("ten cycles completed", live.size == 10)
        check("one VLC player left after every cycle", live.all { it == 1 })
        log("quiet minute")
        delay(60_000); System.gc(); delay(1000)
        log("after a quiet minute: vlc live=${vlcLive()} heap=${heap()} MB threads=${Thread.activeCount()}")
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("player closed", waitFor(6000) { Desktop.playing == null })
        delay(3000)
        log("end: vlc made ${vlcCreated.get()}, freed ${vlcReleased.get()} threads=${Thread.activeCount()}")
        check("every VLC player freed", vlcLive() == 0)
    }

    /** Counts multi-view's "stream ended / cut off" events over [ms] (the multi-view log). */
    private suspend fun drops(ms: Long): Int {
        val f = File(System.getProperty("java.io.tmpdir"), "novaplay-multiview.log")
        val start = runCatching { f.readLines().size }.getOrDefault(0)
        delay(ms)
        return runCatching { f.readLines().drop(start).count { "stream ended" in it || "cut off" in it } }.getOrDefault(-1)
    }

    /** Does the playlist close streams when more play at once? Drops per minute with 2, 3 and 4 screens. */
    private suspend fun limits() {
        if (System.getenv("NOVAPLAY_LOG") == null) log("note: NOVAPLAY_LOG not set, drops can't be counted")
        check("into multi-view, 2 screens", intoMultiView())
        val per = mutableMapOf<Int, Int>()
        per[2] = drops(60_000); log("2 screens: ${per[2]} drops in 60 s")
        multiAction("Add a screen") { it.add() }; allPlaying(3)
        per[3] = drops(60_000); log("3 screens: ${per[3]} drops in 60 s")
        multiAction("Add a screen") { it.add() }; allPlaying(4)
        per[4] = drops(60_000); log("4 screens: ${per[4]} drops in 60 s")
        log("drops per minute by screens: $per (the full-screen player stays open behind: one more stream each)")
        check("all four still playing at the end", allPlaying(4, 20_000))
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { multi == null }
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { Desktop.playing == null }
        delay(3000)
        check("every VLC player freed", vlcLive() == 0)
    }

    // ---- Windows Test 4: long run ---------------------------------------------------------------------------------

    private val pid = ProcessHandle.current().pid()
    private val samples = mutableListOf<String>()

    /** Private memory (MB) and handle count of NovaPlay's own process, read from Windows; (-1, -1) if unavailable. */
    private fun winStats(): Pair<Long, Long> = runCatching {
        val cmd = "\$p=Get-Process -Id $pid; Write-Output ([string][long](\$p.PrivateMemorySize64/1MB) + ' ' + [string]\$p.HandleCount)"
        val p = ProcessBuilder("powershell", "-NoProfile", "-Command", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim().lines().last().trim().split(' ')
        p.waitFor(); out[0].toLong() to out[1].toLong()
    }.getOrDefault(-1L to -1L)

    private data class Res(val priv: Long, val handles: Long, val heap: Long, val threads: Int, val vlc: Int, val streams: Int)
    private suspend fun sample(tag: String): Res {
        System.gc(); delay(500)
        val (priv, h) = withContext(Dispatchers.IO) { winStats() }
        val r = Res(priv, h, heap(), Thread.activeCount(), vlcLive(), relay?.streams() ?: -1)
        val line = "SAMPLE $tag: private=${r.priv} MB handles=${r.handles} heap=${r.heap} MB threads=${r.threads} vlc=${r.vlc} streams=${r.streams}"
        samples += line; log(line); return r
    }

    // ---- what a picture is doing ----------------------------------------------------------------------------------

    /**
     * What a screen shows, judged from two looks a moment apart, using VLC's state, its clock, the frames it handed over
     * (counted from when the channel was opened), whether the stream has a video track, the picture's brightness and
     * when it last changed.
     *
     * Not failures: MOVING, STATIC (a still picture while frames keep coming), BLACK (frames keep coming but they are
     * black: the filler a dead feed sends), STATUS (an error message on screen), BUFFERING (the spinner), STARTING
     * (within [GRACE_MS] of opening, before VLC plays and frames flow).
     * Failures, only once the start-up time is over and nothing on screen says why: STOPPED (VLC isn't playing),
     * NO_VIDEO (VLC plays a stream with no video track), NO_FRAMES (a video track, but no frame since opening),
     * FROZEN (frames stopped arriving, or the clock and the picture both stood still).
     */
    enum class Health { MOVING, STATIC, BLACK, STATUS, BUFFERING, STARTING, STOPPED, NO_VIDEO, NO_FRAMES, FROZEN }
    private val failing = setOf(Health.STOPPED, Health.NO_VIDEO, Health.NO_FRAMES, Health.FROZEN)
    private val alive = setOf(Health.MOVING, Health.STATIC, Health.BLACK)
    /** Time a channel gets after opening before missing frames count (VLC's first frame can take ~10 s on a slow feed). */
    private const val GRACE_MS = 12_000L

    private data class Look(val shown: Long, val time: Long, val lum: Int, val still: Long, val vlc: String,
                            val err: Boolean, val buf: Boolean, val video: Boolean, val idx: String?)
    private fun field(d: String, k: String): String? = Regex("(?:^| )$k=([^ ]*)").find(d)?.groupValues?.get(1)
    private fun look(detail: String, err: Boolean, buf: Boolean, still: Long? = null, base: Long = 0) = Look(
        (field(detail, "shown")?.toLongOrNull() ?: -1).let { if (it < 0) it else it - base }, field(detail, "time")?.toLongOrNull() ?: -1,
        field(detail, "lum")?.toIntOrNull() ?: -1, still ?: field(detail, "still")?.removeSuffix("s")?.toLongOrNull() ?: -1,
        field(detail, "vlc") ?: "?", err, buf, !Regex("(?:^| )video=(?: |$)").containsMatchIn(detail) && field(detail, "video") != "null",
        field(detail, "idx"))

    /** [age]: ms since this channel was opened on this player. */
    private fun health(a: Look, b: Look, age: Long): Health {
        val flowing = b.vlc == "PLAYING" && b.shown > 0 && b.shown != a.shown
        return when {
            b.err -> Health.STATUS
            age < GRACE_MS && !flowing -> if (b.buf) Health.BUFFERING else Health.STARTING
            b.buf -> Health.BUFFERING
            b.vlc != "PLAYING" -> Health.STOPPED
            b.shown <= 0 -> if (!b.video) Health.NO_VIDEO else Health.NO_FRAMES
            b.shown == a.shown -> Health.FROZEN                        // no frame handed over between the looks
            // Frames, but the clock stood still and the picture hasn't changed for 3 s (the clock reads 0 for a while
            // after a stream opens: not counted).
            b.time > 0 && b.time <= a.time && b.still !in 0..2 -> Health.FROZEN
            b.lum in 0..2 -> Health.BLACK
            b.still in 0..4 -> Health.MOVING
            else -> Health.STATIC
        }
    }

    /** When each multi-view screen's current player started (a new player starts its frame count again). */
    private val mvStart = LongArray(8)
    private val mvLast = LongArray(8) { -1 }

    /** Each multi-view screen's [Health] (a screen whose player was just replaced is judged on the new one alone). */
    private suspend fun multiHealth(gap: Long = 1500): List<Health> {
        val a = multiNow()?.screens ?: run { delay(gap); return emptyList() }; delay(gap)
        val b = multiNow()?.screens ?: return emptyList()
        if (a.size != b.size) return multiHealth(gap)
        val now = System.currentTimeMillis()
        return b.indices.map { i ->
            val lb = look(b[i].detail, b[i].error != null, b[i].buffering)
            var la = look(a[i].detail, false, false)
            if (i < 8) {
                if (mvStart[i] == 0L || lb.shown < mvLast[i]) mvStart[i] = now
                mvLast[i] = lb.shown
            }
            if (la.shown > lb.shown) la = la.copy(shown = 0, time = -1)
            health(la, lb, if (i < 8) now - mvStart[i] else Long.MAX_VALUE)
        }
    }

    private fun lookFull(f: FullState, base: Long) = look(f.detail, f.error != null, f.buffering,
        if (f.changedAt == 0L) -1 else (System.currentTimeMillis() - f.changedAt) / 1000, base)

    /** The full-screen player's [Health] [age] ms after the channel opened, frames counted from [base]; and its channel. */
    private suspend fun fullHealth(base: Long, openedAt: Long, gap: Long = 1500): Pair<Health, String>? {
        val a = fullNow() ?: return null; delay(gap)
        val b = fullNow() ?: return null
        return health(lookFull(a, base), lookFull(b, base), System.currentTimeMillis() - openedAt) to b.channel
    }

    /** The full-screen player's position in its channel list (changes on every channel change, even between equal names). */
    private suspend fun fullIdx(): String? = fullNow()?.let { field(it.detail, "idx") }

    /**
     * After a channel change (from list position [fromIdx]) or opening the player (null): waits for the change to
     * happen, then up to [ms] for the channel to show frames, an error or a spinner, and judges it. A failing verdict
     * is only given after the start-up time, and confirmed by two more looks. Null if the player never got there.
     */
    private suspend fun settleFull(fromIdx: String?, ms: Long = 25_000): Pair<Health, String>? {
        val t0 = System.currentTimeMillis()
        // The change itself: the player's list position moves (or, opening, a player exists).
        while (System.currentTimeMillis() - t0 < 6000) {
            val f = fullNow()
            if (f != null && (fromIdx == null || field(f.detail, "idx") != fromIdx)) break
            delay(100)
        }
        val openedAt = System.currentTimeMillis()
        val base = if (fromIdx == null) 0L else fullNow()?.let { field(it.detail, "shown")?.toLongOrNull() } ?: 0L
        var last: Pair<Health, String>? = null
        while (System.currentTimeMillis() - openedAt < ms) {
            val h = fullHealth(base, openedAt) ?: continue
            last = h
            if (h.first in alive || h.first == Health.STATUS) return h
        }
        // Failing: two more looks, so one look in which nothing moved yet isn't the verdict.
        if (last != null && last.first in failing) repeat(2) { fullHealth(base, openedAt, 1000)?.let { if (it.first !in failing) return it; last = it } }
        return last
    }

    /**
     * Frozen for real: a screen judged FROZEN or NO_FRAMES in [need] looks in a row (about a second apart), so a single
     * look in which VLC's clock or frame count didn't move yet isn't counted. Returns the screens that have now been
     * failing long enough, once per look while they stay so.
     */
    private class Streaks(val need: Int = 3) {
        private val run = IntArray(8)
        fun feed(hs: List<Health>): List<Int> = hs.indices.filter { i ->
            if (i >= run.size) return@filter false
            run[i] = if (hs[i] in failing) run[i] + 1 else 0
            run[i] >= need
        }
        fun reset() = run.fill(0)
    }

    private fun Map<Health, Int>.text() = entries.filter { it.value > 0 }.joinToString { "${it.key}=${it.value}" }
    private fun MutableMap<Health, Int>.add(h: Health) { this[h] = (this[h] ?: 0) + 1 }

    // ---- the long run -----------------------------------------------------------------------------------------------

    private suspend fun netSoak() {
        val r = relay ?: run { check("relay running", false); return }
        val t0 = System.currentTimeMillis()
        check("playlist loaded", waitForLibrary()); delay(3000)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        check("full screen showing a picture", settleFull(null, 20_000)?.first in alive)
        delay(5000)
        val base = sample("baseline (full screen, warm)")

        zapRun(200, "1. ")

        openRun(50, "2. ")

        // 3, 4, 6. 30 multi-view cycles with 2, 3 and 4 screens; sound and keys each time.
        var mvOk = 0; var soundGood = 0; var extra = 0; val mv = mutableMapOf<Health, Int>(); val badMv = mutableListOf<String>()
        for (c in 1..30) {
            val n = 2 + (c - 1) % (mvScreens - 1)
            press(KeyEvent.VK_M, after = 0)
            if (!waitFor(6000) { multi != null }) { log("mv $c: didn't open"); continue }
            if (multiNow()?.picking != null) press(KeyEvent.VK_ESCAPE, after = 800)
            repeat(n - 2) { multiAction("Add a screen") { it.add() }; delay(300) }
            val ok = allPlaying(n, 40_000)
            delay(3000)
            val st3 = Streaks(); var hs = multiHealth(1000); var bad = st3.feed(hs)
            repeat(2) { hs = multiHealth(1000); bad = st3.feed(hs) }
            hs.forEach { mv.add(it) }
            if (bad.isNotEmpty()) badMv += "mv $c: frozen 3 s+ on $bad $hs"
            var snd = true
            repeat(n) { press(KeyEvent.VK_RIGHT, after = 500); if (!soundOk(multiNow())) snd = false }
            if (snd) soundGood++
            val streams = r.streams()
            if (streams > 2 * n + 1) { extra++; log("mv $c: $streams streams open for $n screens") }
            if (ok) mvOk++ else log("mv $c: not all playing: ${screens(multiNow())}")
            log("mv $c: $n screens ok=$ok sound=$snd health=$hs streams=$streams vlc=${vlcLive()}")
            press(KeyEvent.VK_ESCAPE, after = 0)
            if (!waitFor(6000) { multi == null }) { log("mv $c: didn't close"); break }
            settleFull(null, 20_000)
            if (c % 10 == 0) { delay(2000); sample("after $c multi-view cycles") }
        }
        check("3/4. 30 multi-view cycles (2, 3, 4 screens) all playing ($mvOk/30)", mvOk >= 29)
        check("6. sound only from the selected screen through every move ($soundGood/30)", soundGood == 30)
        check("10. no frozen screen or screen without frames in multi-view (${mv.text()}) $badMv", badMv.isEmpty())
        check("10. no extra connections: at most 2 per screen + full screen ($extra cycles over)", extra == 0)
        delay(2000)
        check("3. back to one VLC player after multi-view", vlcLive() == 1)

        // 5. Repeated drops with 3, then 4 screens.
        press(KeyEvent.VK_M, after = 0); waitFor(6000) { multi != null }
        if (multiNow()?.picking != null) press(KeyEvent.VK_ESCAPE, after = 800)
        val dropScreens = minOf(3, mvScreens)
        repeat(dropScreens - 2) { multiAction("Add a screen") { it.add() } }
        check("5. $dropScreens screens playing", allPlaying(dropScreens))
        var recovered = 0; var silent = 0; val times = mutableListOf<Long>(); val streaks = Streaks()
        for (d in 1..6) {
            if (d == 4 && mvScreens > dropScreens) { multiAction("Add a screen") { it.add() }; allPlaying(4) }
            val secs = if (d % 2 == 0) 30_000L else 12_000L
            r.cutAll(); val cutAt = System.currentTimeMillis(); streaks.reset()
            while (System.currentTimeMillis() - cutAt < secs) {
                val hs = multiHealth(1000); val f = streaks.feed(hs)
                if (f.isNotEmpty()) { silent += f.size; log("drop $d +${(System.currentTimeMillis() - cutAt) / 1000}s: frozen 3 s+ on screens $f: $hs | ${mrow()}") }
            }
            r.restore(); val back = System.currentTimeMillis()
            var hs: List<Health> = emptyList()
            val ok = waitFor(90_000) { multi?.invoke()?.screens?.all { it.playing && it.error == null && !it.buffering } == true }
            val took = (System.currentTimeMillis() - back) / 1000
            if (ok) { delay(2000); hs = multiHealth() }
            if (ok && hs.isNotEmpty() && hs.all { it in alive }) { recovered++; times += took }
            log("drop $d (${secs / 1000} s, ${multiNow()?.screens?.size} screens): back=$ok in ${took}s, health=$hs streams=${r.streams()}")
            delay(15_000)
        }
        check("5. every drop recovered by itself, every screen showing frames ($recovered/6, seconds $times)", recovered == 6)
        check("10. no screen frozen 3 s+ without a status during drops ($silent screen-seconds)", silent == 0)
        check("6. sound still from one screen after the drops", soundOk(multiNow()))
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { multi == null }
        check("6. full screen showing a picture after multi-view", settleFull(null, 20_000)?.first in alive)
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("player closed", waitFor(6000) { Desktop.playing == null })
        delay(1000)
        check("6. focus on the Live TV list", focusInside && App.openTab == "LIVE" && !App.menuFocused)
        check("6. navigation: reached Search", goTab("SEARCH"))
        check("6. navigation: back to Live TV", goTab("LIVE"))

        // 8, 9. Cool down with nothing playing. Memory is reported, not judged here (looked at separately).
        delay(5000)
        val idle = sample("idle, just after")
        log("cooldown 120 s"); delay(120_000)
        val cool = sample("idle, after cooldown")
        check("9. every VLC player freed (made ${vlcCreated.get()}, freed ${vlcReleased.get()})", vlcLive() == 0)
        check("9. no stream left open through the relay (${r.streams()})", r.streams() == 0)
        log("growth from warm baseline to cooldown: private ${cool.priv - base.priv} MB, handles ${cool.handles - base.handles}, heap ${cool.heap - base.heap} MB, threads ${cool.threads - base.threads}")
        check("10. no handle growth (cooldown ${cool.handles} vs baseline ${base.handles}, under +300)", cool.handles in 0..(base.handles + 300))
        check("10. no thread growth (cooldown ${cool.threads} vs baseline ${base.threads})", cool.threads <= base.threads + 10)
        check("8. memory settled during cooldown (private ${idle.priv} -> ${cool.priv} MB)", cool.priv <= idle.priv + 30)
        log("crash file written during the run: ${File(System.getenv("APPDATA") ?: "", "NovaPlay/cache/last-crash-full.txt").let { it.exists() && it.lastModified() > t0 }}")
        log("all samples:"); samples.forEach { log("  $it") }
        log("total ${(System.currentTimeMillis() - t0) / 60000} min")
        r.close()
    }

    // ---- full screen: channel changes and opening the player -----------------------------------------------------

    /** [n] channel changes in full screen, each judged after it settles; every one logged. */
    private suspend fun zapRun(n: Int, tag: String) {
        val zaps = mutableMapOf<Health, Int>(); val bad = mutableListOf<String>()
        for (i in 1..n) {
            val from = fullIdx()
            press(if (i % 4 == 0) KeyEvent.VK_UP else KeyEvent.VK_DOWN, after = 0)
            val h = settleFull(from)
            val kind = h?.first ?: Health.NO_FRAMES
            zaps.add(kind)
            val line = "zap $i ${h?.second ?: "no player"}: $kind | ${fullNow()?.detail}"
            if (kind in failing || h == null) { bad += line; log("FAILING $line") } else log(line)
            if (i % 50 == 0) { log("zaps $i: ${zaps.text()}"); sample("after $i channel changes") }
        }
        log("zap results: ${zaps.text()}")
        check("${tag}$n channel changes: none stopped, frozen or without frames (${zaps.text()})", bad.isEmpty())
        check("${tag}one VLC player in full screen", vlcLive() == 1)
    }

    /** [n] times: close the player, back on the Live TV list, open a channel again; each opening judged. */
    private suspend fun openRun(n: Int, tag: String) {
        val opens = mutableMapOf<Health, Int>(); var focusOk = 0; val bad = mutableListOf<String>()
        for (i in 1..n) {
            press(KeyEvent.VK_ESCAPE, after = 0)
            if (!waitFor(6000) { Desktop.playing == null }) { log("open $i: player didn't close"); break }
            delay(700)
            if (focusInside && !App.menuFocused && App.openTab == "LIVE") focusOk++ else log("open $i: focus inside=$focusInside tab=${App.openTab} menu=${App.menuFocused}")
            if (i % 2 == 0) press(KeyEvent.VK_DOWN, after = 400)
            press(KeyEvent.VK_ENTER, after = 0)
            val h = settleFull(null)
            val kind = h?.first ?: Health.NO_FRAMES
            opens.add(kind)
            val line = "open $i ${h?.second ?: "no player"}: $kind | ${fullNow()?.detail}"
            if (kind in failing || h == null) { bad += line; log("FAILING $line") } else log(line)
            if (i % 10 == 0) sample("after $i full-screen opens")
        }
        log("open results: ${opens.text()}")
        check("${tag}$n full-screen opens: none stopped, frozen or without frames (${opens.text()})", bad.isEmpty() && opens.values.sum() == n)
        check("${tag}focus back on the Live TV list after every close ($focusOk/$n)", focusOk == n)
        check("${tag}one VLC player", vlcLive() == 1)
    }

    /** Short full-screen check: 20 channel changes, 10 opens, every player freed. */
    private suspend fun fullScreen() {
        check("playlist loaded", waitForLibrary()); delay(3000)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_DOWN, 2); press(KeyEvent.VK_ENTER, after = 0)
        val first = settleFull(null)
        log("first channel: $first | ${fullNow()?.detail}")
        check("full screen opened (${first?.first})", first != null && first.first !in failing)
        zapRun(20, "")
        openRun(10, "")
        press(KeyEvent.VK_ESCAPE, after = 0)
        check("player closed", waitFor(6000) { Desktop.playing == null })
        delay(3000)
        check("every VLC player freed (made ${vlcCreated.get()}, freed ${vlcReleased.get()})", vlcLive() == 0)
    }

    // ---- a live stream ending by itself ---------------------------------------------------------------------------

    /**
     * Full screen through the relay: the server ends the stream (once: one quiet retry that plays; every time: one
     * retry, then the error with Try again); a channel change and closing the player start no retry.
     */
    private suspend fun netEnd() {
        val r = relay ?: run { check("relay running", false); return }
        check("playlist loaded", waitForLibrary()); delay(3000)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_ENTER, after = 0)
        val first = settleFull(null)
        log("opened: $first | ${fullNow()?.detail} | relay streams ${r.streams()}")
        check("full screen playing through the relay (${first?.first})", first?.first in alive && r.served > 0)
        delay(5000)

        // A. The stream ends once: one quiet retry, playing again, no message.
        var p0 = plays
        log("A: ending the stream once"); r.endAllNow()
        val retried = waitFor(60_000) { plays > p0 }
        val a = settleFull(null, 20_000)
        delay(15_000)
        val f = fullNow()
        log("A: plays +${plays - p0}, then $a | error=${f?.error} | ${f?.detail}")
        check("A. ended once: one retry (${plays - p0} new plays)", retried && plays - p0 == 1)
        check("A. ended once: playing again without a message (${a?.first}, error=${f?.error})", a?.first in alive && f?.error == null)

        // B. Later, on the same channel (it played again after A), every connection ends after 1.5 s: expected one
        // retry, then the error and Try again; nothing more after that.
        r.endAfterMs = 1500
        p0 = plays
        log("B: every stream now ends after 1.5 s"); r.endAllNow()
        val shown = waitFor(40_000) { full?.invoke()?.error != null }
        val err = fullNow()?.error
        val afterError = plays - p0
        delay(20_000)
        log("B: error=\"$err\" plays +$afterError at the message, +${plays - p0} 20 s later | ${fullNow()?.detail}")
        check("B. ended again: error shown (\"$err\")", shown && err == "This stream could not be played")
        check("B. exactly one retry before the message ($afterError)", afterError == 1)
        check("B. no further retries while the message shows (${plays - p0})", plays - p0 == 1)
        check("B. message still on screen", fullNow()?.error != null)

        // Try again (Enter on the message, as the Try again button): a fresh start that plays.
        r.endAfterMs = -1
        p0 = plays
        press(KeyEvent.VK_ENTER, after = 0)
        val again = settleFull(null, 25_000)
        log("Try again: plays +${plays - p0}, $again | error=${fullNow()?.error}")
        check("B. Try again opens the stream again and plays (${again?.first})", plays - p0 == 1 && again?.first in alive && fullNow()?.error == null)
        delay(5000)

        // B2. From a fresh start (just after Try again), every connection ends: one retry, then the message, then nothing.
        r.endAfterMs = 1500
        p0 = plays
        log("B2: every stream now ends after 1.5 s"); r.endAllNow()
        val shown2 = waitFor(60_000) { full?.invoke()?.error != null }
        val err2 = fullNow()?.error
        val atMsg = plays - p0
        delay(20_000)
        log("B2: error=\"$err2\" plays +$atMsg at the message, +${plays - p0} 20 s later | ${fullNow()?.detail}")
        check("B2. ending every time: error shown (\"$err2\")", shown2 && err2 == "This stream could not be played")
        check("B2. exactly one retry before the message ($atMsg)", atMsg == 1)
        check("B2. no further retries while the message shows (${plays - p0})", plays - p0 == 1 && fullNow()?.error != null)
        r.endAfterMs = -1
        p0 = plays
        press(KeyEvent.VK_ENTER, after = 0)
        val again2 = settleFull(null, 25_000)
        log("B2 Try again: plays +${plays - p0}, $again2 | error=${fullNow()?.error}")
        check("B2. Try again plays again (${again2?.first})", plays - p0 == 1 && again2?.first in alive && fullNow()?.error == null)

        // C. Intentional stops: a channel change starts exactly one stream; closing the player starts none.
        val from = fullIdx(); p0 = plays; val e0 = ends
        press(KeyEvent.VK_DOWN, after = 0)
        val z = settleFull(from)
        delay(10_000)
        // The old stream's stop must start nothing; only the new stream ending by itself may bring its one retry.
        val ended = ends - e0
        log("C: channel change: plays +${plays - p0}, new stream ended by itself $ended times, $z")
        check("C. channel change: one stream, plus at most one retry only if the new stream ended (${plays - p0} plays, $ended ends)",
            plays - p0 == 1 + minOf(ended, 1))
        p0 = plays
        press(KeyEvent.VK_ESCAPE, after = 0)
        val closed = waitFor(6000) { Desktop.playing == null }
        delay(8000)
        log("C: closed=$closed plays +${plays - p0} vlc=${vlcLive()} relay streams ${r.streams()}")
        check("C. closing the player: no retry (${plays - p0})", closed && plays == p0)
        check("every VLC player freed (made ${vlcCreated.get()}, freed ${vlcReleased.get()})", vlcLive() == 0)
        check("no stream left open through the relay (${r.streams()})", r.streams() == 0)
        r.close()
    }

    // ---- focused checks ---------------------------------------------------------------------------------------------

    private suspend fun mrow(): String = multiNow()?.screens?.joinToString(" || ") { "${it.channel.takeLast(6)} ${it.detail}" } ?: "no multi-view"

    /** Multi-view, [mvScreens] screens: six drops (12 s and 30 s); every second each screen is judged and logged. */
    private suspend fun netMulti() {
        val r = relay ?: run { check("relay running", false); return }
        // Without multi-view the rest has nothing to watch (and its loops would spin with nothing to wait on).
        if (!intoMultiView()) { check("into multi-view", false); r.close(); return }
        check("into multi-view", true)
        repeat(mvScreens - 2) { multiAction("Add a screen") { it.add() } }
        check("$mvScreens screens playing", allPlaying(mvScreens))
        val start = multiHealth(); log("start: $start | ${mrow()}")
        check("every screen showing frames at the start ($start)", start.isNotEmpty() && start.all { it in alive })
        var silent = 0; var recovered = 0; val times = mutableListOf<Long>(); val seen = mutableMapOf<Health, Int>(); val streaks = Streaks()
        for (d in 1..6) {
            val secs = if (d % 2 == 0) 30 else 12
            r.cutAll(); log("CUT $d for $secs s"); val cutAt = System.currentTimeMillis()
            while (System.currentTimeMillis() - cutAt < secs * 1000L) {
                val hs = multiHealth(1000); hs.forEach { seen.add(it) }
                streaks.feed(hs).let { f -> if (f.isNotEmpty()) { silent += f.size; log("FROZEN 3 s+ on screens $f | ${mrow()}") } }
                log("cut$d +${(System.currentTimeMillis() - cutAt) / 1000}s: $hs")
            }
            r.restore(); log("RESTORE $d"); val back = System.currentTimeMillis()
            var took = -1L
            while (System.currentTimeMillis() - back < 60_000) {
                val hs = multiHealth(1000)
                streaks.feed(hs).let { f -> if (f.isNotEmpty()) { silent += f.size; log("FROZEN 3 s+ on screens $f | ${mrow()}") } }
                val st = multiNow()
                if (took < 0 && hs.isNotEmpty() && hs.all { it in alive } && st?.screens?.all { it.playing && it.error == null } == true) took = (System.currentTimeMillis() - back) / 1000
                log("back$d +${(System.currentTimeMillis() - back) / 1000}s: $hs")
                if (took >= 0 && System.currentTimeMillis() - back > 20_000) break
            }
            if (took >= 0) { recovered++; times += took }
            log("drop $d: recovered=${took >= 0} in ${took}s | ${mrow()}")
        }
        log("during cuts: ${seen.text()}")
        check("every drop recovered with every screen showing frames ($recovered/6, seconds $times)", recovered == 6)
        check("no screen frozen 3 s+ or without frames, without a status ($silent screen-seconds)", silent == 0)
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { multi == null }
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { Desktop.playing == null }
        delay(3000)
        check("every VLC player freed", vlcLive() == 0)
        r.close()
    }

    /** Full screen, channel by channel through the start of the list: each judged after it settles, then 8 s later. */
    private suspend fun chanCheck() {
        check("playlist loaded", waitForLibrary()); delay(3000)
        check("reached Live TV", goTab("LIVE"))
        press(KeyEvent.VK_RIGHT, after = 1000); press(KeyEvent.VK_ENTER, after = 0)
        val summary = mutableListOf<String>(); val kinds = mutableMapOf<Health, Int>()
        for (n in 1..12) {
            val from = if (n == 1) null else fullIdx()
            val at = System.currentTimeMillis()
            val h = settleFull(from, 30_000)
            val kind = h?.first ?: Health.NO_FRAMES
            val took = (System.currentTimeMillis() - at) / 1000
            val d1 = fullNow()?.detail
            // Held for 8 s more: a picture that stops later counts too.
            var later = kind
            if (kind in alive) { delay(8000); later = fullHealth(0, 0)?.first ?: Health.NO_FRAMES }
            kinds.add(later)
            val line = "$n ${h?.second ?: "no player"}: $kind after ${took}s, then $later | $d1 | later: ${fullNow()?.detail}"
            summary += line; log("VERDICT $line")
            press(KeyEvent.VK_DOWN, after = 0)
        }
        log("SUMMARY ${kinds.text()}"); summary.forEach { log("  $it") }
        check("every channel shows frames (moving, still or black), a spinner or an error message (${kinds.text()})", kinds.keys.none { it in failing })
        press(KeyEvent.VK_ESCAPE, after = 0); waitFor(6000) { Desktop.playing == null }
        delay(3000)
        check("every VLC player freed", vlcLive() == 0)
    }
}
