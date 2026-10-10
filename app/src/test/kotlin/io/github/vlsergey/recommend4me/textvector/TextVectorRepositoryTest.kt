package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_VECTOR
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class TextVectorRepositoryTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `the same text saved from two threads at once is kept once`() {
        Database("test", folder.resolve("source"), "source").use { db ->
            val now = Instant.now()
            db.dsl.insertInto(ITEM)
                .set(ITEM.ITEM_ID, "p1").set(ITEM.URL, "https://example.org/p1").set(ITEM.TITLE, "p1").set(ITEM.VERSION, "")
                .set(ITEM.UPDATED_AT, now).set(ITEM.FIRST_SEEN_AT, now)
                .execute()
            val vectors = TextVectorRepository(db.dsl)
            val threads = 2
            val pool = Executors.newFixedThreadPool(threads)
            try {
                // Every round starts from no row, and both threads are let go at once: the MERGEs of a
                // key not yet stored are what raced
                repeat(ROUNDS) { round ->
                    vectors.delete("p1", "annotation")
                    val start = CyclicBarrier(threads)
                    val saves = (0 until threads).map { t ->
                        pool.submit {
                            start.await()
                            vectors.save("p1", "annotation", "e", "h$round-$t", FloatArray(4) { t.toFloat() })
                        }
                    }
                    saves.forEach { it.get(10, TimeUnit.SECONDS) }
                }
            } finally {
                pool.shutdownNow()
            }
            assertEquals(1, db.dsl.fetchCount(TEXT_VECTOR))
            assertEquals(setOf("p1" to "annotation"), vectors.hashes("e").keys)
        }
    }

    companion object {
        private const val ROUNDS = 200
    }
}
