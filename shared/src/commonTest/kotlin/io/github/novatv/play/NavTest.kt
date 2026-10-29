package io.github.novatv.play

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavTest {
    @Test fun backClosesAnOpenScreenFirst() {
        val nav = Nav()
        nav.push(Route.AddSource)
        assertEquals(Back.POP, nav.back(railFocused = false))
        assertEquals(Back.POP, nav.back(railFocused = true))
        assertTrue(nav.pop())
        assertFalse(nav.pop())
    }

    @Test fun backFromAPageGoesToTheRail() {
        val nav = Nav().apply { tab = Tab.MOVIES }
        assertEquals(Back.TO_RAIL, nav.back(railFocused = false))
    }

    @Test fun backFromTheRailGoesHomeThenLeaves() {
        val nav = Nav().apply { tab = Tab.GUIDE }
        assertEquals(Back.TO_HOME, nav.back(railFocused = true))
        nav.tab = Tab.HOME
        assertEquals(Back.EXIT, nav.back(railFocused = true))
    }

    @Test fun screensStackAndCloseInOrder() {
        val nav = Nav()
        nav.push(Route.AddSource); nav.push(Route.Leagues)
        assertEquals(Route.Leagues, nav.stack.last())
        nav.pop()
        assertEquals(Route.AddSource, nav.stack.last())
    }
}
