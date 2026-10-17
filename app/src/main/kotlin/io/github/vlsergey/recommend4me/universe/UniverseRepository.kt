package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_CHOICE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_CLASS
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CHARACTER_PROPERTY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CLASS
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_CLASS_CHOICE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE_PROPERTY
import io.github.vlsergey.recommend4me.preferences.CatalogueEntity
import io.github.vlsergey.recommend4me.preferences.CatalogueRenameRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** A universe of the dictionary, with how many of its entries are kept as characters, of how many. */
/** A universe of the dictionary by its key: its catalogue and its id there. */
data class UniverseKey(val catalogue: String, val id: String) {
    val value: String get() = UniverseFacet.valueOf(catalogue, id)
}

class StoredUniverse(
    val catalogue: String,
    val id: String,
    /** The catalogue's name, in the user's languages. */
    val catalogueName: String,
    val description: String?,
    val url: String,
    val addedAt: Instant,
    val refreshedAt: Instant,
    val characters: Int,
    val entries: Int,
    /** The languages the catalogue was asked in for it; none of a universe saved before they were kept. */
    val languages: List<String>,
    /** The statements its characters were asked for with ([UniverseCharacter.properties]); none before they were kept. */
    val properties: Set<String>,
    /** The name the user gave it over the catalogue's; null when none. */
    val localName: String? = null,
) {
    val value: String get() = UniverseFacet.valueOf(catalogue, id)

    /** What it is shown by: the user's name, else the catalogue's. */
    val name: String get() = localName ?: catalogueName

    /** Every name it goes by, the shown one first: what its works' texts are read for. */
    val names: List<String> get() = listOfNotNull(localName, catalogueName).distinct()
}

/**
 * An entry of a universe of the dictionary, whether it is kept as a character ([included]), and
 * the user's word on it alone ([choice]; null when its classes decide). Its [character]'s names
 * begin with the name the user gave it ([localName]), when given.
 */
class KeptEntry(val character: UniverseCharacter, val included: Boolean, val choice: Boolean?, val localName: String? = null)

/**
 * A class the entries of a universe are of: whether its entries are characters, how many of the
 * universe's entries are of it, whether they are kept ([included]) and the user's word on it
 * ([choice]; null when it is kept as the catalogue says — kept when of characters).
 */
class ClassInUniverse(val id: String, val name: String, val character: Boolean, val entries: Int, val included: Boolean, val choice: Boolean?)

/**
 * The dictionary of universes the user agreed on and their entries, in the dictionaries; the
 * names the user gives them in the user's preferences. AN ENTRY IS KEPT AS A CHARACTER by the user's word on it; else when any of its classes
 * is kept — a class by the user's word on it within the universe, else when its entries are
 * characters; an entry the catalogue gave no class is kept: nothing says it is not one.
 */
