package io.github.novatv.play.ui

import io.github.novatv.play.platform.Time
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The small rules behind Settings' new options. */
class SettingsLogicTest {
    @Test fun twentyFourHourAndTwelveHourClocks() {
        assertEquals("20:05", Time.hm(20, 5, h24 = true))
        assertEquals("08:05", Time.hm(8, 5, h24 = true))
        assertEquals("8:05 PM", Time.hm(20, 5, h24 = false))
        assertEquals("8:05 AM", Time.hm(8, 5, h24 = false))
        assertEquals("12:00 AM", Time.hm(0, 0, h24 = false))
        assertEquals("12:30 PM", Time.hm(12, 30, h24 = false))
    }

    @Test fun okMovesAChoiceToTheNextValueAndWrapsRound() {
        val sizes = listOf("SMALL", "MEDIUM", "LARGE")
        assertEquals("LARGE", nextChoice(sizes, "MEDIUM"))
        assertEquals("SMALL", nextChoice(sizes, "LARGE"))
        assertEquals("SMALL", nextChoice(sizes, "unknown"), "an old or unknown value starts again at the first")
    }

    @Test fun adultCategoriesAreRecognisedByName() {
        for (n in listOf("XXX", "Adult", "ADULTS 18+", "EN| XXX Movies", "18+ only", "For Adults")) assertTrue(isAdultName(n), n)
        for (n in listOf("Adventure", "Documentaries", "Kids", "UK| Sky Cinema", "Top 100", "Saxxon"))
            assertFalse(isAdultName(n), n)
    }

    @Test fun onlyAdultCategoriesAreHiddenFromHome() {
        val cats = listOf(io.github.novatv.play.data.Category("1", "Action"), io.github.novatv.play.data.Category("2", "XXX"),
            io.github.novatv.play.data.Category("3", "Adult 18+"))
        assertEquals(setOf("2", "3"), adultCategoryIds(cats))
    }

    @Test fun everyRemoteTipNamesAKeyAndWhatItDoes() {
        assertTrue(TIPS.isNotEmpty())
        for ((_, tips) in TIPS) for ((keys, what) in tips) { assertTrue(keys.isNotBlank()); assertTrue(what.endsWith(".")) }
    }
}
