package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.model.tables.references.TAG_SHOWN_NAME
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG_DISTINCT
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.NORMALIZED_TAG_PARENT
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.SITE_TAG
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_DISTINCT
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_MERGE
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_NAME
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_PARENT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** Which rule gave a tag its name ([TagNaming]). */
enum class NameRule { EXPLICIT, LIKED, GRADED, ALL, ALPHABET }

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
     * of the graph and its pairs told apart moved to [into] — none of [into] with itself — and [from]
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

    // --- The tables before the normalized tags: read once to move what they say, then emptied ---

    /** Whether anything is left of the tables before the normalized tags. */
    fun legacyLeft(): Boolean =
        db.fetchExists(TAG_MERGE) || db.fetchExists(TAG_NAME) || db.fetchExists(TAG_PARENT) || db.fetchExists(TAG_DISTINCT)

    /** A tag's name lowercased to the one it was merged into. */
    fun legacyMerges(): Map<String, String> = db.select(TAG_MERGE.MEMBER, TAG_MERGE.TAG).from(TAG_MERGE).fetchMap({ it.value1()!! }, { it.value2()!! })

    fun legacyNames(): Map<String, String> =
        db.select(TAG_NAME.TAG, TAG_NAME.NAME).from(TAG_NAME).where(TAG_NAME.NAME.isNotNull).fetchMap({ it.value1()!! }, { it.value2()!! })

    fun legacyParents(): List<Pair<String, String>> = db.select(TAG_PARENT.CHILD, TAG_PARENT.PARENT).from(TAG_PARENT).fetch { it.value1()!! to it.value2()!! }

    fun legacyDistinct(): List<Pair<String, String>> = db.select(TAG_DISTINCT.FIRST_TAG, TAG_DISTINCT.OTHER_TAG).from(TAG_DISTINCT).fetch { it.value1()!! to it.value2()!! }

    fun clearLegacy() = db.transaction { c ->
        val tx = DSL.using(c)
        listOf(TAG_MERGE, TAG_NAME, TAG_PARENT, TAG_DISTINCT).forEach { tx.deleteFrom(it).execute() }
    }

    companion object {
        private const val BATCH = 1000

        /** A normalized tag as a value of the works' tags: its id written out. */
        fun key(id: Long): String = id.toString()

        /** The id of a value of the works' tags; null for one that is no normalized tag. */
        fun idOf(key: String): Long? = key.toLongOrNull()

        /** A pair of tags, the smaller id first. */
        fun pair(a: Long, b: Long): Pair<Long, Long> = if (a <= b) a to b else b to a
    }
}
