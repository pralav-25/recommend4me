package io.github.vlsergey.recommend4me.workload

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
    fun `a request of the browser never waits for a computation, nor runs one, where a thread of the background does`() {
        val workload = Workload()
        val ran = AtomicInteger()
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest("GET", "/api/items/s/1/held-out")))
        try {
            assertFailsWith<IllegalStateException> { workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() } }
            assertEquals(0, ran.get())
            assertTrue(workload.waiting().isEmpty())
            // The request's attributes are not passed to a thread it starts: the background runs it
            inBackground { workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() } }
            assertEquals(1, ran.get())
        } finally {
            RequestContextHolder.resetRequestAttributes()
        }
        workload.run(Work("heldOut", source = "s", item = "1"), emptyMap()) { ran.incrementAndGet() }
        assertEquals(2, ran.get())
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
    fun `a work given up for newer data is no failure, and leaves the estimate of its kind as it was`() {
        val workload = Workload()
        val suggester = Work("suggester", source = "s", facet = "universe")
        workload.run(suggester, emptyMap()) { Thread.sleep(30) }
        val estimate = workload.expected(suggester)
        assertFailsWith<CancellationException> { workload.run(suggester, emptyMap()) { throw CancellationException("newer changes") } }
        val last = workload.finished().last()
        assertEquals(false to true, last.failed to last.givenUp)
        assertEquals(estimate, workload.expected(suggester))
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
    fun `works alike asked while one waits are that one, its reasons summed`() {
        val workload = Workload()
        val held = Work("held", item = "1")
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val blocker = thread { workload.run(Work("train", type = "t"), emptyMap()) { started.countDown(); release.await() } }
        started.await()
        val runs = AtomicInteger()
        val answers = (1..3).map { i -> CompletableFuture.supplyAsync { workload.run(held, mapOf("page" to 1)) { runs.incrementAndGet(); "answer" } } }
        while (workload.waiting().singleOrNull()?.reasons?.get("page") != 3) Thread.sleep(5)
        // A batch of its own is not joined
        val own = CompletableFuture.supplyAsync { workload.run(held, mapOf("items" to 1), joinAlike = false) { runs.incrementAndGet(); "own" } }
        while (workload.waiting().size < 2) Thread.sleep(5)
        release.countDown()
        blocker.join()
        assertEquals(listOf("answer", "answer", "answer"), answers.map { it.get(5, TimeUnit.SECONDS) })
        assertEquals("own", own.get(5, TimeUnit.SECONDS))
        assertEquals(2, runs.get())
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
