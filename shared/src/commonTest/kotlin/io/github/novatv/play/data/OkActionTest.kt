package io.github.novatv.play.data

import io.github.novatv.play.data.PreviewController.State
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TV remote's OK on a channel: preview first, watch on the second press. */
class OkActionTest {
    private fun ch(id: String) = Channel(id, "Channel $id", null, "c", null, null, 1)
    private val a = ch("a")
    private val b = ch("b")

    @Test fun firstOkPreviewsWhenNothingShows() {
        assertEquals(OkAction.PREVIEW, okAction(a, State.Idle, true))
    }

    @Test fun okOnAnotherChannelPreviewsIt() {
        assertEquals(OkAction.PREVIEW, okAction(b, State.Playing(a), true))
        assertEquals(OkAction.PREVIEW, okAction(b, State.Loading(a), true))
        assertEquals(OkAction.PREVIEW, okAction(b, State.Failed(a, "x"), true))
    }

    @Test fun secondOkOnTheShownChannelWatches() {
        assertEquals(OkAction.WATCH, okAction(a, State.Playing(a), true))
        assertEquals(OkAction.WATCH, okAction(a, State.Loading(a), true))
        assertEquals(OkAction.WATCH, okAction(a, State.Waiting(a), true))
    }

    @Test fun failedPreviewGoesFullScreen() {
        assertEquals(OkAction.WATCH, okAction(a, State.Failed(a, "x"), true))
    }

    @Test fun noPreviewWatchesAtOnce() {
        assertEquals(OkAction.WATCH, okAction(a, State.Unsupported, false))
        assertEquals(OkAction.WATCH, okAction(a, State.Idle, false))
    }
}
