package io.github.novatv.play.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Windows: real arrow-key presses (not direct focus moves) move focus through a rail and a page. */
@OptIn(ExperimentalTestApi::class)
class ArrowKeysTest {
    private fun item(tag: String, req: FocusRequester? = null) =
        Modifier.testTag(tag).then(if (req != null) Modifier.focusRequester(req) else Modifier).size(40.dp).focusable()

    @Test fun arrowsMoveFocusInAllFourDirections() = runComposeUiTest {
        val start = FocusRequester()
        setContent {
            Row(Modifier.arrowKeysMoveFocus(LocalFocusManager.current)) {
                Column { Box(item("r1", start)); Box(item("r2")) }
                Column { Box(item("p1")); Box(item("p2")) }
            }
        }
        runOnIdle { start.requestFocus() }
        fun press(k: Key) = onRoot().performKeyInput { pressKey(k) }
        press(Key.DirectionDown); onNodeWithTag("r2").assertIsFocused()
        press(Key.DirectionRight); onNodeWithTag("p2").assertIsFocused()
        press(Key.DirectionUp); onNodeWithTag("p1").assertIsFocused()
        press(Key.DirectionLeft); onNodeWithTag("r1").assertIsFocused()
    }

    @Test fun keysHandledByAScreenStillWin() = runComposeUiTest {
        val start = FocusRequester(); var handled = 0
        setContent {
            Row(Modifier.arrowKeysMoveFocus(LocalFocusManager.current)) {
                // Like the rail's Right: the screen takes the key itself.
                Column(Modifier.onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) { handled++; true } else false }) {
                    Box(item("r1", start))
                }
                Box(item("p1"))
            }
        }
        runOnIdle { start.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("r1").assertIsFocused()
        assertEquals(1, handled)
    }

    @Test fun offWhileThePlayerIsOpen() = runComposeUiTest {
        val start = FocusRequester()
        setContent {
            Column(Modifier.arrowKeysMoveFocus(LocalFocusManager.current) { false }) { Box(item("a", start)); Box(item("b")) }
        }
        runOnIdle { start.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("a").assertIsFocused()
    }

    @Test fun arrowsLeaveTheSearchBoxAtItsEnds() = runComposeUiTest {
        val field = FocusRequester()
        setContent {
            Column(Modifier.arrowKeysMoveFocus(LocalFocusManager.current)) {
                Box(item("above"))
                Row {
                    Box(item("left"))
                    InputField("ab", "Search", {}, Modifier.testTag("field").focusRequester(field).size(200.dp, 54.dp))
                    Box(item("right"))
                }
                Box(item("below"))
            }
        }
        fun press(k: Key) = onRoot().performKeyInput { pressKey(k) }
        runOnIdle { field.requestFocus() }
        press(Key.DirectionDown); onNodeWithTag("below").assertIsFocused()
        runOnIdle { field.requestFocus() }
        press(Key.DirectionUp); onNodeWithTag("above").assertIsFocused()
        // In the text, Left and Right move the cursor first; at the ends they move on.
        runOnIdle { field.requestFocus() }
        press(Key.MoveEnd); press(Key.DirectionLeft); onNodeWithTag("field").assertIsFocused()
        press(Key.DirectionLeft); onNodeWithTag("field").assertIsFocused()
        press(Key.DirectionLeft); onNodeWithTag("left").assertIsFocused()
        runOnIdle { field.requestFocus() }
        press(Key.MoveEnd); press(Key.DirectionRight); onNodeWithTag("right").assertIsFocused()
    }

    @Test fun enterSelectsTheFocusedTile() = runComposeUiTest {
        val start = FocusRequester(); var clicks = 0
        setContent {
            Column(Modifier.arrowKeysMoveFocus(LocalFocusManager.current)) {
                Tile({ }, Modifier.testTag("t1").focusRequester(start).size(40.dp), null, RoundedCornerShape(4.dp), 1f, Color.Transparent, Color.Gray, Color.White) {}
                Tile({ clicks++ }, Modifier.testTag("t2").size(40.dp), null, RoundedCornerShape(4.dp), 1f, Color.Transparent, Color.Gray, Color.White) {}
            }
        }
        runOnIdle { start.requestFocus() }
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("t2").assertIsFocused()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, clicks) }
    }
}
