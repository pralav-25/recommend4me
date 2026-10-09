package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.item.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkLikenessTest {
    private fun key(id: String) = ItemKey("s", id)

    /** Ten works; "liked" graded 5, "disliked" graded 1; "twin" is the same work as "liked" on another site. */
    private val grades = mapOf(key("liked") to 5, key("disliked") to 1)
    private val works = mapOf(key("twin") to key("liked"))
    private fun workOf(k: ItemKey) = (works[k] ?: k).hashCode().toLong()

    private fun likeness(scores: Map<Pair<String, String>, Float>) = WorkLikeness.of(grades, ::workOf) { action ->
        scores.forEach { (pair, s) -> action(key(pair.first), key(pair.second), s) }
    }

    @Test
    fun `a work most alike a graded one is surprising, and lands on the axis of its grade`() {
        // Against "liked": "a" scores above every other work of the catalogue
        val scores = HashMap<Pair<String, String>, Float>()
        (0 until 10).forEach { scores["liked" to "w$it"] = it.toFloat() }
        scores["liked" to "a"] = 100f
        scores["disliked" to "a"] = 0f
        (0 until 10).forEach { scores["disliked" to "w$it"] = it.toFloat() }
        val v = likeness(scores).vector(key("a"))!!
        assertEquals(WorkLikeness.DIM, v.size)
        // Grade 5: axes 8 and 9; grade 1: axes 0 and 1
        assertTrue(v[8] > 2f, "most alike the liked work: ${v.toList()}")
        assertTrue(v[0] < 0.1f, "least alike the disliked one: ${v.toList()}")
        assertEquals(0f, v[9])
    }

    @Test
    fun `a work's own work never counts for it, nor the works under test`() {
        val scores = mapOf(
            ("liked" to "twin") to 100f, ("liked" to "x") to 1f, ("liked" to "y") to 2f,
            ("disliked" to "twin") to 1f, ("disliked" to "x") to 5f, ("disliked" to "y") to 2f,
        )
        val l = likeness(scores)
        // The twin is the liked work itself: only its likeness to the disliked work counts
        val twin = l.vector(key("twin"))!!
        assertEquals(0f, twin[8])
        assertTrue(twin[0] > 0f)
        // With the disliked work under test, x has nothing from it
        val x = l.vector(key("x"), excluded = setOf(key("disliked")))!!
        assertEquals(0f, x[0])
        assertTrue(x[8] > 0f)
        assertNull(l.vector(key("nobody")))
    }
}
