package io.github.vlsergey.recommend4me.dictionary

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.DICTIONARY_MOVED
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger(DictionaryMove::class.java)

/**
 * THE TAGS AND THE UNIVERSES MOVED TO THE DICTIONARIES COMMON TO EVERY CONTENT TYPE: they were kept
 * in the files of each type (its corrections, the names of its tags in its model). Copied row by row,
 * the same columns, before the migrations of the type's files drop them; a file whose tables are gone
 * already has nothing to give.
 *
 * The tags are keyed by their numbers: those of two types could not be one dictionary, and a second
 * type with tags of its own stops the start rather than mix them. Only books had any.
 */
object DictionaryMove {
    /** The tables of a type's corrections, in the order their keys need. */
    val CORRECTIONS = listOf(
        "normalized_tag", "site_tag", "normalized_tag_parent", "normalized_tag_distinct", "normalized_tag_merge",
        "tag_entry", "tag_entry_text", "tag_entry_above", "normalized_tag_entry",
        "universe", "universe_property", "universe_character", "universe_character_property", "universe_character_class",
        "universe_class", "universe_class_choice", "universe_character_choice", "universe_local_name",
    )

    /** The table of a type's model. */
    val MODEL = listOf("tag_shown_name")

    /** Copies the [tables] of [from] — the file [owner] of a type — that it still has into [to], once. */
    fun copy(owner: String, from: DSLContext, to: DSLContext, tables: List<String>) {
        val present = tables.filter { exists(from, it) }
        if (present.isEmpty()) return
        if (to.fetchExists(DICTIONARY_MOVED, DICTIONARY_MOVED.OWNER.eq(owner))) return
        val started = System.currentTimeMillis()
        to.transaction { c ->
            val tx = DSL.using(c)
            present.forEach { table ->
                val rows = from.fetch("SELECT * FROM ${table.uppercase()}")
                if (rows.isEmpty()) return@forEach
                if (table == "normalized_tag" && tx.fetchCount(DSL.table(DSL.name("NORMALIZED_TAG"))) > 0) {
                    error("$owner has tags of its own, and the dictionaries have tags of another type already: they cannot be one by their numbers")
                }
                rows.forEach { r ->
                    tx.insertInto(DSL.table(DSL.name(table.uppercase())))
                        .set(r.fields().associate { f -> DSL.field(DSL.name(f.name)) to r.get(f) })
                        .execute()
                }
                log.info("{}: {} rows of {} moved to the dictionaries", owner, rows.size, table)
            }
            if ("normalized_tag" in present) {
                // The tags made after them numbered after the last moved
                val next = (tx.fetchValue("SELECT MAX(ID) FROM NORMALIZED_TAG") as Number?)?.toLong()?.plus(1) ?: 1
                tx.execute("ALTER TABLE NORMALIZED_TAG ALTER COLUMN ID RESTART WITH $next")
            }
            tx.insertInto(DICTIONARY_MOVED).set(DICTIONARY_MOVED.OWNER, owner).set(DICTIONARY_MOVED.MOVED_AT, Instant.now()).execute()
        }
        log.info("{}: the dictionaries moved in {} ms", owner, System.currentTimeMillis() - started)
    }

    private fun exists(db: DSLContext, table: String): Boolean =
        db.fetchExists(
            DSL.selectOne().from("INFORMATION_SCHEMA.TABLES")
                .where(DSL.field("TABLE_SCHEMA").eq("PUBLIC"), DSL.field("TABLE_NAME").eq(table.uppercase())),
        )
}
