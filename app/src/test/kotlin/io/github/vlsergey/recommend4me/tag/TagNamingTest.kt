package io.github.vlsergey.recommend4me.tag

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TagNamingTest {

    private fun tag(vararg variants: Triple<String, String, IntArray>) = TagStats("1").apply {
        variants.forEach { (source, name, uses) ->
            variant(SiteTagRef(source, name.lowercase()), name).uses.apply {
                works = uses[0]
                graded = uses[1]
                liked = uses[2]
            }
        }
    }

    @Test
    fun `the user's name first, then the name most used by the works liked, graded, all, then the alphabet`() {
        val t = tag(
            Triple("ao3", "Angst", intArrayOf(50, 10, 2)),
            Triple("ficbook", "Ангст", intArrayOf(30, 20, 5)),
            Triple("f95", "angst", intArrayOf(90, 1, 0)),
        )
        assertEquals(ShownName("Тоска", NameRule.EXPLICIT), TagNaming.name(t, "Тоска"))
        assertEquals(ShownName("Ангст", NameRule.LIKED), TagNaming.name(t, null))
        val noneLiked = tag(Triple("ao3", "Angst", intArrayOf(50, 10, 0)), Triple("ficbook", "Ангст", intArrayOf(30, 20, 0)))
        assertEquals(ShownName("Ангст", NameRule.GRADED), TagNaming.name(noneLiked, null))
        val noneGraded = tag(Triple("ao3", "Angst", intArrayOf(50, 0, 0)), Triple("ficbook", "Ангст", intArrayOf(30, 0, 0)))
        assertEquals(ShownName("Angst", NameRule.ALL), TagNaming.name(noneGraded, null))
        // Used equally: the first by the alphabet
        val even = tag(Triple("ao3", "Angst", intArrayOf(5, 0, 0)), Triple("ficbook", "Ангст", intArrayOf(5, 0, 0)))
        assertEquals("Angst", TagNaming.name(even, null).name)
        // A name of two sites is counted together
        val same = tag(Triple("ao3", "Fluff", intArrayOf(3, 0, 0)), Triple("ficbook", "Fluff", intArrayOf(3, 0, 0)), Triple("f95", "Флафф", intArrayOf(5, 0, 0)))
        assertEquals("Fluff", TagNaming.name(same, null).name)
    }

    @Test
    fun `liked is above the middle of the scale`() {
        assertTrue(Tags.liked(5) && Tags.liked(4))
        assertFalse(Tags.liked(3) || Tags.liked(1))
    }
}
