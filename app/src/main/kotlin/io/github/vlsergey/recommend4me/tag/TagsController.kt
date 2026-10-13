package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.TagsApi
import io.github.vlsergey.recommend4me.api.model.SimilarTag as ApiSimilarTag
import io.github.vlsergey.recommend4me.api.model.TagDistinctRequest
import io.github.vlsergey.recommend4me.api.model.TagInfo
import io.github.vlsergey.recommend4me.api.model.TagMergeRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRule
import io.github.vlsergey.recommend4me.api.model.TagPage
import io.github.vlsergey.recommend4me.api.model.TagSplitRequest
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

    override fun listSimilarTags(type: String, tag: String, limit: Int): ResponseEntity<List<ApiSimilarTag>> {
        val (t, facet) = of(type)
        return ResponseEntity.ok(tags.similar(t, facet, tag, limit.coerceIn(1, 200)).map { ApiSimilarTag(it.tag.toApi(), it.similarity) })
    }

    override fun mergeTags(type: String, tagMergeRequest: TagMergeRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        if (tagMergeRequest.tags.distinct().size < 2) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Нужно хотя бы два тега")
        return tags.merge(t, facet, tagMergeRequest.tags, tagMergeRequest.name)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun nameTag(type: String, tagNameRequest: TagNameRequest): ResponseEntity<TagInfo> {
        val (t, facet) = of(type)
        return tags.name(t, facet, tagNameRequest.tag, tagNameRequest.name)?.let { ResponseEntity.ok(it.toApi()) } ?: ResponseEntity.notFound().build()
    }

    override fun splitTag(type: String, tagSplitRequest: TagSplitRequest): ResponseEntity<Unit> {
        val (t, facet) = of(type)
        return if (tags.split(t, facet, tagSplitRequest.member)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
    }

    override fun setTagsDistinct(type: String, tagDistinctRequest: TagDistinctRequest): ResponseEntity<Unit> {
        val (t, facet) = of(type)
        val r = tagDistinctRequest
        if (r.first == r.other) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Тег не может отличаться от самого себя")
        tags.setDistinct(t, facet, r.first, r.other, r.different)
        return ResponseEntity.noContent().build()
    }

    private fun NamedTag.toApi() = TagInfo(
        key = stats.key,
        name = name.name,
        rule = TagNameRule.valueOf(name.rule.name),
        explicitName = explicit,
        works = stats.items.size,
        graded = stats.graded.size,
        liked = stats.liked.size,
        variants = stats.variants.values.sortedByDescending { it.uses.works }
            .map { TagVariantInfo(it.source, it.name, it.uses.works, it.uses.graded, it.uses.liked) },
        members = stats.members.sorted(),
    )
}
