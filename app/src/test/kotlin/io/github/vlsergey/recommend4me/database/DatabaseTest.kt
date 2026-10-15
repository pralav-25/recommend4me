package io.github.vlsergey.recommend4me.database

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A file compacted while it is open: what a run of writes left unused is given back, the data kept. */
class DatabaseTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `a file mostly unused is compacted while open, what is used kept`() {
        Database("test", folder.resolve("ratings"), "ratings").use { db ->
            // The old pages are kept for 45 s after a write, what a crash falls back to: used till then.
            // The application compacts every quarter of an hour; the test does not wait
            db.dsl.execute("SET RETENTION_TIME 0")
            db.dsl.execute("CREATE TABLE junk (id INT PRIMARY KEY, data VARBINARY(100000))")
            val random = Random(1)
            // Random bytes: the file's compression gives nothing back of them
            repeat(900) { i -> db.dsl.execute("INSERT INTO junk VALUES (?, ?)", i, random.nextBytes(100_000)) }
            db.dsl.execute("DELETE FROM junk WHERE id >= 10")
            // H2 lets go of the pages of the rows deleted in the background, a checkpoint at a time, slower
            // on a busy machine: till they are let go — of 900 rows 10 kept, a few percent of the pages
            // live — not till the share merely stops falling for a while
            val until = System.currentTimeMillis() + 120_000
            while (db.usage()!!.third >= RELEASED && System.currentTimeMillis() < until) {
                db.dsl.execute("CHECKPOINT")
                Thread.sleep(250)
            }
            // Compacted only when it gives back much: a file of 64 MB or more, mostly unused. A pass cannot
            // move the chunk written last: written late, at the end of the file, it stops the cut there.
            // The application writes on and compacts again in a quarter of an hour — the next chunk written
            // into the space given back near the start — and so the test, a write between the passes
            val (before, first) = assertNotNull(db.compact(60_000), "usage ${db.usage()}")
            assertTrue(before > 64L shl 20, "before $before")
            var after = first
            var pass = 0
            while (after >= before / 2 && pass++ < PASSES) {
                db.dsl.execute("UPDATE junk SET data = ? WHERE id = 0", byteArrayOf(pass.toByte()))
                db.dsl.execute("CHECKPOINT")
                after = db.compact(60_000)?.second ?: after
            }
            assertTrue(after < before / 2, "from $before to $after")
            // What is used is all there, and the file is written to as before
            assertEquals(10, db.dsl.fetchCount(db.dsl.selectFrom("junk")))
            db.dsl.execute("INSERT INTO junk VALUES (?, ?)", 1000, byteArrayOf(1, 2, 3))
            assertEquals(11, db.dsl.fetchCount(db.dsl.selectFrom("junk")))
        }
    }

    @Test
    fun `a small file is not compacted while open`() {
        Database("small", folder.resolve("ratings"), "ratings").use { db -> assertNull(db.compact(1000)) }
    }

    private companion object {
        /** The share of live pages in the file's chunks, percent, below which the deleted rows are let go. */
        const val RELEASED = 20

        /** The passes of compaction after the first, at most: as many as the application makes in an hour and a quarter. */
        const val PASSES = 5
    }
}
