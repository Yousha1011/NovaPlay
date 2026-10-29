package io.github.novatv.play.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer

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
