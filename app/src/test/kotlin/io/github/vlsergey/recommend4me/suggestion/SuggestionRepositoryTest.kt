package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.draft.DraftRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** The chances of the works as kept: what a refit writes, and what it leaves. */
class SuggestionRepositoryTest {

    @TempDir
    lateinit var folder: Path

    private val now = Instant.parse("2026-10-14T12:00:00Z")

    private fun chance(item: String, key: String, chance: Double) = Chance(item, "tags", key, had = false, chance = chance)

    @Test
    fun `a refit of the whole facet leaves the chances an answer made of a work since it read them`() {
        Database("model", folder.resolve("model"), "model").use { db ->
            val repository = SuggestionRepository(db.dsl, "site", DraftRepository(db.dsl, "site"))
            fun of(item: String) = repository.ofItems(listOf(item), withDrafts = false)[item]?.get("tags")?.mapValues { it.value.chance }
            repository.replace("tags", mapOf("a" to 1L, "b" to 1L), listOf(chance("a", "space", 0.6), chance("b", "space", 0.6)), now)

            // The refit reads the fingerprints, and scores; meanwhile an answer on "a" makes its chances anew
            val read = repository.fingerprints("tags")
            repository.replace("tags", mapOf("a" to 2L), listOf(chance("a", "space", 0.95)), now)
            // The refit writes what it made of the data read before the answer
            repository.replace("tags", mapOf("a" to 1L, "b" to 1L), listOf(chance("a", "space", 0.61), chance("b", "space", 0.7)), now, expected = read)

            assertEquals(mapOf("space" to 0.95), of("a"), "the answer's chances kept")
            assertEquals(2L, repository.fingerprint("a", "tags"))
            assertEquals(mapOf("space" to 0.7), of("b"), "the others written")
        }
    }

    @Test
    fun `chances that come out as they were are not written again`() {
        Database("model", folder.resolve("model2"), "model").use { db ->
            val repository = SuggestionRepository(db.dsl, "site", DraftRepository(db.dsl, "site"))
            repository.replace("tags", mapOf("a" to 1L), listOf(chance("a", "space", 0.6)), now)
            // A refit's noise beyond a ten-thousandth: the row as it was
            repository.replace("tags", mapOf("a" to 1L), listOf(chance("a", "space", 0.600001)), now)
            assertEquals(0.6, repository.ofItems(listOf("a"), withDrafts = false).getValue("a").getValue("tags").getValue("space").chance, 1e-9)
        }
    }
}
