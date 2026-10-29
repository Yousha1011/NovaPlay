package io.github.novatv.play.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/**
 * Up from a page's top control goes to the open tab's rail icon (never to nothing), and Down from there returns to
 * that control. Each test lays out the top of one TV page the way the real screen does, inside a rail and a page
 * wired as Root wires them, and drives it with key presses.
 */
@OptIn(ExperimentalTestApi::class)
class TopExitTest {
    private fun item(tag: String, req: FocusRequester? = null) =
        Modifier.testTag(tag).then(if (req != null) Modifier.focusRequester(req) else Modifier).size(40.dp).focusable()

    /** A rail (Home above the open tab, Settings below) and a page holding [content], with [first] focused. */
    private fun ComposeUiTest.screen(first: String, content: @Composable () -> Unit) {
        val openTab = FocusRequester(); val page = FocusRequester(); val state = TopExit()
        setContent {
            val fm = LocalFocusManager.current
            // Android moves focus with the D-pad on its own; desktop Compose doesn't with arrow keys, so this stands in.
            Row(Modifier.onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                    Key.DirectionDown -> fm.moveFocus(FocusDirection.Down); Key.DirectionUp -> fm.moveFocus(FocusDirection.Up)
                    Key.DirectionLeft -> fm.moveFocus(FocusDirection.Left); Key.DirectionRight -> fm.moveFocus(FocusDirection.Right)
                    else -> false
                }
            }) {
                Column(Modifier.returnFromTop(state, page).enterAt(openTab)) {
                    Box(item("rail-home")); Box(item("rail-open", openTab)); Box(item("rail-settings"))
                }
                Box(Modifier.topExit(true, fm, openTab, state).rememberFocus(page)) {
                    Column { Spacer(Modifier.height(60.dp)); content() }
                }
            }
        }
        runOnIdle { page.requestFocus() }
        onNodeWithTag(first).assertIsFocused()
    }

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
        // Focus is never left on nothing.
        onAllNodes(isFocused()).assertCountEquals(1)
    }

    /** Up leaves [first] for the open tab's icon, Down comes back to [first], and the rest of the page still works. */
    private fun ComposeUiTest.upThenDown(first: String, below: String) {
        press(Key.DirectionUp)
        onNodeWithTag("rail-open").assertIsFocused()
        press(Key.DirectionDown)
        onNodeWithTag(first).assertIsFocused()
        press(Key.DirectionDown)
        onNodeWithTag(below).assertIsFocused()
    }

    @Test fun settings() = runComposeUiTest {
        // The playlist card first, then a text box and the Playback buttons below.
        screen("playlist") { Column { Box(item("playlist")); Box(item("add")); Box(item("guide-box")); Row { Box(item("ts")); Box(item("hls")) } } }
        upThenDown("playlist", "add")
    }

    @Test fun liveTv() = runComposeUiTest {
        screen("cat0") {
            Row {
                LazyColumn(Modifier.height(200.dp).rememberFocusInList()) { items(10) { Box(item("cat$it")) } }
                LazyColumn(Modifier.height(200.dp).rememberFocusInList()) { items(50) { Box(item("ch$it")) } }
            }
        }
        upThenDown("cat0", "cat1")
    }

    @Test fun guide() = runComposeUiTest {
        // The Now button and category chips above the channel grid.
        screen("now") { Column { Row { Box(item("now")); Box(item("chip")) }; LazyColumn(Modifier.height(200.dp)) { items(20) { Box(item("row$it")) } } } }
        upThenDown("now", "row0")
    }

    @Test fun moviesAndSeries() = runComposeUiTest {
        // Overview / Browse all buttons, then poster rows.
        screen("overview") {
            Column {
                Row { Box(item("overview")); Box(item("browse")) }
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.height(200.dp)) { items(30) { Box(item("poster$it")) } }
            }
        }
        upThenDown("overview", "poster0")
    }

    @Test fun home() = runComposeUiTest {
        // The banner's Play and More info, then rows of cards.
        screen("play") {
            LazyColumn(Modifier.height(300.dp)) {
                item { Row { Box(item("play")); Box(item("info")) } }
                items(5) { r -> LazyRow { items(8) { Box(item("card$r-$it")) } } }
            }
        }
        upThenDown("play", "card0-0")
    }

    @Test fun sportsAndSearch() = runComposeUiTest {
        // Header buttons over tab buttons (Sports); a box over results (Search on Windows-style fields).
        screen("refresh") { Column { Box(item("refresh")); Row { Box(item("live-now")); Box(item("today")) }; Box(item("card")) } }
        upThenDown("refresh", "live-now")
    }

    @Test fun repeatedUpOnTheRailMovesThroughTheRailAsBefore() = runComposeUiTest {
        screen("a") { Column { Box(item("a")); Box(item("b")) } }
        press(Key.DirectionUp)
        onNodeWithTag("rail-open").assertIsFocused()
        // Another Up is ordinary rail movement (and disarms the way back).
        press(Key.DirectionUp)
        onNodeWithTag("rail-home").assertIsFocused()
        press(Key.DirectionDown)
        onNodeWithTag("rail-open").assertIsFocused()
    }

    @Test fun upInsideThePageStillMovesUp() = runComposeUiTest {
        screen("a") { Column { Box(item("a")); Box(item("b")); Box(item("c")) } }
        press(Key.DirectionDown); press(Key.DirectionDown)
        onNodeWithTag("c").assertIsFocused()
        press(Key.DirectionUp)
        onNodeWithTag("b").assertIsFocused()
    }
}
