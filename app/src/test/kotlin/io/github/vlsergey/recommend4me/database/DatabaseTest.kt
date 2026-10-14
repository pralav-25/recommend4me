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
            // H2 lets go of the rows deleted in the background, a second or two: till the share used stops falling
            val until = System.currentTimeMillis() + 15_000
            var last = -1
            var same = 0
            while (same < 4 && System.currentTimeMillis() < until) {
                Thread.sleep(500)
                val now = db.usage()!!.third
                same = if (now == last) same + 1 else 0
                last = now
            }
            // Compacted only when it gives back much: a file of 64 MB or more, mostly unused
            val (before, after) = assertNotNull(db.compact(60_000), "usage ${db.usage()}")
            assertTrue(before > 64L shl 20, "before $before")
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
}
