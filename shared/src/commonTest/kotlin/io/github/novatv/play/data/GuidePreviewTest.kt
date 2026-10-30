package io.github.novatv.play.data

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.novatv.play.data.PreviewController.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Guide's preview: the same controller and player as Live TV, following the highlighted guide cell. */
class GuidePreviewTest {
    private class FakeEngine : PreviewEngine {
        val played = mutableListOf<String>()
        var releases = 0
        var listener: ((PreviewEvent) -> Unit)? = null
        override fun play(url: String, onEvent: (PreviewEvent) -> Unit) { played += url; listener = onEvent }
        override fun release() { releases++; listener = null }
        @Composable override fun Video(modifier: Modifier) {}
    }

    private val now = 1_000_000_000L
    private val hour = 3_600_000L
    private fun ch(id: String) = Channel(id, "Channel $id", null, "c", null, null, 1)
    private fun prog(c: Channel, start: Long, stop: Long, title: String = "Show") = Programme(c.id, start, stop, title, null, null)
    private fun onNow(c: Channel) = prog(c, now - hour / 2, now + hour / 2, "On now")
    private fun later(c: Channel) = prog(c, now + hour, now + 2 * hour, "Later")
    private fun ended(c: Channel) = prog(c, now - 2 * hour, now - hour, "Earlier")

    private fun TestScope.controller(e: PreviewEngine, resolved: MutableList<String> = mutableListOf()) =
        PreviewController(this, e, { c -> resolved += c.id; "http://host/live/${c.id}.ts" }, debounceMs = 700)

    private fun PreviewController.highlight(c: Channel, p: Programme?, visible: Boolean = true) = followGuide(GuidePreview.of(c, p, now), visible)

    @Test fun whatIsOnNowAndChannelTilesPreviewTheChannelButOtherProgrammesDoNot() {
        val c = ch("1")
        assertEquals(GuidePreview.Play(c), GuidePreview.of(c, onNow(c), now))
        assertEquals(GuidePreview.Play(c), GuidePreview.of(c, null, now), "the channel tile, or a cell with no guide data")
        assertIs<GuidePreview.Upcoming>(GuidePreview.of(c, later(c), now))
        assertIs<GuidePreview.Ended>(GuidePreview.of(c, ended(c), now))
        // A programme is on from its start until (not including) its stop.
        assertEquals(GuidePreview.Play(c), GuidePreview.of(c, prog(c, now, now + hour), now))
        assertIs<GuidePreview.Ended>(GuidePreview.of(c, prog(c, now - hour, now), now))
    }

