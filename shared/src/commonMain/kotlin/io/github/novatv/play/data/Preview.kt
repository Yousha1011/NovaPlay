package io.github.novatv.play.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What a preview player reports back. */
sealed interface PreviewEvent {
    /** The first picture is ready. */
    data object Ready : PreviewEvent
    data class Error(val message: String) : PreviewEvent
}

/**
 * A small, muted player for previews, provided by platforms that can afford a second decoder next to the screens
 * (Android TV). It is a separate player from the full-screen one, never shared with it. All calls on the main thread.
 */
interface PreviewEngine {
    /** Starts [url] muted, replacing whatever played; [onEvent] reports readiness and errors. */
    fun play(url: String, onEvent: (PreviewEvent) -> Unit)
    /** Stops and frees the player and its decoder. */
    fun release()
    /** Where the picture is drawn. */
    @Composable fun Video(modifier: Modifier)
    /**
     * Whether the channel previewing now can be handed, player and stream as they are, to the full-screen player
     * (Android TV only; other platforms have no such player to hand over).
     */
    fun canHandOver(): Boolean = false
    /**
     * Full screen gave the player back, still playing: the window takes it on again and reports its events to
     * [onEvent]. False if there is nothing to take back (other platforms, or the player went).
     */
    fun takeBack(onEvent: (PreviewEvent) -> Unit): Boolean = false
    /**
     * Whether full screen can take this window's player even while its channel is still loading (or not yet asked
     * for): full screen then opens the stream in that player itself instead of building another.
     */
    fun canTakeOver(): Boolean = false
    /** A stream address is being looked up for the next play: keep the idle player for it until [play] or [release]. */
    fun starting() {}

    /** Pauses the window's channel, or plays it on; returns whether it is paused now (false where it can't pause). */
    fun togglePause(): Boolean = false
}

/**
 * The Live TV preview: what is highlighted plays in a small muted window once the highlight rests on it for
 * [debounceMs], so moving through the list doesn't open a stream per row. Stream addresses come from [resolve]
 * (the playlist's own provider, as the full-screen player uses). Without an engine, or with previews turned off,
 * the state is [State.Unsupported] and screens show their still panel.
 *
 * Preview audio is always muted: sound starts only when the user presses OK and the full-screen player opens.
 */
class PreviewController(
    private val scope: CoroutineScope,
    private val engine: PreviewEngine?,
    private val resolve: suspend (Channel) -> String?,
    private val enabled: () -> Boolean = { true },
    private val debounceMs: Long = 700,
) {
    sealed interface State {
        /** Nothing highlighted, or stopped. */
        data object Idle : State
        /** No preview on this device, or turned off in Settings. */
        data object Unsupported : State
        /** Highlighted; waits for the highlight to settle. */
        data class Waiting(val channel: Channel) : State
        data class Loading(val channel: Channel) : State
        data class Playing(val channel: Channel) : State
        data class Failed(val channel: Channel, val message: String) : State
    }

    val available: Boolean get() = engine != null && enabled()

    private val _state = MutableStateFlow<State>(if (available) State.Idle else State.Unsupported)
    val state: StateFlow<State> = _state
    private var job: Job? = null
    /** Waits for full screen to take a handed-over player; see [handOver]. */
    private var watch: Job? = null
    /**
     * The screen whose highlight started the preview ("live", "guide"); "" for none. Observed, so a screen's panel
     * redraws when the same channel's preview passes from one screen to the other.
     */
    var owner by mutableStateOf("")
        private set
    /**
     * The page whose window shows the picture ("live", "guide", "sports", "match"). Pages stay built when another tab
     * is open, but the one video view can only be in one place: the page in front claims it.
     */
    var surface by mutableStateOf("")
    /** The channel previewing (or about to), whichever page started it. */
    val channel: Channel? get() = _state.value.channel()
    /** A channel full screen handed back, numbered so each hand back is seen once (the pages line their lists up with it). */
    data class Returned(val channel: Channel, val seq: Int)
    var returned by mutableStateOf<Returned?>(null)
        private set
    /** Bumped on every change, so a late event from an earlier stream is ignored. */
    private var generation = 0

    /** The window's channel is paused (OK on it on the TV). Any other change plays again. */
    var paused by mutableStateOf(false)
        private set

    /** Pauses the channel playing in the window, or plays it on. */
    fun togglePause() {
        val e = engine ?: return
        if (_state.value !is State.Playing) return
        paused = e.togglePause()
    }

    private fun State.channel(): Channel? = when (this) {
        is State.Waiting -> channel; is State.Loading -> channel; is State.Playing -> channel; is State.Failed -> channel; else -> null
    }

    /**
     * [c] is highlighted on [by]'s screen: previews it after a short pause (again if it failed); the earlier preview
     * stops at once.
     */
    fun select(c: Channel, by: String = "live") {
        paused = false
        val e = engine
        if (e == null || !enabled()) { stop(); return }
        val cur = _state.value
        if (owner == by && cur.channel()?.id == c.id && cur !is State.Failed) return
        owner = by
        job?.cancel()
        watch?.cancel(); watch = null
        e.release()
        val gen = ++generation
        _state.value = State.Waiting(c)
        job = scope.launch {
            delay(debounceMs)
            _state.value = State.Loading(c)
            e.starting()
            val url = try { resolve(c) } catch (x: CancellationException) { throw x } catch (x: Exception) { null }
            if (gen != generation) return@launch
            if (url == null) { e.release(); _state.value = State.Failed(c, "Could not get the stream address"); return@launch }
            e.play(url) { ev ->
                if (gen == generation) _state.value = when (ev) {
                    PreviewEvent.Ready -> State.Playing(c)
                    is PreviewEvent.Error -> State.Failed(c, ev.message)
                }
            }
        }
    }

    /**
     * Full screen handed back the player playing [c] (perhaps another channel than the one it took): the window shows
     * it as it plays, with no new stream. Returns false, changing nothing, if the engine has nothing to take back.
     */
    fun adopt(c: Channel, by: String): Boolean {
        val e = engine ?: return false
        job?.cancel(); job = null
        watch?.cancel(); watch = null
        val gen = ++generation
        val ok = e.takeBack { ev ->
            if (gen == generation) _state.value = when (ev) {
                PreviewEvent.Ready -> State.Playing(c)
                is PreviewEvent.Error -> State.Failed(c, ev.message)
            }
        }
        if (!ok) return false
        owner = by
        _state.value = State.Playing(c)
        returned = Returned(c, (returned?.seq ?: 0) + 1)
        return true
    }

    /**
     * Full screen is taking the player for the channel shown: any stream address lookup still running stops (it would
     * otherwise start the channel in a player full screen now has), and late events are ignored. The state is left as
     * it is; full screen hands the player back with [adopt] or clears it with [stop].
     */
    fun handOver(withinMs: Long = HANDOVER_MS, taken: () -> Boolean = { true }) {
        job?.cancel(); job = null
        generation++
        // Full screen never took it (it didn't open): the window would wait on a lookup that was cancelled, so the
        // channel starts again here instead.
        val c = channel
        val by = owner
        watch?.cancel()
        watch = scope.launch {
            delay(withinMs)
            watch = null
            if (taken() || channel?.id != c?.id) return@launch
            stop()
            if (c != null) select(c, by)
        }
    }

    /** Stops any preview and frees its player: leaving the screen, switching playlist, the full-screen player, background. */
    fun stop() {
        paused = false
        job?.cancel(); job = null
        watch?.cancel(); watch = null
        owner = ""
        generation++
        engine?.release()
        _state.value = if (available) State.Idle else State.Unsupported
    }

    /** Stops the preview only if [by]'s screen started it, so a screen being hidden can't stop the next screen's. */
    fun stop(by: String) { if (owner == by) stop() }

    @Composable fun Video(modifier: Modifier) { engine?.Video(modifier) }

    companion object {
        /** How long full screen has to take a handed-over player before the window starts the channel again. */
        const val HANDOVER_MS = 5_500L
    }
}

