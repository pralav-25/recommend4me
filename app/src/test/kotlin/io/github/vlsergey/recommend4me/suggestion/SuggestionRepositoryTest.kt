package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.Database
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The models of the facets as kept: no chance of any work, only the model and the values it chooses from. */
class SuggestionRepositoryTest {

    @TempDir
    lateinit var folder: Path

    private val now = Instant.parse("2026-10-14T12:00:00Z")

    @Test
    fun `a facet's model is kept with the values it chooses from, in their order`() {
        Database("model", folder.resolve("model"), "model").use { db ->
            val repository = SuggestionRepository(db.dsl, "site")
            assertNull(repository.suggester("tags"))
            repository.saveSuggester("tags", "bilinear-1", now, 7L, byteArrayOf(1, 2), listOf("space", "magic:old", "a b"), SuggesterFit(null, 10, 3, 3))
            val kept = repository.suggester("tags")!!
            assertEquals(listOf("space", "magic:old", "a b"), kept.values)
            assertEquals(7L, kept.basis)
            assertContentEquals(byteArrayOf(1, 2), kept.content)
            // Fitted again: the new values in place of the old
            repository.saveSuggester("tags", "bilinear-1", now, 8L, byteArrayOf(3), listOf("space"), SuggesterFit(null, 10, 3, 1))
            assertEquals(listOf("space"), repository.suggester("tags")!!.values)
        }
    }
}
