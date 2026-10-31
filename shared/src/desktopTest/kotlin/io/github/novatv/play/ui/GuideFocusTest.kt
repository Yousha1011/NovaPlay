package io.github.novatv.play.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Guide's grid remembers the cell last used: a page laid out like the Guide (the Now button and chips over a
 * scrolling list of channel rows, each a channel tile and programme cells), wired as Root wires a page.
 */
@OptIn(ExperimentalTestApi::class)
class GuideFocusTest {
    private fun item(tag: String, req: FocusRequester? = null) =
        Modifier.testTag(tag).then(if (req != null) Modifier.focusRequester(req) else Modifier).size(40.dp).focusable()

    private class Screen {
        lateinit var fm: FocusManager
        lateinit var list: LazyListState
        val rail = FocusRequester(); val guide = FocusRequester(); val other = FocusRequester()
        var shown by mutableStateOf("guide")
        var rows by mutableStateOf((0 until 40).toList())
    }

    private fun androidx.compose.ui.test.ComposeUiTest.guide(): Screen {
        val s = Screen()
        setContent {
            s.fm = LocalFocusManager.current
            s.list = rememberLazyListState()
            Row {
                Box(item("rail", s.rail))
                Box {
                    // As Root lays out tabs: hidden pages stay built but aren't placed.
                    val hide = { name: String -> if (s.shown == name) Modifier else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} } }
                    Column(Modifier.rememberFocus(s.guide).then(hide("guide"))) {
                        Row { Box(item("now")); Box(item("chip")) }
                        LazyColumn(Modifier.height(300.dp).rememberFocusInList(), state = s.list) {
                            items(s.rows, key = { it }) { r ->
                                Row(Modifier.rememberFocusInList()) {
                                    Box(item("tile$r")); Box(item("cell$r-0")); Box(item("cell$r-1")); Box(item("cell$r-2"))
                                }
                            }
                        }
                    }
                    Column(Modifier.rememberFocus(s.other).then(hide("other"))) { Box(item("other0")); Box(item("other1")) }
                }
            }
        }
        runOnIdle { s.guide.requestFocus() }
        return s
    }

    private fun SemanticsNodeInteractionsProvider.oneFocused() = onAllNodes(isFocused()).assertCountEquals(1)

    /** Down into the grid to row [row], then Right to its cell [cell]. */
    private fun androidx.compose.ui.test.ComposeUiTest.goTo(s: Screen, row: Int, cell: Int) {
        repeat(row + 1) { runOnIdle { s.fm.moveFocus(FocusDirection.Down) } }
        repeat(cell + 1) { runOnIdle { s.fm.moveFocus(FocusDirection.Right) } }
        onNodeWithTag("cell$row-$cell").assertIsFocused()
    }

    @Test fun theMenuAndBackReturnToTheSameCell() = runComposeUiTest {
        val s = guide()
        onNodeWithTag("now").assertIsFocused()
        goTo(s, 18, 2)
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.guide.requestFocus() }
        onNodeWithTag("cell18-2").assertIsFocused()
        oneFocused()
    }

    @Test fun anotherTabAndBackReturnToTheSameCell() = runComposeUiTest {
        val s = guide()
        goTo(s, 12, 1)
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.shown = "other" }
        runOnIdle { s.other.requestFocus() }
        onNodeWithTag("other0").assertIsFocused()
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.shown = "guide" }
        runOnIdle { s.guide.requestFocus() }
        onNodeWithTag("cell12-1").assertIsFocused()
    }

    @Test fun theScrollPositionIsKept() = runComposeUiTest {
        val s = guide()
        goTo(s, 25, 0)
        val before = runOnIdle { s.list.firstVisibleItemIndex to s.list.firstVisibleItemScrollOffset }
        assertTrue(before.first > 0, "the list scrolled to reach row 25")
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.shown = "other" }
        runOnIdle { s.other.requestFocus() }
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.shown = "guide" }
        runOnIdle { s.guide.requestFocus() }
        onNodeWithTag("cell25-0").assertIsFocused()
        assertEquals(before, runOnIdle { s.list.firstVisibleItemIndex to s.list.firstVisibleItemScrollOffset })
    }

    @Test fun aCellThatIsGoneFallsBackToAValidOne() = runComposeUiTest {
        val s = guide()
        goTo(s, 3, 1)
        runOnIdle { s.rail.requestFocus() }
        // The playlist or category changed while away: that row is no longer there.
        runOnIdle { s.rows = s.rows - 3 }
        runOnIdle { s.guide.requestFocus() }
        oneFocused()
        val tag = runOnIdle { onAllNodes(isFocused()).fetchSemanticsNodes().single().config[androidx.compose.ui.semantics.SemanticsProperties.TestTag] }
        assertTrue(tag != "rail" && !tag.startsWith("other"), "focus stays in the Guide (got $tag)")
    }
}
