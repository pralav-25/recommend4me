package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.search.SearchJudgmentRepository
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A type's corrections of before moved: its links to the sources' corrections, its search judgments to the preferences. */
class TypeCorrectionsMoveTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `the links and judgments of a type are moved, the file kept aside`() {
        val old = folder.resolve("types/books/corrections")
        Files.createDirectories(old.parent)
        Database("books/corrections", old, "typecorrections").use { db ->
            db.dsl.execute("INSERT INTO item_link VALUES ('a', '1', 'b', '2', CURRENT_TIMESTAMP)")
            db.dsl.execute("INSERT INTO search_judgment VALUES ('space', 'b', '2', TRUE, CURRENT_TIMESTAMP)")
        }
        val a = Database("a/corrections", folder.resolve("a"), "corrections", placeholders = mapOf("source" to "a"))
        val b = Database("b/corrections", folder.resolve("b"), "corrections", placeholders = mapOf("source" to "b"))
        val preferences = Database("preferences", folder.resolve("preferences"), "preferences")
        val dictionaries = Database("dictionaries", folder.resolve("dictionaries"), "dictionaries")
        listOf(a, b, preferences, dictionaries).use {
            val corrections = mapOf("a" to a.dsl, "b" to b.dsl)
            TypeCorrectionsMove.move("books", old, corrections, preferences.dsl, dictionaries.dsl)
            assertFalse(Files.exists(folder.resolve("types/books/corrections.mv.db")))
            assertTrue(Files.exists(folder.resolve("types/books/corrections.mv.db.moved")))
            // Started again: nothing more to move
            TypeCorrectionsMove.move("books", old, corrections, preferences.dsl, dictionaries.dsl)

            val links = TypeLinkRepository(corrections)
            assertEquals(listOf(ItemKey("a", "1") to ItemKey("b", "2")), links.links())
            assertEquals(mapOf(ItemKey("b", "2") to true), SearchJudgmentRepository(preferences.dsl, listOf("a", "b")).of(" Space "))
            assertEquals(emptyMap(), SearchJudgmentRepository(preferences.dsl, listOf("c")).of("space"))

            // A link within a source is not one across them, nor the other way round
            val withinA = CorrectionRepository(a.dsl, "a")
            withinA.link("3", "1", Instant.now())
            assertEquals(listOf("1" to "3"), withinA.links())
            assertEquals(listOf(ItemKey("a", "1") to ItemKey("b", "2")), links.links())
            // Linked by the smaller key, whichever side is named first; forgotten on either side
            links.link(ItemKey("b", "5"), ItemKey("a", "4"), Instant.now())
            assertEquals(1, a.dsl.fetchCount(a.dsl.selectFrom("ITEM_LINK").where("OTHER_ITEM_ID = '5'")))
            links.forget(ItemKey("b", "5"))
            links.forget(ItemKey("a", "1"))
            assertEquals(emptyList(), links.links())
            assertEquals(listOf("1" to "3"), withinA.links())
        }
    }

    private fun <T> List<Database>.use(block: () -> T): T = try {
        block()
    } finally {
        forEach(Database::close)
    }
}
