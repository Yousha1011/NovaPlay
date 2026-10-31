package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What parental controls hide, and the PIN's checks. */
class ParentalTest {
    private val lib = Library(
        liveCategories = listOf(Category("1", "News"), Category("2", "XXX Live"), Category("3", "Kids")),
        channels = listOf(
            Channel("a", "BBC News", null, "1", null, null, 1),
            Channel("b", "Late night", null, "2", null, null, 2),
            Channel("c", "Cartoons", null, "3", null, null, 3),
            Channel("d", "Horror 24/7", null, "3", null, null, 4),
        ),
        movieCategories = listOf(Category("m1", "Action"), Category("m2", "Adults 18+")),
        movies = listOf(movie("x", "m1"), movie("y", "m2")),
        seriesCategories = listOf(Category("s1", "Drama"), Category("s2", "Thrillers")),
        series = listOf(series("p", "s1"), series("q", "s2")),
    )
    private fun movie(id: String, cat: String) = Movie(id, "Film $id", null, cat, null, null, null)
    private fun series(id: String, cat: String) = Series(id, "Show $id", null, cat, null, null, null)
    private fun key(c: Channel) = "ch:src:${c.id}"
    private fun rules(on: Boolean = true, adult: Boolean = false, cats: Set<String> = emptySet(), chans: Set<String> = emptySet()) =
        Parental.Rules(on, adult, cats, chans)

    @Test fun nothingBlockedReturnsTheSameLibrary() {
        assertSame(lib, Parental.filter(lib, "src", rules(), ::key))
        assertSame(lib, Parental.filter(lib, "src", rules(on = false, adult = true, cats = setOf("src|l:1")), ::key), "controls off")
    }

    @Test fun blockedCategoriesHideTheirChannelsFilmsAndSeries() {
        val f = Parental.filter(lib, "src", rules(cats = setOf(Parental.liveKey("src", "1"), Parental.seriesKey("src", "s2"))), ::key)
        assertEquals(listOf("2", "3"), f.liveCategories.map { it.id })
        assertEquals(listOf("b", "c", "d"), f.channels.map { it.id })
        assertEquals(listOf("p"), f.series.map { it.id })
        assertEquals(listOf("x", "y"), f.movies.map { it.id }, "films untouched")
    }

    @Test fun adultCategoriesAreBlockedByName() {
        val f = Parental.filter(lib, "src", rules(adult = true), ::key)
        assertEquals(listOf("a", "c", "d"), f.channels.map { it.id })
        assertEquals(listOf("x"), f.movies.map { it.id })
        assertEquals(listOf("m1"), f.movieCategories.map { it.id })
    }

    @Test fun singleChannelsCanBeBlocked() {
        val f = Parental.filter(lib, "src", rules(chans = setOf("ch:src:d")), ::key)
        assertEquals(listOf("a", "b", "c"), f.channels.map { it.id })
        assertEquals(3, f.liveCategories.size, "its category stays")
    }

    @Test fun blocksBelongToTheirOwnPlaylist() {
        val other = rules(cats = setOf(Parental.liveKey("other", "1")), chans = setOf("ch:other:a"))
        assertSame(lib, Parental.filter(lib, "src", other, ::key))
    }

    @Test fun pinChecks() {
        assertTrue(Parental.validPin("1234")); assertTrue(Parental.validPin("12345678"))
        assertFalse(Parental.validPin("123")); assertFalse(Parental.validPin("12a4")); assertFalse(Parental.validPin("123456789"))
        assertEquals(Parental.hash("2468"), Parental.hash("2468"))
        assertNotEquals(Parental.hash("2468"), Parental.hash("2469"))
        assertFalse(Parental.hash("2468").contains("2468"), "the PIN itself isn't kept")
    }
}
