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
            repository.saveSuggester("tags", "bilinear-1", now, 7L, byteArrayOf(1, 2), listOf("space", "magic:old", "a b"), null, SuggesterFit(null, 10, 3, 3))
            val kept = repository.suggester("tags")!!
            assertEquals(listOf("space", "magic:old", "a b"), kept.values)
            assertEquals(7L, kept.basis)
            assertContentEquals(byteArrayOf(1, 2), kept.content)
            // Fitted again: the new values in place of the old
            repository.saveSuggester("tags", "bilinear-1", now, 8L, byteArrayOf(3), listOf("space"), null, SuggesterFit(null, 10, 3, 1))
            assertEquals(listOf("space"), repository.suggester("tags")!!.values)
            // No scoring kept of it: a page scores the works on the whole task till the next refresh keeps one
            assertNull(repository.scoring("tags"))
            repository.keepValues("tags", listOf("space"), byteArrayOf(9, 8))
            val scoring = repository.scoring("tags")!!
            assertEquals("bilinear-1" to listOf("space"), scoring.suggester to scoring.values)
            assertContentEquals(byteArrayOf(9, 8), scoring.bytes)
        }
    }

    @Test
    fun `the counts of the values' names in a work's texts are kept a line a work, every fitting's in place of the one before`() {
        Database("model", folder.resolve("model"), "model").use { db ->
            val repository = SuggestionRepository(db.dsl, "site")
            repository.keepMentions("characters", mapOf("w1" to mapOf("wikidataQ1" to 3, "original:f" to 1), "w2" to emptyMap()))
            assertEquals(mapOf("w1" to mapOf("wikidataQ1" to 3, "original:f" to 1), "w2" to emptyMap()), repository.mentions("characters", null))
            assertEquals(setOf("w2"), repository.mentions("characters", listOf("w2", "w3")).keys)
            repository.keepMentions("characters", mapOf("w3" to mapOf("x" to 2)))
            assertEquals(mapOf("w3" to mapOf("x" to 2)), repository.mentions("characters", null))
            repository.forget("w3")
            assertEquals(emptyMap(), repository.mentions("characters", null))
        }
    }
}
