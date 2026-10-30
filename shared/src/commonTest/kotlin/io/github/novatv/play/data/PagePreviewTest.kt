package io.github.novatv.play.data

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.novatv.play.Nav
import io.github.novatv.play.PageVisibility
import io.github.novatv.play.Route
import io.github.novatv.play.Tab
import io.github.novatv.play.data.PreviewController.State
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Previews run only on a visible page: the menu, a screen opened over the page, another tab or the full-screen player
 * stops them, and coming back waits for the user to move before starting again.
 */
class PagePreviewTest {
    private class FakeEngine : PreviewEngine {
        val played = mutableListOf<String>()
        var releases = 0
        /** Players started and not yet let go. */
        var active = 0
        var listener: ((PreviewEvent) -> Unit)? = null
        override fun play(url: String, onEvent: (PreviewEvent) -> Unit) { played += url; active++; listener = onEvent }
        override fun release() { releases++; active = 0; listener = null }
        @Composable override fun Video(modifier: Modifier) {}
    }

    private val now = 1_000_000_000L
    private fun ch(id: String) = Channel(id, "Channel $id", null, "c", null, null, 1)
    private fun onNow(c: Channel) = Programme(c.id, now - 1000, now + 3_600_000, "On now", null, null)

    private class Screens(scope: TestScope, val e: FakeEngine = FakeEngine()) {
        val nav = Nav()
        val controller = PreviewController(scope, e, { c -> "http://host/live/${c.id}.ts" }, debounceMs = 700)
        val live = PagePreview(controller, "live")
        val guide = PagePreview(controller, "guide")
        var liveChannel: Channel? = null
        var guideCell: GuidePreview? = null
        /** What each screen's effect does after any change, as Live TV and the Guide do. */
        fun settle() {
            live.update(liveChannel, liveChannel, nav.visibility(Tab.LIVE) == PageVisibility.VISIBLE)
            guide.update(guideCell, (guideCell as? GuidePreview.Play)?.channel, nav.visibility(Tab.GUIDE) == PageVisibility.VISIBLE)
        }
    }

    @Test fun visibilityTellsAPageInUseFromHiddenCoveredMenuAndPlayer() {
        val nav = Nav().apply { tab = Tab.LIVE }
        assertEquals(PageVisibility.VISIBLE, nav.visibility(Tab.LIVE))
        assertEquals(PageVisibility.HIDDEN, nav.visibility(Tab.GUIDE))
        nav.menuOpen = true
        assertEquals(PageVisibility.MENU_OPEN, nav.visibility(Tab.LIVE))
        nav.menuOpen = false
        nav.push(Route.AddSource)
        assertEquals(PageVisibility.COVERED, nav.visibility(Tab.LIVE))
        nav.pop()
        nav.covered = true
        assertEquals(PageVisibility.BEHIND_PLAYER, nav.visibility(Tab.LIVE))
        nav.covered = false
        assertEquals(PageVisibility.VISIBLE, nav.visibility(Tab.LIVE))
    }

    @Test fun openingTheMenuStopsALiveTvPreview() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.LIVE }
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.active)
        s.nav.menuOpen = true; s.settle()
        assertEquals(State.Idle, s.controller.state.value)
        assertEquals(0, s.e.active, "no player is left decoding under the menu")
    }

    @Test fun openingTheMenuStopsAGuidePreview() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.GUIDE }
        s.guideCell = GuidePreview.of(ch("7"), onNow(ch("7")), now); s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.active)
        s.nav.menuOpen = true; s.settle()
        assertEquals(State.Idle, s.controller.state.value)
        assertEquals(0, s.e.active)
    }

    @Test fun closingTheMenuDoesNotRestartThePreviewUntilTheUserMoves() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.LIVE }
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        s.nav.menuOpen = true; s.settle()
        s.nav.menuOpen = false; s.settle(); advanceUntilIdle()
        assertEquals(State.Idle, s.controller.state.value)
        assertEquals(1, s.e.played.size)
        // Moving to another channel starts it; moving back to the first one starts that too.
        s.liveChannel = ch("2"); s.settle(); advanceUntilIdle()
        assertEquals("http://host/live/2.ts", s.e.played.last())
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        assertEquals("http://host/live/1.ts", s.e.played.last())
    }

    @Test fun theFullScreenPlayerStopsThePreviewFirstAndReturningWaitsForAMove() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.GUIDE }
        s.guideCell = GuidePreview.of(ch("7"), onNow(ch("7")), now); s.settle(); advanceUntilIdle()
        // play() stops the preview before the player opens; then the app's window is covered.
        s.controller.stop()
        assertEquals(0, s.e.active)
        s.nav.covered = true; s.settle()
        s.nav.covered = false; s.settle(); advanceUntilIdle()
        assertEquals(State.Idle, s.controller.state.value)
        assertEquals(1, s.e.played.size)
    }

    @Test fun screensOpenedOverAPageAndOtherTabsStopItsPreview() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.LIVE }
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        s.nav.push(Route.AddSource); s.settle()
        assertEquals(0, s.e.active)
        s.nav.pop(); s.settle()
        s.liveChannel = ch("2"); s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.active)
        s.nav.tab = Tab.HOME; s.settle()
        assertEquals(0, s.e.active)
    }

    @Test fun switchingBetweenTheGuideAndLiveTvNeverLeavesTwoPreviews() = runTest {
        val s = Screens(this)
        s.liveChannel = ch("1"); s.guideCell = GuidePreview.of(ch("1"), onNow(ch("1")), now)
        for (t in listOf(Tab.LIVE, Tab.GUIDE, Tab.LIVE, Tab.GUIDE)) {
            // Through the menu, as on the remote: it opens, the tab changes under it, then focus goes into the page.
            s.nav.menuOpen = true; s.settle()
            s.nav.tab = t; s.settle()
            s.nav.menuOpen = false; s.settle()
            // The highlight moves on the new page.
            if (t == Tab.LIVE) s.liveChannel = ch("${s.e.played.size + 10}") else s.guideCell = GuidePreview.Play(ch("${s.e.played.size + 10}"))
            s.settle(); advanceUntilIdle()
            assertTrue(s.e.active <= 1, "one preview player at most")
            assertEquals(if (t == Tab.LIVE) "live" else "guide", s.controller.owner, "the preview belongs to the page in view")
            assertIs<State.Loading>(s.controller.state.value)
        }
    }
}