/** What the Guide previews for a highlighted cell. */
sealed interface GuidePreview {
    /** The channel's live stream. */
    data class Play(val channel: Channel) : GuidePreview
    /** A programme that isn't on now: nothing plays (a channel's stream is only ever what is on now). */
    data class Upcoming(val programme: Programme) : GuidePreview
    data class Ended(val programme: Programme) : GuidePreview

    companion object {
        /**
         * The channel's stream for its own tile, a cell with no guide information, or the programme on [now]; nothing
         * for a programme that has ended or not started, since the stream wouldn't show it.
         */
        fun of(c: Channel, p: Programme?, now: Long): GuidePreview = when {
            p == null || now in p.start until p.stop -> Play(c)
            p.start > now -> Upcoming(p)
            else -> Ended(p)
        }
    }
}

/**
 * The Guide's preview follows its highlight: [target]'s channel plays while the Guide is [visible]; a programme that
 * isn't on now, nothing highlighted, or a hidden Guide stops the Guide's preview (never another screen's).
 */
fun PreviewController.followGuide(target: GuidePreview?, visible: Boolean) {
    if (visible && target is GuidePreview.Play) select(target.channel, "guide") else stop("guide")
}

/**
 * One screen's preview ([owner]: "live", "guide"), following that screen's highlight while its page is visible. Hidden,
 * covered by a screen or the menu, or behind the player, the screen's preview stops and frees its player (another
 * screen's is left alone). Coming back doesn't restart it: the highlight the page left with waits until the user
 * moves to another channel or programme.
 */
class PagePreview(private val controller: PreviewController, private val owner: String) {
    /** The highlight showing when the page came into view; null once the user has moved on. */
    private var held: Any? = null
    /** Next update is the page coming into view: keep what is already playing (from this tab or another). */
    private var arriving = true

    /**
     * [key] identifies the highlight (a channel, a guide cell); [channel] is what to preview for it, or null for none.
     * One preview is shared by Live TV, the TV guide and Sports, so switching between them, opening the menu or a
     * highlight with nothing to show (a programme that isn't on now) leaves the channel playing.
     */
    fun update(key: Any?, channel: Channel?, visible: Boolean) {
        if (!visible) { arriving = true; return }
        if (arriving) { arriving = false; if (controller.channel != null) { held = key; return } }
        if (key != null && key == held) return
        held = null
        if (channel != null) controller.select(channel, owner)
    }
}

/** What one OK on a channel does on the TV (a quick double OK always watches it full screen). */
enum class OkAction { PREVIEW, PAUSE, WATCH }

/** Two OKs this close together (ms) are a double OK: full screen. */
const val DOUBLE_OK_MS = 350L

/**
 * TV remote, one OK on [c]: previews it (moving through the list starts nothing); on the channel already playing in the
 * window, pauses it or plays it on. With no preview here (none on this device, or turned off) OK watches straight
 * away. A channel still starting, or whose preview failed, is asked for again.
 */
fun okAction(c: Channel, state: PreviewController.State, previewAvailable: Boolean): OkAction = when {
    !previewAvailable -> OkAction.WATCH
    state is PreviewController.State.Playing && state.channel.id == c.id -> OkAction.PAUSE
    else -> OkAction.PREVIEW
}
