package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_STAT
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** What a pass of the background keeps of the tags. */
class TagRepositoryTest {

    @TempDir
    lateinit var folder: Path

    @Test
    fun `a tag merged while the works were counted gets no statistics, the others do`() {
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { db ->
            val tags = TagRepository(db.dsl)
            val kept = tags.create()
            val gone = tags.create()
            // Counted with both; the second merged away before the write
            db.dsl.deleteFrom(NORMALIZED_TAG).where(NORMALIZED_TAG.ID.eq(gone)).execute()
            tags.replaceCalculated(mapOf(kept to ShownName("Hulls", NameRule.ALL), gone to ShownName("Sails", NameRule.ALL)))
            tags.keepStats(emptyMap(), mapOf(kept to TagCount(Counts(3, 2, 1), listOf("site")), gone to TagCount(Counts(5, 0, 0), listOf("site"))))
            assertEquals(listOf(kept), db.dsl.select(TAG_STAT.TAG).from(TAG_STAT).fetch(TAG_STAT.TAG))
            assertEquals(mapOf(kept to TagCount(Counts(3, 2, 1), listOf("site"))), tags.counts())
            assertEquals("Hulls", db.dsl.select(TAG_STAT.CALCULATED_NAME).from(TAG_STAT).fetchOne(TAG_STAT.CALCULATED_NAME))
        }
    }
}
