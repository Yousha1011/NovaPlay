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
 * One TV window is shared by Live TV, the TV guide and Sports: the menu, a screen opened over the page or another of
 * those tabs leaves the channel playing, and arriving on a page keeps it until the user moves to another channel.
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
        val live = PagePreview(controller, "tv")
        val guide = PagePreview(controller, "tv")
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

    @Test fun openingTheMenuKeepsTheChannelPlaying() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.LIVE }
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        s.nav.menuOpen = true; s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.active)
        assertEquals("1", s.controller.channel?.id)
        s.nav.menuOpen = false; s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.played.size, "closing the menu doesn't restart it")
    }

    @Test fun switchingTabsKeepsTheChannelUntilTheUserMoves() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.LIVE }
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        // Over to the guide, whose highlight lands on another channel: the first one keeps playing.
        s.nav.tab = Tab.GUIDE
        s.guideCell = GuidePreview.of(ch("7"), onNow(ch("7")), now); s.settle(); advanceUntilIdle()
        assertEquals("1", s.controller.channel?.id)
        assertEquals(1, s.e.played.size)
        // Moving in the guide switches the window to what is highlighted.
        s.guideCell = GuidePreview.of(ch("8"), onNow(ch("8")), now); s.settle(); advanceUntilIdle()
        assertEquals("http://host/live/8.ts", s.e.played.last())
        // Back on Live TV, still channel 8.
        s.nav.tab = Tab.LIVE; s.settle(); advanceUntilIdle()
        assertEquals("8", s.controller.channel?.id)
    }

    @Test fun aProgrammeThatIsNotOnNowLeavesTheChannelPlaying() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.GUIDE }
        s.guideCell = GuidePreview.of(ch("7"), onNow(ch("7")), now); s.settle(); advanceUntilIdle()
        s.guideCell = GuidePreview.Upcoming(Programme("8", now + 7_200_000, now + 9_000_000, "Later", null, null)); s.settle(); advanceUntilIdle()
        assertEquals("7", s.controller.channel?.id)
        assertEquals(1, s.e.active)
    }

    @Test fun screensOpenedOverAPageKeepTheChannelPlaying() = runTest {
        val s = Screens(this).apply { nav.tab = Tab.LIVE }
        s.liveChannel = ch("1"); s.settle(); advanceUntilIdle()
        s.nav.push(Route.AddSource); s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.active)
        s.nav.pop(); s.settle(); advanceUntilIdle()
        assertEquals(1, s.e.played.size)
    }

    @Test fun switchingBetweenTheGuideAndLiveTvNeverLeavesTwoPreviews() = runTest {
        val s = Screens(this)
        s.liveChannel = ch("1"); s.guideCell = GuidePreview.of(ch("1"), onNow(ch("1")), now)
        for (t in listOf(Tab.LIVE, Tab.GUIDE, Tab.LIVE, Tab.GUIDE)) {
            s.nav.menuOpen = true; s.settle()
            s.nav.tab = t; s.settle()
            s.nav.menuOpen = false; s.settle()
            if (t == Tab.LIVE) s.liveChannel = ch("${s.e.played.size + 10}") else s.guideCell = GuidePreview.Play(ch("${s.e.played.size + 10}"))
            s.settle(); advanceUntilIdle()
            assertTrue(s.e.active <= 1, "one preview player at most")
            assertEquals("tv", s.controller.owner, "one window shared by the TV tabs")
        }
    }
}
