package io.github.novatv.play.data

import io.github.novatv.play.data.PreviewController.State
import kotlin.test.Test
import kotlin.test.assertEquals

/** One OK or click on a channel: play it in the window; on the one already there, watch it full screen. */
class OkActionTest {
    private fun ch(id: String) = Channel(id, "Channel $id", null, "c", null, null, 1)
    private val a = ch("a")
    private val b = ch("b")

    @Test fun firstOkPreviews() {
        assertEquals(OkAction.PREVIEW, okAction(a, State.Idle, true))
    }

    @Test fun okOnAnotherChannelPreviewsIt() {
        assertEquals(OkAction.PREVIEW, okAction(b, State.Playing(a), true))
        assertEquals(OkAction.PREVIEW, okAction(b, State.Loading(a), true))
    }

    @Test fun okOnTheChannelInTheWindowWatchesItFullScreen() {
        assertEquals(OkAction.WATCH, okAction(a, State.Playing(a), true))
        assertEquals(OkAction.WATCH, okAction(a, State.Loading(a), true))
        assertEquals(OkAction.WATCH, okAction(a, State.Waiting(a), true))
    }

    @Test fun failedAsksAgain() {
        assertEquals(OkAction.PREVIEW, okAction(a, State.Failed(a, "x"), true))
    }

    @Test fun noPreviewWatchesAtOnce() {
        assertEquals(OkAction.WATCH, okAction(a, State.Unsupported, false))
    }
}
