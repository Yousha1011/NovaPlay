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
    /**
     * The screen whose highlight started the preview ("live", "guide"); "" for none. Observed, so a screen's panel
     * redraws when the same channel's preview passes from one screen to the other.
     */
    var owner by mutableStateOf("")
        private set
    /** Bumped on every change, so a late event from an earlier stream is ignored. */
    private var generation = 0

    private fun State.channel(): Channel? = when (this) {
        is State.Waiting -> channel; is State.Loading -> channel; is State.Playing -> channel; is State.Failed -> channel; else -> null
    }

    /**
     * [c] is highlighted on [by]'s screen: previews it after a short pause (again if it failed); the earlier preview
     * stops at once.
     */
    fun select(c: Channel, by: String = "live") {
        val e = engine
        if (e == null || !enabled()) { stop(); return }
        val cur = _state.value
        if (owner == by && cur.channel()?.id == c.id && cur !is State.Failed) return
        owner = by
        job?.cancel()
        e.release()
        val gen = ++generation
        _state.value = State.Waiting(c)
        job = scope.launch {
            delay(debounceMs)
            _state.value = State.Loading(c)
            val url = try { resolve(c) } catch (x: CancellationException) { throw x } catch (x: Exception) { null }
            if (gen != generation) return@launch
            if (url == null) { _state.value = State.Failed(c, "Could not get the stream address"); return@launch }
            e.play(url) { ev ->
                if (gen == generation) _state.value = when (ev) {
                    PreviewEvent.Ready -> State.Playing(c)
                    is PreviewEvent.Error -> State.Failed(c, ev.message)
                }
            }
        }
    }

    /** Stops any preview and frees its player: leaving the screen, switching playlist, the full-screen player, background. */
    fun stop() {
        job?.cancel(); job = null
        owner = ""
        generation++
        engine?.release()
        _state.value = if (available) State.Idle else State.Unsupported
    }

    /** Stops the preview only if [by]'s screen started it, so a screen being hidden can't stop the next screen's. */
    fun stop(by: String) { if (owner == by) stop() }

    @Composable fun Video(modifier: Modifier) { engine?.Video(modifier) }
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
