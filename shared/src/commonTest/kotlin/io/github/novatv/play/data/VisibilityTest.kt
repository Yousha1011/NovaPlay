package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** Hiding countries, regions and single categories. */
class VisibilityTest {
    private val lib = Library(
        liveCategories = listOf(Category("1", "UK| NEWS"), Category("2", "UK| SPORT"), Category("3", "IT| CINEMA"), Category("4", "Kids")),
        channels = listOf(
            Channel("a", "BBC News", null, "1", null, null, 1),
            Channel("b", "Sky Sports", null, "2", null, null, 2),
            Channel("c", "Rai Cinema", null, "3", null, null, 3),
            Channel("d", "Cartoons", null, "4", null, null, 4),
        ),
        movieCategories = listOf(Category("m1", "EN: Action"), Category("m2", "IT: Commedia")),
        movies = listOf(Movie("x", "X", null, "m1", null, null, null), Movie("y", "Y", null, "m2", null, null, null)),
    )

    @Test fun regionsComeFromCategoryPrefixes() {
        assertEquals("UK", Visibility.region("UK| NEWS"))
        assertEquals("EN", Visibility.region("EN: Action"))
        assertEquals("4K", Visibility.region("4K| RELAX ☼"))
        assertNull(Visibility.region("Kids"))
        assertNull(Visibility.region("Documentaries: nature"), "a word, not a region code")
        assertEquals(listOf("IT" to 2, "UK" to 2, "EN" to 1), Visibility.regions(lib))
    }

    @Test fun hidingARegionHidesItsCategoriesInEverySection() {
        val f = Visibility.filter(lib, "src", setOf(Visibility.regionKey("src", "IT")))
        assertEquals(listOf("1", "2", "4"), f.liveCategories.map { it.id })
        assertEquals(listOf("a", "b", "d"), f.channels.map { it.id })
        assertEquals(listOf("x"), f.movies.map { it.id })
    }

    @Test fun singleCategoriesCanBeHidden() {
        val f = Visibility.filter(lib, "src", setOf(Parental.liveKey("src", "2")))
        assertEquals(listOf("a", "c", "d"), f.channels.map { it.id })
        assertEquals(2, f.movies.size)
    }

    @Test fun nothingHiddenOrAnotherPlaylistsSettingsChangeNothing() {
        assertSame(lib, Visibility.filter(lib, "src", emptySet()))
        assertSame(lib, Visibility.filter(lib, "src", setOf(Visibility.regionKey("other", "UK"))))
    }
}
