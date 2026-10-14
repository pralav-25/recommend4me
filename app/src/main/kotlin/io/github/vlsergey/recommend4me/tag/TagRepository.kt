package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.model.tables.references.TAG_SHOWN_NAME
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG_DISTINCT
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG_ENTRY
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG_PARENT
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.SITE_TAG
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_ENTRY
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_ENTRY_ABOVE
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_ENTRY_TEXT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** Which rule gave a tag its name ([TagNaming]). */
enum class NameRule { EXPLICIT, CATALOGUE, LIKED, GRADED, ALL, ALPHABET }

/** An entry of a catalogue a tag is bound to: "wikidata", "Q305561". */
data class TagEntryRef(val catalogue: String, val entry: String)

/** A tag's name worked out, with the rule that gave it. */
data class ShownName(val name: String, val rule: NameRule)

/** A tag of a site: its source and the site's own key of it. */
data class SiteTagRef(val source: String, val tag: String)

/**
 * THE NORMALIZED TAGS OF A CONTENT TYPE, in its database of the user's word ([db]): every normalized
 * tag — an id and the name the user gave it, if any — every site's tag bound to one of them, the
 * graph of them, the pairs told apart. The names worked out of the sites' are kept apart, in the
 * type's model database ([model]): they are made again from the rest.
 *
 * A tag is known to the application — as a value of the works' tags, in the user's answers — by its
 * id, written as a text ([key]).
 */
class TagRepository(private val db: DSLContext, private val model: DSLContext) {

    // --- The normalized tags and the sites' tags bound to them ---

    /** Every site's tag and the normalized tag it is bound to. */
    fun bindings(): Map<SiteTagRef, Long> =
        db.select(SITE_TAG.SOURCE, SITE_TAG.TAG, SITE_TAG.NORMALIZED).from(SITE_TAG).fetch()
            .associate { SiteTagRef(it.value1()!!, it.value2()!!) to it.value3()!! }

    /** The site's tags of one [source] and the normalized tags they are bound to: site's key to id. */
    fun bindingsOf(source: String): Map<String, Long> =
        db.select(SITE_TAG.TAG, SITE_TAG.NORMALIZED).from(SITE_TAG).where(SITE_TAG.SOURCE.eq(source)).fetchMap({ it.value1()!! }, { it.value2()!! })

    /** A new normalized tag, named by the user when [name] is given; its id. */
    fun create(name: String? = null): Long =
        db.insertInto(NORMALIZED_TAG).set(NORMALIZED_TAG.NAME, name?.take(1000)).returningResult(NORMALIZED_TAG.ID).fetchOne()!!.value1()!!

    fun exists(id: Long): Boolean = db.fetchExists(NORMALIZED_TAG, NORMALIZED_TAG.ID.eq(id))

