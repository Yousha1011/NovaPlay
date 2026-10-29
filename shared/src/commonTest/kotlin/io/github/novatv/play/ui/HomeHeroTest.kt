package io.github.novatv.play.ui

import io.github.novatv.play.data.Movie
import io.github.novatv.play.data.Series
import io.github.novatv.play.data.Store
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class HomeHeroTest {
    private fun movie(id: String) = Movie(id, "Film $id", null, "c", null, null, null)
    private fun series(id: String) = Series(id, "Show $id", null, "c", null, null, null)
    private fun resume(key: String) = Store.Resume(key, key, null, 60_000, 120_000)

    @Test fun continueWatchingComesFirst() {
        val m = movie("1")
        val h = pickHero(listOf(resume("mv:s:1")), { if (it == "mv:s:1") m else null }, { null }, listOf(movie("9")), emptyList())
        assertSame(m, h?.movie)
        assertEquals("Continue watching", h?.label)
    }

    @Test fun episodesResolveToTheirSeries() {
        val s = series("5")
        val h = pickHero(listOf(resume("ep:s:5:77")), { null }, { if (it.startsWith("ep:s:5:")) s else null }, emptyList(), emptyList())
        assertSame(s, h?.series)
    }

    @Test fun titlesNoLongerInThePlaylistAreSkipped() {
        val newest = movie("9")
        val h = pickHero(listOf(resume("mv:s:gone")), { null }, { null }, listOf(newest), emptyList())
        assertSame(newest, h?.movie)
        assertEquals("New movie", h?.label)
    }

    @Test fun fallsBackToNewestSeriesThenNothing() {
        val s = series("3")
        assertSame(s, pickHero(emptyList(), { null }, { null }, emptyList(), listOf(s))?.series)
        assertNull(pickHero(emptyList(), { null }, { null }, emptyList(), emptyList()))
    }

    @Test fun timeLeftReadsNaturally() {
        assertEquals("1h 25m left", timeLeft(85 * 60_000L))
        assertEquals("12m left", timeLeft(12 * 60_000L))
        assertEquals("1m left", timeLeft(5_000L))
    }
}
