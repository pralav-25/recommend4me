package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.refusal.Refusal
import org.springframework.http.HttpStatus
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.workload.Workload
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

/**
 * A normalized tag: its key (its id written out), the sites' tags bound to it, its works, and the
 * graded works the user said have it not ([rejected]).
 */
class TagStats(val key: String) {
    val variants = LinkedHashMap<SiteTagRef, TagVariant>()
    val items = HashSet<ItemKey>()
    val graded = HashSet<ItemKey>()
    val liked = HashSet<ItemKey>()
    val rejected = HashSet<ItemKey>()

    /** How many works have it, graded and liked among them: of the sets when counted, as kept when read ([Tags.stats]). */
    var counts = Counts(0, 0, 0)

    /** The sources whose site's tags are bound to it. */
    var sources: List<String> = emptyList()

    val id: Long get() = TagRepository.idOf(key)!!

    fun variant(site: SiteTagRef, name: String) = variants.getOrPut(site) { TagVariant(site, name, Uses()) }
}

/**
 * A tag named, with the tags directly above and under it, named, the entries of the catalogues it is
 * bound to, the one bound first first, and what its sites' tags' own pages say it means ([descriptions],
 * read for one tag alone).
 */
class NamedTag(
    val stats: TagStats,
    val name: ShownName,
    val explicit: String?,
    val parents: List<Pair<String, String>> = emptyList(),
    val children: List<Pair<String, String>> = emptyList(),
    val entries: List<BoundEntry> = emptyList(),
    val descriptions: List<SiteDescription> = emptyList(),
    val offers: List<CatalogueOffer> = emptyList(),
    /** The user ignores it: kept with its works, not read by the model of the grades. */
    val ignored: Boolean = false,
)

/**
 * Another tag offered above or under a tag ([relation]: PARENT, CHILD) by what the entries they are
 * bound to are a kind of in [catalogue]: the entries of the way up, from the lower tag's to the
 * upper's, by their labels — "satire", "humor". Only offered: the user says.
 */
class CatalogueOffer(val catalogue: String, val tag: String, val name: String, val relation: TagRelation, val path: List<String>)

/** What an offer of the tags' graph is ([Tags.hierarchyOffers]). */
enum class HierarchyKind {
    /** The first tag under the second. */
    LINK,

    /** The two tags one: bound to one entry. */
    MERGE,

    /** A new tag of [HierarchyOffer.entry] above the tags. */
    NEW_PARENT,

    /** The tag [HierarchyOffer.existing], of the entry's name, bound to it and put above the tags. */
    BIND_PARENT,

    /** The first tag right under the second no more: it is under it through the third already. */
    UNLINK,
}

/**
 * An offer of the tags' graph: its [kind], the tags it is of, the entry of [catalogue] it is by — none of the user's graph alone ([HierarchyKind.UNLINK]) — the
 * way up from the first tag's entry, how many graded works it changes, and how many levels up the
 * catalogue the tags meet — of a new tag, the farther of the two of its nearest pair.
 */
class HierarchyOffer(
    val kind: HierarchyKind, val catalogue: String?, val tags: List<String>, val entry: TagEntryRef?, val way: List<TagEntryRef>, val graded: Int, val levels: Int,
    /** Of [HierarchyKind.BIND_PARENT], the tag of the entry's name to bind and put above. */
    val existing: String? = null,
    /** Of a tag above ([HierarchyKind.NEW_PARENT], [HierarchyKind.BIND_PARENT]), the user said its entry is too general. */
    val rejected: Boolean = false,
)

/** The entry of a catalogue a tag is bound to, as the catalogue gave it when last asked. */
class BoundEntry(val catalogue: String, val entry: TagEntry) {
    /** Every name of it in every language kept: the label and the aliases — a tag is found by any of them. */
    val names: List<String> get() = (entry.labels.values + entry.aliases.values.flatten()).distinct()
}

/** What a site says one of its tags means: the site's tag and the text. */
class SiteDescription(val site: SiteTagRef, val text: String)

/** An entry a catalogue found, and the tags bound to it already: their keys and names. */
class FoundEntry(val catalogue: String, val entry: TagEntry, val tags: List<Pair<String, String>>)

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

    /** Under it through other tags: read only — said of the tags between. */
    DESCENDANT,

    /** Above it through other tags: read only — said of the tags between. */
    ANCESTOR,
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
 * worked out after the works and their grades change and kept with its statistics; nothing is kept
 * in memory.
 */