    /** Every normalized tag. */
    fun ids(): Set<Long> = db.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).fetchSet { it.value1()!! }

    /** [site] bound to the normalized tag [id], whatever it was bound to before. */
    fun bind(site: SiteTagRef, id: Long, now: Instant) {
        db.insertInto(SITE_TAG).set(SITE_TAG.SOURCE, site.source).set(SITE_TAG.TAG, site.tag.take(500)).set(SITE_TAG.NORMALIZED, id).set(SITE_TAG.BOUND_AT, now)
            .onDuplicateKeyUpdate().set(SITE_TAG.NORMALIZED, id).set(SITE_TAG.BOUND_AT, now)
            .execute()
    }

    /**
     * The normalized tag [from] merged into [into]: every site's tag of it bound to [into], its links
     * of the graph, its pairs told apart and its entries of the catalogues moved to [into] — none of
     * [into] with itself — and [from]
     * gone. The user's answers on it are moved by the caller: they are in the sources' databases.
     */
    fun merge(from: Long, into: Long, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.update(SITE_TAG).set(SITE_TAG.NORMALIZED, into).set(SITE_TAG.BOUND_AT, now).where(SITE_TAG.NORMALIZED.eq(from)).execute()
        val p = NORMALIZED_TAG_PARENT
        val links = tx.selectFrom(p).where(p.CHILD.eq(from).or(p.PARENT.eq(from))).fetch()
            .map { (if (it.child == from) into else it.child!!) to (if (it.parent == from) into else it.parent!!) }
        val d = NORMALIZED_TAG_DISTINCT
        val told = tx.selectFrom(d).where(d.FIRST_TAG.eq(from).or(d.OTHER_TAG.eq(from))).fetch()
            .map { pair(if (it.firstTag == from) into else it.firstTag!!, if (it.otherTag == from) into else it.otherTag!!) }
        // Its entries of the catalogues go to [into] besides its own, bound when they were: the user's word kept
        val e = NORMALIZED_TAG_ENTRY
        tx.selectFrom(e).where(e.TAG.eq(from)).fetch().forEach {
            tx.insertInto(e).set(e.TAG, into).set(e.CATALOGUE, it.catalogue).set(e.ENTRY, it.entry).set(e.BOUND_AT, it.boundAt).onDuplicateKeyIgnore().execute()
        }
        // [from]'s links and pairs go with it, as it goes; the ones moved to [into] come back
        tx.deleteFrom(NORMALIZED_TAG).where(NORMALIZED_TAG.ID.eq(from)).execute()
        links.filter { (child, parent) -> child != parent }.distinct().forEach { (child, parent) ->
            tx.insertInto(p).set(p.CHILD, child).set(p.PARENT, parent).set(p.LINKED_AT, now).onDuplicateKeyIgnore().execute()
        }
        told.filter { (a, b) -> a != b }.distinct().forEach { (a, b) ->
            tx.insertInto(d).set(d.FIRST_TAG, a).set(d.OTHER_TAG, b).set(d.SAID_AT, now).onDuplicateKeyIgnore().execute()
        }
    }

    // --- Names ---

    /** The name the user gives the normalized tag [id]; null takes it back. */
    fun name(id: Long, name: String?) {
        db.update(NORMALIZED_TAG).set(NORMALIZED_TAG.NAME, name?.take(1000)).where(NORMALIZED_TAG.ID.eq(id)).execute()
    }

    /** The names the user gave, by id. */
    fun explicitNames(): Map<Long, String> =
        db.select(NORMALIZED_TAG.ID, NORMALIZED_TAG.NAME).from(NORMALIZED_TAG).where(NORMALIZED_TAG.NAME.isNotNull)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** What the tags of [keys] are shown by — the user's name, else the one worked out last — of every tag when null; by key. */
    fun shown(keys: Collection<String>?): Map<String, String> {
        val ids = keys?.mapNotNull(::idOf)
        if (ids != null && ids.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        fun worked(only: List<Long>?) = model.select(TAG_SHOWN_NAME.TAG, TAG_SHOWN_NAME.NAME).from(TAG_SHOWN_NAME)
            .where(only?.let { TAG_SHOWN_NAME.TAG.`in`(it) } ?: DSL.noCondition()).fetch()
        fun given(only: List<Long>?) = db.select(NORMALIZED_TAG.ID, NORMALIZED_TAG.NAME).from(NORMALIZED_TAG)
            .where(NORMALIZED_TAG.NAME.isNotNull, only?.let { NORMALIZED_TAG.ID.`in`(it) } ?: DSL.noCondition()).fetch()
        val chunks = ids?.distinct()?.chunked(BATCH) ?: listOf(null)
        chunks.forEach { chunk -> worked(chunk).forEach { out[key(it.value1()!!)] = it.value2()!! } }
        chunks.forEach { chunk -> given(chunk).forEach { out[key(it.value1()!!)] = it.value2()!! } }
        return out
    }

    /** The name a new tag is shown by until the names are worked out again: of the site's tag it is made for. */
    fun showAs(id: Long, name: ShownName) {
        model.insertInto(TAG_SHOWN_NAME).set(TAG_SHOWN_NAME.TAG, id).set(TAG_SHOWN_NAME.NAME, name.name.take(1000)).set(TAG_SHOWN_NAME.RULE, name.rule.name)
            .onDuplicateKeyIgnore().execute()
    }

    /** The names worked out of every tag replaced by [named]: kept in the model database, made again from the rest. */
    fun replaceShown(named: Map<Long, ShownName>) = model.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(TAG_SHOWN_NAME).execute()
        named.entries.chunked(BATCH).forEach { chunk ->
            val batch = tx.batch(tx.insertInto(TAG_SHOWN_NAME, TAG_SHOWN_NAME.TAG, TAG_SHOWN_NAME.NAME, TAG_SHOWN_NAME.RULE).values(null as Long?, null as String?, null as String?))
            chunk.forEach { (id, s) -> batch.bind(id, s.name.take(1000), s.rule.name) }
            batch.execute()
        }
    }

    // --- The graph and the pairs told apart ---

    /** Every tag's parents: child to its parents, by key. */
    fun parents(): Map<String, Set<String>> =
        db.select(NORMALIZED_TAG_PARENT.CHILD, NORMALIZED_TAG_PARENT.PARENT).from(NORMALIZED_TAG_PARENT).fetch()
            .groupBy({ key(it.value1()!!) }, { key(it.value2()!!) }).mapValues { it.value.toSet() }

    /** [child] put under [parent] — or, [linked] false, taken from under it. */
    fun setParent(child: Long, parent: Long, linked: Boolean, now: Instant) {
        val p = NORMALIZED_TAG_PARENT
        if (linked) db.insertInto(p).set(p.CHILD, child).set(p.PARENT, parent).set(p.LINKED_AT, now).onDuplicateKeyIgnore().execute()
        else db.deleteFrom(p).where(p.CHILD.eq(child), p.PARENT.eq(parent)).execute()
    }

    /** Every pair of tags the user said are different, the smaller id first. */
    fun distinct(): Set<Pair<Long, Long>> =
        db.select(NORMALIZED_TAG_DISTINCT.FIRST_TAG, NORMALIZED_TAG_DISTINCT.OTHER_TAG).from(NORMALIZED_TAG_DISTINCT)
            .fetch { it.value1()!! to it.value2()!! }.toSet()

    /** The user's word that [a] and [b] are different tags — or, [different] false, its taking back. */
    fun setDistinct(a: Long, b: Long, different: Boolean, now: Instant) {
        val (x, y) = pair(a, b)
        val d = NORMALIZED_TAG_DISTINCT
        if (different) db.insertInto(d).set(d.FIRST_TAG, x).set(d.OTHER_TAG, y).set(d.SAID_AT, now).onDuplicateKeyIgnore().execute()
        else db.deleteFrom(d).where(d.FIRST_TAG.eq(x), d.OTHER_TAG.eq(y)).execute()
    }

    // --- The entries of a catalogue the tags are bound to ---

    /** Every tag bound to entries of the catalogues: tag id to its entries, the one bound first first. */
    fun entriesOfTags(): Map<Long, List<TagEntryRef>> {
        val e = NORMALIZED_TAG_ENTRY
        return db.select(e.TAG, e.CATALOGUE, e.ENTRY).from(e).orderBy(e.BOUND_AT, e.CATALOGUE, e.ENTRY).fetch()
            .groupBy({ it.value1()!! }, { TagEntryRef(it.value2()!!, it.value3()!!) })
    }

    /** The tag [id] bound to the entry [ref] besides the ones it is — or, [bound] false, no more to it. */
    fun bindEntry(id: Long, ref: TagEntryRef, bound: Boolean, now: Instant) {
        val e = NORMALIZED_TAG_ENTRY
        if (bound) {
            db.insertInto(e).set(e.TAG, id).set(e.CATALOGUE, ref.catalogue).set(e.ENTRY, ref.entry).set(e.BOUND_AT, now).onDuplicateKeyIgnore().execute()
        } else {
            db.deleteFrom(e).where(e.TAG.eq(id), e.CATALOGUE.eq(ref.catalogue), e.ENTRY.eq(ref.entry)).execute()
        }
    }

    /** The entry of [catalogue] as the catalogue gave it now: its texts in place of those kept. */
    fun keepEntry(catalogue: String, entry: TagEntry, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.insertInto(TAG_ENTRY).set(TAG_ENTRY.CATALOGUE, catalogue).set(TAG_ENTRY.ENTRY, entry.id).set(TAG_ENTRY.URL, entry.url.take(1000)).set(TAG_ENTRY.FETCHED_AT, now)
            .onDuplicateKeyUpdate().set(TAG_ENTRY.URL, entry.url.take(1000)).set(TAG_ENTRY.FETCHED_AT, now)
            .execute()
        val t = TAG_ENTRY_TEXT
        tx.deleteFrom(t).where(t.CATALOGUE.eq(catalogue), t.ENTRY.eq(entry.id)).execute()
        fun put(language: String, kind: String, texts: List<String>) = texts.forEachIndexed { i, text ->
            tx.insertInto(t).set(t.CATALOGUE, catalogue).set(t.ENTRY, entry.id).set(t.LANGUAGE, language).set(t.KIND, kind).set(t.POSITION, i)
                .set(t.TEXT, text.take(2000)).execute()
        }
        entry.labels.forEach { (language, text) -> put(language, LABEL, listOf(text)) }
        entry.aliases.forEach { (language, texts) -> put(language, ALIAS, texts) }
        entry.descriptions.forEach { (language, text) -> put(language, DESCRIPTION, listOf(text)) }
    }

    /** What every entry read is a kind of: the entry to the ones right above it. */
    fun aboveLinks(): Map<TagEntryRef, List<TagEntryRef>> {
        val a = TAG_ENTRY_ABOVE
        return db.select(a.CATALOGUE, a.ENTRY, a.ABOVE).from(a).fetch()
            .groupBy({ TagEntryRef(it.value1()!!, it.value2()!!) }, { TagEntryRef(it.value1()!!, it.value3()!!) })
    }

    /** Of [refs], the entries whose kinds were read: the walk up goes on from what is kept of them. */
    fun aboveRead(refs: Collection<TagEntryRef>): Set<TagEntryRef> {
        if (refs.isEmpty()) return emptySet()
        val wanted = refs.toSet()
        return db.select(TAG_ENTRY.CATALOGUE, TAG_ENTRY.ENTRY).from(TAG_ENTRY).where(TAG_ENTRY.ABOVE_READ_AT.isNotNull).fetch()
            .map { TagEntryRef(it.value1()!!, it.value2()!!) }.filter { it in wanted }.toSet()
    }

    /** What the kept entry [ref] is a kind of, as its catalogue says now: [above] in place of what was kept. */
    fun keepAbove(ref: TagEntryRef, above: List<String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        val a = TAG_ENTRY_ABOVE
        tx.deleteFrom(a).where(a.CATALOGUE.eq(ref.catalogue), a.ENTRY.eq(ref.entry)).execute()
        above.distinct().forEach { tx.insertInto(a).set(a.CATALOGUE, ref.catalogue).set(a.ENTRY, ref.entry).set(a.ABOVE, it).execute() }
        tx.update(TAG_ENTRY).set(TAG_ENTRY.ABOVE_READ_AT, now).where(TAG_ENTRY.CATALOGUE.eq(ref.catalogue), TAG_ENTRY.ENTRY.eq(ref.entry)).execute()
    }

    /** The entries kept — of [refs] alone, when given — with their texts: by reference. */
    fun keptEntries(refs: Collection<TagEntryRef>? = null): Map<TagEntryRef, TagEntry> {
        if (refs != null && refs.isEmpty()) return emptyMap()
        val wanted = refs?.toSet()
        val urls = db.select(TAG_ENTRY.CATALOGUE, TAG_ENTRY.ENTRY, TAG_ENTRY.URL).from(TAG_ENTRY).fetch()
            .map { TagEntryRef(it.value1()!!, it.value2()!!) to it.value3()!! }.filter { wanted == null || it.first in wanted }.toMap()
        val t = TAG_ENTRY_TEXT
        val texts = db.select(t.CATALOGUE, t.ENTRY, t.LANGUAGE, t.KIND, t.TEXT).from(t).orderBy(t.POSITION).fetch()
            .groupBy { TagEntryRef(it.value1()!!, it.value2()!!) }
        return urls.mapValues { (ref, url) ->
            val own = texts[ref].orEmpty()
            fun of(kind: String) = own.filter { it.value4() == kind }.groupBy({ it.value3()!! }, { it.value5()!! })
            TagEntry(ref.entry, of(LABEL).mapValues { it.value.first() }, of(ALIAS), of(DESCRIPTION).mapValues { it.value.first() }, url)
        }
    }

    companion object {
        private const val BATCH = 1000
        private const val LABEL = "L"
        private const val ALIAS = "A"
        private const val DESCRIPTION = "D"

        /** A normalized tag as a value of the works' tags: its id written out. */
        fun key(id: Long): String = id.toString()

        /** The id of a value of the works' tags; null for one that is no normalized tag. */
        fun idOf(key: String): Long? = key.toLongOrNull()

        /** A pair of tags, the smaller id first. */
        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