    @Test fun highlightingAProgrammeOnNowPreviewsItsChannelAfterThePause() = runTest {
        val e = FakeEngine(); val p = controller(e)
        val c = ch("7")
        p.highlight(c, onNow(c))
        advanceTimeBy(699); runCurrent()
        assertTrue(e.played.isEmpty())
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("http://host/live/7.ts"), e.played)
        e.listener!!(PreviewEvent.Ready)
        assertEquals(State.Playing(c), p.state.value)
        assertEquals("guide", p.owner)
    }

    @Test fun rapidMovesThroughTheGuideOnlyPreviewTheFinalCell() = runTest {
        val e = FakeEngine(); val resolved = mutableListOf<String>(); val p = controller(e, resolved)
        for (id in listOf("1", "2", "3", "4", "5")) { val c = ch(id); p.highlight(c, onNow(c)); advanceTimeBy(150) }
        // Passing over a programme that isn't on now on the way.
        p.highlight(ch("6"), later(ch("6"))); advanceTimeBy(150)
        p.highlight(ch("8"), null)
        advanceUntilIdle()
        assertEquals(listOf("8"), resolved)
        assertEquals(listOf("http://host/live/8.ts"), e.played)
    }

    @Test fun movingToAnotherProgrammeStopsThePreviousPreview() = runTest {
        val e = FakeEngine(); val p = controller(e)
        val a = ch("1"); val b = ch("2")
        p.highlight(a, onNow(a)); advanceUntilIdle()
        val old = e.listener!!
        val before = e.releases
        p.highlight(b, onNow(b))
        assertEquals(before + 1, e.releases, "the earlier stream is let go at once")
        old(PreviewEvent.Ready)
        assertEquals(State.Waiting(b), p.state.value, "a late event from the earlier channel changes nothing")
        advanceUntilIdle()
        // Along the same row to a later programme: the channel's stream stops and nothing new opens.
        p.highlight(b, later(b)); advanceUntilIdle()
        assertEquals(State.Idle, p.state.value)
        assertEquals(2, e.played.size)
    }

    @Test fun programmesWithoutAPlayableStreamNeverOpenAPlayer() = runTest {
        val e = FakeEngine(); val resolved = mutableListOf<String>(); val p = controller(e, resolved)
        p.highlight(ch("1"), later(ch("1"))); advanceUntilIdle()
        p.highlight(ch("2"), ended(ch("2"))); advanceUntilIdle()
        assertTrue(e.played.isEmpty()); assertTrue(resolved.isEmpty())
        assertEquals(State.Idle, p.state.value)
    }

    @Test fun leavingTheGuideReleasesThePlayer() = runTest {
        val e = FakeEngine(); val p = controller(e)
        val c = ch("1")
        p.highlight(c, onNow(c)); advanceUntilIdle()
        val before = e.releases
        p.highlight(c, onNow(c), visible = false)
        assertEquals(before + 1, e.releases)
        assertEquals(State.Idle, p.state.value)
        // Hidden while still waiting: the pending start never happens.
        p.highlight(ch("2"), onNow(ch("2"))); p.highlight(ch("2"), onNow(ch("2")), visible = false); advanceUntilIdle()
        assertEquals(1, e.played.size)
    }

    @Test fun liveTvBeingHiddenDoesNotStopTheGuidesPreviewAndViceVersa() = runTest {
        val e = FakeEngine(); val p = controller(e)
        p.highlight(ch("1"), onNow(ch("1"))); advanceUntilIdle()
        p.stop("live")
        assertEquals(State.Loading(ch("1")), p.state.value, "Live TV going out of view leaves the Guide's preview alone")
        p.select(ch("2"), "live"); advanceUntilIdle()
        p.followGuide(null, visible = false)
        assertEquals(State.Loading(ch("2")), p.state.value, "the Guide going out of view leaves Live TV's preview alone")
    }

    @Test fun okStopsThePreviewBeforeFullScreenAndLateEventsAreIgnored() = runTest {
        // play() calls stop() before the full-screen player opens; this is what that stop guarantees.
        val e = FakeEngine(); val p = controller(e)
        val c = ch("1")
        p.highlight(c, onNow(c)); advanceUntilIdle()
        val old = e.listener!!
        val before = e.releases
        p.stop()
        assertEquals(before + 1, e.releases)
        old(PreviewEvent.Ready)
        assertEquals(State.Idle, p.state.value)
        p.highlight(c, onNow(c)); advanceUntilIdle()
        assertEquals(2, e.played.size, "highlighting the cell again previews it again")
    }

    @Test fun previewErrorsStayOutOfTheCatalogueAndGuideErrors() = runTest {
        val e = FakeEngine(); val p = controller(e)
        val lib = MutableStateFlow(Repo.LibState(error = "Playlist error", epgStatus = "Guide failed"))
        val c = ch("1")
        p.highlight(c, onNow(c)); advanceUntilIdle()
        e.listener!!(PreviewEvent.Error("Could not reach the server"))
        assertEquals(State.Failed(c, "Could not reach the server"), p.state.value)
        assertEquals("Playlist error", lib.value.error)
        assertEquals("Guide failed", lib.value.epgStatus)
    }
}
