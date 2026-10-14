package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.refusal.Refusal
import org.springframework.http.HttpStatus
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
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

/** A tag named, with the tags directly above and under it, named, and the entry of a catalogue it is bound to. */
class NamedTag(
    val stats: TagStats,
    val name: ShownName,
    val explicit: String?,
    val parents: List<Pair<String, String>> = emptyList(),
    val children: List<Pair<String, String>> = emptyList(),
    val entry: BoundEntry? = null,
)

/** The entry of a catalogue a tag is bound to, as the catalogue gave it when last asked. */
class BoundEntry(val catalogue: String, val entry: TagEntry) {
    /** Every name of it in every language kept: the label and the aliases — a tag is found by any of them. */
    val names: List<String> get() = (entry.labels.values + entry.aliases.values.flatten()).distinct()
}

/** An entry a catalogue found, and the tag bound to it already, if any. */
class FoundEntry(val catalogue: String, val entry: TagEntry, val tag: String?)

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

/** A page of a tag's table of the alike, and how many rows the whole table has. */
class SimilarPage(val total: Int, val rows: List<SimilarTag>)

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
    private val plugins: Plugins,
    /** The languages a tag's entry is asked in, the user's first: its label in the first that has one names the tag. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
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
                // Every site's tag of the work counted, two of one tag both: each is a variant of it with
                // its works; the tag's works are a set, the work one of them once
                keys.distinct().forEach { k ->
                    val site = SiteTagRef(s.id, k)
                    val tag = bindings[site]?.let(TagRepository::key) ?: return@forEach
                    fromSite.getOrPut(id) { HashSet() } += tag
                    count(id, tag, site, (names[k] ?: k).trim())
                }
            }
            // A tag the user gave a work the site does not
            s.corrections.allFacets().filter { it.facet == facet && it.added && TagRepository.idOf(it.key) != null }.forEach { c ->
                if (fromSite[c.itemId]?.contains(c.key) != true) count(c.itemId, c.key, null, null)
            }
        }
        return out
    }

    /**
     * What every tag of one reading is named: the stats, the user's names, the entries of a catalogue
     * the tags are bound to, the tags above and under them.
     */
    private inner class Naming(
        val stats: Map<String, TagStats>, val explicit: Map<Long, String>, val entries: Map<Long, BoundEntry>, val hierarchy: TagHierarchy,
    ) {
        fun explicitOf(key: String): String? = TagRepository.idOf(key)?.let(explicit::get)

        fun entryOf(key: String): BoundEntry? = TagRepository.idOf(key)?.let(entries::get)

        /** The label of the tag's entry in the user's first language that has one. */
        fun cataloguedOf(key: String): String? = entryOf(key)?.entry?.labels?.let { labels -> languages.firstNotNullOfOrNull { labels[it] } }

        fun nameOf(t: TagStats): ShownName = TagNaming.name(t, explicitOf(t.key), cataloguedOf(t.key))

        fun shown(key: String): String = stats[key]?.let { nameOf(it).name } ?: explicitOf(key) ?: cataloguedOf(key) ?: key

        /** Every name the tag goes by: of its sites' tags, the user's, its entry's in every language. */
        fun allNames(t: TagStats): List<String> =
            t.variants.values.map { it.name } + listOfNotNull(explicitOf(t.key)) + entryOf(t.key)?.names.orEmpty()

        fun named(t: TagStats) = NamedTag(
            t, nameOf(t), explicitOf(t.key),
            parents = hierarchy.parentsOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            children = hierarchy.childrenOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            entry = entryOf(t.key),
        )
    }

    private fun naming(type: TypeStore, facet: String, stats: Map<String, TagStats> = stats(type, facet)) =
        Naming(stats, type.tags.explicitNames(), boundEntries(type), TagHierarchy(type.tags.parents()))

    /** The entry of a catalogue every tag is bound to, by the tag's id: the bindings and the entries kept, read at once. */
    private fun boundEntries(type: TypeStore): Map<Long, BoundEntry> {
        val refs = type.tags.entriesOfTags()
        val kept = type.tags.keptEntries(refs.values.toSet())
        return refs.mapNotNull { (id, ref) -> kept[ref]?.let { id to BoundEntry(ref.catalogue, it) } }.toMap()
    }

    /** The tags of a type with their names, the most used first; [query] — of a name of theirs on a site, the user's or its entry's, any case. */
    fun list(type: TypeStore, facet: String, query: String?): List<NamedTag> {
        val n = naming(type, facet)
        val wanted = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return n.stats.values
            .filter { t -> wanted == null || n.allNames(t).any { it.lowercase().contains(wanted) } }
            .map(n::named)
            .sortedWith(compareByDescending<NamedTag> { it.stats.items.size }.thenBy { it.name.name.lowercase() })
    }

    fun one(type: TypeStore, facet: String, tag: String): NamedTag? {
        val n = naming(type, facet)
        return n.stats[tag]?.let(n::named)
    }

    /**
     * A page of the table of the alike of [tag]: every other normalized tag and every site's tag
     * bound to it (merged), but the rows of the relations [hidden], the likest first by the meaning
     * of their names, of one site or another — a site's tag's name encoded as the search encodes
     * short texts, a tag the mean of its sites' tags' directions, alike by the cosine. Alike the
     * tag's own names; alike the text [query] instead, when one is given. Of rows alike equally, by
     * key: a page read after another continues it while nothing changed.
     */
    fun similar(type: TypeStore, facet: String, tag: String, query: String?, hidden: Set<TagRelation>, offset: Int, limit: Int): SimilarPage? {
        val stats = stats(type, facet)
        val own = stats[tag] ?: return null
        val n = naming(type, facet, stats)
        val distinct = type.tags.distinct()
        val meaning = Meaning(type, stats)
        val asked = query?.trim()?.takeIf { it.isNotEmpty() }
        val target = if (asked == null) meaning.direction(own.variants.keys) else meaning.ofText(asked)
        fun likeness(sites: Collection<SiteTagRef>): Double = meaning.likeness(target, sites)
        fun relation(key: String): TagRelation {
            val other = TagRepository.idOf(key)!!
            return when {
                key in n.hierarchy.childrenOf(tag) -> TagRelation.CHILD
                key in n.hierarchy.parentsOf(tag) -> TagRelation.PARENT
                TagRepository.pair(own.id, other) in distinct -> TagRelation.DIFFERENT
                else -> TagRelation.NONE
            }
        }
        /** A row before it is named — a tag, or a site's tag bound to this one — named only on the page asked for. */
        class Row(val tag: TagStats, val similarity: Double, val relation: TagRelation, val site: TagVariant?, val key: String)
        val others = stats.values.filter { it.key != tag }.map { t -> Row(t, likeness(t.variants.keys), relation(t.key), null, t.key) }
        // Every site's tag bound to this one: a row of its own, merged — taken out by the user, a tag of its own
        val bound = own.variants.values.map { v -> Row(own, likeness(listOf(v.site)), TagRelation.MERGED, v, "site:${v.site.source}/${v.site.tag}") }
        val rows = (others + bound).filter { it.relation !in hidden }
            .sortedWith(compareByDescending<Row> { it.similarity }.thenBy { it.key })
        val page = rows.drop(offset.coerceAtLeast(0)).take(limit)
        return SimilarPage(rows.size, page.map { SimilarTag(n.named(it.tag), it.similarity, it.relation, it.site) })
    }

    /**
     * THE MEANING OF THE SITES' TAGS' NAMES of one reading ([stats]): every name encoded as the search
     * encodes short texts, a tag's direction the mean of its sites' tags', alike by the cosine. Read
     * for one operation, kept nowhere.
     */
    private inner class Meaning(type: TypeStore, stats: Map<String, TagStats>) {
        private val vectors = HashMap<SiteTagRef, FloatArray>()

        init {
            type.sources.forEach { s ->
                val names = stats.values.flatMap { t -> t.variants.values.filter { it.site.source == s.id } }.associate { it.site to it.name }
                if (names.isEmpty()) return@forEach
                val encoded = phrases.of(s, names.values.distinct())
                names.forEach { (site, name) -> encoded[name]?.let { vectors[site] = it } }
            }
        }

        /** The mean direction of the sites' tags [sites]; null when none is encoded. */
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

        /** A text typed, encoded as the names are and kept nowhere: it is not a name of the sites. */
        fun ofText(text: String): FloatArray? = phrases.encoder()?.encode(listOf(text))?.firstOrNull()?.let { v ->
            val norm = norm(v).takeIf { it > 0 } ?: return@let null
            FloatArray(v.size) { v[it] / norm }
        }

        /** How alike the sites' tags [sites] are to [target]: the cosine; nought when either is not known. */
        fun likeness(target: FloatArray?, sites: Collection<SiteTagRef>): Double =
            target?.let { tv -> direction(sites)?.let { d -> d.indices.sumOf { (d[it] * tv[it]).toDouble() } } } ?: 0.0
    }

    /**
     * The tags of the layer [facet] found by [query] for a work of [source], at most [limit], in
     * three steps, each only while the ones before found fewer: the tags a site's tag of [source]
     * of that name holds it; then by any of their names — of every site, the user's, the one shown;
     * then the likest by the meaning of their names. Of the first two, a name that is the text first,
     * then one beginning with it, then one holding it; the most used first among them.
     */
    fun find(type: TypeStore, facet: String, source: String, query: String, limit: Int): List<NamedTag> {
        val wanted = query.trim().lowercase()
        val stats = stats(type, facet)
        val n = naming(type, facet, stats)
        val found = LinkedHashMap<String, TagStats>()
        fun step(namesOf: (TagStats) -> List<String>) {
            if (found.size >= limit) return
            stats.values.filter { it.key !in found }
                .mapNotNull { t ->
                    val names = namesOf(t).map { it.lowercase() }
                    when {
                        names.any { it == wanted } -> 0
                        names.any { it.startsWith(wanted) } -> 1
                        names.any { wanted in it } -> 2
                        else -> null
                    }?.let { t to it }
                }
                .sortedWith(compareBy<Pair<TagStats, Int>> { it.second }.thenByDescending { it.first.items.size }.thenBy { it.first.key })
                .forEach { found.putIfAbsent(it.first.key, it.first) }
        }
        step { t -> t.variants.values.filter { it.site.source == source }.map { it.name } }
        step { t -> n.allNames(t) + n.shown(t.key) }
        if (found.size < limit && wanted.isNotEmpty()) {
            val meaning = Meaning(type, stats)
            val target = meaning.ofText(query.trim())
            if (target != null) {
                stats.values.filter { it.key !in found && it.variants.isNotEmpty() }
                    .map { it to meaning.likeness(target, it.variants.keys) }
                    .sortedWith(compareByDescending<Pair<TagStats, Double>> { it.second }.thenBy { it.first.key })
                    .take(limit - found.size)
                    .forEach { found[it.first.key] = it.first }
            }
        }
        return found.values.take(limit).map(n::named)
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
        if (tag == other) throw Refusal.TAG_SELF.refuse(HttpStatus.BAD_REQUEST, "The tag $tag is not related to itself")
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
                if (other in hierarchy.ancestors(tag)) {
                    throw Refusal.TAG_ABOVE.refuse(HttpStatus.BAD_REQUEST, "The tag $other is above $tag already: as its child it would be its own parent")
                }
                type.tags.setParent(b, a, true, now)
            }
            TagRelation.PARENT -> {
                if (other in hierarchy.descendants(tag)) {
                    throw Refusal.TAG_BELOW.refuse(HttpStatus.BAD_REQUEST, "The tag $other is below $tag already: as its parent it would be its own child")
                }
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

    // --- The entries of a catalogue ---

    /** The entries every catalogue of tags finds by [text] — an id or a page's address is the entry itself — each with the tag bound to it already. */
    fun findEntries(type: TypeStore, text: String): List<FoundEntry> {
        if (text.isBlank()) return emptyList()
        val bound = type.tags.entriesOfTags().entries.associate { (id, ref) -> ref to TagRepository.key(id) }
        return plugins.tagCatalogues.flatMap { c ->
            c.find(text, languages).map { FoundEntry(c.id, it, bound[TagEntryRef(c.id, it.id)]) }
        }
    }

    /**
     * The tag [tag] bound to the entry [entry] of [catalogue] — the entry read of the catalogue now
     * and kept, its label the tag's name unless the user named it — or, [catalogue] null, to none.
     * The tag's works are read again: its name is what the model reads.
     */
    fun bindEntry(type: TypeStore, facet: String, tag: String, catalogue: String?, entry: String?): NamedTag? {
        val id = TagRepository.idOf(tag)?.takeIf(type.tags::exists) ?: return null
        val now = Instant.now()
        if (catalogue == null || entry == null) {
            type.tags.bindEntry(id, null, now)
            log.info("{}: tag {} bound to no entry", type.id, tag)
        } else {
            val source = plugins.tagCatalogues.firstOrNull { it.id == catalogue } ?: throw NoSuchElementException("No catalogue of tags $catalogue")
            val read = source.entries(listOf(entry), languages).firstOrNull() ?: throw NoSuchElementException("$catalogue has no $entry")
            type.tags.keepEntry(catalogue, read, now)
            type.tags.bindEntry(id, TagEntryRef(catalogue, read.id), now)
            log.info("{}: tag {} bound to {}:{} ({})", type.id, tag, catalogue, read.id, languages.firstNotNullOfOrNull { read.labels[it] })
        }
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
        val n = naming(type, facet)
        val stats = n.stats
        type.tags.replaceShown(stats.values.associate { t -> t.id to n.nameOf(t) })
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
 * WHAT A TAG IS SHOWN BY, the user's order: the name the user gave it; else the label of the entry of
 * a catalogue the user bound it to, in the user's first language that has one ([catalogued]: the tag
 * translated); else its name on the sites most used by the works the user liked (graded above the
 * middle of the scale), by the works graded, by all its works; else the first by the alphabet. One
 * order of all its names on the sites ([names]): by the liked works, of those used equally by the
 * graded, then by all, then by the alphabet — its first the name, the interface lists the others
 * after it in the same order.
 */
object TagNaming {
    fun name(t: TagStats, explicit: String?, catalogued: String? = null): ShownName {
        if (explicit != null) return ShownName(explicit, NameRule.EXPLICIT)
        if (catalogued != null) return ShownName(catalogued, NameRule.CATALOGUE)
        val (first, uses) = names(t).firstOrNull() ?: return ShownName(t.key, NameRule.ALPHABET)
        val rule = when {
            uses.liked > 0 -> NameRule.LIKED
            uses.graded > 0 -> NameRule.GRADED
            uses.works > 0 -> NameRule.ALL
            else -> NameRule.ALPHABET
        }
        return ShownName(first, rule)
    }

    /** The tag's names on the sites, each as written with its uses of every site together, in the order of the name. */
    fun names(t: TagStats): List<Pair<String, Uses>> {
        val byName = HashMap<String, Uses>()
        t.variants.values.forEach { v ->
            val u = byName.getOrPut(v.name) { Uses() }
            u.works += v.uses.works
            u.graded += v.uses.graded
            u.liked += v.uses.liked
        }
        return byName.toList().sortedWith(
            compareByDescending<Pair<String, Uses>> { it.second.liked }.thenByDescending { it.second.graded }.thenByDescending { it.second.works }
                .thenBy { it.first },
        )
    }
}
