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

class PreviewControllerTest {
    private class FakeEngine : PreviewEngine {
        val played = mutableListOf<String>()
        var releases = 0
        var listener: ((PreviewEvent) -> Unit)? = null
        override fun play(url: String, onEvent: (PreviewEvent) -> Unit) { played += url; listener = onEvent }
        override fun release() { releases++; listener = null }
        @Composable override fun Video(modifier: Modifier) {}
    }

    private fun ch(id: String) = Channel(id, "Channel $id", null, "c", null, null, 1)

    private fun TestScope.controller(engine: PreviewEngine?, enabled: () -> Boolean = { true }, resolved: MutableList<String> = mutableListOf(),
                                     resolve: suspend (Channel) -> String? = { c -> resolved += c.id; "http://host/live/${c.id}.ts" }) =
        PreviewController(this, engine, resolve, enabled, debounceMs = 700)

    @Test fun selectingAChannelPlaysItsAddressAfterThePause() = runTest {
        val e = FakeEngine()
        val p = controller(e)
        p.select(ch("1"))
        assertIs<State.Waiting>(p.state.value)
        advanceTimeBy(699); runCurrent()
        assertTrue(e.played.isEmpty(), "nothing opens before the highlight settles")
        advanceTimeBy(2); runCurrent()
        assertEquals(listOf("http://host/live/1.ts"), e.played)
        assertIs<State.Loading>(p.state.value)
        e.listener!!(PreviewEvent.Ready)
        assertEquals(State.Playing(ch("1")), p.state.value)
    }

    @Test fun rapidMovesOnlyOpenTheLastChannel() = runTest {
        val e = FakeEngine()
        val resolved = mutableListOf<String>()
        val p = controller(e, resolved = resolved)
        for (id in listOf("1", "2", "3", "4")) { p.select(ch(id)); advanceTimeBy(200) }
        advanceUntilIdle()
        assertEquals(listOf("4"), resolved, "addresses are only worked out for the channel the highlight stopped on")
        assertEquals(listOf("http://host/live/4.ts"), e.played)
    }

    @Test fun switchingChannelsStopsThePreviousPreviewAndIgnoresItsLateEvents() = runTest {
        val e = FakeEngine()
        val p = controller(e)
        p.select(ch("1")); advanceUntilIdle()
        val first = e.listener!!
        val releasesBefore = e.releases
        p.select(ch("2"))
        assertEquals(releasesBefore + 1, e.releases, "the earlier stream is let go at once")
        first(PreviewEvent.Ready)
        assertEquals(State.Waiting(ch("2")), p.state.value, "a late event from channel 1 changes nothing")
        advanceUntilIdle()
        assertEquals("http://host/live/2.ts", e.played.last())
    }

    @Test fun reselectingTheSameChannelDoesNotRestartIt() = runTest {
        val e = FakeEngine()
        val p = controller(e)
        p.select(ch("1")); advanceUntilIdle()
        p.select(ch("1")); advanceUntilIdle()
        assertEquals(1, e.played.size)
    }

    @Test fun stoppingReleasesThePlayerAndReturnsToIdle() = runTest {
        val e = FakeEngine()
        val p = controller(e)
        p.select(ch("1")); advanceUntilIdle()
        p.stop()
        assertEquals(State.Idle, p.state.value)
        assertTrue(e.releases >= 1)
        // Stopped while waiting: the pending start never happens.
        p.select(ch("2")); p.stop(); advanceUntilIdle()
        assertEquals(1, e.played.size)
    }

    @Test fun errorsStayInThePreviewAndCanBeRetried() = runTest {
        val e = FakeEngine()
        val catalogue = MutableStateFlow(Repo.LibState(error = "Playlist error"))
        val p = controller(e)
        p.select(ch("1")); advanceUntilIdle()
        e.listener!!(PreviewEvent.Error("Could not reach the server"))
        assertEquals(State.Failed(ch("1"), "Could not reach the server"), p.state.value)
        assertEquals("Playlist error", catalogue.value.error, "the catalogue's own error is untouched")
        p.select(ch("1")); advanceUntilIdle()
        assertEquals(2, e.played.size, "highlighting a failed channel again retries it")
    }

    @Test fun noAddressIsAPreviewFailure() = runTest {
        val e = FakeEngine()
        val p = controller(e, resolve = { null })
        p.select(ch("1")); advanceUntilIdle()
        assertEquals(State.Failed(ch("1"), "Could not get the stream address"), p.state.value)
        assertTrue(e.played.isEmpty())
    }

    @Test fun withoutAnEngineOrWhenTurnedOffNothingPlays() = runTest {
        val none = controller(null)
        none.select(ch("1")); advanceUntilIdle()
        assertEquals(State.Unsupported, none.state.value)

        val e = FakeEngine()
        var on = false
        val off = controller(e, enabled = { on })
        off.select(ch("1")); advanceUntilIdle()
        assertEquals(State.Unsupported, off.state.value)
        assertTrue(e.played.isEmpty())
        on = true
        off.select(ch("1")); advanceUntilIdle()
        assertEquals(1, e.played.size)
    }
}
