package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals

/** Multi-view's grid and reconnect timing, the same on every platform. */
class MultiViewTest {
    @Test fun gridsForTwoToFourScreens() {
        assertEquals(2 to 1, MultiView.grid(2))
        assertEquals(2 to 2, MultiView.grid(3))
        assertEquals(2 to 2, MultiView.grid(4))
        assertEquals(4, MultiView.MAX)
    }

    @Test fun reconnectsSoonThenBacksOff() {
        assertEquals(2_000L, MultiView.retryDelay(1))
        assertEquals(6_000L, MultiView.retryDelay(3))
        assertEquals(10_000L, MultiView.retryDelay(9))
    }
}
