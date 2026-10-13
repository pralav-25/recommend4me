package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
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
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

private val log = LoggerFactory.getLogger(Tags::class.java)

/** How many works have a site's tag: all of them, the graded, the liked. */
class Uses {
    var works = 0
    var graded = 0
    var liked = 0
}

/** The user merged, named, linked or told apart tags of a content type: what the works have changed. */
data class TagsChanged(val type: String)

/** A site's tag bound to a normalized tag: its source, the site's key of it, its name there, its works. */
class TagVariant(val site: SiteTagRef, val name: String, val uses: Uses)

/** A normalized tag: its key (its id written out), the sites' tags bound to it, its works. */
class TagStats(val key: String) {
    val variants = LinkedHashMap<SiteTagRef, TagVariant>()
    val items = HashSet<ItemKey>()
    val graded = HashSet<ItemKey>()
    val liked = HashSet<ItemKey>()

    val id: Long get() = TagRepository.idOf(key)!!

    fun variant(site: SiteTagRef, name: String) = variants.getOrPut(site) { TagVariant(site, name, Uses()) }
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

    /** One tag: a site's tag bound to it, or a tag merged into it. */
    MERGED,

    /** Under it: a work with this one has the other. */
    CHILD,

    /** Above it: a work with the other has this one. */
    PARENT,

    /** A different tag, not offered as alike. */
    DIFFERENT,
}

/**
 * A row of a tag's table of the alike: another normalized tag alike it — or [site], a site's tag
 * bound to it — with how alike, 0..1, and what the user said it is to the tag.
 */
class SimilarTag(val tag: NamedTag, val similarity: Double, val relation: TagRelation, val site: TagVariant? = null)

/**
 * THE NORMALIZED TAGS OF A CONTENT TYPE ([TagRepository]), AS THE USER MERGES, NAMES AND LINKS THEM.
 * Every site's tag is bound to a normalized tag ([TagNormalization]); the user merges normalized
 * tags — binds the sites' tags of one to another — takes a site's tag out to a tag of its own, puts
 * tags under others, tells two apart. The application only offers tags alike by the meaning of their
 * names, within one site and across them; it merges nothing by itself.
 *
 * A tag is shown by the name the user gave it, else by its most used name on the sites ([TagNaming]):
 * worked out after the works and their grades change and kept in the model database; nothing is kept
 * in memory.
 */