@Service
class Tags(
    private val normalization: TagNormalization,
    private val phrases: PhraseVectors,
    private val events: ApplicationEventPublisher,
    private val plugins: Plugins,
    /** The languages a tag's entry is asked in, the user's first: its label in the first that has one names the tag. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
    private val workload: Workload,
) {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tag-names").apply { isDaemon = true } }
    private val pending = ConcurrentHashMap<String, ScheduledFuture<*>>()

    /** What the tags are read over: every source of every type with a layer of tags; null when none has one. */
    fun scope(): TagScope? = normalization.scope()

    // --- Reading ---

    /**
     * Every normalized tag as kept in the dictionaries: its sites' tags with their names and works,
     * its works and its sources as last counted ([countAll], in the background) — no work read now,
     * no sets of works.
     */
    fun stats(scope: TagScope, facet: String): Map<String, TagStats> {
        val out = HashMap<String, TagStats>()
        scope.tags.siteTags().forEach { r ->
            val t = out.getOrPut(TagRepository.key(r.normalized)) { TagStats(TagRepository.key(r.normalized)) }
            // A site's tag no work has now is no variant of the tag, as of the counting
            if (r.name != null) t.variants[r.site] = TagVariant(r.site, r.name, Uses().apply { works = r.counts.works; graded = r.counts.graded; liked = r.counts.liked })
        }
        scope.tags.counts().forEach { (id, c) ->
            out.getOrPut(TagRepository.key(id)) { TagStats(TagRepository.key(id)) }.apply { counts = c.counts; sources = c.sources }
        }
        scope.tags.ids().forEach { id -> out.getOrPut(TagRepository.key(id)) { TagStats(TagRepository.key(id)) } }
        return out
    }

    /**
     * Every normalized tag with its sites' tags and its works, of every type, counted now: one pass
     * over every work of every source — seconds of a catalogue of games. What the screen of the tags
     * shows is kept of it ([rename]); read now only where the works themselves are wanted.
     */
    fun countAll(scope: TagScope, facet: String): Map<String, TagStats> = workload.run("tags: every work counted") { counted(scope, facet) }

    private fun counted(scope: TagScope, facet: String): Map<String, TagStats> {
        val bindings = scope.tags.bindings()
        val out = HashMap<String, TagStats>()
        val rejections = HashMap<String, MutableSet<ItemKey>>()
        scope.sources.forEach { s ->
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
            // A tag the user gave a work the site does not; a graded work the user said has it not
            s.corrections.allFacets().filter { it.facet == facet && TagRepository.idOf(it.key) != null }.forEach { c ->
                if (!c.added) {
                    if (c.itemId in grades) rejections.getOrPut(c.key) { HashSet() } += ItemKey(s.id, c.itemId)
                } else if (fromSite[c.itemId]?.contains(c.key) != true) count(c.itemId, c.key, null, null)
            }
        }
        // A tag of no work yet — one the user made above others — is a tag all the same
        scope.tags.ids().forEach { id -> out.getOrPut(TagRepository.key(id)) { TagStats(TagRepository.key(id)) } }
        rejections.forEach { (key, items) -> out[key]?.rejected?.addAll(items) }
        val sourcesOf = bindings.entries.groupBy({ TagRepository.key(it.value) }, { it.key.source })
        out.values.forEach { t ->
            t.counts = Counts(t.items.size, t.graded.size, t.liked.size)
            t.sources = sourcesOf[t.key].orEmpty().distinct().sorted()
        }
        return out
    }

    /** The works of the tags [keys]: by a site's tag bound to them, or by the user's word — a read of those alone. */
    private fun itemsOf(scope: TagScope, facet: String, keys: Collection<String>): Set<ItemKey> {
        val ids = keys.mapNotNull(TagRepository::idOf).toSet()
        if (ids.isEmpty()) return emptySet()
        val wanted = keys.toSet()
        val sites = scope.tags.bindings().filterValues { it in ids }.keys.groupBy({ it.source }, { it.tag })
        return scope.sources.flatMap { s ->
            val bySite = normalization.siteFacetOf(s, facet)?.let { f -> sites[s.id]?.let { s.items.itemsWith(f, it).values.flatten() } }.orEmpty()
            val byUser = s.corrections.allFacets().filter { it.facet == facet && it.added && it.key in wanted }.map { it.itemId }
            (bySite + byUser).map { ItemKey(s.id, it) }
        }.toSet()
    }

    /**
     * What every tag of one reading is named: the stats, the user's names, the entries of a catalogue
     * the tags are bound to, the tags above and under them.
     */
    private inner class Naming(
        val stats: Map<String, TagStats>, val explicit: Map<Long, String>, val entries: Map<Long, List<BoundEntry>>, val hierarchy: TagHierarchy,
        val ignored: Set<String>,
    ) {
        fun explicitOf(key: String): String? = TagRepository.idOf(key)?.let(explicit::get)

        fun entriesOf(key: String): List<BoundEntry> = TagRepository.idOf(key)?.let(entries::get).orEmpty()

        /** The label of the tag's entries in the user's first language one of them has it in: of the one bound first, of those that do. */
        fun cataloguedOf(key: String): String? {
            val bound = entriesOf(key)
            return languages.firstNotNullOfOrNull { language -> bound.firstNotNullOfOrNull { it.entry.labels[language] } }
        }

        fun nameOf(t: TagStats): ShownName = TagNaming.name(t, explicitOf(t.key), cataloguedOf(t.key))

        fun shown(key: String): String = stats[key]?.let { nameOf(it).name } ?: explicitOf(key) ?: cataloguedOf(key) ?: key

        /** Every name the tag goes by: of its sites' tags, the user's, its entries' in every language. */
        fun allNames(t: TagStats): List<String> =
            t.variants.values.map { it.name } + listOfNotNull(explicitOf(t.key)) + entriesOf(t.key).flatMap { it.names }

        fun named(t: TagStats, descriptions: List<SiteDescription> = emptyList(), offers: List<CatalogueOffer> = emptyList()) = NamedTag(
            t, nameOf(t), explicitOf(t.key),
            parents = hierarchy.parentsOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            children = hierarchy.childrenOf(t.key).map { it to shown(it) }.sortedBy { it.second.lowercase() },
            entries = entriesOf(t.key),
            descriptions = descriptions,
            offers = offers,
            ignored = t.key in ignored,
        )
    }

    private fun naming(scope: TagScope, facet: String, stats: Map<String, TagStats> = stats(scope, facet)) =
        Naming(stats, scope.tags.explicitNames(), boundEntries(scope), TagHierarchy(scope.tags.parents()), scope.tags.ignored())

    /** The entries of the catalogues every tag is bound to, by the tag's id: the bindings and the entries kept, read at once. */
    private fun boundEntries(scope: TagScope): Map<Long, List<BoundEntry>> {
        val refs = scope.tags.entriesOfTags()
        val kept = scope.tags.keptEntries(refs.values.flatten().toSet())
        return refs.mapValues { (_, list) -> list.mapNotNull { ref -> kept[ref]?.let { BoundEntry(ref.catalogue, it) } } }
    }

    /** What the sites' own pages say the sites' tags of [t] mean, in the order of its sites' tags. */
    private fun descriptionsOf(scope: TagScope, facet: String, t: TagStats): List<SiteDescription> {
        val bySource = t.variants.keys.groupBy { it.source }
        val read = scope.sources.filter { it.id in bySource }.flatMap { s ->
            val siteFacet = normalization.siteFacetOf(s, facet) ?: return@flatMap emptyList()
            s.items.facetDescriptions(siteFacet, bySource.getValue(s.id).map { it.tag }).map { (tag, text) -> SiteTagRef(s.id, tag) to text }
        }.toMap()
        return t.variants.keys.mapNotNull { site -> read[site]?.let { SiteDescription(site, it) } }
    }

    /**
     * The tags of a type with their names, the most used first; [query] — of a name of theirs on a
     * site, the user's or its entry's, any case; [with] — the sites a tag is used on and the
     * catalogues it is bound to an entry of, every one; [without] — none of them ([sources]);
     * [ignored] — the tags the user ignores alone, or those not; either when null.
     */
    fun list(
        scope: TagScope, facet: String, query: String?, with: Set<String> = emptySet(), without: Set<String> = emptySet(), ignored: Boolean? = null,
    ): List<NamedTag> {
        val n = naming(scope, facet)
        val wanted = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        return n.stats.values
            .filter { t -> wanted == null || n.allNames(t).any { it.lowercase().contains(wanted) } }
            .map(n::named)
            .filter { t ->
                if (ignored != null && t.ignored != ignored) return@filter false
                if (with.isEmpty() && without.isEmpty()) return@filter true
                val from = t.stats.sources.toSet() + t.entries.map { it.catalogue }
                from.containsAll(with) && from.none { it in without }
            }
            .sortedWith(compareByDescending<NamedTag> { it.stats.counts.works }.thenBy { it.name.name.lowercase() })
    }

    /** What a tag may come from: the sites of every type that have the free tags, then the catalogues tags are bound to entries of. */
    fun sources(scope: TagScope, facet: String): List<String> =
        scope.sources.filter { normalization.siteFacetOf(it, facet) != null }.map { it.id } + plugins.tagCatalogues.map { it.id }

    fun one(scope: TagScope, facet: String, tag: String): NamedTag? {
        val n = naming(scope, facet)
        return n.stats[tag]?.let { n.named(it, descriptionsOf(scope, facet, it), offersOf(scope, n, tag)) }
    }

    /**
     * The other tags offered above and under [tag] by the catalogues: a tag bound to an entry its
     * entries are a kind of, up to [ABOVE_LEVELS] levels — offered above it; a tag whose entries are
     * a kind of its own — under it. Not a tag that is above or under it already, by the user's word
     * or through others: the nearest way of each, a tag once.
     */
    private fun offersOf(scope: TagScope, n: Naming, tag: String): List<CatalogueOffer> {
        val graph = CatalogueGraph(scope, n)
        val found = graph.links().filter { it.child == tag || it.parent == tag }
        if (found.isEmpty()) return emptyList()
        val label = graph.labels(found.flatMap { it.way })
        return found.map { l ->
            if (l.child == tag) CatalogueOffer(l.way.first().catalogue, l.parent, n.shown(l.parent), TagRelation.PARENT, l.way.map(label))
            else CatalogueOffer(l.way.first().catalogue, l.child, n.shown(l.child), TagRelation.CHILD, l.way.map(label))
        }.sortedWith(compareBy<CatalogueOffer> { it.relation }.thenBy { it.path.size }.thenBy { it.name.lowercase() })
    }

    /** One tag under another by their entries: [way] up from the child's entry to the parent's. */
    private class EntryLink(val child: String, val parent: String, val way: List<TagEntryRef>)

    /**
     * WHAT THE ENTRIES OF THE TAGS ARE A KIND OF, of one reading: the tags' entries and what was read
     * up from them ([readAbove]), walked up [ABOVE_LEVELS] levels. Read for one operation, kept nowhere.
     */
    private inner class CatalogueGraph(val scope: TagScope, val n: Naming) {
        val refsOf: Map<String, List<TagEntryRef>> =
            n.entries.entries.filter { it.value.isNotEmpty() }.associate { (id, list) -> TagRepository.key(id) to list.map { TagEntryRef(it.catalogue, it.entry.id) } }
        val tagsOf: Map<TagEntryRef, List<String>> = HashMap<TagEntryRef, MutableList<String>>().also { m ->
            refsOf.forEach { (key, refs) -> refs.forEach { m.getOrPut(it) { ArrayList() } += key } }
        }
        val links: Map<TagEntryRef, List<TagEntryRef>> = if (refsOf.isEmpty()) emptyMap() else scope.tags.aboveLinks()

        /** Every entry reached up from [starts], with the way to it from one of them, the nearest. */
        fun up(starts: List<TagEntryRef>): Map<TagEntryRef, List<TagEntryRef>> {
            val ways = LinkedHashMap<TagEntryRef, List<TagEntryRef>>()
            starts.forEach { ways[it] = listOf(it) }
            var frontier = starts
            repeat(ABOVE_LEVELS) {
                frontier = frontier.flatMap { from -> links[from].orEmpty().filter { it !in ways }.map { to -> to.also { ways[to] = ways.getValue(from) + to } } }
            }
            return ways
        }

        /** Of every tag, the entries reached up from its own. */
        val reached: Map<String, Map<TagEntryRef, List<TagEntryRef>>> by lazy { refsOf.mapValues { (_, refs) -> up(refs) } }

        /**
         * Every tag under another by their entries — one of its entries a kind of one of the other's,
         * the nearest way — but of two related already, by the user's word or through others, and
         * but over the head of a tag between: one whose entry is above the child's and under the
         * parent's, or one the child is under by the user's word whose entry is under the parent's.
         */
        fun links(): List<EntryLink> {
            val upFrom = HashMap<TagEntryRef, Set<TagEntryRef>>()
            fun above(ref: TagEntryRef) = upFrom.getOrPut(ref) { up(listOf(ref)).keys - ref }
            return reached.flatMap { (child, ways) ->
                val related = n.hierarchy.ancestors(child) + n.hierarchy.descendants(child) + child
                // What stands between: the entries of other tags above its own, and the entries of the tags it is under by the user's word
                val between = ways.filter { (ref, way) -> way.size > 1 && tagsOf[ref].orEmpty().any { it != child } }.keys +
                    n.hierarchy.ancestors(child).flatMap { refsOf[it].orEmpty() }
                val nearest = LinkedHashMap<String, List<TagEntryRef>>()
                ways.entries.filter { it.value.size > 1 }.sortedBy { it.value.size }.forEach { (ref, way) ->
                    tagsOf[ref].orEmpty().filter { it !in related }.forEach { parent ->
                        // Not over the head of a tag between: that one is offered, and above it the parent
                        val over = between.any { b -> parent !in tagsOf[b].orEmpty() && ref in above(b) }
                        if (!over) nearest.putIfAbsent(parent, way)
                    }
                }
                nearest.map { (parent, way) -> EntryLink(child, parent, way) }
            }
        }

        /** The label of every entry of [refs] in the user's first language that has one; else its id. */
        fun labels(refs: Collection<TagEntryRef>): (TagEntryRef) -> String {
            val kept = scope.tags.keptEntries(refs.toSet())
            return { ref -> kept[ref]?.labels?.let { labels -> languages.firstNotNullOfOrNull { labels[it] } } ?: ref.entry }
        }
    }

    /**
     * THE TOP OFFERS OF THE TAGS' GRAPH, the nearest in the catalogue first, of those alike near the
     * ones that change most of the graded works — each only offered, the user says:
     * - a tag under another by what their entries are a kind of ([CatalogueGraph.links]): the graded
     *   works of the child without the parent get it, and those the user said have not the parent
     *   have not the child either;
     * - two tags bound to one entry merged: the graded works of the smaller of the two;
     * - a new tag above two tags or more, of an entry no tag is bound to where two of them meet
     *   first — of the entries both are a kind of, the nearest to the farther of the two: the graded
     *   works of any of them get it. Not where a tag is there already: that tag is offered instead.
     *   Of several such entries of a pair, the most precise alone: one none of the others is under,
     *   of those the nearer by the two ways together. Never of two tags one under the other by the
     *   user's word; above the roots of the user's trees alone, a tag under another of them left out.
     *   Of two tags under a tag of the user's both, only where they meet under its entry: a new tag
     *   between them and it, never one over its head — nor of a tag above both bound to no entry.
     *   A tag in one such offer alone, the nearest: one sharing a tag with a nearer one is not offered
     *   till the nearer is said — then it is offered above the new tag.
     */
    fun hierarchyOffers(scope: TagScope, facet: String): List<HierarchyOffer> = workload.run("tags: the offers of the graph") { offers(scope, facet) }

    private fun offers(scope: TagScope, facet: String): List<HierarchyOffer> {
        val started = System.currentTimeMillis()
        // The graded works of every tag are wanted: counted now
        val n = naming(scope, facet, countAll(scope, facet))
        val graph = CatalogueGraph(scope, n)
        fun graded(key: String) = n.stats[key]?.graded.orEmpty()
        fun rejected(key: String) = n.stats[key]?.rejected.orEmpty()
        val distinct = scope.tags.distinct()
        val tooGeneral = scope.tags.tooGeneral()
        val out = ArrayList<HierarchyOffer>()
        // Under another
        graph.links().forEach { l ->
            val gets = graded(l.child) - graded(l.parent) - rejected(l.parent)
            val loses = rejected(l.parent) - rejected(l.child)
            out += HierarchyOffer(HierarchyKind.LINK, l.way.first().catalogue, listOf(l.child, l.parent), null, l.way, (gets + loses).size, l.way.size - 1)
        }
        // A link over the head: a tag right under another it is under through a third already — the link changes no work
        n.stats.keys.forEach { child ->
            val parents = n.hierarchy.parentsOf(child)
            parents.forEach { parent ->
                val via = parents.firstOrNull { it != parent && parent in n.hierarchy.ancestors(it) } ?: return@forEach
                out += HierarchyOffer(HierarchyKind.UNLINK, null, listOf(child, parent, via), null, emptyList(), 0, 0)
            }
        }
        // One entry, two tags
        graph.tagsOf.forEach { (ref, keys) ->
            keys.sorted().let { sorted ->
                sorted.forEachIndexed { i, a ->
                    sorted.drop(i + 1).forEach { b ->
                        val ids = TagRepository.pair(TagRepository.idOf(a)!!, TagRepository.idOf(b)!!)
                        // The tag of more works first: the one the other is merged into
                        val two = listOf(a, b).sortedByDescending { n.stats[it]?.counts?.works ?: 0 }
                        if (ids !in distinct) out += HierarchyOffer(HierarchyKind.MERGE, ref.catalogue, two, ref, listOf(ref), minOf(graded(a).size, graded(b).size), 0)
                    }
                }
            }
        }
        // A new tag above: where two tags meet first — the entries both reach the nearest, by the farther
        // of the two ways — when no tag is bound to one of them; every pair meeting there under it
        val under = HashMap<TagEntryRef, MutableSet<String>>()
        val levels = HashMap<TagEntryRef, Int>()
        val keys = graph.reached.keys.sorted()
        keys.forEachIndexed { i, a ->
            val upA = graph.reached.getValue(a)
            val relatedA = n.hierarchy.ancestors(a) + n.hierarchy.descendants(a)
            keys.drop(i + 1).forEach { b ->
                // One under the other already: no tag above the two, the upper one stands for both
                if (b in relatedA) return@forEach
                val upB = graph.reached.getValue(b)
                val common = upA.keys.filter { it in upB }
                val nearest = common.minOfOrNull { maxOf(upA.getValue(it).size, upB.getValue(it).size) } ?: return@forEach
                val meet = common.filter { maxOf(upA.getValue(it).size, upB.getValue(it).size) == nearest }
                if (meet.any { it in graph.tagsOf }) return@forEach
                // One place a pair: the most precise of where it meets — none of the others under it, the nearer of the two ways together
                // A catalogue's classes may go round: then all of them are alike precise
                val precise = meet.filter { e -> meet.none { other -> other != e && e in graph.up(listOf(other)) } }.ifEmpty { meet }
                    .minWith(compareBy<TagEntryRef> { upA.getValue(it).size + upB.getValue(it).size }.thenBy { it.entry })
                // Under a tag of the user's both already: a new one only between — its entry under every such tag's entry — never over its head
                val shared = n.hierarchy.ancestors(a).intersect(n.hierarchy.ancestors(b))
                if (shared.isNotEmpty()) {
                    val aboveIt = graph.up(listOf(precise)).keys
                    if (shared.any { c -> graph.refsOf[c].orEmpty().none { it in aboveIt } }) return@forEach
                }
                under.getOrPut(precise) { HashSet() } += listOf(a, b)
                levels.merge(precise, nearest - 1, ::minOf)
            }
        }
        // A tag of the entry's label already — of a site, the user's — bound to it not: that tag offered above, not a new one
        val texts = scope.tags.keptEntries(under.keys)
        val byName = HashMap<String, MutableSet<String>>()
        if (under.isNotEmpty()) n.stats.values.forEach { t -> (n.allNames(t) + n.shown(t.key)).forEach { byName.getOrPut(it.trim().lowercase()) { HashSet() } += t.key } }
        under.forEach { (ref, all) ->
            // Above the roots of the user's trees: a tag under another of them is under the new one through it
            val roots = all.filter { t -> n.hierarchy.ancestors(t).none { it in all } }
            // Its labels alone: an alias is too loose — "action" is one of "activity", and no "Экшн" stands for it
            val names = texts[ref]?.labels?.values.orEmpty().map { it.trim().lowercase() }
            val named = names.flatMap { byName[it].orEmpty() }.distinct().filter { it !in all }.maxByOrNull { n.stats[it]?.counts?.works ?: 0 }
            if (named != null) {
                val related = n.hierarchy.ancestors(named) + n.hierarchy.descendants(named)
                val tags = roots.filter { it !in related }.sortedByDescending { graded(it).size }
                if (tags.isEmpty()) return@forEach
                val gets = all.flatMap(::graded).toSet() - graded(named)
                out += HierarchyOffer(HierarchyKind.BIND_PARENT, ref.catalogue, tags, ref, listOf(ref), gets.size, levels.getValue(ref), named, ref in tooGeneral)
                return@forEach
            }
            if (roots.size < 2) return@forEach
            val children = roots.sortedByDescending { graded(it).size }
            out += HierarchyOffer(HierarchyKind.NEW_PARENT, ref.catalogue, children, ref, listOf(ref), all.flatMap(::graded).toSet().size, levels.getValue(ref), rejected = ref in tooGeneral)
        }
        log.info("{}: {} offers of the tags' graph worked out in {} ms", scope.id, out.size, System.currentTimeMillis() - started)
        // The nearest first — what the entries are right above, not "process" all of them are far up — then the ones changing most
        val sorted = out.sortedWith(compareBy<HierarchyOffer> { it.levels }.thenByDescending { it.graded }.thenBy { it.kind }.thenBy { it.tags.joinToString() })
        // A tag in one new tag alone, the most precise: one sharing a tag with a nearer is left till that is said.
        // One the user said is too general holds no tag: shown when asked, it leaves its tags to the others
        val placed = HashSet<String>()
        return sorted.filter { o ->
            if (o.kind != HierarchyKind.NEW_PARENT && o.kind != HierarchyKind.BIND_PARENT || o.rejected) return@filter true
            (o.tags.none { it in placed }).also { if (it) placed += o.tags }
        }
    }

    /** The names of the tags of [offers] and their entries as kept: of one reading. */
    fun namesOf(scope: TagScope, offers: List<HierarchyOffer>): Pair<Map<String, String>, Map<TagEntryRef, TagEntry>> =
        scope.tags.shown(offers.flatMap { it.tags + listOfNotNull(it.existing) }.toSet()) to scope.tags.keptEntries(offers.flatMap { it.way }.toSet())

    /**
     * A new tag bound to the entry [entry] of [catalogue] — named by its label — or the tag [existing]
     * bound to it besides its own, put above the tags [children]. Returns it.
     */
    fun createAbove(scope: TagScope, facet: String, catalogue: String, entry: String, children: List<String>, existing: String? = null): NamedTag? {
        val ids = children.map { TagRepository.idOf(it)?.takeIf(scope.tags::exists) ?: return null }
        val above = existing?.let { TagRepository.idOf(it)?.takeIf(scope.tags::exists) ?: return null }
        val source = plugins.tagCatalogues.firstOrNull { it.id == catalogue } ?: throw NoSuchElementException("No catalogue of tags $catalogue")
        val read = source.entries(listOf(entry), languages).firstOrNull() ?: throw NoSuchElementException("$catalogue has no $entry")
        val before = itemsOf(scope, facet, children + listOfNotNull(existing))
        val now = Instant.now()
        // The tag, its entry and its place above the children, all or nothing; the catalogue asked before
        val id = scope.tags.transaction { t ->
            val made = above ?: t.create()
            t.keepEntry(catalogue, read, now)
            t.bindEntry(made, TagEntryRef(catalogue, read.id), true, now)
            ids.forEach { t.setParent(it, made, true, now) }
            made
        }
        log.info("{}: tag {} {} {}:{} above {}", scope.id, id, if (above == null) "made of" else "bound to", catalogue, read.id, children)
        changed(scope, facet, before)
        return one(scope, facet, TagRepository.key(id))
    }

    /**
     * What the entries [from] of [catalogue] are a kind of, read up from them [ABOVE_LEVELS] levels
     * and kept: of an entry read before, what was kept of it — unless [anew], every one asked again
     * and the names of those met on the way too; the entries met on the way kept with their names.
     */
    private fun readAbove(scope: TagScope, catalogue: TagCatalogue, from: Collection<String>, anew: Boolean = false) {
        val now = Instant.now()
        fun ref(id: String) = TagEntryRef(catalogue.id, id)
        val links = scope.tags.aboveLinks()
        val seen = HashSet(from)
        // The entries kept: the ones bound, the ones above kept on the way
        val known = HashSet(from)
        var frontier = from.distinct()
        var asked = 0
        repeat(ABOVE_LEVELS) {
            if (frontier.isEmpty()) return@repeat
            val read = if (anew) emptySet() else scope.tags.aboveRead(frontier.map(::ref))
            val said = frontier.filter { ref(it) !in read }.takeIf { it.isNotEmpty() }?.let(catalogue::above).orEmpty()
            asked += frontier.count { ref(it) !in read }
            val aboveOf = frontier.associateWith { id -> if (ref(id) in read) links[ref(id)].orEmpty().map { it.entry } else said[id].orEmpty() }
            // The entries above kept before the links to them: a link is to an entry kept, one the catalogue
            // does not give — gone from it — no link, and no way on from it
            val above = aboveOf.values.flatten().distinct().filter { it !in known }
            val kept = if (anew) emptySet() else scope.tags.keptEntries(above.map(::ref)).keys.map { it.entry }.toSet()
            known += kept
            val missing = above.filter { it !in kept }
            if (missing.isNotEmpty()) catalogue.entries(missing, languages).forEach { scope.tags.keepEntry(catalogue.id, it, now); known += it.id }
            frontier.filter { ref(it) !in read }.forEach { id -> scope.tags.keepAbove(ref(id), said[id].orEmpty().filter { it in known }, now) }
            frontier = aboveOf.values.flatten().filter { it in known && seen.add(it) }.distinct()
        }
        log.info("{}: what {} entries of {} are a kind of read, {} asked of it, {} met", scope.id, from.size, catalogue.id, asked, seen.size)
    }

    /** What the entries bound to the tags are a kind of, of every entry never read: the bindings made before it was read. */
    private fun readAboveOfBound(scope: TagScope) {
        val bound = scope.tags.entriesOfTags().values.flatten().toSet()
        val read = scope.tags.aboveRead(bound)
        (bound - read).groupBy { it.catalogue }.forEach { (id, refs) ->
            val catalogue = plugins.tagCatalogues.firstOrNull { it.id == id } ?: return@forEach
            try {
                readAbove(scope, catalogue, refs.map { it.entry })
            } catch (e: Exception) {
                log.warn("{}: what the entries of {} are a kind of was not read: {}", scope.id, id, e.message)
            }
        }
    }

    /**
     * A page of the table of the alike of [tag]: every other normalized tag and every site's tag
     * bound to it (merged), but the rows of the relations [hidden], the likest first by the meaning
     * of their names, of one site or another and of the entries of the catalogues they are bound to
     * ([Meaning]) — a name encoded as the search encodes short texts, a tag the mean of its names'
     * directions, alike by the cosine. Alike the
     * tag's own names; alike the text [query] instead, when one is given. Of rows alike equally, by
     * key: a page read after another continues it while nothing changed.
     */
    fun similar(
        scope: TagScope, facet: String, tag: String, query: String?, hidden: Set<TagRelation>, offset: Int, limit: Int, ignored: Boolean? = null,
    ): SimilarPage? {
        val stats = stats(scope, facet)
        val own = stats[tag] ?: return null
        val n = naming(scope, facet, stats)
        val distinct = scope.tags.distinct()
        val meaning = Meaning(scope, stats)
        val asked = query?.trim()?.takeIf { it.isNotEmpty() }
        // The tag's own direction; till it is made, of its sites' tags' names the sources keep
        val target = if (asked == null) meaning.names(own.key).ifEmpty { meaning.names(null, own.variants.keys) } else meaning.ofText(asked)
        val descendants = n.hierarchy.descendants(tag)
        val ancestors = n.hierarchy.ancestors(tag)
        fun relation(key: String): TagRelation {
            val other = TagRepository.idOf(key)!!
            return when {
                key in n.hierarchy.childrenOf(tag) -> TagRelation.CHILD
                key in n.hierarchy.parentsOf(tag) -> TagRelation.PARENT
                key in descendants -> TagRelation.DESCENDANT
                key in ancestors -> TagRelation.ANCESTOR
                TagRepository.pair(own.id, other) in distinct -> TagRelation.DIFFERENT
                else -> TagRelation.NONE
            }
        }
        /** A row before it is named — a tag, or a site's tag bound to this one — named only on the page asked for. */
        class Row(val tag: TagStats, val similarity: Double, val relation: TagRelation, val site: TagVariant?, val key: String)
        val others = stats.values.filter { it.key != tag }.map { t -> Row(t, meaning.likeness(target, t.key), relation(t.key), null, t.key) }
        // Every site's tag bound to this one: a row of its own, merged — taken out by the user, a tag of its own
        val bound = own.variants.values.map { v -> Row(own, meaning.likeness(target, null, listOf(v.site)), TagRelation.MERGED, v, "site:${v.site.source}/${v.site.tag}") }
        val rows = (others + bound).filter { it.relation !in hidden && (ignored == null || (it.tag.key in n.ignored) == ignored) }
            .sortedWith(compareByDescending<Row> { it.similarity }.thenBy { it.key })
        val page = rows.drop(offset.coerceAtLeast(0)).take(limit)
        return SimilarPage(rows.size, page.map { SimilarTag(n.named(it.tag), it.similarity, it.relation, it.site) })
    }

    /**
     * THE MEANING OF THE TAGS' NAMES of one reading ([stats]): every name of a tag — its sites' tags'
     * and its entries' — a vector, made in the background and kept in the dictionaries ([TagVectors]);
     * a site's tag alone by its name's vector the source keeps. Alike by the best cosine of their
     * names: a synonym added finds more and blurs nothing. A tag of no vectors yet is alike nothing.
     * Read for one operation, kept nowhere.
     */
    private inner class Meaning(private val scope: TagScope, stats: Map<String, TagStats>) {
        private val encoder = phrases.encoder()

        /** The vectors of every tag's names by the encoder in use, by key: those kept. */
        private val ofTags: Map<String, List<FloatArray>> = encoder?.let { scope.tags.vectors(plugins.pluginOf(it), it.id) }.orEmpty()

        private val siteNames: Map<SiteTagRef, String> = stats.values.flatMap { t -> t.variants.values }.associate { it.site to it.name }

        /** The vectors of the names of [sites] the sources keep: a site's tag alone, not its whole tag. */
        private fun ofSites(sites: Collection<SiteTagRef>): List<FloatArray> =
            sites.groupBy { it.source }.flatMap { (source, refs) ->
                val s = scope.source(source) ?: return@flatMap emptyList()
                phrases.cached(s, refs.mapNotNull { siteNames[it] }).values.mapNotNull(TagVectors::unit)
            }

        /** The vectors of the names of the tag [key]; of [sites] alone when given — a site's tag of it. */
        fun names(key: String?, sites: Collection<SiteTagRef>? = null): List<FloatArray> =
            if (sites != null) ofSites(sites) else key?.let { ofTags[it] }.orEmpty()

        /** A text typed, encoded as the names are and kept nowhere: it is not a name of the sites. */
        fun ofText(text: String): List<FloatArray> = encoder?.encode(listOf(text))?.firstOrNull()?.let(TagVectors::unit)?.let { listOf(it) }.orEmpty()

        /** How alike the tag [key] — or its sites' tags [sites] alone — is to the names [target]: the best cosine; nought when either is not known. */
        fun likeness(target: List<FloatArray>, key: String?, sites: Collection<SiteTagRef>? = null): Double = TagVectors.likeness(target, names(key, sites))
    }

    /**
     * The tags of the layer [facet] found by [query] for a work of [source], at most [limit], in
     * three steps, each only while the ones before found fewer: the tags a site's tag of [source]
     * of that name holds it; then by any of their names — of every site, the user's, the one shown;
     * then the likest by the meaning of their names. Of the first two, a name that is the text first,
     * then one beginning with it, then one holding it; the most used first among them. A tag the user
     * ignores is never found: it is not offered to add.
     */
    fun find(scope: TagScope, facet: String, source: String, query: String, limit: Int): List<NamedTag> {
        val wanted = query.trim().lowercase()
        val n = naming(scope, facet)
        val stats = n.stats.filterKeys { it !in n.ignored }
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
                .sortedWith(compareBy<Pair<TagStats, Int>> { it.second }.thenByDescending { it.first.counts.works }.thenBy { it.first.key })
                .forEach { found.putIfAbsent(it.first.key, it.first) }
        }
        step { t -> t.variants.values.filter { it.site.source == source }.map { it.name } }
        step { t -> n.allNames(t) + n.shown(t.key) }
        if (found.size < limit && wanted.isNotEmpty()) {
            val meaning = Meaning(scope, stats)
            val target = meaning.ofText(query.trim())
            if (target.isNotEmpty()) {
                stats.values.filter { it.key !in found && (it.variants.isNotEmpty() || n.entriesOf(it.key).isNotEmpty()) }
                    .map { it to meaning.likeness(target, it.key) }
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
    fun relate(scope: TagScope, facet: String, tag: String, other: String, relation: TagRelation): NamedTag? {
        if (tag == other) throw Refusal.TAG_SELF.refuse(HttpStatus.BAD_REQUEST, "The tag $tag is not related to itself")
        if (relation == TagRelation.DESCENDANT || relation == TagRelation.ANCESTOR) {
            throw Refusal.TAG_THROUGH.refuse(HttpStatus.BAD_REQUEST, "$relation is read, never said: said of the tags between")
        }
        val a = TagRepository.idOf(tag)?.takeIf(scope.tags::exists) ?: return null
        val b = TagRepository.idOf(other)?.takeIf(scope.tags::exists) ?: return null
        val before = itemsOf(scope, facet, listOf(tag, other))
        val now = Instant.now()
        // What was said of the two taken back and the new word said, all or nothing: a refusal leaves them as they were
        scope.tags.transaction { t ->
            t.setParent(b, a, false, now)
            t.setParent(a, b, false, now)
            t.setDistinct(a, b, false, now)
            val hierarchy = TagHierarchy(t.parents())
            when (relation) {
                TagRelation.MERGED -> t.merge(b, a, now)
                TagRelation.CHILD -> {
                    if (other in hierarchy.ancestors(tag)) {
                        throw Refusal.TAG_ABOVE.refuse(HttpStatus.BAD_REQUEST, "The tag $other is above $tag already: as its child it would be its own parent")
                    }
                    t.setParent(b, a, true, now)
                }
                TagRelation.PARENT -> {
                    if (other in hierarchy.descendants(tag)) {
                        throw Refusal.TAG_BELOW.refuse(HttpStatus.BAD_REQUEST, "The tag $other is below $tag already: as its parent it would be its own child")
                    }
                    t.setParent(a, b, true, now)
                }
                TagRelation.DIFFERENT -> t.setDistinct(a, b, true, now)
                TagRelation.NONE, TagRelation.DESCENDANT, TagRelation.ANCESTOR -> Unit
            }
        }
        if (relation == TagRelation.MERGED) moveMergedAnswers(scope, facet)
        log.info("{}: tag {} is {} to {}", scope.id, other, relation, tag)
        changed(scope, facet, before)
        return one(scope, facet, tag)
    }

    /**
     * The user's answers of every merge not done moved to the tag kept, in every source — the
     * earliest merge first, a chain of merges in its order — and the merge done once they all are.
     * A source that fails leaves its merge to the next start: moving again what was moved is no
     * change.
     */
    private fun moveMergedAnswers(scope: TagScope, facet: String) {
        scope.tags.pendingMerges().forEach { (from, into) ->
            try {
                scope.sources.forEach { s -> s.corrections.moveFacetValues(facet, listOf(TagRepository.key(from)), TagRepository.key(into)) }
                scope.tags.answersMoved(from)
            } catch (e: Exception) {
                log.warn("{}: the answers on tag {} merged into {} were not moved, left to the next start: {}", scope.id, from, into, e.message, e)
            }
        }
    }

    /**
     * The site's tag [site] bound to the normalized tag [tag] — to a new one of its own when null:
     * taken out of the tag it was. The user's answers stay with the tags they were given on.
     * Returns the tag it is bound to now.
     */
    fun bind(scope: TagScope, facet: String, site: SiteTagRef, tag: String?): NamedTag? {
        val was = scope.tags.bindings()[site] ?: return null
        val before = itemsOf(scope, facet, listOfNotNull(TagRepository.key(was), tag))
        val known = tag?.let { TagRepository.idOf(it)?.takeIf(scope.tags::exists) ?: return null }
        // A new tag of its own and the site's tag bound to it, both or neither
        val to = scope.tags.transaction { t -> (known ?: t.create()).also { t.bind(site, it, Instant.now()) } }
        log.info("{}: {} of {} bound to tag {}, was {}", scope.id, site.tag, site.source, to, was)
        changed(scope, facet, before)
        return one(scope, facet, TagRepository.key(to))
    }

    /** The name the user gives [tag]; null or blank takes it back — the tag is named by its sites again. */
    fun name(scope: TagScope, facet: String, tag: String, name: String?): NamedTag? {
        val id = TagRepository.idOf(tag)?.takeIf(scope.tags::exists) ?: return null
        scope.tags.name(id, name?.trim()?.takeIf { it.isNotEmpty() })
        changed(scope, facet, itemsOf(scope, facet, listOf(tag)))
        return one(scope, facet, tag)
    }

    /**
     * Whether the user ignores [tag]: it stays with its works, shown and found, but the model of the
     * grades reads it no more — it learns anew without it.
     */
    fun ignore(scope: TagScope, facet: String, tag: String, ignored: Boolean): NamedTag? {
        val id = TagRepository.idOf(tag)?.takeIf(scope.tags::exists) ?: return null
        scope.tags.ignore(id, ignored)
        log.info("{}: tag {} {}", scope.id, id, if (ignored) "ignored" else "no longer ignored")
        // The model alone reads it otherwise: the works, their names and the search are as they were
        scope.types.forEach { events.publishEvent(TagsChanged(it)) }
        return one(scope, facet, tag)
    }

    // --- The entries of a catalogue ---

    /** The entries every catalogue of tags finds by [text] — an id or a page's address is the entry itself — each with the tags bound to it already. */
    fun findEntries(scope: TagScope, text: String): List<FoundEntry> {
        if (text.isBlank()) return emptyList()
        val bound = HashMap<TagEntryRef, MutableList<String>>()
        scope.tags.entriesOfTags().forEach { (id, refs) -> refs.forEach { bound.getOrPut(it) { ArrayList() } += TagRepository.key(id) } }
        val found = plugins.tagCatalogues.flatMap { c -> c.find(text, languages).map { c.id to it } }
        val names = scope.tags.shown(found.flatMap { (c, e) -> bound[TagEntryRef(c, e.id)].orEmpty() })
        return found.map { (c, e) -> FoundEntry(c, e, bound[TagEntryRef(c, e.id)].orEmpty().map { it to (names[it] ?: it) }) }
    }

    /**
     * The tag [tag] bound to the entry [entry] of [catalogue] besides the ones it is — the entry read
     * of the catalogue now and kept; the label of the one bound first the tag's name unless the user
     * named it — or, [bound] false, no more to it. The tag's works are read again: its name is what
     * the model reads.
     */
    fun bindEntry(scope: TagScope, facet: String, tag: String, catalogue: String, entry: String, bound: Boolean): NamedTag? {
        val id = TagRepository.idOf(tag)?.takeIf(scope.tags::exists) ?: return null
        val now = Instant.now()
        if (!bound) {
            scope.tags.bindEntry(id, TagEntryRef(catalogue, entry), false, now)
            log.info("{}: tag {} bound no more to {}:{}", scope.id, tag, catalogue, entry)
        } else {
            val source = plugins.tagCatalogues.firstOrNull { it.id == catalogue } ?: throw NoSuchElementException("No catalogue of tags $catalogue")
            val read = source.entries(listOf(entry), languages).firstOrNull() ?: throw NoSuchElementException("$catalogue has no $entry")
            scope.tags.transaction { t ->
                t.keepEntry(catalogue, read, now)
                t.bindEntry(id, TagEntryRef(catalogue, read.id), true, now)
            }
            log.info("{}: tag {} bound to {}:{} ({})", scope.id, tag, catalogue, read.id, languages.firstNotNullOfOrNull { read.labels[it] })
            // What it is a kind of: the binding stands without it, read again from the tag's card
            try {
                readAbove(scope, source, listOf(read.id))
            } catch (e: Exception) {
                log.warn("{}: what {}:{} is a kind of was not read: {}", scope.id, catalogue, read.id, e.message)
            }
        }
        changed(scope, facet, itemsOf(scope, facet, listOf(tag)))
        return one(scope, facet, tag)
    }

    /**
     * The entries [tag] is bound to read of their catalogues again: their names and descriptions, and
     * what they are a kind of, every level up asked again. What was kept stays shown till then.
     */
    fun refreshEntries(scope: TagScope, facet: String, tag: String): NamedTag? {
        val id = TagRepository.idOf(tag)?.takeIf(scope.tags::exists) ?: return null
        val now = Instant.now()
        scope.tags.entriesOfTags()[id].orEmpty().groupBy { it.catalogue }.forEach { (catalogueId, refs) ->
            val catalogue = plugins.tagCatalogues.firstOrNull { it.id == catalogueId } ?: throw NoSuchElementException("No catalogue of tags $catalogueId")
            val ids = refs.map { it.entry }
            catalogue.entries(ids, languages).forEach { scope.tags.keepEntry(catalogueId, it, now) }
            readAbove(scope, catalogue, ids, anew = true)
        }
        log.info("{}: the entries of tag {} read again", scope.id, tag)
        changed(scope, facet, itemsOf(scope, facet, listOf(tag)))
        return one(scope, facet, tag)
    }

    /** The tags changed: their names worked out again now, their works read again — their chances, their search — and the model of the grades trained again. */
    private fun changed(scope: TagScope, facet: String, items: Collection<ItemKey>) {
        rename(scope, facet)
        items.groupBy({ it.source }, { it.id }).forEach { (source, ids) -> events.publishEvent(ItemsChanged(source, ids)) }
        scope.types.forEach { events.publishEvent(TagsChanged(it)) }
    }

    // --- The names worked out ---

    /**
     * Every tag counted now — every work of every source — and what is shown of it kept: the names
     * worked out, the works of every tag and of every site's tag, the sources of its sites' tags.
     * The screen of the tags reads them ([stats]), not the works.
     */
    fun rename(scope: TagScope, facet: String) = workload.run("tags: named and counted") { renamed(scope, facet) }

    private fun renamed(scope: TagScope, facet: String) {
        val started = System.currentTimeMillis()
        val n = naming(scope, facet, countAll(scope, facet))
        val stats = n.stats
        // Of the application's working: the user's own name is shown over it, not kept in it
        scope.tags.replaceCalculated(stats.values.associate { t -> t.id to TagNaming.name(t, null, n.cataloguedOf(t.key)) })
        scope.tags.keepStats(
            sites = stats.values.flatMap { t -> t.variants.values }.associate { v -> v.site to (v.name to Counts(v.uses.works, v.uses.graded, v.uses.liked)) },
            tags = stats.values.associate { t -> t.id to TagCount(t.counts, t.sources) },
        )
        log.info("{}: names and works of {} tags counted and kept in {} ms", scope.id, stats.size, System.currentTimeMillis() - started)
    }

    /** The sites' new tags bound, and the names worked out again, shortly after the last of a quick series of changes. */
    private fun schedule() {
        pending.compute(RENAMING) { _, old ->
            old?.cancel(false)
            scheduler.schedule({
                try {
                    val scope = scope() ?: return@schedule
                    normalization.bindNew()
                    rename(scope, scope.facet)
                } catch (e: Exception) {
                    log.warn("The tags were not bound and named: {}", e.message, e)
                }
            }, RENAME_DELAY_SECONDS, TimeUnit.SECONDS)
        }
    }

    @EventListener
    fun gradesChanged(event: GradesChanged) = schedule()

    @EventListener
    fun itemsChanged(event: ItemsChanged) = schedule()

    /** After the tags are normalized on start ([TagNormalization.start]). */
    @Order(-5)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        val scope = scope() ?: return
        // The answers of a merge a stop or a failure left unmoved: moved before anything reads them
        moveMergedAnswers(scope, scope.facet)
        schedule()
        // The bindings made before what their entries are a kind of was read: read once, in the background
        scheduler.execute { readAboveOfBound(scope) }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
    }

    companion object {
        /** The one renaming waited for, of every type's tags. */
        private const val RENAMING = "tags"

        /** The quiet after the last change before the names are worked out again: a page is sent several times as it grows. */
        private const val RENAME_DELAY_SECONDS = 10L

        /**
         * How many levels up what an entry is a kind of is read and offered from: "satire" →
         * "humor" → "creative work" → … — the bound of the reading, not of what is shown of it.
         */
        const val ABOVE_LEVELS = 5

        /** A grade above the middle of the scale: of five, 4 and 5. */
        fun liked(grade: Int): Boolean = grade > (Grades.MIN + Grades.MAX) / 2.0
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
