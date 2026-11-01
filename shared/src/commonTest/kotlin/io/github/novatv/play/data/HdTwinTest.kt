package io.github.novatv.play.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding the HD version of a 4K channel, from names as the user's playlist writes them. */
class HdTwinTest {
    private fun ch(id: String, name: String) = Channel(id, name, null, "c", null, null, 0)

    private val all = listOf(
        ch("1", "4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"),
        ch("2", "UK: SKY SPORTS F1 ᴴᴰ ◉"),
        ch("3", "UK: SKY SPORTS F1 SD"),
        ch("4", "UK: SKY SPORTS F1 HEVC 4K"),
        ch("5", "4K: ELEVEN SPORTS 1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"),
        ch("6", "PL: ELEVEN SPORTS 1 ᴴᴰ"),
        ch("7", "PL: ELEVEN SPORTS 1 ᴿᴬᵂ"),
        ch("8", "4K: V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ"),
        ch("9", "NO V SPORT 1 ᴿᴬᵂ ⱽᴵᴾ"),
        ch("10", "UK: BBC ONE HD"),
    )

    @Test fun namesReduceToTheChannel() {
        assertEquals("SKY SPORTS F1", HdTwin.core("4K: SKY SPORTS F1 ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertEquals("SKY SPORTS F1", HdTwin.core("UK: SKY SPORTS F1 ᴴᴰ ◉"))
        assertTrue(HdTwin.is4k("4K: V SPORT ᵁᴴᴰ ³⁸⁴⁰ᴾ"))
        assertTrue(HdTwin.is4k("UK: SKY SPORTS F1 HEVC 4K"))
        assertFalse(HdTwin.is4k("UK: SKY SPORTS F1 ᴴᴰ ◉"))
    }

    @Test fun picksTheHdVersion() {
        assertEquals("2", HdTwin.of(all[0], all)?.id)
        assertEquals("6", HdTwin.of(all[4], all)?.id)
    }

    @Test fun noTwinMeansKeepTheChannel() {
        assertNull(HdTwin.of(all[7], all), "no V SPORT in HD under that name")
        assertNull(HdTwin.of(all[9], all), "already HD")
    }
}
