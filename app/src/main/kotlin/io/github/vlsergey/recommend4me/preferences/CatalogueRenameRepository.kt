package io.github.vlsergey.recommend4me.preferences

import io.github.vlsergey.recommend4me.database.preferences.tables.references.CATALOGUE_ENTITY_RENAME
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger(CatalogueRenameRepository::class.java)

/** An entity of a catalogue the user renamed: the catalogue, the entity's id in it. */
data class CatalogueEntity(val catalogue: String, val entity: String)

/**
 * THE NAMES THE USER GIVES THE ENTITIES OF A CATALOGUE — a universe, a character of Wikidata — over
 * the catalogue's, in the user's preferences: shown everywhere in their place, read in the works'
 * texts as one more name of them. A character is one entity in every universe it is in.
 */
class CatalogueRenameRepository(private val db: DSLContext) {

    /** Every name the user gave, by entity. */
    fun all(): Map<CatalogueEntity, String> {
        val r = CATALOGUE_ENTITY_RENAME
        return db.select(r.CATALOGUE, r.ENTITY, r.NAME).from(r).fetch().associate { CatalogueEntity(it.value1()!!, it.value2()!!) to it.value3()!! }
    }

    /** The name the user gives [entity]; null or blank takes it back. */
    fun rename(entity: CatalogueEntity, name: String?, now: Instant) {
        val r = CATALOGUE_ENTITY_RENAME
        val given = name?.trim()?.takeIf { it.isNotEmpty() }?.take(1000)
        if (given == null) {
            db.deleteFrom(r).where(r.CATALOGUE.eq(entity.catalogue), r.ENTITY.eq(entity.entity)).execute()
            return
        }
        db.insertInto(r).set(r.CATALOGUE, entity.catalogue).set(r.ENTITY, entity.entity).set(r.NAME, given).set(r.RENAMED_AT, now)
            .onDuplicateKeyUpdate().set(r.NAME, given).set(r.RENAMED_AT, now)
            .execute()
    }

    companion object {
        /**
         * The names the user gave in a file of before — the dictionaries' or a type's corrections'
         * `universe_local_name` — copied into the preferences [to], a name given there already kept;
         * nothing of a file that has no such table.
         */
        fun moveFrom(owner: String, from: DSLContext, to: DSLContext) {
            val present = from.fetchExists(
                DSL.selectOne().from("INFORMATION_SCHEMA.TABLES")
                    .where(DSL.field("TABLE_SCHEMA").eq("PUBLIC"), DSL.field("TABLE_NAME").eq("UNIVERSE_LOCAL_NAME")),
            )
            if (!present) return
            val r = CATALOGUE_ENTITY_RENAME
            val rows = from.fetch("SELECT CATALOGUE, ENTRY_ID, NAME, NAMED_AT FROM UNIVERSE_LOCAL_NAME")
            to.transaction { c ->
                val tx = DSL.using(c)
                rows.forEach {
                    tx.insertInto(r).set(r.CATALOGUE, it.get(0, String::class.java)).set(r.ENTITY, it.get(1, String::class.java))
                        .set(r.NAME, it.get(2, String::class.java)).set(r.RENAMED_AT, it.get(3, java.time.OffsetDateTime::class.java).toInstant())
                        .onDuplicateKeyIgnore().execute()
                }
            }
            if (rows.isNotEmpty()) log.info("{}: {} names the user gave moved to the preferences", owner, rows.size)
        }
    }
}