@Service
class Tags(
    private val stores: Stores,
    private val normalization: TagNormalization,
    private val phrases: PhraseVectors,
    private val events: ApplicationEventPublisher,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-names").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** The facet of the layer learnt from the sites' tags of a type; null for a type without one. */
    fun facetOf(type: TypeStore): String? = normalization.facetOf(type)

    // --- Reading ---

    /** Every normalized tag of the type with its sites' tags and its works: one pass over every source. */
    fun stats(type: TypeStore, facet: String): Map<String, TagStats> {
        val bindings = type.tags.bindings()
        val out = HashMap<String, TagStats>()
        type.sources.forEach { s ->
            val siteFacet = normalization.siteFacetOf(s, facet) ?: return@forEach
            val names = s.names(siteFacet)
            // The latest grade of every item, of any version: the work as the user graded it last
            val grades = s.ratings.all().groupBy { it.itemId }.mapValues { (_, r) -> r.maxBy { it.ratedAt }.grade }
            val fromSite = HashMap<String, MutableSet<String>>()
            /** A work of [tag]; through the site's tag [site] when it is the site's, not the user's word alone. */
            fun count(id: String, tag: String, site: SiteTagRef?, written: String?) {
                val stats = out.getOrPut(tag) { TagStats(tag) }
                val key = ItemKey(s.id, id)
                val uses = site?.let { stats.variant(it, written!!).uses }
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
                    val site = SiteTagRef(s.id, k)
                    val tag = bindings[site]?.let(TagRepository::key) ?: return@forEach
                    if (fromSite.getOrPut(id) { HashSet() }.add(tag)) count(id, tag, site, (names[k] ?: k).trim())
                }
            }
            // A tag the user gave a work the site does not
            s.corrections.allFacets().filter { it.facet == facet && it.added && TagRepository.idOf(it.key) != null }.forEach { c ->
                if (fromSite[c.itemId]?.contains(c.key) != true) count(c.itemId, c.key, null, null)
            }
        }
        return out
    }

    /** What every tag of one reading is named: the stats, the user's names, the tags above and under them. */
    private class Naming(val stats: Map<String, TagStats>, val explicit: Map<Long, String>, val hierarchy: TagHierarchy) {
        fun explicitOf(key: String): String? = TagRepository.idOf(key)?.let(explicit::get)

        fun shown(key: String): String = stats[key]?.let { TagNaming.name(it, explicitOf(key)).name } ?: explicitOf(key) ?: key

        fun named(t: TagStats) = NamedTag(
            t, TagNaming.name(t, explicitOf(t.key)), explicitOf(t.key),
            parents = hierarchy.parentsOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            children = hierarchy.childrenOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
        )
    }

    private fun naming(type: TypeStore, facet: String, stats: Map<String, TagStats> = stats(type, facet)) =
        Naming(stats, type.tags.explicitNames(), TagHierarchy(type.tags.parents()))

    /** The tags of a type with their names, the most used first; [query] — of a name of theirs on a site or the user's, any case. */
    fun list(type: TypeStore, facet: String, query: String?): List<NamedTag> {
        val n = naming(type, facet)
        val wanted = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return n.stats.values
            .filter { t -> wanted == null || t.variants.values.any { it.name.lowercase().contains(wanted) } || n.explicitOf(t.key)?.lowercase()?.contains(wanted) == true }
            .map(n::named)
            .sortedWith(compareByDescending<NamedTag> { it.stats.items.size }.thenBy { it.name.name.lowercase() })
    }

    fun one(type: TypeStore, facet: String, tag: String): NamedTag? {
        val n = naming(type, facet)
        return n.stats[tag]?.let(n::named)
    }

    /**
     * The table of the alike of [tag]: the normalized tags most alike it by the meaning of their
     * names, of one site or another — a site's tag's name encoded as the search encodes short texts,
     * a tag the mean of its sites' tags' directions, alike by the cosine — with every tag the user
     * said something of to it besides (under or above it, different), and every site's tag bound to
     * it (merged), whatever their likeness.
     */
    fun similar(type: TypeStore, facet: String, tag: String, limit: Int): List<SimilarTag> {
        val stats = stats(type, facet)
        val own = stats[tag] ?: return emptyList()
        val n = naming(type, facet, stats)
        val distinct = type.tags.distinct()
        val vectors = HashMap<SiteTagRef, FloatArray>()
        type.sources.forEach { s ->
            val names = stats.values.flatMap { t -> t.variants.values.filter { it.site.source == s.id } }.associate { it.site to it.name }
            if (names.isEmpty()) return@forEach
            val encoded = phrases.of(s, names.values.distinct())
            names.forEach { (site, name) -> encoded[name]?.let { vectors[site] = it } }
        }
        fun direction(sites: Collection<SiteTagRef>): FloatArray? {
            val list = sites.mapNotNull { vectors[it] }
            if (list.isEmpty()) return null
            val sum = FloatArray(list.first().size)
            list.forEach { v ->
                val norm = norm(v).takeIf { it > 0 } ?: return@forEach
                for (i in sum.indices) sum[i] += v[i] / norm
            }
            val total = norm(sum).takeIf { it > 0 } ?: return null
            return FloatArray(sum.size) { sum[it] / total }
        }
        val target = direction(own.variants.keys)
        fun likeness(sites: Collection<SiteTagRef>): Double =
            target?.let { tv -> direction(sites)?.let { d -> d.indices.sumOf { (d[it] * tv[it]).toDouble() } } } ?: 0.0
        fun relation(key: String): TagRelation {
            val other = TagRepository.idOf(key)!!
            return when {
                key in n.hierarchy.childrenOf(tag) -> TagRelation.CHILD
                key in n.hierarchy.parentsOf(tag) -> TagRelation.PARENT
                TagRepository.pair(own.id, other) in distinct -> TagRelation.DIFFERENT
                else -> TagRelation.NONE
            }
        }
        val others = stats.values.filter { it.key != tag }.map { t -> Triple(t, likeness(t.variants.keys), relation(t.key)) }
        val alike = others.sortedByDescending { it.second }.take(limit).map { it.first.key }.toSet()
        val rows = others.filter { it.first.key in alike || it.third != TagRelation.NONE }.map { (t, s, r) -> SimilarTag(n.named(t), s, r) }
        // Every site's tag bound to this one: a row of its own, merged — taken out by the user, a tag of its own
        val named = n.named(own)
        val bound = own.variants.values.map { v -> SimilarTag(named, likeness(listOf(v.site)), TagRelation.MERGED, v) }
        return (rows + bound).sortedByDescending { it.similarity }
    }

    // --- The user's word ---

    /**
     * What the user says the normalized tag [other] is to [tag] — merged into it, under it, above
     * it, different, or nothing — in place of what was said of the two before. Merged, [other] is no
     * more: its sites' tags are bound to [tag], the user's answers on it are answers on [tag] — the
     * newest stands of a work answered on both — and so are its links. A tag is never put above or
     * under one of its own descendants or ancestors.
     */
    fun relate(type: TypeStore, facet: String, tag: String, other: String, relation: TagRelation): NamedTag? {
        require(tag != other) { "Тег не может быть связан сам с собой" }
        val a = TagRepository.idOf(tag)?.takeIf(type.tags::exists) ?: return null
        val b = TagRepository.idOf(other)?.takeIf(type.tags::exists) ?: return null
        val before = stats(type, facet)
        val now = Instant.now()
        type.tags.setParent(b, a, false, now)
        type.tags.setParent(a, b, false, now)
        type.tags.setDistinct(a, b, false, now)
        val hierarchy = TagHierarchy(type.tags.parents())
        when (relation) {
            TagRelation.MERGED -> {
                type.tags.merge(b, a, now)
                type.sources.forEach { s -> s.corrections.moveFacetValues(facet, listOf(other), tag) }
            }
            TagRelation.CHILD -> {
                require(other !in hierarchy.ancestors(tag)) { "Этот тег уже выше — подтегом он стал бы своим же надтегом" }
                type.tags.setParent(b, a, true, now)
            }
            TagRelation.PARENT -> {
                require(other !in hierarchy.descendants(tag)) { "Этот тег уже ниже — надтегом он стал бы своим же подтегом" }
                type.tags.setParent(a, b, true, now)
            }
            TagRelation.DIFFERENT -> type.tags.setDistinct(a, b, true, now)
            TagRelation.NONE -> Unit
        }
        log.info("{}: tag {} is {} to {}", type.id, other, relation, tag)
        changed(type, facet, before[tag]?.items.orEmpty() + before[other]?.items.orEmpty())
        return one(type, facet, tag)
    }

    /**
     * The site's tag [site] bound to the normalized tag [tag] — to a new one of its own when null:
     * taken out of the tag it was. The user's answers stay with the tags they were given on.
     * Returns the tag it is bound to now.
     */
    fun bind(type: TypeStore, facet: String, site: SiteTagRef, tag: String?): NamedTag? {
        val before = stats(type, facet)
        val was = type.tags.bindings()[site] ?: return null
        val to = if (tag == null) type.tags.create() else TagRepository.idOf(tag)?.takeIf(type.tags::exists) ?: return null
        type.tags.bind(site, to, Instant.now())
        log.info("{}: {} of {} bound to tag {}, was {}", type.id, site.tag, site.source, to, was)
        changed(type, facet, before[TagRepository.key(was)]?.items.orEmpty() + before[TagRepository.key(to)]?.items.orEmpty())
        return one(type, facet, TagRepository.key(to))
    }

    /** The name the user gives [tag]; null or blank takes it back — the tag is named by its sites again. */
    fun name(type: TypeStore, facet: String, tag: String, name: String?): NamedTag? {
        val id = TagRepository.idOf(tag)?.takeIf(type.tags::exists) ?: return null
        type.tags.name(id, name?.trim()?.takeIf { it.isNotEmpty() })
        val named = one(type, facet, tag)
        changed(type, facet, named?.stats?.items.orEmpty())
        return named
    }

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
        val explicit = type.tags.explicitNames()
        type.tags.replaceShown(stats.values.associate { t -> t.id to TagNaming.name(t, explicit[t.id]) })
        log.info("{}: names of {} tags worked out in {} ms", type.id, stats.size, System.currentTimeMillis() - started)
    }

    /** The sites' new tags bound, and the names worked out again, shortly after the last of a quick series of changes. */
    private fun schedule(typeId: String) {
        pending.compute(typeId) { _, old ->
            old?.cancel(false)
            scheduler.schedule({
                try {
                    val type = stores.type(typeId) ?: return@schedule
                    val facet = facetOf(type) ?: return@schedule
                    normalization.bindNew(type)
                    rename(type, facet)
                } catch (e: Exception) {
                    log.warn("{}: the tags were not bound and named: {}", typeId, e.message, e)
                }
            }, RENAME_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    @EventListener
    fun gradesChanged(event: GradesChanged) = schedule(event.type)

    @EventListener
    fun itemsChanged(event: ItemsChanged) = schedule(stores.typeOf(event.source).id)

    /** After the tags are normalized on start ([TagNormalization.start]). */
    @Order(-5)
    @EventListener(ApplicationReadyEvent::class)
    fun start() = stores.types.forEach { schedule(it.id) }

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
