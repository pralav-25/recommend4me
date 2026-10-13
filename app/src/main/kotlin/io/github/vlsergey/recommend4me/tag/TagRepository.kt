package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_DISTINCT
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_MERGE
import io.github.vlsergey.recommend4me.database.typecorrections.tables.references.TAG_NAME
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant

/** Which rule gave a tag its name ([TagNaming]). */
enum class NameRule { EXPLICIT, LIKED, GRADED, ALL, ALPHABET }

/** A tag's name worked out, with the rule that gave it. */
data class ShownName(val name: String, val rule: NameRule)

/**
 * The user's word on the sites' free tags of a content type, in its database of corrections: the tags merged into
 * others, the names given, the pairs told apart — and the names worked out, kept beside them.
 */
class TagRepository(private val db: DSLContext) {

    /** Every tag merged into another: member to the tag it is. */
    fun merges(facet: String): Map<String, String> =
        db.select(TAG_MERGE.MEMBER, TAG_MERGE.TAG).from(TAG_MERGE).where(TAG_MERGE.FACET.eq(facet)).fetchMap({ it.value1()!! }, { it.value2()!! })

    /**
     * [members] merged into [tag], with the tags merged into them before: every one of them points
     * at [tag] now. [tag] itself is merged into nothing.
     */
    fun merge(facet: String, tag: String, members: Collection<String>, now: Instant) = db.transaction { c ->
        val tx = DSL.using(c)
        val m = TAG_MERGE
        val all = members.filter { it != tag }.toSet()
        tx.deleteFrom(m).where(m.FACET.eq(facet), m.MEMBER.eq(tag)).execute()
        if (all.isEmpty()) return@transaction
        // What was merged into a member is merged into the tag now
        tx.update(m).set(m.TAG, tag).where(m.FACET.eq(facet), m.TAG.`in`(all)).execute()
        all.forEach { member ->
            tx.insertInto(m).set(m.FACET, facet).set(m.MEMBER, member).set(m.TAG, tag).set(m.MERGED_AT, now)
                .onDuplicateKeyUpdate().set(m.TAG, tag).set(m.MERGED_AT, now)
                .execute()
        }
        // A member's own name is no tag's any more
        tx.deleteFrom(TAG_NAME).where(TAG_NAME.FACET.eq(facet), TAG_NAME.TAG.`in`(all)).execute()
    }

    /** [member] taken out of the tag it was merged into: a tag of its own again. */
    fun split(facet: String, member: String): Boolean =
        db.deleteFrom(TAG_MERGE).where(TAG_MERGE.FACET.eq(facet), TAG_MERGE.MEMBER.eq(member)).execute() > 0

    /** The name the user gave [tag]; null takes it back. */
    fun name(facet: String, tag: String, name: String?) {
        val n = TAG_NAME
        db.insertInto(n).set(n.FACET, facet).set(n.TAG, tag).set(n.NAME, name?.take(1000))
            .onDuplicateKeyUpdate().set(n.NAME, name?.take(1000))
            .execute()
    }

    /** The names the user gave, by tag. */
    fun explicitNames(facet: String): Map<String, String> =
        db.select(TAG_NAME.TAG, TAG_NAME.NAME).from(TAG_NAME).where(TAG_NAME.FACET.eq(facet), TAG_NAME.NAME.isNotNull)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    /** What the [tags] are shown by: the user's name, else the one worked out last; every tag's when null. */
    fun shown(facet: String, tags: Collection<String>?): Map<String, String> {
        val n = TAG_NAME
        fun read(only: Collection<String>?) = db.select(n.TAG, n.NAME, n.SHOWN).from(n)
            .where(n.FACET.eq(facet), only?.let { n.TAG.`in`(it) } ?: DSL.noCondition()).fetch()
        val rows = if (tags == null) read(null) else tags.distinct().chunked(BATCH).flatMap(::read)
        return rows.mapNotNull { r -> (r.value2() ?: r.value3())?.let { r.value1()!! to it } }.toMap()
    }

    /** The names worked out of every tag replaced by [named]: a tag no longer named loses its worked-out name, keeps the user's. */
    fun replaceShown(facet: String, named: Map<String, ShownName>) = db.transaction { c ->
        val tx = DSL.using(c)
        val n = TAG_NAME
        tx.update(n).setNull(n.SHOWN).setNull(n.RULE).where(n.FACET.eq(facet)).execute()
        tx.deleteFrom(n).where(n.FACET.eq(facet), n.NAME.isNull).execute()
        named.entries.chunked(BATCH).forEach { chunk ->
            chunk.forEach { (tag, s) ->
                tx.insertInto(n).set(n.FACET, facet).set(n.TAG, tag).set(n.SHOWN, s.name.take(1000)).set(n.RULE, s.rule.name)
                    .onDuplicateKeyUpdate().set(n.SHOWN, s.name.take(1000)).set(n.RULE, s.rule.name)
                    .execute()
            }
        }
    }

    /** Every pair of tags the user said are different, in the order of their keys. */
    fun distinct(facet: String): Set<Pair<String, String>> =
        db.select(TAG_DISTINCT.FIRST_TAG, TAG_DISTINCT.OTHER_TAG).from(TAG_DISTINCT).where(TAG_DISTINCT.FACET.eq(facet))
            .fetch { it.value1()!! to it.value2()!! }.toSet()

    /** The user's word that [a] and [b] are different tags — or, [different] false, its taking back. */
    fun setDistinct(facet: String, a: String, b: String, different: Boolean, now: Instant) {
        val (x, y) = pair(a, b)
        val d = TAG_DISTINCT
        if (different) {
            db.insertInto(d).set(d.FACET, facet).set(d.FIRST_TAG, x).set(d.OTHER_TAG, y).set(d.SAID_AT, now)
                .onDuplicateKeyIgnore().execute()
        } else {
            db.deleteFrom(d).where(d.FACET.eq(facet), d.FIRST_TAG.eq(x), d.OTHER_TAG.eq(y)).execute()
        }
    }

    companion object {
        private const val BATCH = 1000

        /** A pair of tags in the order of their keys. */
        fun pair(a: String, b: String): Pair<String, String> = if (a <= b) a to b else b to a
    }
}
