package io.github.vlsergey.recommend4me.database

import org.h2.engine.SessionLocal
import org.h2.jdbc.JdbcConnection
import org.jooq.impl.DSL
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
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
            // The rows written last kept: their chunk at the end of the file, H2 cannot merely cut the
            // free space after it off — what is given back is given by the moving of the chunks
            db.dsl.execute("DELETE FROM junk WHERE id < 890")
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
                db.dsl.execute("UPDATE junk SET data = ? WHERE id = 899", byteArrayOf(pass.toByte()))
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
    fun `bytes are written as bytes, no LOB of them left in the file`() {
        Database("bytes", folder.resolve("ratings"), "ratings").use { db ->
            db.dsl.execute("CREATE TABLE junk (id INT PRIMARY KEY, data VARBINARY(100000))")
            val random = Random(2)
            val written = List(5) { random.nextBytes(100_000) }
            written.forEachIndexed { i, bytes -> db.dsl.execute("INSERT INTO junk VALUES (?, ?)", i, bytes) }
            db.dsl.insertInto(DSL.table("junk")).values(5, written[0]).execute()
            db.dsl.batch("INSERT INTO junk VALUES (?, ?)").bind(6, written[1]).bind(7, written[2]).execute()
            db.dsl.transaction { c -> DSL.using(c).execute("UPDATE junk SET data = ? WHERE id = 4", written[3]) }
            // Neither the LOBs of a session nor those of a table: H2's own maps of them all empty
            val lobs = db.dsl.connectionResult { c ->
                val mv = (c.unwrap(JdbcConnection::class.java).session as SessionLocal).database.store.mvStore
                mv.mapNames.filter { it.startsWith("lob") || it.startsWith("tempLob") }.associateWith { mv.openMap<Any, Any>(it).sizeAsLong() }
            }
            assertTrue(lobs.isNotEmpty() && lobs.values.all { it == 0L }, "lobs $lobs")
            val read = db.dsl.fetch("SELECT id, data FROM junk ORDER BY id").associate { it.get(0, Int::class.java) to it.get(1, ByteArray::class.java) }
            // Every row's bytes as written: the one updated of its new ones
            val expected = listOf(0, 1, 2, 3, 3, 0, 1, 2).map { written[it] }
            assertEquals((0..7).toList(), read.keys.sorted())
            (0..7).forEach { id -> assertTrue(read.getValue(id).contentEquals(expected[id]), "row $id") }
        }
    }

    @Test
    fun `a file mostly unused is written anew before it is opened, what is used kept`() {
        val file = folder.resolve("ratings")
        Database("test", file, "ratings").use { }
        // Closed without compacting: the file as a run of writes leaves it
        DriverManager.getConnection("jdbc:h2:file:${file.toAbsolutePath()};COMPRESS=TRUE;MAX_COMPACT_TIME=0", "sa", "").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE junk (id INT PRIMARY KEY, data VARBINARY(100000))") }
            val random = Random(3)
            c.prepareStatement("INSERT INTO junk VALUES (?, ?)").use { s ->
                repeat(900) { i -> s.setInt(1, i); s.setBytes(2, random.nextBytes(100_000)); s.executeUpdate() }
            }
            c.createStatement().use { it.execute("DELETE FROM junk WHERE id < 890") }
        }
        // What is kept, read before: byte for byte the same after
        val kept = DriverManager.getConnection("jdbc:h2:file:${file.toAbsolutePath()};MAX_COMPACT_TIME=0", "sa", "").use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT id, data FROM junk ORDER BY id").use { r -> generateSequence { if (r.next()) r.getInt(1) to r.getBytes(2) else null }.toList() }
            }
        }
        val mvFile = folder.resolve("ratings.mv.db")
        val before = Files.size(mvFile)
        assertTrue(before > 64L shl 20, "before $before")
        Database("test", file, "ratings").use { db ->
            val after = Files.size(mvFile)
            assertTrue(after < before / 2, "from $before to $after")
            val read = db.dsl.fetch("SELECT id, data FROM junk ORDER BY id").map { it.get(0, Int::class.java) to it.get(1, ByteArray::class.java) }
            assertEquals((890..899).toList(), kept.map { it.first })
            assertEquals(kept.map { it.first }, read.map { it.first })
            kept.zip(read).forEach { (k, r) -> assertTrue(k.second.size == 100_000 && k.second.contentEquals(r.second), "row ${k.first}: ${k.second.size} and ${r.second.size} bytes") }
        }
        // Written anew once: of a file mostly used, nothing
        val rewritten = Files.size(mvFile)
        Database("test", file, "ratings").use { }
        assertTrue(Files.size(mvFile) <= rewritten + (1L shl 20), "again ${Files.size(mvFile)}, was $rewritten")
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
