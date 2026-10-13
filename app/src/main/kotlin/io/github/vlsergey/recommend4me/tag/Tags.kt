package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.layer.LayerValues
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private val log = LoggerFactory.getLogger(Tags::class.java)

/** How many works have a name of a tag as a site writes it: all of them, the graded, the liked. */
class Uses {
    var works = 0
    var graded = 0
    var liked = 0
}

/** The user merged, named, linked or told apart tags of a content type: what the works have changed. */
data class TagsChanged(val type: String)

/** A name of a tag as a site writes it, and its works there. */
class TagVariant(val source: String, val name: String, val uses: Uses)

/** A tag of the application: its key, the names it has on the sites, the tags merged into it, its works. */
class TagStats(val key: String) {
    val variants = LinkedHashMap<Pair<String, String>, TagVariant>()
    val members = LinkedHashSet<String>()
    val items = HashSet<ItemKey>()
    val graded = HashSet<ItemKey>()
    val liked = HashSet<ItemKey>()

    fun variant(source: String, name: String) = variants.getOrPut(source to name) { TagVariant(source, name, Uses()) }

    /** Its works: of a tag merged into another, counted by its names — its works are the other's. */
    fun works(): Int = if (items.isNotEmpty()) items.size else variants.values.sumOf { it.uses.works }
    fun gradedWorks(): Int = if (items.isNotEmpty()) graded.size else variants.values.sumOf { it.uses.graded }
    fun likedWorks(): Int = if (items.isNotEmpty()) liked.size else variants.values.sumOf { it.uses.liked }
}

/** A tag named, with the tags directly above and under it, named. */
class NamedTag(
    val stats: TagStats,
    val name: ShownName,
    val explicit: String?,
    val parents: List<Pair<String, String>> = emptyList(),
    val children: List<Pair<String, String>> = emptyList(),
)

/** What the user said a tag is to another. */
enum class TagRelation {
    NONE,

    /** Merged into it: one tag. */
    MERGED,

    /** Under it: a work with this one has the other. */
    CHILD,

    /** Above it: a work with the other has this one. */
    PARENT,

    /** A different tag, not offered as alike. */
    DIFFERENT,
}

/** A tag alike another by the meaning of their names, 0..1, and what the user said it is to the other. */
class SimilarTag(val tag: NamedTag, val similarity: Double, val relation: TagRelation)

/**
 * THE SITES' FREE TAGS OF A CONTENT TYPE, EVERY SITE'S TOGETHER, AS THE USER MERGES AND NAMES THEM.
 * A tag of the application is a site's tag by its name, any case — "Магия" of one site and "магия"
 * of another one tag; the user merges tags that are one ("Angst" and "Ангст"), takes a tag out of
 * one, tells two apart. The application only offers tags alike by the meaning of their names,
 * within one site and across them; it merges nothing by itself.
 *
 * A tag is shown by the name the user gave it, else by its most used name on the sites ([TagNaming]).
 * The names worked out are kept in the database and worked out again after the works and their
 * grades change; nothing is kept in memory.
 */
@Service
class Tags(private val stores: Stores, private val phrases: PhraseVectors, private val events: ApplicationEventPublisher) {

    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-names").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** The facet of the layer learnt from the sites' tags of a type; null for a type without one. */
    fun facetOf(type: TypeStore): String? =
        type.sources.firstNotNullOfOrNull { s -> s.schema.facets.firstOrNull { it.editable && it.examplesFrom != null } }?.key

    // --- Reading ---

