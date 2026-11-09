package io.github.novatv.play.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.github.novatv.play.App
import io.github.novatv.play.data.Channel

/**
 * Lines a page's channel list up with the channel full screen handed back (it may have changed channel there): the
 * list scrolls to that channel, the page's highlight and header follow it, and focus moves to its row once focus is in
 * the list. Each hand back is handled once per page; a page hidden at the time catches up when it is next shown.
 * Nothing here plays anything: the highlight lands on the channel already playing, which the preview leaves as it is.
 */
class ReturnSync {
    internal val requester = FocusRequester()
    /** The row focus should move to, until it gets there. */
    internal var targetId by mutableStateOf<String?>(null)
    /** Whether focus is somewhere in the page's list area (on Live TV, its groups and channels). */
    internal var listFocused by mutableStateOf(false)
    /** The group switched to for the return ("all"), whose tile focus passes through so the group column remembers it. */
    internal val groupRequester = FocusRequester()
    internal var groupTarget by mutableStateOf<String?>(null)
    /** The last hand back this page dealt with (-1: none yet). */
    internal var handled by mutableIntStateOf(-1)

    /** On the list area (the channel list, or groups and channels together): tracks whether focus is in it. */
    fun list(m: Modifier): Modifier = m.onFocusChanged { listFocused = it.hasFocus }

    /** On a group's tile (pages with a group column): the group switched to takes focus once, on the way to the row. */
    fun group(m: Modifier, id: String): Modifier = if (groupTarget != id) m else m.focusRequester(groupRequester)

    /** On a channel's focusable tile: the target row takes focus when asked, and stops being the target once it has. */
    fun row(m: Modifier, id: String): Modifier =
        if (targetId != id) m else m.focusRequester(requester).onFocusChanged { if (it.isFocused) targetId = null }
}

@Composable
fun rememberReturnSync(): ReturnSync = remember { ReturnSync() }

/**
 * Follows hand backs for one page. [groups] and [current] are the page's groups and the one showing; [focusedId] the
 * channel highlighted now; [select] shows another group; [highlight] sets the page's highlighted channel.
 */
@Composable
fun ReturnSync.Follow(visible: Boolean, groups: List<Group>, current: Group?, focusedId: String?, list: LazyListState,
                      select: (String) -> Unit, highlight: (Channel) -> Unit) {
    val back = App.preview.returned
    LaunchedEffect(back?.seq, visible, groups.isEmpty()) {
        if (back == null || !visible || groups.isEmpty() || back.seq == handled) return@LaunchedEffect
        handled = back.seq
        val id = back.channel.id
        // Moved on since (another channel, or stopped): nothing to line up with.
        if (App.preview.channel?.id != id) return@LaunchedEffect
        if (focusedId == id) return@LaunchedEffect
        var g = current
        var i = g?.channels?.indexOfFirst { it.id == id } ?: -1
        if (i < 0) {
            // Not in the group showing: All channels, if it is there (a channel the playlist no longer has is left).
            g = groups.firstOrNull { it.id == "all" } ?: return@LaunchedEffect
            i = g.channels.indexOfFirst { it.id == id }
            if (i < 0) return@LaunchedEffect
            select(g.id)
            groupTarget = g.id
            withFrameNanos { }
        }
        highlight((g ?: return@LaunchedEffect).channels[i])
        targetId = id
        list.scrollToItem(i)
    }
    LaunchedEffect(targetId, listFocused) {
        if (targetId == null || !listFocused) return@LaunchedEffect
        withFrameNanos { }
        // Through the new group's tile first (if the page has a group column), so Left from the row comes back to it
        // rather than to the group focus was last on.
        if (groupTarget != null) {
            try { groupRequester.requestFocus() } catch (e: Exception) { }
            groupTarget = null
            withFrameNanos { }
        }
        try { requester.requestFocus() } catch (e: Exception) { }
    }
}
