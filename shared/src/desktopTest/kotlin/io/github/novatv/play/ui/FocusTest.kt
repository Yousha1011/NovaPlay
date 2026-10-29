package io.github.novatv.play.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

/** The two focus rules the TV app relies on, checked with real Compose focus movement (what the D-pad triggers). */
@OptIn(ExperimentalTestApi::class)
class FocusTest {
    private fun item(tag: String, req: FocusRequester? = null) =
        Modifier.testTag(tag).then(if (req != null) Modifier.focusRequester(req) else Modifier).size(40.dp).focusable()

    @Test fun leftIntoTheRailLandsOnTheOpenTabNotTheIconBesideTheRow() = runComposeUiTest {
        lateinit var fm: FocusManager
        val openTab = FocusRequester()
        val row = FocusRequester()
        setContent {
            fm = LocalFocusManager.current
            Row {
                // The rail: the open tab (Home) at the top, other tabs below; the last one sits level with the row.
                Column(Modifier.enterAt(openTab)) {
                    Box(item("home", openTab))
                    Box(item("live")); Box(item("guide")); Box(item("recordings"))
                }
                Column {
                    Spacer(Modifier.height(120.dp))
                    Box(item("row", row))
                }
            }
        }
        runOnIdle { row.requestFocus() }
        onNodeWithTag("row").assertIsFocused()
        runOnIdle { fm.moveFocus(FocusDirection.Left) }
        onNodeWithTag("home").assertIsFocused()
        // Inside the rail, Up and Down still move between icons.
        runOnIdle { fm.moveFocus(FocusDirection.Down) }
        onNodeWithTag("live").assertIsFocused()
    }

    @Test fun aHiddenTabStillRemembersItsFocusedItem() = runComposeUiTest {
        lateinit var fm: FocusManager
        val pageA = FocusRequester(); val pageB = FocusRequester(); val rail = FocusRequester()
        var shown by mutableStateOf("A")
        setContent {
            fm = LocalFocusManager.current
            Row {
                Box(item("rail", rail))
                Box {
                    // As Root lays out tabs: the same focus group whether shown or hidden; hidden ones aren't placed.
                    for ((name, req) in listOf("A" to pageA, "B" to pageB)) {
                        Column(Modifier.rememberFocus(req).then(if (shown == name) Modifier else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} })) {
                            Box(item("${name}1")); Box(item("${name}2")); Box(item("${name}3"))
                        }
                    }
                }
            }
        }
        runOnIdle { pageA.requestFocus() }
        onNodeWithTag("A1").assertIsFocused()
        runOnIdle { fm.moveFocus(FocusDirection.Down); fm.moveFocus(FocusDirection.Down) }
        onNodeWithTag("A3").assertIsFocused()
        // To the rail, open tab B and use it, then come back to A.
        runOnIdle { rail.requestFocus() }
        runOnIdle { shown = "B" }
        runOnIdle { pageB.requestFocus() }
        onNodeWithTag("B1").assertIsFocused()
        runOnIdle { rail.requestFocus() }
        runOnIdle { shown = "A" }
        runOnIdle { pageA.requestFocus() }
        onNodeWithTag("A3").assertIsFocused()
    }

    @Test fun aNewPageStartsAtItsFirstItem() = runComposeUiTest {
        val page = FocusRequester()
        setContent { Column(Modifier.rememberFocus(page)) { Box(item("first")); Box(item("second")) } }
        runOnIdle { page.requestFocus() }
        onNodeWithTag("first").assertIsFocused()
    }

    @Test fun aHiddenTabWithLazyListsStillRemembersItsRow() = runComposeUiTest {
        lateinit var fm: FocusManager
        val pageA = FocusRequester(); val pageB = FocusRequester(); val rail = FocusRequester()
        var shown by mutableStateOf("A")
        setContent {
            fm = LocalFocusManager.current
            Row {
                Box(item("rail", rail))
                Box {
                    for ((name, req) in listOf("A" to pageA, "B" to pageB)) {
                        // Like Live TV: a category column beside a lazy channel list.
                        Row(Modifier.rememberFocus(req).then(if (shown == name) Modifier else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} })) {
                            Column { Box(item("${name}cat1")); Box(item("${name}cat2")) }
                            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(200.dp).rememberFocusInList()) {
                                items(30) { i -> Box(item("${name}ch$i")) }
                            }
                        }
                    }
                }
            }
        }
        runOnIdle { pageA.requestFocus() }
        runOnIdle { fm.moveFocus(FocusDirection.Right); fm.moveFocus(FocusDirection.Down); fm.moveFocus(FocusDirection.Down) }
        onNodeWithTag("Ach2").assertIsFocused()
        runOnIdle { rail.requestFocus() }
        runOnIdle { shown = "B" }
        runOnIdle { pageB.requestFocus() }
        runOnIdle { rail.requestFocus() }
        runOnIdle { shown = "A" }
        runOnIdle { pageA.requestFocus() }
        onNodeWithTag("Ach2").assertIsFocused()
    }
}