class UniverseRepository(
    private val db: DSLContext,
    /** The user's languages: the texts are shown in the first of them that has one. */
    private val languages: List<String>,
    /** The names the user gives the universes and the characters, in the user's preferences. */
    private val renames: CatalogueRenameRepository,
) {

    /** Every universe of the dictionary by its key alone, its characters not read: what the chances of a work read want of it. */
    fun keys(): List<UniverseKey> = db.select(UNIVERSE.CATALOGUE, UNIVERSE.UNIVERSE_ID).from(UNIVERSE).fetch { UniverseKey(it.value1()!!, it.value2()!!) }

    fun all(): List<StoredUniverse> {
        val local = localNames()
        return db.selectFrom(UNIVERSE).fetch().map { u ->
            val entries = entries(u.catalogue!!, u.universeId!!, local)
            StoredUniverse(
                // Of a universe saved before the texts were kept by language, the ones shown then
                u.catalogue!!, u.universeId!!,
                if (u.labels != null) shown(u.labels) ?: u.universeId!! else u.name!!,
                if (u.labels != null) shown(u.descriptions) else u.description,
                u.url!!,
                u.addedAt!!, u.refreshedAt!!, entries.count { it.included }, entries.size,
                u.languages?.split(',')?.filter { it.isNotEmpty() }.orEmpty(),
                u.properties?.split(',')?.filter { it.isNotEmpty() }.orEmpty().toSet(),
                local[UniverseFacet.valueOf(u.catalogue!!, u.universeId!!)],
            )
        }.sortedBy { it.name.lowercase() }
    }

    /** The names the user gave the universes and the characters over the catalogue's: value ("<catalogue>:<id>") to name. */
    fun localNames(): Map<String, String> =
        renames.all().mapKeys { (e, _) -> UniverseFacet.valueOf(e.catalogue, e.entity) }

    /** The name the user gives a universe or a character of [catalogue] over the catalogue's; null or blank takes it back. */
    fun setLocalName(catalogue: String, id: String, name: String?, now: Instant) = renames.rename(CatalogueEntity(catalogue, id), name, now)

    fun find(catalogue: String, id: String): StoredUniverse? = all().firstOrNull { it.catalogue == catalogue && it.id == id }

    /**
     * Adds the universe, or refreshes it: its name and every entry replaced by what the catalogue
     * says now, with the [classes] of the entries and their statements of [properties], the ones
     * asked for; the user's words on its classes and entries stay.
     */
    fun save(catalogue: String, entry: UniverseEntry, characters: List<UniverseCharacter>, classes: List<UniverseClass>, properties: Set<String>, now: Instant) {
        db.transaction { tx ->
            val t = tx.dsl()
            val asked = entry.languages.joinToString(",")
            val askedProperties = properties.sorted().joinToString(",")
            t.insertInto(UNIVERSE)
                .set(UNIVERSE.CATALOGUE, catalogue).set(UNIVERSE.UNIVERSE_ID, entry.id).set(UNIVERSE.NAME, entry.name.take(1000))
                .set(UNIVERSE.DESCRIPTION, entry.description?.take(2000)).set(UNIVERSE.URL, entry.url.take(2000))
                .set(UNIVERSE.LABELS, encode(entry.labels)).set(UNIVERSE.DESCRIPTIONS, encode(entry.descriptions)).set(UNIVERSE.LANGUAGES, asked)
                .set(UNIVERSE.PROPERTIES, askedProperties)
                .set(UNIVERSE.ADDED_AT, now).set(UNIVERSE.REFRESHED_AT, now)
                .onDuplicateKeyUpdate()
                .set(UNIVERSE.NAME, entry.name.take(1000)).set(UNIVERSE.DESCRIPTION, entry.description?.take(2000))
                .set(UNIVERSE.LABELS, encode(entry.labels)).set(UNIVERSE.DESCRIPTIONS, encode(entry.descriptions)).set(UNIVERSE.LANGUAGES, asked)
                .set(UNIVERSE.PROPERTIES, askedProperties)
                .set(UNIVERSE.URL, entry.url.take(2000)).set(UNIVERSE.REFRESHED_AT, now)
                .execute()
            t.deleteFrom(UNIVERSE_PROPERTY).where(UNIVERSE_PROPERTY.CATALOGUE.eq(catalogue), UNIVERSE_PROPERTY.UNIVERSE_ID.eq(entry.id)).execute()
            entry.properties.flatMap { (p, values) -> values.distinct().map { p to it } }.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { (property, value) ->
                    t.insertInto(UNIVERSE_PROPERTY)
                        .set(UNIVERSE_PROPERTY.CATALOGUE, catalogue).set(UNIVERSE_PROPERTY.UNIVERSE_ID, entry.id)
                        .set(UNIVERSE_PROPERTY.PROPERTY, property).set(UNIVERSE_PROPERTY.PROPERTY_VALUE, value.take(2000))
                }).execute()
            }
            t.deleteFrom(UNIVERSE_CHARACTER).where(UNIVERSE_CHARACTER.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER.UNIVERSE_ID.eq(entry.id)).execute()
            characters.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { c ->
                    t.insertInto(UNIVERSE_CHARACTER)
                        .set(UNIVERSE_CHARACTER.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER.UNIVERSE_ID, entry.id)
                        .set(UNIVERSE_CHARACTER.CHARACTER_ID, c.id).set(UNIVERSE_CHARACTER.NAMES, c.names.joinToString("\n"))
                        .set(UNIVERSE_CHARACTER.DESCRIPTION, c.description?.take(2000)).set(UNIVERSE_CHARACTER.URL, c.url.take(2000))
                        .set(UNIVERSE_CHARACTER.SEX, c.sex?.name)
                        .set(UNIVERSE_CHARACTER.LABELS, encode(c.labels)).set(UNIVERSE_CHARACTER.ALIASES, encodeAll(c.aliases))
                        .set(UNIVERSE_CHARACTER.DESCRIPTIONS, encode(c.descriptions))
                }).execute()
            }
            val statements = characters.distinctBy { it.id }.flatMap { c -> c.properties.flatMap { (p, values) -> values.distinct().map { Triple(c.id, p, it) } } }
            statements.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { (character, property, value) ->
                    t.insertInto(UNIVERSE_CHARACTER_PROPERTY)
                        .set(UNIVERSE_CHARACTER_PROPERTY.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER_PROPERTY.UNIVERSE_ID, entry.id)
                        .set(UNIVERSE_CHARACTER_PROPERTY.CHARACTER_ID, character).set(UNIVERSE_CHARACTER_PROPERTY.PROPERTY, property)
                        .set(UNIVERSE_CHARACTER_PROPERTY.PROPERTY_VALUE, value.take(2000))
                }).execute()
            }
            characters.flatMap { c -> c.classes.distinct().map { c.id to it } }.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { (character, cls) ->
                    t.insertInto(UNIVERSE_CHARACTER_CLASS)
                        .set(UNIVERSE_CHARACTER_CLASS.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID, entry.id)
                        .set(UNIVERSE_CHARACTER_CLASS.CHARACTER_ID, character).set(UNIVERSE_CHARACTER_CLASS.CLASS_ID, cls)
                }).execute()
            }
            classes.chunked(BATCH).forEach { chunk ->
                t.batch(chunk.map { c ->
                    t.insertInto(UNIVERSE_CLASS)
                        .set(UNIVERSE_CLASS.CATALOGUE, catalogue).set(UNIVERSE_CLASS.CLASS_ID, c.id)
                        .set(UNIVERSE_CLASS.NAME, c.name.take(1000)).set(UNIVERSE_CLASS.IS_CHARACTER, c.character).set(UNIVERSE_CLASS.LABELS, encode(c.labels))
                        .onDuplicateKeyUpdate()
                        .set(UNIVERSE_CLASS.NAME, c.name.take(1000)).set(UNIVERSE_CLASS.IS_CHARACTER, c.character).set(UNIVERSE_CLASS.LABELS, encode(c.labels))
                }).execute()
            }
        }
    }

    /** Every statement the dictionary keeps of its universes and the works within them: universe value, property, value. */
    fun universeStatements(): List<Triple<String, String, String>> =
        db.select(UNIVERSE_PROPERTY.CATALOGUE, UNIVERSE_PROPERTY.UNIVERSE_ID, UNIVERSE_PROPERTY.PROPERTY, UNIVERSE_PROPERTY.PROPERTY_VALUE).from(UNIVERSE_PROPERTY)
            .fetch { Triple(UniverseFacet.valueOf(it.value1()!!, it.value2()!!), it.value3()!!, it.value4()!!) }

    /** Every statement the dictionary keeps of the characters of its universes: character value, property, value. */
    fun characterStatements(): List<Triple<String, String, String>> =
        db.selectDistinct(
            UNIVERSE_CHARACTER_PROPERTY.CATALOGUE, UNIVERSE_CHARACTER_PROPERTY.CHARACTER_ID, UNIVERSE_CHARACTER_PROPERTY.PROPERTY, UNIVERSE_CHARACTER_PROPERTY.PROPERTY_VALUE,
        ).from(UNIVERSE_CHARACTER_PROPERTY)
            .fetch { Triple(UniverseFacet.valueOf(it.value1()!!, it.value2()!!), it.value3()!!, it.value4()!!) }

    /** The universes every entry of the dictionary is in: character value to universe values. */
    fun characterUniverses(): Map<String, Set<String>> =
        db.select(UNIVERSE_CHARACTER.CATALOGUE, UNIVERSE_CHARACTER.CHARACTER_ID, UNIVERSE_CHARACTER.UNIVERSE_ID).from(UNIVERSE_CHARACTER).fetch()
            .groupBy({ UniverseFacet.valueOf(it.value1()!!, it.value2()!!) }, { UniverseFacet.valueOf(it.value1()!!, it.value3()!!) })
            .mapValues { it.value.toSet() }

    fun remove(catalogue: String, id: String) {
        db.deleteFrom(UNIVERSE).where(UNIVERSE.CATALOGUE.eq(catalogue), UNIVERSE.UNIVERSE_ID.eq(id)).execute()
    }

    /** The entries of the universe kept as characters. */
    fun characters(catalogue: String, id: String): List<UniverseCharacter> = entries(catalogue, id).filter { it.included }.map { it.character }

    /** Every entry of the universe, kept or not, by name; the names the user gave first ([local], read once for a reading of many universes). */
    fun entries(catalogue: String, id: String, local: Map<String, String> = localNames()): List<KeptEntry> {
        val classes = classesOf(catalogue, id)
        val ofEntry = HashMap<String, MutableList<String>>()
        db.select(UNIVERSE_CHARACTER_CLASS.CHARACTER_ID, UNIVERSE_CHARACTER_CLASS.CLASS_ID).from(UNIVERSE_CHARACTER_CLASS)
            .where(UNIVERSE_CHARACTER_CLASS.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID.eq(id))
            .fetch { ofEntry.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! }
        val choices = db.select(UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID, UNIVERSE_CHARACTER_CHOICE.INCLUDED).from(UNIVERSE_CHARACTER_CHOICE)
            .where(UNIVERSE_CHARACTER_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID.eq(id))
            .fetchMap({ it.value1()!! }, { it.value2()!! })
        val kept = classes.mapValues { (_, c) -> c.included }
        val statements = HashMap<String, MutableMap<String, MutableList<String>>>()
        db.select(UNIVERSE_CHARACTER_PROPERTY.CHARACTER_ID, UNIVERSE_CHARACTER_PROPERTY.PROPERTY, UNIVERSE_CHARACTER_PROPERTY.PROPERTY_VALUE)
            .from(UNIVERSE_CHARACTER_PROPERTY)
            .where(UNIVERSE_CHARACTER_PROPERTY.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_PROPERTY.UNIVERSE_ID.eq(id))
            .fetch { statements.getOrPut(it.value1()!!) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } += it.value3()!! }
        return db.selectFrom(UNIVERSE_CHARACTER).where(UNIVERSE_CHARACTER.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER.UNIVERSE_ID.eq(id))
            .fetch { r ->
                val own = ofEntry[r.characterId!!].orEmpty()
                val sex = r.sex?.let(CharacterSex::valueOf)
                val stated = statements[r.characterId!!].orEmpty()
                // The name the user gave it, as a label of a language of its own before the user's: its first name, and one more it goes by
                val given = local[UniverseFacet.valueOf(catalogue, r.characterId!!)]
                val localLabel = listOfNotNull(given?.let { LOCAL to it }).toMap()
                val localFirst = if (given == null) emptyList() else listOf(LOCAL)
                val character = if (r.labels != null) {
                    UniverseCharacter(r.characterId!!, localLabel + decode(r.labels), decodeAll(r.aliases), decode(r.descriptions), r.url!!, localFirst + languages, own, sex, stated)
                } else {
                    // Saved before the texts were kept by language: its texts as they were shown then
                    val names = r.names!!.split('\n')
                    UniverseCharacter(
                        r.characterId!!, localLabel + (SHOWN to names.first()), mapOf(SHOWN to names.drop(1)), listOfNotNull(r.description?.let { SHOWN to it }).toMap(),
                        r.url!!, localFirst + languages + SHOWN, own, sex, stated,
                    )
                }
                val choice = choices[r.characterId!!]
                KeptEntry(character, choice ?: (own.isEmpty() || own.any { kept[it] == true }), choice, given)
            }
            .sortedBy { it.character.names.first().lowercase() }
    }

    /** The name of every universe of [values] ("<catalogue>:<id>") in the dictionary, in the user's languages; of every universe when null. */
    fun universeNames(values: Collection<String>?): Map<String, String> {
        val u = UNIVERSE
        val query = db.select(u.CATALOGUE, u.UNIVERSE_ID, u.LABELS, u.NAME).from(u)
        val rows = if (values == null) query.fetch() else {
            val wanted = byCatalogue(values).ifEmpty { return emptyMap() }
            query.where(DSL.or(wanted.map { (catalogue, ids) -> u.CATALOGUE.eq(catalogue).and(u.UNIVERSE_ID.`in`(ids)) })).fetch()
        }
        val local = localNames()
        return rows.associate { r ->
            val id = r.value2()!!
            val value = UniverseFacet.valueOf(r.value1()!!, id)
            value to (local[value] ?: if (r.value3() != null) shown(r.value3()) ?: id else r.value4()!!)
        }
    }

    /**
     * The name every character of [values] ("<catalogue>:<id>") is shown by — the first of its
     * names, as [UniverseCharacter.names] orders them — and its sex, of whichever universe of the
     * dictionary has it; of every character when null.
     */
    fun characterNames(values: Collection<String>?): Map<String, Pair<String, CharacterSex?>> {
        val c = UNIVERSE_CHARACTER
        val query = db.select(c.CATALOGUE, c.CHARACTER_ID, c.LABELS, c.ALIASES, c.NAMES, c.SEX).from(c)
        val rows = if (values == null) query.fetch() else {
            val wanted = byCatalogue(values).ifEmpty { return emptyMap() }
            query.where(DSL.or(wanted.map { (catalogue, ids) -> c.CATALOGUE.eq(catalogue).and(c.CHARACTER_ID.`in`(ids)) })).fetch()
        }
        val local = localNames()
        return rows.associate { r ->
            val id = r.value2()!!
            val name = local[UniverseFacet.valueOf(r.value1()!!, id)] ?: if (r.value3() != null) {
                val labels = decode(r.value3())
                val aliases = decodeAll(r.value4())
                languages.firstNotNullOfOrNull { labels[it] } ?: languages.firstNotNullOfOrNull { aliases[it]?.firstOrNull() } ?: id
            } else {
                // Saved before the texts were kept by language: as it was shown then
                r.value5()!!.substringBefore('\n')
            }
            UniverseFacet.valueOf(r.value1()!!, id) to (name to r.value6()?.let(CharacterSex::valueOf))
        }
    }

    /**
     * Every name each character of [values] ("<catalogue>:<id>") goes by in the user's languages — the
     * name the user gave it, then the labels and aliases language by language — for a search by any of
     * them; one read of the table.
     */
    fun characterAllNames(values: Collection<String>): Map<String, List<String>> {
        val wanted = byCatalogue(values).ifEmpty { return emptyMap() }
        val c = UNIVERSE_CHARACTER
        val local = localNames()
        val out = HashMap<String, List<String>>()
        db.select(c.CATALOGUE, c.CHARACTER_ID, c.LABELS, c.ALIASES, c.NAMES).from(c)
            .where(DSL.or(wanted.map { (catalogue, ids) -> c.CATALOGUE.eq(catalogue).and(c.CHARACTER_ID.`in`(ids)) })).fetch().forEach { r ->
                val value = UniverseFacet.valueOf(r.value1()!!, r.value2()!!)
                val own = if (r.value3() != null) {
                    val labels = decode(r.value3())
                    val aliases = decodeAll(r.value4())
                    languages.flatMap { listOfNotNull(labels[it]) + aliases[it].orEmpty() }
                } else {
                    // Saved before the texts were kept by language: as it was shown then
                    r.value5()!!.split('\n')
                }
                // A character of several universes is one entry: the same names in each
                out.putIfAbsent(value, (listOfNotNull(local[value]) + own).distinct())
            }
        return out
    }

    /** The ids of [values] ("<catalogue>:<id>") by catalogue; a value of no catalogue left out. */
    /**
     * What the catalogue says of [values] — universes and characters of the dictionary — in the first
     * of the user's languages that has it: value to description; one read of each table.
     */
    fun descriptions(values: Collection<String>): Map<String, String> {
        val wanted = byCatalogue(values).ifEmpty { return emptyMap() }
        val u = UNIVERSE
        val c = UNIVERSE_CHARACTER
        val out = HashMap<String, String>()
        db.select(u.CATALOGUE, u.UNIVERSE_ID, u.LABELS, u.DESCRIPTIONS, u.DESCRIPTION).from(u)
            .where(DSL.or(wanted.map { (catalogue, ids) -> u.CATALOGUE.eq(catalogue).and(u.UNIVERSE_ID.`in`(ids)) })).fetch().forEach { r ->
                // Of a universe saved before the texts were kept by language, the one shown then
                val text = if (r.value3() != null) shown(r.value4()) else r.value5()
                text?.takeIf { it.isNotBlank() }?.let { out[UniverseFacet.valueOf(r.value1()!!, r.value2()!!)] = it }
            }
        // A character of several universes is one entry: its description the same in each
        db.select(c.CATALOGUE, c.CHARACTER_ID, c.LABELS, c.DESCRIPTIONS, c.DESCRIPTION).from(c)
            .where(DSL.or(wanted.map { (catalogue, ids) -> c.CATALOGUE.eq(catalogue).and(c.CHARACTER_ID.`in`(ids)) })).fetch().forEach { r ->
                val text = if (r.value3() != null) shown(r.value4()) else r.value5()
                text?.takeIf { it.isNotBlank() }?.let { out.putIfAbsent(UniverseFacet.valueOf(r.value1()!!, r.value2()!!), it) }
            }
        return out
    }

    private fun byCatalogue(values: Collection<String>): Map<String, List<String>> =
        values.mapNotNull(UniverseFacet::parse).groupBy({ it.first }, { it.second }).mapValues { it.value.distinct() }

    /** Every class the entries of the universe are of, the most entries first. */
    fun classes(catalogue: String, id: String): List<ClassInUniverse> = classesOf(catalogue, id).values.sortedByDescending { it.entries }

    private fun classesOf(catalogue: String, id: String): Map<String, ClassInUniverse> {
        val counts = db.select(UNIVERSE_CHARACTER_CLASS.CLASS_ID, DSL.count()).from(UNIVERSE_CHARACTER_CLASS)
            .where(UNIVERSE_CHARACTER_CLASS.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CLASS.UNIVERSE_ID.eq(id))
            .groupBy(UNIVERSE_CHARACTER_CLASS.CLASS_ID)
            .fetchMap({ it.value1()!! }, { it.value2()!! })
        if (counts.isEmpty()) return emptyMap()
        val known = db.selectFrom(UNIVERSE_CLASS).where(UNIVERSE_CLASS.CATALOGUE.eq(catalogue), UNIVERSE_CLASS.CLASS_ID.`in`(counts.keys))
            .fetchMap({ it.classId!! }, { it })
        val choices = db.select(UNIVERSE_CLASS_CHOICE.CLASS_ID, UNIVERSE_CLASS_CHOICE.INCLUDED).from(UNIVERSE_CLASS_CHOICE)
            .where(UNIVERSE_CLASS_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CLASS_CHOICE.UNIVERSE_ID.eq(id))
            .fetchMap({ it.value1()!! }, { it.value2()!! })
        return counts.mapValues { (cls, n) ->
            val character = known[cls]?.isCharacter ?: false
            val choice = choices[cls]
            val name = known[cls]?.let { if (it.labels != null) shown(it.labels) else it.name } ?: cls
            ClassInUniverse(cls, name, character, n, choice ?: character, choice)
        }
    }

    /** The user's word on a class within the universe: its entries kept, or left out; null takes it back. */
    fun chooseClass(catalogue: String, id: String, cls: String, included: Boolean?) {
        db.deleteFrom(UNIVERSE_CLASS_CHOICE)
            .where(UNIVERSE_CLASS_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CLASS_CHOICE.UNIVERSE_ID.eq(id), UNIVERSE_CLASS_CHOICE.CLASS_ID.eq(cls))
            .execute()
        if (included != null) {
            db.insertInto(UNIVERSE_CLASS_CHOICE)
                .set(UNIVERSE_CLASS_CHOICE.CATALOGUE, catalogue).set(UNIVERSE_CLASS_CHOICE.UNIVERSE_ID, id)
                .set(UNIVERSE_CLASS_CHOICE.CLASS_ID, cls).set(UNIVERSE_CLASS_CHOICE.INCLUDED, included)
                .execute()
        }
    }

    /** The user's word on one entry of the universe, over its classes; null takes it back. */
    fun chooseEntry(catalogue: String, id: String, character: String, included: Boolean?) {
        db.deleteFrom(UNIVERSE_CHARACTER_CHOICE)
            .where(UNIVERSE_CHARACTER_CHOICE.CATALOGUE.eq(catalogue), UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID.eq(id), UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID.eq(character))
            .execute()
        if (included != null) {
            db.insertInto(UNIVERSE_CHARACTER_CHOICE)
                .set(UNIVERSE_CHARACTER_CHOICE.CATALOGUE, catalogue).set(UNIVERSE_CHARACTER_CHOICE.UNIVERSE_ID, id)
                .set(UNIVERSE_CHARACTER_CHOICE.CHARACTER_ID, character).set(UNIVERSE_CHARACTER_CHOICE.INCLUDED, included)
                .execute()
        }
    }

    /** The text of [byLanguage] (as kept: [encode]) in the first of the user's languages that has one; null when none does, or none was kept. */
    private fun shown(byLanguage: String?): String? = decode(byLanguage).let { texts -> languages.firstNotNullOfOrNull { texts[it] } }

    companion object {
        private const val BATCH = 1000

        /** The language of the texts of an entry saved before they were kept by language: the ones shown then, the last choice. */
        private const val SHOWN = ""

        /** The "language" of the name the user gave an entry: before every language of the user's. */
        private const val LOCAL = "local"

        /** Texts by language as kept: a line each, "<language><TAB><text>". */
        private fun encode(texts: Map<String, String>): String = encodeAll(texts.mapValues { listOf(it.value) })

        private fun encodeAll(texts: Map<String, List<String>>): String =
            texts.flatMap { (lang, list) -> list.map { "$lang\t${it.replace('\n', ' ')}" } }.joinToString("\n")

        private fun decodeAll(kept: String?): Map<String, List<String>> =
            kept.orEmpty().lines().filter { '\t' in it }.groupBy({ it.substringBefore('\t') }, { it.substringAfter('\t') })

        private fun decode(kept: String?): Map<String, String> = decodeAll(kept).mapValues { it.value.first() }
    }
}
