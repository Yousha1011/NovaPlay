package io.github.novatv.play.data

import io.github.novatv.play.data.PreviewController.State
import kotlin.test.Test
import kotlin.test.assertEquals

/** The TV remote's single OK on a channel: preview it, then pause or play on (a double OK watches it full screen). */
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

    @Test fun okOnThePlayingChannelPausesIt() {
        assertEquals(OkAction.PAUSE, okAction(a, State.Playing(a), true))
    }

    @Test fun stillStartingOrFailedAsksAgain() {
        assertEquals(OkAction.PREVIEW, okAction(a, State.Loading(a), true))
        assertEquals(OkAction.PREVIEW, okAction(a, State.Failed(a, "x"), true))
    }

    @Test fun noPreviewWatchesAtOnce() {
        assertEquals(OkAction.WATCH, okAction(a, State.Unsupported, false))
    }
}
