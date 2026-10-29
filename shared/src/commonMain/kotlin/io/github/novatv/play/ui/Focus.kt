package io.github.novatv.play.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * A focus group that focus always enters at [target], whichever side it comes from. The rail uses it so Left from a
 * page lands on the open tab's icon, not on the icon level with the row that had focus (which opened that tab).
 */
fun Modifier.enterAt(target: FocusRequester): Modifier = focusProperties { onEnter = { target.requestFocus() } }.focusGroup()

/**
 * A focus group, reachable through [requester], that remembers its last focused child and returns there when focus
 * comes back. It must stay on a page while the page is hidden too, or the memory is lost when another tab opens.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.rememberFocus(requester: FocusRequester): Modifier = focusRequester(requester).focusRestorer().focusGroup()

/**
 * For a scrolling list inside a page: remembers its own focused row, which the page's memory alone loses for rows a
 * lazy list builds on demand (Live TV's channels, for one).
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun Modifier.rememberFocusInList(): Modifier = focusRestorer()

/**
 * Up from a page's top control: with nothing above it, focus used to go nowhere (the screen had no focused item). The
 * page now hands it to [fallback] (the open tab's rail icon) and arms [TopExit], so the next Down there comes straight
 * back to the control it left. Focus that would leave the page upwards some other way is sent to [fallback] too.
 */
class TopExit {
    var armed = false
    internal var inPage = false
}

fun Modifier.topExit(enabled: Boolean, fm: FocusManager, fallback: FocusRequester, state: TopExit): Modifier =
    if (!enabled) this else onFocusChanged { state.inPage = it.hasFocus }.onKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionUp) return@onKeyEvent false
        val moved = fm.moveFocus(FocusDirection.Up)
        if (!moved || !state.inPage) { state.armed = true; fallback.requestFocus() }
        true
    }

/** On the rail: Down right after [topExit] sent focus there goes back into the page; any other key disarms it. */
fun Modifier.returnFromTop(state: TopExit, page: FocusRequester): Modifier = onPreviewKeyEvent { e ->
    if (e.type != KeyEventType.KeyDown || !state.armed) return@onPreviewKeyEvent false
    state.armed = false
    if (e.key == Key.DirectionDown) { page.requestFocus(); true } else false
}
