package io.github.novatv.play.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Windows: the player is drawn over the app and holds focus; when it closes nothing is focused, so the page takes
 * focus back (as Root does on App.playerClosed) on the item last used, and the arrow keys work again.
 */
@OptIn(ExperimentalTestApi::class)
class PlayerCloseFocusTest {
    @Test fun focusReturnsToTheItemPlayedAfterThePlayerCloses() = runComposeUiTest {
        var playing by mutableStateOf(false)
        var closed by mutableIntStateOf(0)
        val page = FocusRequester(); val first = FocusRequester(); val player = FocusRequester()
        setContent {
            LaunchedEffect(closed) { if (closed > 0) page.requestFocus() }
            Box(Modifier.fillMaxSize().arrowKeysMoveFocus(LocalFocusManager.current) { !playing }) {
                Column(Modifier.rememberFocus(page)) {
                    for (i in 1..4) Box(Modifier.testTag("ch$i").let { if (i == 1) it.focusRequester(first) else it }.size(40.dp).focusable())
                }
                if (playing) {
                    Box(Modifier.testTag("player").focusRequester(player).fillMaxSize().focusable())
                    LaunchedEffect(Unit) { player.requestFocus() }
                }
            }
        }
        runOnIdle { first.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        onNodeWithTag("ch3").assertIsFocused()

        // Play ch3, then close the player as Esc does.
        runOnIdle { playing = true }
        onNodeWithTag("player").assertIsFocused()
        onNodeWithTag("ch3").assertIsNotFocused()
        runOnIdle { playing = false; closed++ }

        onNodeWithTag("ch3").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("ch4").assertIsFocused()
    }
}
