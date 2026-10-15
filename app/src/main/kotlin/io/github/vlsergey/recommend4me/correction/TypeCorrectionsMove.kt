package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_LINK
import io.github.vlsergey.recommend4me.database.preferences.tables.references.SEARCH_JUDGMENT
import io.github.vlsergey.recommend4me.dictionary.DictionaryMove
import io.github.vlsergey.recommend4me.preferences.CatalogueRenameRepository
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.OffsetDateTime

private val log = LoggerFactory.getLogger(TypeCorrectionsMove::class.java)

/**
 * THE CORRECTIONS OF A CONTENT TYPE ARE NO FILE OF ITS OWN ANY MORE: a link of works of two of its
 * sources is kept by the source of the smaller key ([TypeLinkRepository]), the user's word on what
 * a search found is the user's preference. A type's corrections file of before is migrated as it was
 * — its tags and universes given to the dictionaries on the way — its rows copied over, and the file
 * renamed `corrections.mv.db.moved`, kept, never read again.
 */
object TypeCorrectionsMove {

    fun move(type: String, file: Path, sourceCorrections: Map<String, DSLContext>, preferences: DSLContext, dictionaries: DSLContext) {
        val data = file.resolveSibling(file.fileName.toString() + ".mv.db")
        if (!Files.exists(data)) return
        val owner = "$type/corrections"
        Database(owner, file, "typecorrections") { db ->
            CatalogueRenameRepository.moveFrom(owner, db, preferences)
            DictionaryMove.copy(owner, db, dictionaries, DictionaryMove.CORRECTIONS)
        }.use { old ->
            val links = old.dsl.fetch("SELECT SOURCE, ITEM_ID, OTHER_SOURCE, OTHER_ITEM_ID, LINKED_AT FROM ITEM_LINK")
            links.forEach { r ->
                val source = r.get(0, String::class.java)
                val to = sourceCorrections[source]
                if (to == null) {
                    log.warn("{}: a link of {} kept in the file of before alone, the source is gone", owner, source)
                    return@forEach
                }
                to.insertInto(ITEM_LINK).set(ITEM_LINK.ITEM_ID, r.get(1, String::class.java))
                    .set(ITEM_LINK.OTHER_SOURCE, r.get(2, String::class.java)).set(ITEM_LINK.OTHER_ITEM_ID, r.get(3, String::class.java))
                    .set(ITEM_LINK.LINKED_AT, r.get(4, OffsetDateTime::class.java).toInstant())
                    .onDuplicateKeyIgnore().execute()
            }
            val judgments = old.dsl.fetch("SELECT QUERY, SOURCE, ITEM_ID, RELEVANT, JUDGED_AT FROM SEARCH_JUDGMENT")
            judgments.forEach { r ->
                preferences.insertInto(SEARCH_JUDGMENT).set(SEARCH_JUDGMENT.QUERY, r.get(0, String::class.java))
                    .set(SEARCH_JUDGMENT.SOURCE, r.get(1, String::class.java)).set(SEARCH_JUDGMENT.ITEM_ID, r.get(2, String::class.java))
                    .set(SEARCH_JUDGMENT.RELEVANT, r.get(3, Boolean::class.java)).set(SEARCH_JUDGMENT.JUDGED_AT, r.get(4, OffsetDateTime::class.java).toInstant())
                    .onDuplicateKeyIgnore().execute()
            }
            log.info("{}: {} links moved to the sources' corrections, {} judgments of the search to the preferences", owner, links.size, judgments.size)
        }
        Files.move(data, data.resolveSibling(data.fileName.toString() + ".moved"), StandardCopyOption.REPLACE_EXISTING)
    }
}
