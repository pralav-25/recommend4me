package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.TagsApi
import io.github.vlsergey.recommend4me.api.model.SimilarTag as ApiSimilarTag
import io.github.vlsergey.recommend4me.api.model.SimilarTagPage
import io.github.vlsergey.recommend4me.api.model.TagBindingRequest
import io.github.vlsergey.recommend4me.api.model.TagInfo
import io.github.vlsergey.recommend4me.api.model.TagNameRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRule
import io.github.vlsergey.recommend4me.api.model.TagPage
import io.github.vlsergey.recommend4me.api.model.TagRef
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.api.model.TagRelation as ApiTagRelation
import io.github.vlsergey.recommend4me.api.model.TagVariantInfo
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class TagsController(private val stores: Stores, private val tags: Tags) : TagsApi {

    /** The type and its facet of free tags; 404 for a type without one. */
    private fun of(typeId: String): Pair<TypeStore, String> {
        val type = stores.type(typeId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Нет такого типа")
        val facet = tags.facetOf(type) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "У этого типа нет тегов сайтов")
        return type to facet
    }

    override fun listTags(type: String, query: String?, offset: Int, limit: Int): ResponseEntity<TagPage> {
        val (t, facet) = of(type)
        val all = tags.list(t, facet, query)
        return ResponseEntity.ok(TagPage(all.size, all.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, 1000)).map { it.toApi() }))
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
        hidden: List<ApiTagRelation>?,
    ): ResponseEntity<SimilarTagPage> {
        val (t, facet) = of(type)
        // Rows of no relation are the offers themselves: never hidden
        val hide = hidden.orEmpty().map { TagRelation.valueOf(it.name) }.toSet() - TagRelation.NONE
        val page = tags.similar(t, facet, tag, query, hide, offset, limit.coerceIn(1, 1000)) ?: return ResponseEntity.notFound().build()
        val rows = page.rows.map { ApiSimilarTag(it.tag.toApi(), it.similarity, ApiTagRelation.valueOf(it.relation.name), it.site?.toApi()) }
        return ResponseEntity.ok(SimilarTagPage(page.total, rows))
    }

    override fun relateTags(type: String, tagRelationRequest: TagRelationRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        val r = tagRelationRequest
        val related = try {
            tags.relate(t, facet, r.tag, r.other, TagRelation.valueOf(r.relation.name))
        } catch (e: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, e.message)
        }
        return related?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun nameTag(type: String, tagNameRequest: TagNameRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        return tags.name(t, facet, tagNameRequest.tag, tagNameRequest.name)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun bindSiteTag(type: String, tagBindingRequest: TagBindingRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        val r = tagBindingRequest
        return tags.bind(t, facet, SiteTagRef(r.source, r.siteTag), r.tag)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    private fun NamedTag.toApi() = TagInfo(
        key = stats.key,
        name = name.name,
        rule = TagNameRule.valueOf(name.rule.name),
        explicitName = explicit,
        works = stats.items.size,
        graded = stats.graded.size,
        liked = stats.liked.size,
        variants = stats.variants.values.sortedByDescending { it.uses.works }.map { it.toApi() },
        parents = parents.map { (key, name) -> TagRef(key, name) },
        children = children.map { (key, name) -> TagRef(key, name) },
    )

    private fun TagVariant.toApi() = TagVariantInfo(site.source, site.tag, name, uses.works, uses.graded, uses.liked)
}