    /** Every tag of the type with its names on the sites and its works: one pass over every source. */
    fun stats(type: TypeStore, facet: String): Map<String, TagStats> {
        val merges = type.tags.merges(facet)
        val out = HashMap<String, TagStats>()
        type.sources.forEach { s ->
            val layer = s.schema.facets.firstOrNull { it.key == facet } ?: return@forEach
            val siteFacet = layer.examplesFrom ?: return@forEach
            val names = s.names(siteFacet)
            // The latest grade of every item, of any version: the work as the user graded it last
            val grades = s.ratings.all().groupBy { it.itemId }.mapValues { (_, r) -> r.maxBy { it.ratedAt }.grade }
            val fromSite = HashMap<String, MutableSet<String>>()
            /** A work of [tag]; of its name [written] when it is one of the tag's — not the name of an answer moved from a tag taken out since. */
            fun count(id: String, written: String?, tag: String) {
                val stats = out.getOrPut(tag) { TagStats(tag) }
                val key = ItemKey(s.id, id)
                val uses = written?.let { stats.variant(s.id, it).uses }
                uses?.let { it.works++ }
                stats.items += key
                grades[id]?.let { g ->
                    uses?.let { it.graded++ }
                    stats.graded += key
                    if (liked(g)) {
                        uses?.let { it.liked++ }
                        stats.liked += key
                    }
                }
            }
            s.items.forEachFacet(siteFacet) { id, keys ->
                keys.forEach { k ->
                    val written = (names[k] ?: k).trim()
                    val tag = LayerValues.keyOf(written, merges)
                    if (fromSite.getOrPut(id) { HashSet() }.add(tag)) count(id, written, tag)
                }
            }
            // A tag the user gave a work the site does not: of the user's own words
            s.corrections.allFacets().filter { it.facet == facet && it.added }.forEach { c ->
                val tag = merges[c.key] ?: c.key
                val written = (c.name ?: c.key).trim()
                if (fromSite[c.itemId]?.contains(tag) != true) count(c.itemId, written.takeIf { LayerValues.keyOf(it, merges) == tag }, tag)
            }
        }
        // The tags merged into another are the user's merges alone, whatever names the works have
        merges.forEach { (member, tag) -> out[tag]?.members?.add(member) }
        return out
    }

    /** What every tag of one reading is named: the stats, the user's names, the tags above and under them. */
    private class Naming(val stats: Map<String, TagStats>, val explicit: Map<String, String>, val hierarchy: TagHierarchy) {
        fun shown(key: String): String = stats[key]?.let { TagNaming.name(it, explicit[key]).name } ?: explicit[key] ?: key

        fun named(t: TagStats) = NamedTag(
            t, TagNaming.name(t, explicit[t.key]), explicit[t.key],
            parents = hierarchy.parentsOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            children = hierarchy.childrenOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
        )
    }

    private fun naming(type: TypeStore, facet: String, stats: Map<String, TagStats> = stats(type, facet)) =
        Naming(stats, type.tags.explicitNames(facet), TagHierarchy(type.tags.parents(facet)))

    /** The tags of a type with their names, the most used first; [query] — of a name of theirs on a site, any case. */
    fun list(type: TypeStore, facet: String, query: String?): List<NamedTag> {
        val n = naming(type, facet)
        val wanted = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return n.stats.values
            .filter { t -> wanted == null || t.key.contains(wanted) || t.variants.keys.any { it.second.lowercase().contains(wanted) } || n.explicit[t.key]?.lowercase()?.contains(wanted) == true }
            .map(n::named)
            .sortedWith(compareByDescending<NamedTag> { it.stats.items.size }.thenBy { it.name.name.lowercase() })
    }

    fun one(type: TypeStore, facet: String, tag: String): NamedTag? {
        val n = naming(type, facet)
        return n.stats[tag]?.let(n::named)
    }

