package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.database.Database
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A picture whose download failed: tried again when it is due, never when it cannot be. */
class PictureRepositoryTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `a failed picture waits till it is due, a lasting failure for ever, a download clears it`() {
        Database("test/source", folder.resolve("source"), "source").use { db ->
            db.dsl.execute(
                "INSERT INTO item (item_id, url, title, version, updated_at, first_seen_at) VALUES ('i1', 'u', 't', '', ?, ?)",
                Instant.now(), Instant.now(),
            )
            val pictures = PictureRepository(db.dsl)
            pictures.sync("i1", listOf("https://host/0.png", "https://host/1.png"))
            fun pending() = pictures.pendingOf(listOf("i1"), "enc", emptySet(), 10).map { it.position }
            assertEquals(listOf(0, 1), pending())

            // The cover failed the third time: not tried again till then
            val later = Instant.now().plusSeconds(3600)
            pictures.saveError("i1", 0, "HTTP 503 from https://host/0.png", 3, later)
            // The screenshot is gone: never
            pictures.saveError("i1", 1, "HTTP 404 from https://host/1.png", 1, null)
            assertEquals(emptyList(), pending())
            assertEquals(emptySet(), pictures.itemsWithPending("enc"))
            assertEquals(later.toEpochMilli(), pictures.nextRetry()!!.toEpochMilli())
            assertEquals(3, pictures.find("i1", 0, null)!!.failures)

            // Due: tried again, its failures counted on
            pictures.saveError("i1", 0, "HTTP 503 from https://host/0.png", 3, Instant.now().minusSeconds(1))
            assertEquals(listOf(0), pending())
            assertEquals(setOf("i1"), pictures.itemsWithPending("enc"))

            // Downloaded: the failures are past
            pictures.saveAnalysis("i1", 0, null, "enc", "i1/0-preview.png", null, Instant.now())
            val cover = pictures.find("i1", 0, null)!!
            assertNull(cover.error)
            assertEquals(0, cover.failures)
            assertNull(pictures.nextRetry())

            // A new address is a new picture: the one gone is tried again
            pictures.sync("i1", listOf("https://host/0.png", "https://host/1-new.png"))
            assertEquals(0, pictures.find("i1", 1, null)!!.failures)
            assertEquals(listOf(0, 1), pending())
        }
    }
}
