package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.TagsApi
import io.github.vlsergey.recommend4me.api.model.CatalogueOffer
import io.github.vlsergey.recommend4me.api.model.FoundTagEntry
import io.github.vlsergey.recommend4me.api.model.HierarchyOfferKind
import io.github.vlsergey.recommend4me.api.model.TagAboveRequest
import io.github.vlsergey.recommend4me.api.model.HierarchyOffer as ApiHierarchyOffer
import io.github.vlsergey.recommend4me.api.model.TagDescription
import io.github.vlsergey.recommend4me.api.model.TagEntryInfo
import io.github.vlsergey.recommend4me.api.model.TagEntryRequest
import org.springframework.beans.factory.annotation.Value
import io.github.vlsergey.recommend4me.api.model.SimilarTag as ApiSimilarTag
import io.github.vlsergey.recommend4me.api.model.SimilarTagPage
import io.github.vlsergey.recommend4me.api.model.TagBindingRequest
import io.github.vlsergey.recommend4me.api.model.TagIgnoredRequest
import io.github.vlsergey.recommend4me.api.model.TagInfo
import io.github.vlsergey.recommend4me.api.model.TagNameRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRule
import io.github.vlsergey.recommend4me.api.model.TagPage
import io.github.vlsergey.recommend4me.api.model.TagRef
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.api.model.TagRelation as ApiTagRelation
import io.github.vlsergey.recommend4me.api.model.TagVariantInfo
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