    /**
     * The tags most alike [tag] by the meaning of their names, of one site or of another — a tag's
     * names on the sites encoded as the search encodes short texts, a tag the mean of its names'
     * directions, alike by the cosine — with every tag the user said something of to it besides
     * (merged into it, under or above it, different): those stay among them, marked, whatever their
     * likeness.
     */
    fun similar(type: TypeStore, facet: String, tag: String, limit: Int): List<SimilarTag> {
        val stats = stats(type, facet)
        val own = stats[tag] ?: return emptyList()
        val n = naming(type, facet, stats)
        val distinct = type.tags.distinct(facet)
        // A tag merged into this one is shown by its own names among the tag's
        val members = own.members.map { m ->
            TagStats(m).also { s -> own.variants.forEach { (k, v) -> if (LayerValues.keyOf(v.name) == m) s.variants[k] = v } }
        }
        fun relation(key: String): TagRelation = when {
            key in own.members -> TagRelation.MERGED
            key in n.hierarchy.childrenOf(tag) -> TagRelation.CHILD
            key in n.hierarchy.parentsOf(tag) -> TagRelation.PARENT
            TagRepository.pair(tag, key) in distinct -> TagRelation.DIFFERENT
            else -> TagRelation.NONE
        }
        // Every name of every tag, encoded once and kept in the database of the source it is of
        val vectors = HashMap<Pair<String, String>, FloatArray>()
        type.sources.forEach { s ->
            val names = stats.values.flatMap { t -> t.variants.keys.filter { it.first == s.id }.map { it.second } }.distinct()
            if (names.isNotEmpty()) phrases.of(s, names).forEach { (name, v) -> vectors[s.id to name] = v }
        }
        fun direction(t: TagStats): FloatArray? {
            val list = t.variants.keys.mapNotNull { vectors[it] }
            if (list.isEmpty()) return null
            val sum = FloatArray(list.first().size)
            list.forEach { v ->
                val n = norm(v).takeIf { it > 0 } ?: return@forEach
                for (i in sum.indices) sum[i] += v[i] / n
            }
            val n = norm(sum).takeIf { it > 0 } ?: return null
            return FloatArray(sum.size) { sum[it] / n }
        }
        val target = direction(own)
        fun likeness(t: TagStats): Double = target?.let { tv -> direction(t)?.let { d -> d.indices.sumOf { (d[it] * tv[it]).toDouble() } } } ?: 0.0
        // A row a tag: a member is shown by its names among this tag's, never besides as a tag of its own too
        val all = (stats.values.filter { it.key != tag && it.key !in own.members } + members).map { t -> Triple(t, likeness(t), relation(t.key)) }
        val shown = all.sortedByDescending { it.second }.take(limit).map { it.first.key }.toSet()
        return all.filter { it.first.key in shown || it.third != TagRelation.NONE }
            .sortedByDescending { it.second }
            .map { (t, s, r) -> SimilarTag(n.named(t), s, r) }
    }

    /**
     * What the user says [other] is to [tag] — merged into it, under it, above it, different, or
     * nothing — in place of what was said of the two before. A tag is never put above or under one of
     * its own descendants or ancestors.
     */
    fun relate(type: TypeStore, facet: String, tag: String, other: String, relation: TagRelation): NamedTag? {
        require(tag != other) { "Тег не может быть связан сам с собой" }
        val before = stats(type, facet)
        val now = Instant.now()
        val merged = type.tags.merges(facet)[other] == tag
        if (merged && relation == TagRelation.MERGED) return one(type, facet, tag)
        if (merged) type.tags.split(facet, other)
        type.tags.setParent(facet, other, tag, false, now)
        type.tags.setParent(facet, tag, other, false, now)
        type.tags.setDistinct(facet, tag, other, false, now)
        val hierarchy = TagHierarchy(type.tags.parents(facet))
        when (relation) {
            TagRelation.MERGED -> return merge(type, facet, listOf(tag, other), null)
            TagRelation.CHILD -> {
                require(other !in hierarchy.ancestors(tag)) { "Этот тег уже выше — подтегом он стал бы своим же надтегом" }
                type.tags.setParent(facet, other, tag, true, now)
            }
            TagRelation.PARENT -> {
                require(other !in hierarchy.descendants(tag)) { "Этот тег уже ниже — надтегом он стал бы своим же подтегом" }
                type.tags.setParent(facet, tag, other, true, now)
            }
            TagRelation.DIFFERENT -> type.tags.setDistinct(facet, tag, other, true, now)
            TagRelation.NONE -> Unit
        }
        log.info("{}: {} is {} to {}", type.id, other, relation, tag)
        changed(type, facet, before[tag]?.items.orEmpty() + before[other]?.items.orEmpty())
        return one(type, facet, tag)
    }

    // --- The user's word ---

    /**
     * [tags] merged into the first of them, and named [name] when given: the user's answers on them
     * become answers on it — the newest stands of a work answered on more than one — and their works
     * are its works. Returns the tag.
     */
    fun merge(type: TypeStore, facet: String, tags: List<String>, name: String?): NamedTag? {
        val into = tags.firstOrNull() ?: return null
        val before = stats(type, facet)
        val members = tags.drop(1).filter { it != into }.distinct()
        val merges = type.tags.merges(facet)
        // What was merged into a member before goes with it
        val moved = members + merges.filterValues { it in members }.keys
        type.tags.merge(facet, into, members, Instant.now())
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { type.tags.name(facet, into, it) }
        type.sources.forEach { s -> s.corrections.moveFacetValues(facet, moved, into) }
        log.info("{}: tags {} merged into {}", type.id, moved, into)
        changed(type, facet, (members + into).flatMap { before[it]?.items.orEmpty() })
        return one(type, facet, into)
    }

