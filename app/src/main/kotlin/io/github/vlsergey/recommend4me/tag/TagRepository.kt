package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_STAT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_VECTOR
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_DISTINCT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_MERGE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG_PARENT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY_ABOVE
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY_TEXT
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_ENTRY_TOO_GENERAL
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_OFFERS
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.exception.IntegrityConstraintViolationException
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

/** How many works have a tag, graded and liked among them. */
data class Counts(val works: Int, val graded: Int, val liked: Int)

/** A site's tag bound to the tag [normalized]: its name on the site and its works as last counted; no name — no work has it now. */
data class SiteTagCount(val site: SiteTagRef, val normalized: Long, val name: String?, val counts: Counts)

/** A tag's works as last counted, and the sources whose site's tags are bound to it. */
data class TagCount(val counts: Counts, val sources: List<String>)

/**
 * THE NORMALIZED TAGS OF EVERY CONTENT TYPE, in the user's dictionaries ([db]): every normalized
 * tag — an id and the name the user gave it, if any — every site's tag bound to one of them, the
 * graph of them, the pairs told apart; and the names worked out of the sites', made again from the
 * rest.
 *
 * A tag is known to the application — as a value of the works' tags, in the user's answers — by its
 * id, written as a text ([key]).
 */
class TagRepository(private val db: DSLContext) {

