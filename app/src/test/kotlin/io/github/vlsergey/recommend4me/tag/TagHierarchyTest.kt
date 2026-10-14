package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.correction.FacetCorrection
import kotlin.test.Test
import kotlin.test.assertEquals

class TagHierarchyTest {

    // слоуберн → романтика → отношения; флафф → романтика
    private val h = TagHierarchy(mapOf("слоуберн" to setOf("романтика"), "романтика" to setOf("отношения"), "флафф" to setOf("романтика")))

    private fun answer(key: String, added: Boolean) = FacetCorrection("w", "tags", key, added, null)

    @Test
    fun `a tag gives the work every tag above it`() {
        assertEquals(listOf("слоуберн", "романтика", "отношения"), h.close(listOf("слоуберн"), emptySet()))
        assertEquals(setOf("романтика", "отношения"), h.ancestors("флафф"))
        assertEquals(setOf("романтика", "слоуберн", "флафф"), h.descendants("отношения"))
    }

    @Test
    fun `a tag the user said the work has not takes every tag under it, over any has`() {
        val corrected = TagHierarchy.corrected(
            mapOf("tags" to listOf("слоуберн", "драма")),
            listOf(answer("флафф", true), answer("романтика", false)),
            mapOf("tags" to h),
        )
        // Not the slow burn nor the fluff, under the romance taken away; not the romance; the relations still not said of
        assertEquals(listOf("драма"), corrected["tags"])
    }

    @Test
    fun `a tag above another is put before it, the order of the rest kept`() {
        // By chance: the slow burn, the drama, the relations, the romance
        val ordered = h.parentsFirst(listOf("слоуберн", "драма", "отношения", "романтика")) { it }
        // The romance and the relations above the slow burn, before it — the relations first, above the romance
        assertEquals(listOf("отношения", "романтика", "слоуберн", "драма"), ordered)
        // Tags unrelated keep their order
        assertEquals(listOf("драма", "флафф"), h.parentsFirst(listOf("драма", "флафф")) { it })
    }

    @Test
    fun `a has of a tag is a has of every tag above it, for the model to learn`() {
        val expanded = h.expand("tags", listOf(answer("слоуберн", true), answer("драма", false)))
        assertEquals(setOf("слоуберн", "романтика", "отношения"), expanded.filter { it.added }.map { it.key }.toSet())
        assertEquals(setOf("драма"), expanded.filter { !it.added }.map { it.key }.toSet())
        val rejected = h.expand("tags", listOf(answer("романтика", false)))
        assertEquals(setOf("романтика", "слоуберн", "флафф"), rejected.filter { !it.added }.map { it.key }.toSet())
    }
}
