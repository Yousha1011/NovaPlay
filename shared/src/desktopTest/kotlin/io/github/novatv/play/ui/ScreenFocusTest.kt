package io.github.novatv.play.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Home, Movies, Series, Sports and Search remember the item last used, through the menu and other tabs: each test lays
 * out one screen's shape (its scrolling rows and grids, each with [rememberFocusInList]) inside pages wired as Root
 * wires them, moves to a deep item and leaves.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenFocusTest {
    private fun item(tag: String) = Modifier.testTag(tag).size(40.dp).focusable()

    private class Screen {
        lateinit var fm: FocusManager
        val rail = FocusRequester(); val page = FocusRequester(); val other = FocusRequester()
        var shown by mutableStateOf("page")
        /** The screen's items; tests remove some to stand in for a refresh. */
        var items by mutableStateOf((0 until 40).toList())
    }

    private fun ComposeUiTest.screen(content: @Composable (Screen) -> Unit): Screen {
        val s = Screen()
        setContent {
            s.fm = LocalFocusManager.current
            Row {
                Box(Modifier.testTag("rail").size(40.dp).focusRequester(s.rail).focusable())
                Box {
                    val hide = { name: String -> if (s.shown == name) Modifier else Modifier.layout { m, c -> m.measure(c); layout(0, 0) {} } }
                    Box(Modifier.rememberFocus(s.page).then(hide("page"))) { content(s) }
                    Column(Modifier.rememberFocus(s.other).then(hide("other"))) { Box(item("other0")); Box(item("other1")) }
                }
            }
        }
        runOnIdle { s.page.requestFocus() }
        return s
    }

    private enum class Dir(val f: FocusDirection) { Down(FocusDirection.Down), Right(FocusDirection.Right) }
    private fun ComposeUiTest.move(s: Screen, vararg d: Dir) = d.forEach { runOnIdle { s.fm.moveFocus(it.f) } }
    private fun ComposeUiTest.viaMenu(s: Screen) { runOnIdle { s.rail.requestFocus() }; runOnIdle { s.page.requestFocus() } }
    private fun ComposeUiTest.viaOtherTab(s: Screen) {
        runOnIdle { s.rail.requestFocus() }; runOnIdle { s.shown = "other" }; runOnIdle { s.other.requestFocus() }
        runOnIdle { s.rail.requestFocus() }; runOnIdle { s.shown = "page" }; runOnIdle { s.page.requestFocus() }
    }
    private fun ComposeUiTest.focusedTag(): String {
        onAllNodes(isFocused()).assertCountEquals(1)
        return runOnIdle { onAllNodes(isFocused()).fetchSemanticsNodes().single().config[SemanticsProperties.TestTag] }
    }
    private fun ComposeUiTest.assertInPage(prefix: String) {
        val t = focusedTag()
        assertTrue(t.startsWith(prefix), "focus stays on a valid item of the screen (got $t)")
    }

    private val D = Dir.Down; private val R = Dir.Right

    /** Home: the banner's buttons over rows of cards, each row scrolling sideways. */
    @Composable private fun Home(s: Screen) {
        LazyColumn(Modifier.height(320.dp).rememberFocusInList()) {
            item { Row { Box(item("play")); Box(item("info")) } }
            items(listOf(0, 1, 2, 3, 4, 5, 6, 7), key = { it }) { r ->
                LazyRow(Modifier.width(200.dp).rememberFocusInList()) { items(s.items, key = { it }) { Box(item("card$r-$it")) } }
            }
        }
    }

    /** Movies and Series: category chips over a scrolling poster grid. */
    @Composable private fun Vod(s: Screen) {
        Column {
            LazyRow(Modifier.width(200.dp).rememberFocusInList()) { items(listOf("all", "action", "drama")) { Box(item("chip-$it")) } }
            LazyVerticalGrid(GridCells.Fixed(4), Modifier.height(240.dp).rememberFocusInList()) { items(s.items, key = { it }) { Box(item("poster$it")) } }
        }
    }

    /** Sports: the Live now / Today buttons over a grid of game cards. */
    @Composable private fun Sports(s: Screen) {
        Column {
            LazyRow(Modifier.rememberFocusInList()) { items(listOf("live", "today", "channels")) { Box(item("tab-$it")) } }
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.height(240.dp).rememberFocusInList()) { items(s.items, key = { it }) { Box(item("game$it")) } }
        }
    }

    /** Search: the results, a row each for channels, movies and series. */
    @Composable private fun Search(s: Screen) {
        LazyColumn(Modifier.height(240.dp).rememberFocusInList()) {
            items(listOf("ch", "mv", "sr"), key = { it }) { kind ->
                LazyRow(Modifier.width(200.dp).rememberFocusInList()) { items(s.items, key = { it }) { Box(item("$kind$it")) } }
            }
        }
    }

    @Test fun homeReturnsToTheRowAndCardAfterTheMenuAndAnotherTab() = runComposeUiTest {
        val s = screen { Home(it) }
        onNodeWithTag("play").assertIsFocused()
        move(s, D, D, D, D, R, R, R, R, R, R)
        onNodeWithTag("card3-6").assertIsFocused()
        viaMenu(s); onNodeWithTag("card3-6").assertIsFocused()
        viaOtherTab(s); onNodeWithTag("card3-6").assertIsFocused()
    }

    @Test fun moviesReturnToADeepPosterAfterTheMenu() = runComposeUiTest {
        val s = screen { Vod(it) }
        move(s, D, D, D, D, D, D, R, R)
        val deep = focusedTag()
        assertTrue(deep.startsWith("poster") && deep.removePrefix("poster").toInt() >= 20, "a poster several rows down (got $deep)")
        viaMenu(s); onNodeWithTag(deep).assertIsFocused()
    }

    @Test fun seriesReturnToADeepPosterAfterSwitchingTabs() = runComposeUiTest {
        val s = screen { Vod(it) }
        move(s, D, D, D, D, D, D, D, R)
        val deep = focusedTag()
        viaOtherTab(s); onNodeWithTag(deep).assertIsFocused()
    }

    @Test fun sportsReturnToTheSelectedGame() = runComposeUiTest {
        val s = screen { Sports(it) }
        move(s, D, D, D, D, D, D, R)
        val game = focusedTag()
        assertTrue(game.startsWith("game"), "on a game card (got $game)")
        viaMenu(s); onNodeWithTag(game).assertIsFocused()
        viaOtherTab(s); onNodeWithTag(game).assertIsFocused()
    }

    @Test fun searchReturnsToTheResult() = runComposeUiTest {
        val s = screen { Search(it) }
        move(s, D, R, R, R, R, R)
        onNodeWithTag("mv5").assertIsFocused()
        viaOtherTab(s); onNodeWithTag("mv5").assertIsFocused()
    }

    @Test fun aHomeCardThatIsGoneFallsBackToAValidOne() = runComposeUiTest {
        val s = screen { Home(it) }
        move(s, D, D, R, R, R)
        onNodeWithTag("card1-3").assertIsFocused()
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.items = s.items - 3 }
        runOnIdle { s.page.requestFocus() }
        val t = focusedTag()
        assertTrue(t.startsWith("card") || t == "play" || t == "info", "a valid Home item (got $t)")
    }

    @Test fun aPosterThatIsGoneFallsBackToAValidOne() = runComposeUiTest {
        val s = screen { Vod(it) }
        move(s, D, D, D, R)
        val gone = focusedTag()
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.items = s.items - gone.removePrefix("poster").toInt() }
        runOnIdle { s.page.requestFocus() }
        val t = focusedTag()
        assertTrue(t != gone && (t.startsWith("poster") || t.startsWith("chip")), "a valid Movies item (got $t)")
    }

    @Test fun aGameThatIsGoneFallsBackToAValidOne() = runComposeUiTest {
        val s = screen { Sports(it) }
        move(s, D, D, D)
        val gone = focusedTag()
        runOnIdle { s.rail.requestFocus() }
        // The refresh found no games at all.
        runOnIdle { s.items = emptyList() }
        runOnIdle { s.page.requestFocus() }
        assertInPage("tab-")
        assertTrue(gone.startsWith("game"))
    }

    @Test fun aSearchResultThatIsGoneFallsBackToAValidOne() = runComposeUiTest {
        val s = screen { Search(it) }
        move(s, D, R, R)
        onNodeWithTag("mv2").assertIsFocused()
        runOnIdle { s.rail.requestFocus() }
        runOnIdle { s.items = s.items - 2 }
        runOnIdle { s.page.requestFocus() }
        val t = focusedTag()
        assertTrue(t != "mv2" && (t.startsWith("ch") || t.startsWith("mv") || t.startsWith("sr")), "a valid result (got $t)")
    }
}