    /**
     * The writes of one action of the user on the tags — every one of them, or, failed, none: the
     * repository [block] is given writes in one transaction of the database of the user's word. A
     * write of a repository's own transaction inside it (a merge) is a part of it.
     */
    fun <T> transaction(block: (TagRepository) -> T): T = db.transactionResult { c -> block(TagRepository(DSL.using(c))) }

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
        // The names of the tag it leaves and of the one it joins are others now: their vectors made anew
        val was = db.select(SITE_TAG.NORMALIZED).from(SITE_TAG).where(SITE_TAG.SOURCE.eq(site.source), SITE_TAG.TAG.eq(site.tag.take(500))).fetchOne()?.value1()
        forgetVectors(listOfNotNull(was, id))
        db.insertInto(SITE_TAG).set(SITE_TAG.SOURCE, site.source).set(SITE_TAG.TAG, site.tag.take(500)).set(SITE_TAG.NORMALIZED, id).set(SITE_TAG.BOUND_AT, now)
            .onDuplicateKeyUpdate().set(SITE_TAG.NORMALIZED, id).set(SITE_TAG.BOUND_AT, now)
            .execute()
    }

    /**
     * The normalized tag [from] merged into [into]: every site's tag of it bound to [into], its links
     * of the graph, its pairs told apart and its entries of the catalogues moved to [into] — none of
     * [into] with itself — and [from] gone. The user's answers on it are in the sources' databases:
     * the merge is kept as one whose answers are still to be moved ([pendingMerges]) till the
     * caller has moved them ([answersMoved]).
     */
    fun merge(from: Long, into: Long, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        val m = NORMALIZED_TAG_MERGE
        tx.insertInto(m).set(m.FROM_TAG, from).set(m.INTO_TAG, into).set(m.MERGED_AT, now)
            .onDuplicateKeyUpdate().set(m.INTO_TAG, into).set(m.MERGED_AT, now).execute()
        tx.update(SITE_TAG).set(SITE_TAG.NORMALIZED, into).set(SITE_TAG.BOUND_AT, now).where(SITE_TAG.NORMALIZED.eq(from)).execute()
        // [into] has the names of both now
        tx.deleteFrom(TAG_VECTOR).where(TAG_VECTOR.TAG.eq(into)).execute()
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

    /** The merges whose answers are still to be moved, the earliest first: tag merged, tag kept. */
    fun pendingMerges(): List<Pair<Long, Long>> {
        val m = NORMALIZED_TAG_MERGE
        return db.select(m.FROM_TAG, m.INTO_TAG).from(m).orderBy(m.MERGED_AT).fetch { it.value1()!! to it.value2()!! }
    }

    /** The answers on the tag [from] merged are moved in every source: its merge done. */
    fun answersMoved(from: Long) {
        db.deleteFrom(NORMALIZED_TAG_MERGE).where(NORMALIZED_TAG_MERGE.FROM_TAG.eq(from)).execute()
    }

    // --- Names ---

    /** The name the user gives the normalized tag [id]; null takes it back. */
    fun name(id: Long, name: String?) {
        db.update(NORMALIZED_TAG).set(NORMALIZED_TAG.NAME, name?.take(1000)).where(NORMALIZED_TAG.ID.eq(id)).execute()
    }

    // --- Ignored ---

    /** Whether the user ignores the tag [id]: kept with its works, not read by the model of the grades. */
    fun ignore(id: Long, ignored: Boolean) {
        db.update(NORMALIZED_TAG).set(NORMALIZED_TAG.IGNORED, ignored).where(NORMALIZED_TAG.ID.eq(id)).execute()
    }

    /** The keys of the tags the user ignores. */
    fun ignored(): Set<String> =
        db.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).where(NORMALIZED_TAG.IGNORED.isTrue).fetchSet { it.value1()!!.toString() }

    /** The names the user gave, by id. */
    fun explicitNames(): Map<Long, String> =
        db.select(NORMALIZED_TAG.ID, NORMALIZED_TAG.NAME).from(NORMALIZED_TAG).where(NORMALIZED_TAG.NAME.isNotNull)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** What the tags of [keys] are shown by — the user's name, else the one worked out last — of every tag when null; by key. */
    fun shown(keys: Collection<String>?): Map<String, String> {
        val ids = keys?.mapNotNull(::idOf)
        if (ids != null && ids.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        fun worked(only: List<Long>?) = db.select(TAG_STAT.TAG, TAG_STAT.CALCULATED_NAME).from(TAG_STAT)
            .where(TAG_STAT.CALCULATED_NAME.isNotNull, only?.let { TAG_STAT.TAG.`in`(it) } ?: DSL.noCondition()).fetch()
        fun given(only: List<Long>?) = db.select(NORMALIZED_TAG.ID, NORMALIZED_TAG.NAME).from(NORMALIZED_TAG)
            .where(NORMALIZED_TAG.NAME.isNotNull, only?.let { NORMALIZED_TAG.ID.`in`(it) } ?: DSL.noCondition()).fetch()
        val chunks = ids?.distinct()?.chunked(BATCH) ?: listOf(null)
        chunks.forEach { chunk -> worked(chunk).forEach { out[key(it.value1()!!)] = it.value2()!! } }
        chunks.forEach { chunk -> given(chunk).forEach { out[key(it.value1()!!)] = it.value2()!! } }
        return out
    }

    /** The name a new tag is shown by until the names are worked out again: of the site's tag it is made for. */
    /**
     * THE TAGS THAT STILL ARE: a pass of the background counts the works for seconds, and a tag
     * merged or taken out meanwhile is gone — its statistics are not written.
     */
    private fun alive(tx: DSLContext): Set<Long> = tx.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG).fetchSet(NORMALIZED_TAG.ID).filterNotNull().toSet()

    /**
     * [write] done again once when a tag it writes of was taken out between its reading the tags
     * that are ([alive]) and its write — milliseconds; not locked, so a merge does not wait on it.
     */
    private fun <T> againIfGone(write: () -> T): T = try {
        write()
    } catch (e: IntegrityConstraintViolationException) {
        write()
    }

    fun showAs(id: Long, name: ShownName) {
        db.insertInto(TAG_STAT).set(TAG_STAT.TAG, id).set(TAG_STAT.CALCULATED_NAME, name.name.take(1000)).set(TAG_STAT.CALCULATED_RULE, name.rule.name)
            .onDuplicateKeyIgnore().execute()
    }

    /**
     * The names the application works the tags out to replaced by [named], kept with their statistics:
     * the label of an entry, else of the sites' names; the user's own name is no part of them.
     */
    fun replaceCalculated(named: Map<Long, ShownName>) = againIfGone { db.transaction { c ->
        val tx = DSL.using(c)
        val alive = alive(tx)
        // Only what changed: a name or its rule changed, a tag new — not the whole table every few seconds
        val stored = tx.select(TAG_STAT.TAG, TAG_STAT.CALCULATED_NAME, TAG_STAT.CALCULATED_RULE).from(TAG_STAT).fetch()
            .associate { it.value1()!! to (it.value2() to it.value3()) }
        named.entries.filter { (id, s) -> id in alive && stored[id] != (s.name.take(1000) to s.rule.name) }.chunked(BATCH).forEach { chunk ->
            tx.batch(chunk.map { (id, s) ->
                tx.insertInto(TAG_STAT).set(TAG_STAT.TAG, id).set(TAG_STAT.CALCULATED_NAME, s.name.take(1000)).set(TAG_STAT.CALCULATED_RULE, s.rule.name)
                    .onDuplicateKeyUpdate().set(TAG_STAT.CALCULATED_NAME, s.name.take(1000)).set(TAG_STAT.CALCULATED_RULE, s.rule.name)
            }).execute()
        }
    } }

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

    /** The offers of the tags' graph as last worked out, as JSON; null before the first. */
    fun offers(): String? = db.select(TAG_OFFERS.OFFERS).from(TAG_OFFERS).where(TAG_OFFERS.ID.eq(OFFERS_ROW)).fetchOne(TAG_OFFERS.OFFERS)

    /** Keeps the offers of the tags' graph worked out [now], as JSON, over those before. */
    fun keepOffers(offers: String, now: Instant) {
        db.insertInto(TAG_OFFERS).set(TAG_OFFERS.ID, OFFERS_ROW).set(TAG_OFFERS.MADE_AT, now).set(TAG_OFFERS.OFFERS, offers)
            .onDuplicateKeyUpdate().set(TAG_OFFERS.MADE_AT, now).set(TAG_OFFERS.OFFERS, offers)
            .execute()
    }

    /** The entries the user said are too general to be a tag above others. */
    fun tooGeneral(): Set<TagEntryRef> {
        val g = TAG_ENTRY_TOO_GENERAL
        return db.select(g.CATALOGUE, g.ENTRY).from(g).fetch { TagEntryRef(it.value1()!!, it.value2()!!) }.toSet()
    }

    /** The user's word that [entry] is too general to be a tag above others — or, [tooGeneral] false, its taking back. */
    fun setTooGeneral(entry: TagEntryRef, tooGeneral: Boolean, now: Instant) {
        val g = TAG_ENTRY_TOO_GENERAL
        if (tooGeneral) db.insertInto(g).set(g.CATALOGUE, entry.catalogue).set(g.ENTRY, entry.entry).set(g.SAID_AT, now).onDuplicateKeyIgnore().execute()
        else db.deleteFrom(g).where(g.CATALOGUE.eq(entry.catalogue), g.ENTRY.eq(entry.entry)).execute()
    }

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
        // The entry's names are the tag's, or no more
        forgetVectors(listOf(id))
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
        // The names of the tags bound to it may be others now
        val e = NORMALIZED_TAG_ENTRY
        tx.deleteFrom(TAG_VECTOR).where(TAG_VECTOR.TAG.`in`(DSL.select(e.TAG).from(e).where(e.CATALOGUE.eq(catalogue), e.ENTRY.eq(entry.id)))).execute()
    }

    // --- The statistics of the tags ---

    /** Every site's tag bound to a tag, with its name and its works as last counted ([keepStats]). */
    fun siteTags(): List<SiteTagCount> =
        db.select(SITE_TAG.SOURCE, SITE_TAG.TAG, SITE_TAG.NORMALIZED, SITE_TAG.NAME, SITE_TAG.WORKS, SITE_TAG.GRADED, SITE_TAG.LIKED).from(SITE_TAG)
            .fetch { SiteTagCount(SiteTagRef(it.value1()!!, it.value2()!!), it.value3()!!, it.value4(), Counts(it.value5()!!, it.value6()!!, it.value7()!!)) }

    /** Every tag's works and the sources of its sites' tags, as last counted ([keepStats]). */
    fun counts(): Map<Long, TagCount> =
        db.select(TAG_STAT.TAG, TAG_STAT.WORKS, TAG_STAT.GRADED, TAG_STAT.LIKED, TAG_STAT.SOURCES).from(TAG_STAT)
            .fetchMap({ it.value1()!! }, { TagCount(Counts(it.value2()!!, it.value3()!!, it.value4()!!), it.value5()!!.filterNotNull()) })

    /**
     * The statistics counted now in place of those kept, the rows that changed alone: of every
     * site's tag counted its name and works — of one not counted, no name and none — and of every tag
     * its works and the sources of its sites' tags.
     */
    fun keepStats(sites: Map<SiteTagRef, Pair<String, Counts>>, tags: Map<Long, TagCount>) = againIfGone { db.transaction { c ->
        val tx = DSL.using(c)
        val st = SITE_TAG
        tx.select(st.SOURCE, st.TAG, st.NAME, st.WORKS, st.GRADED, st.LIKED).from(st).fetch().forEach { r ->
            val site = SiteTagRef(r.value1()!!, r.value2()!!)
            val (name, counts) = sites[site] ?: (null to Counts(0, 0, 0))
            if (r.value3() == name && Counts(r.value4()!!, r.value5()!!, r.value6()!!) == counts) return@forEach
            tx.update(st).set(st.NAME, name?.take(500)).set(st.WORKS, counts.works).set(st.GRADED, counts.graded).set(st.LIKED, counts.liked)
                .where(st.SOURCE.eq(site.source), st.TAG.eq(site.tag)).execute()
        }
        val alive = alive(tx)
        val kept = TagRepository(tx).counts()
        val ts = TAG_STAT
        tags.forEach { (tag, count) ->
            if (tag !in alive || kept[tag] == count) return@forEach
            val sources = count.sources.toTypedArray<String?>()
            tx.insertInto(ts).set(ts.TAG, tag).set(ts.WORKS, count.counts.works).set(ts.GRADED, count.counts.graded).set(ts.LIKED, count.counts.liked).set(ts.SOURCES, sources)
                .onDuplicateKeyUpdate().set(ts.WORKS, count.counts.works).set(ts.GRADED, count.counts.graded).set(ts.LIKED, count.counts.liked).set(ts.SOURCES, sources)
                .execute()
        }
    } }

    // --- The vectors of the tags ---

    /** The vectors of the tags [ids] dropped: their names changed, they are made anew in the background. */
    fun forgetVectors(ids: Collection<Long>) {
        if (ids.isNotEmpty()) db.deleteFrom(TAG_VECTOR).where(TAG_VECTOR.TAG.`in`(ids)).execute()
    }

    /** The tags that have no vector by [plugin]'s [model]: a join, not a read of every tag. */
    fun withoutVectors(plugin: String, model: String): List<Long> {
        val v = TAG_VECTOR
        return db.select(NORMALIZED_TAG.ID).from(NORMALIZED_TAG)
            .leftJoin(v).on(v.TAG.eq(NORMALIZED_TAG.ID), v.PLUGIN.eq(plugin), v.MODEL.eq(model))
            .where(v.TAG.isNull).fetch { it.value1()!! }
    }

    /** Keeps the vectors of the tags, one of every name of each; none: a tag of no name to encode. */
    fun keepVectors(plugin: String, model: String, vectors: Map<Long, List<FloatArray>>) = db.transaction { c ->
        val tx = DSL.using(c)
        val v = TAG_VECTOR
        vectors.forEach { (tag, list) ->
            val packed = list.map(Vectors::half).toTypedArray<ByteArray?>()
            tx.insertInto(v).set(v.TAG, tag).set(v.PLUGIN, plugin).set(v.MODEL, model).set(v.VECTORS, packed)
                .onDuplicateKeyUpdate().set(v.VECTORS, packed).execute()
        }
    }

    /** The vectors of every tag's names by [plugin]'s [model], by key: the tags of no name left out. */
    fun vectors(plugin: String, model: String): Map<String, List<FloatArray>> {
        val v = TAG_VECTOR
        return db.select(v.TAG, v.VECTORS).from(v).where(v.PLUGIN.eq(plugin), v.MODEL.eq(model)).fetch()
            .mapNotNull { r -> r.value2()!!.mapNotNull { it?.let(Vectors::fromHalf) }.takeIf { it.isNotEmpty() }?.let { key(r.value1()!!) to it } }
            .toMap()
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

        /** The one row of the offers of the tags' graph. */
        private const val OFFERS_ROW = 1
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
