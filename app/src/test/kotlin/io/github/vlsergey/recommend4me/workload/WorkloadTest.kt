package io.github.vlsergey.recommend4me.workload

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the queue of the computations tells of the works done, running and planned. */
class WorkloadTest {

    @Test
    fun `a planned work begins with the reasons it was planned for, and is planned no longer`() {
        val workload = Workload()
        val train = Work("train", type = "books")
        workload.plan(train, Instant.now().plusSeconds(3), "grade")
        workload.plan(train, null, "grade")
        workload.plan(train, null, "answer")
        assertEquals(1, workload.planned().size)
        workload.run(train) {
            assertEquals(mapOf("grade" to 2, "answer" to 1), workload.running()!!.reasons)
        }
        assertTrue(workload.planned().isEmpty())
        assertNull(workload.running())
        assertEquals(mapOf("grade" to 2, "answer" to 1), workload.finished().single().reasons)
    }

    @Test
    fun `the last five works done are told, the oldest first, a failed one marked`() {
        val workload = Workload()
        repeat(6) { i -> workload.run(Work("score", type = "t$i"), emptyMap()) {} }
        assertFailsWith<IllegalStateException> { workload.run(Work("score", type = "bad"), emptyMap()) { error("no") } }
        val done = workload.finished()
        assertEquals(listOf("t2", "t3", "t4", "t5", "bad"), done.map { it.work.type })
        assertEquals(listOf(false, false, false, false, true), done.map { it.failed })
    }

    @Test
    fun `a work is expected to take as long as the last run of its kind, of any item`() {
        val workload = Workload()
        assertNull(workload.expected(Work("heldOut", source = "s", item = "1")))
        workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { Thread.sleep(30) }
        val expected = workload.expected(Work("heldOut", source = "s", item = "2"))!!
        assertTrue(expected >= 30, "expected $expected ms")
        assertNull(workload.expected(Work("heldOut", source = "other", item = "2")))
    }

    @Test
    fun `a work inside another goes on and is not told apart`() {
        val workload = Workload()
        workload.run(Work("train", type = "books"), emptyMap()) {
            workload.run(Work("score", type = "books"), emptyMap()) {}
        }
        assertEquals(listOf("train"), workload.finished().map { it.work.what })
    }
}