    /** [member] taken out of the tag it was merged into: a tag of its own again; the user's answers stay with the tag. */
    fun split(type: TypeStore, facet: String, member: String): Boolean {
        val tag = type.tags.merges(facet)[member] ?: return false
        val items = stats(type, facet)[tag]?.items.orEmpty()
        type.tags.split(facet, member)
        log.info("{}: tag {} taken out of {}", type.id, member, tag)
        changed(type, facet, items)
        return true
    }

    /** The name the user gives [tag]; null or blank takes it back — the tag is named by its sites again. */
    fun name(type: TypeStore, facet: String, tag: String, name: String?): NamedTag? {
        type.tags.name(facet, tag, name?.trim()?.takeIf { it.isNotEmpty() })
        val named = one(type, facet, tag)
        changed(type, facet, named?.stats?.items.orEmpty())
        return named
    }

    /** The user's word that two tags are different — not offered as alike again — or its taking back. */
    fun setDistinct(type: TypeStore, facet: String, a: String, b: String, different: Boolean) =
        type.tags.setDistinct(facet, a, b, different, Instant.now())

    /** The tags changed: their names worked out again now, their works read again — their chances, their search — and the model of the grades trained again. */
    private fun changed(type: TypeStore, facet: String, items: Collection<ItemKey>) {
        rename(type, facet)
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) -> events.publishEvent(ItemsChanged(source, ids)) }
        events.publishEvent(TagsChanged(type.id))
    }

    // --- The names worked out ---

    /** The names of every tag of the type worked out now and kept. */
    fun rename(type: TypeStore, facet: String) {
        val started = System.currentTimeMillis()
        val stats = stats(type, facet)
        val explicit = type.tags.explicitNames(facet)
        type.tags.replaceShown(facet, stats.mapValues { (key, t) -> TagNaming.name(t, explicit[key]) })
        log.info("{}: names of {} tags worked out in {} ms", type.id, stats.size, System.currentTimeMillis() - started)
    }

    /** The names worked out again shortly after the last of a quick series of changes. */
    private fun scheduleRename(typeId: String) {
        pending.compute(typeId) { _, old ->
            old?.cancel(false)
            scheduler.schedule({
                try {
                    val type = stores.type(typeId) ?: return@schedule
                    facetOf(type)?.let { rename(type, it) }
                } catch (e: Exception) {
                    log.warn("{}: the names of the tags were not worked out: {}", typeId, e.message, e)
                }
            }, RENAME_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    @EventListener
    fun gradesChanged(event: GradesChanged) = scheduleRename(event.type)

    @EventListener
    fun itemsChanged(event: ItemsChanged) = scheduleRename(stores.typeOf(event.source).id)

    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.forEach { scheduleRename(it.id) }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        /** The quiet after the last change before the names are worked out again: a page is sent several times as it grows. */
        private const val RENAME_DELAY_SECONDS = 10L

        /** A grade above the middle of the scale: of five, 4 and 5. */
        fun liked(grade: Int): Boolean = grade > (Grades.MIN + Grades.MAX) / 2.0

        private fun norm(v: FloatArray): Float = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
    }
}

/**
 * WHAT A TAG IS SHOWN BY, the user's order: the name the user gave it; else its name on the sites
 * most used by the works the user liked (graded above the middle of the scale), by the works graded,
 * by all its works; else the first by the alphabet. Of names used equally, the first by the alphabet.
 */
object TagNaming {
    fun name(t: TagStats, explicit: String?): ShownName {
        if (explicit != null) return ShownName(explicit, NameRule.EXPLICIT)
        // A name as written, of every site together
        val byName = HashMap<String, Uses>()
        t.variants.values.forEach { v ->
            val u = byName.getOrPut(v.name) { Uses() }
            u.works += v.uses.works
            u.graded += v.uses.graded
            u.liked += v.uses.liked
        }
        fun most(rule: NameRule, of: (Uses) -> Int): ShownName? {
            val best = byName.maxOfOrNull { of(it.value) }?.takeIf { it > 0 } ?: return null
            return ShownName(byName.filter { of(it.value) == best }.keys.min(), rule)
        }
        return most(NameRule.LIKED) { it.liked } ?: most(NameRule.GRADED) { it.graded } ?: most(NameRule.ALL) { it.works }
            ?: ShownName(byName.keys.minOrNull() ?: t.key, NameRule.ALPHABET)
    }
}
