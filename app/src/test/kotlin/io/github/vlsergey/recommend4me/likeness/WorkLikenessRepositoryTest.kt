package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The rerankers' scores kept: read by a work of either side, given new hashes, forgotten with a work. */
class WorkLikenessRepositoryTest {

    @TempDir
    lateinit var folder: Path

    private fun key(id: String) = ItemKey("s", id)

    @Test
    fun `a work's pairs are read by either side, and go with it`() {
        Database("model", folder.resolve("model"), "model").use { db ->
            val repository = WorkLikenessRepository(db.dsl)
            repository.save("r", listOf(PairScore(key("g"), key("a"), 1, 0.5f), PairScore(key("g"), key("b"), 2, 0.6f), PairScore(key("h"), key("a"), 3, 0.7f)))
            repository.keepTexts("r", mapOf(key("a") to WorkText(10, false), key("g") to WorkText(20, true)), emptyList())

            assertEquals(mapOf(key("a") to 1L, key("b") to 2L), repository.hashesOfGraded("r", key("g")))
            assertEquals(mapOf(key("g") to 1L, key("h") to 3L), repository.hashesOfItem("r", key("a")))
            assertEquals(mapOf(key("a") to WorkText(10, false), key("g") to WorkText(20, true)), repository.texts("r"))

            repository.restamp("r", listOf(PairScore(key("g"), key("a"), 7, 0f)))
            assertEquals(mapOf(key("g") to 7L, key("h") to 3L), repository.hashesOfItem("r", key("a")))
            var scores = HashMap<Pair<String, String>, Float>()
            repository.forEach("r") { g, item, score -> scores[g.id to item.id] = score }
            assertEquals(0.5f, scores["g" to "a"], "the score as it was")

            repository.forget(key("a"))
            scores = HashMap()
            repository.forEach("r") { g, item, score -> scores[g.id to item.id] = score }
            assertEquals(mapOf(("g" to "b") to 0.6f), scores)
            assertEquals(mapOf(key("g") to WorkText(20, true)), repository.texts("r"))

            repository.forget(key("g"))
            assertEquals(emptyMap(), repository.hashesOfGraded("r", key("g")))
            assertEquals(emptyMap(), repository.texts("r"))
        }
    }

    @Test
    fun `the texts of works gone from a pass are forgotten, the others kept as they are`() {
        Database("model", folder.resolve("model2"), "model").use { db ->
            val repository = WorkLikenessRepository(db.dsl)
            repository.keepTexts("r", mapOf(key("a") to WorkText(1, false), key("b") to WorkText(2, false)), emptyList())
            repository.keepTexts("r", mapOf(key("a") to WorkText(5, true)), listOf(key("b")))
            assertEquals(mapOf(key("a") to WorkText(5, true)), repository.texts("r"))
        }
    }
}