@RestController
class TagsController(
    private val stores: Stores,
    private val tags: Tags,
    /** The user's languages: of an entry's texts, those of the first that has them are shown. */
    @Value("\${recommend4me.languages:ru,en}") private val languages: List<String>,
) : TagsApi {

    /** The type and its facet of free tags; 404 for a type without one. */
    private fun of(typeId: String): Pair<TypeStore, String> {
        val type = stores.type(typeId) ?: throw Refusal.NO_SUCH_TYPE.refuse(HttpStatus.NOT_FOUND, "There is no type '$typeId'")
        val facet = tags.facetOf(type) ?: throw Refusal.NO_SITE_TAGS.refuse(HttpStatus.NOT_FOUND, "The type $typeId has no site tags")
        return type to facet
    }

    override fun listTags(
        type: String, query: String?, with: List<String>?, without: List<String>?, ignored: Boolean?, offset: Int, limit: Int,
    ): ResponseEntity<TagPage> {
        val (t, facet) = of(type)
        val all = tags.list(t, facet, query, with.orEmpty().toSet(), without.orEmpty().toSet(), ignored)
        return ResponseEntity.ok(
            TagPage(
                total = all.size,
                tags = all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 1000)).map { it.toApi() },
                sources = tags.sources(t, facet),
            ),
        )
    }

    override fun getTag(type: String, tag: String): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        return tags.one(t, facet, tag)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun listSimilarTags(
        type: String,
        tag: String,
        query: String?,
        offset: Int,
        limit: Int,
        ignored: Boolean?,
        hidden: List<ApiTagRelation>?,
    ): ResponseEntity<SimilarTagPage> {
        val (t, facet) = of(type)
        // Rows of no relation are the offers themselves: never hidden
        val hide = hidden.orEmpty().map { TagRelation.valueOf(it.name) }.toSet() - TagRelation.NONE
        val page = tags.similar(t, facet, tag, query, hide, offset, limit.coerceIn(1, 1000), ignored) ?: return ResponseEntity.notFound().build()
        val rows = page.rows.map { ApiSimilarTag(it.tag.toApi(), it.similarity, ApiTagRelation.valueOf(it.relation.name), it.site?.toApi()) }
        return ResponseEntity.ok(SimilarTagPage(page.total, rows))
    }

    override fun relateTags(type: String, tagRelationRequest: TagRelationRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        val r = tagRelationRequest
        val related = tags.relate(t, facet, r.tag, r.other, TagRelation.valueOf(r.relation.name))
        return related?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun nameTag(type: String, tagNameRequest: TagNameRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        return tags.name(t, facet, tagNameRequest.tag, tagNameRequest.name)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun ignoreTag(type: String, tagIgnoredRequest: TagIgnoredRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        return tags.ignore(t, facet, tagIgnoredRequest.tag, tagIgnoredRequest.ignored)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun bindSiteTag(type: String, tagBindingRequest: TagBindingRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        val r = tagBindingRequest
        return tags.bind(t, facet, SiteTagRef(r.source, r.siteTag), r.tag)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun findTagEntries(type: String, query: String): ResponseEntity<List<FoundTagEntry>> {
        val (t, _) = of(type)
        return ResponseEntity.ok(
            tags.findEntries(t, query).map { f ->
                FoundTagEntry(f.catalogue, f.entry.id, f.entry.url, f.tags.map { (key, name) -> TagRef(key, name) }, f.entry.labels.first(), f.entry.descriptions.first())
            },
        )
    }

    override fun bindTagEntry(type: String, tagEntryRequest: TagEntryRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        val r = tagEntryRequest
        return try {
            tags.bindEntry(t, facet, r.tag, r.catalogue, r.entry, r.bound)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    override fun listHierarchyOffers(type: String): ResponseEntity<List<ApiHierarchyOffer>> {
        val (t, facet) = of(type)
        val offers = tags.hierarchyOffers(t, facet)
        val (names, kept) = tags.namesOf(t, offers)
        fun label(ref: TagEntryRef) = kept[ref]?.labels?.first() ?: ref.entry
        return ResponseEntity.ok(
            offers.map { o ->
                ApiHierarchyOffer(
                    kind = HierarchyOfferKind.valueOf(o.kind.name),
                    catalogue = o.catalogue,
                    tags = o.tags.map { TagRef(it, names[it] ?: it) },
                    path = o.way.map(::label),
                    graded = o.graded,
                    levels = o.levels,
                    existing = o.existing?.let { TagRef(it, names[it] ?: it) },
                    entry = o.entry?.entry,
                    url = o.entry?.let { kept[it]?.url },
                )
            },
        )
    }

    override fun createTagAbove(type: String, tagAboveRequest: TagAboveRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        val r = tagAboveRequest
        return try {
            tags.createAbove(t, facet, r.catalogue, r.entry, r.children, r.tag)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    override fun refreshTagEntries(type: String, tag: String): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        return try {
            tags.refreshEntries(t, facet, tag)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
        } catch (e: NoSuchElementException) {
            ResponseEntity.notFound().build()
        }
    }

    /** The text of the user's first language that has one. */
    private fun Map<String, String>.first(): String? = languages.firstNotNullOfOrNull { this[it] }

    private fun NamedTag.toApi() = TagInfo(
        key = stats.key,
        name = name.name,
        rule = TagNameRule.valueOf(name.rule.name),
        explicitName = explicit,
        works = stats.items.size,
        graded = stats.graded.size,
        liked = stats.liked.size,
        variants = stats.variants.values.sortedByDescending { it.uses.works }.map { it.toApi() },
        names = TagNaming.names(stats).map { it.first },
        parents = parents.map { (key, name) -> TagRef(key, name) },
        children = children.map { (key, name) -> TagRef(key, name) },
        catalogueEntries = entries.map { b -> TagEntryInfo(b.catalogue, b.entry.id, b.entry.url, b.names, b.entry.labels.first(), b.entry.descriptions.first()) },
        // The sites' words first, then the entries'
        descriptions = descriptions.map { d -> TagDescription(d.site.source, d.text, stats.variants[d.site]?.name) } +
            entries.mapNotNull { b -> b.entry.descriptions.first()?.let { TagDescription(b.catalogue, it, b.entry.labels.first()) } },
        ignored = ignored,
        catalogueOffers = offers.map { o -> CatalogueOffer(o.catalogue, TagRef(o.tag, o.name), ApiTagRelation.valueOf(o.relation.name), o.path) },
    )

    private fun TagVariant.toApi() = TagVariantInfo(site.source, site.tag, name, uses.works, uses.graded, uses.liked)
}
