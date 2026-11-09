package io.github.novatv.play.ui

import io.github.novatv.play.data.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReturnPlaceTest {
    private fun ch(id: String) = Channel(id, "Channel $id", null, "c", null, null, 1)
    private val all = Group("all", "All channels", listOf(ch("1"), ch("2"), ch("3"), ch("4"), ch("5")))
    private val relax = Group("relax", "Relax", listOf(ch("50"), ch("51")))
    private val sport = Group("sport", "Sport", listOf(ch("4"), ch("5")))
    private val groups = listOf(all, relax, sport)

    @Test fun inTheGroupShowingStaysInIt() =
        assertEquals(ReturnPlace(sport, 1, switched = false), returnPlace(groups, sport, "5", playingId = "5", focusedId = "4"))

    @Test fun outsideTheGroupShowingGoesToAllChannels() =
        assertEquals(ReturnPlace(all, 3, switched = true), returnPlace(groups, relax, "4", playingId = "4", focusedId = "50"))

    @Test fun alreadyOnAllChannelsIsNotASwitch() =
        assertEquals(ReturnPlace(all, 1, switched = false), returnPlace(groups, all, "2", playingId = "2", focusedId = "1"))

    @Test fun noGroupShowingYetUsesAllChannels() =
        assertEquals(ReturnPlace(all, 0, switched = true), returnPlace(groups, null, "1", playingId = "1", focusedId = null))

    @Test fun alreadyHighlightedLeavesThePageAlone() =
        assertNull(returnPlace(groups, all, "2", playingId = "2", focusedId = "2"))

    @Test fun thePreviewMovedOnSinceLeavesThePageAlone() {
        assertNull(returnPlace(groups, all, "2", playingId = "3", focusedId = "1"), "another channel plays now")
        assertNull(returnPlace(groups, all, "2", playingId = null, focusedId = "1"), "the preview stopped")
    }

    @Test fun aChannelThePlaylistNoLongerHasLeavesThePageAlone() {
        assertNull(returnPlace(groups, relax, "99", playingId = "99", focusedId = "50"))
        assertNull(returnPlace(listOf(relax), relax, "4", playingId = "4", focusedId = "50"), "no All channels group")
    }
}
