package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.ItemsApi
import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.ContributionGroup as ApiContributionGroup
import io.github.vlsergey.recommend4me.api.model.FacetValueUse
import io.github.vlsergey.recommend4me.api.model.HeldOut as ApiHeldOut
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetValues
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.MatchFeedbackRequest
import io.github.vlsergey.recommend4me.api.model.PartText
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.mark.ThingRef
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.picture.PictureService
import io.github.vlsergey.recommend4me.picture.Served
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.textvector.TextVectors
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.time.Duration
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.ItemView as ApiItemView

private val log = LoggerFactory.getLogger(ItemsController::class.java)

@RestController
class ItemsController(
    private val stores: Stores,
    private val list: ItemList,
    private val cards: ItemCards,
    private val details: ItemDetailsReader,
    private val pictures: PictureService,
    private val contexts: SourceContexts,
    private val textVectors: TextVectors,
    private val recommendations: Recommendations,
    private val events: ApplicationEventPublisher,
) : ItemsApi {

    override fun listItems(
        type: String, view: ApiItemView, sort: ItemSort, sortNumber: String?, search: String?,
        hidden: List<String>?, hiddenWithout: List<String>?, source: List<String>?, offset: Int, limit: Int,
    ): ResponseEntity<ItemPage> = ResponseEntity.ok(
        list.page(
            type, ItemView.valueOf(view.name), ItemOrder.valueOf(sort.name), sortNumber, search,
            hidden.orEmpty().toSet(), hiddenWithout.orEmpty().toSet(), source?.toSet()?.takeIf { it.isNotEmpty() }, offset, limit,
        ),
    )

    override fun listFacets(type: String): ResponseEntity<List<FacetFilter>> = ResponseEntity.ok(list.facets(type))

    override fun listFacetValues(source: String, facet: String, query: String?, limit: Int): ResponseEntity<List<FacetValueUse>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.schema.facet(facet) == null) return ResponseEntity.notFound().build()
        val piece = query?.trim()?.lowercase().orEmpty()
        return ResponseEntity.ok(
            FacetValues.of(store, facet).asSequence()
                .filter { piece.isEmpty() || piece in it.name.lowercase() || piece in it.key.lowercase() }
                // A value beginning with what was typed before one only holding it
                .sortedBy { if (it.name.lowercase().startsWith(piece)) 0 else 1 }
                .take(limit.coerceIn(1, 500))
                .map { FacetValueUse(it.key, it.name, it.items) }
                .toList(),
        )
    }

    override fun lookupItems(type: String, query: String, limit: Int): ResponseEntity<List<LinkedItem>> {
        val typeStore = stores.type(type) ?: return ResponseEntity.notFound().build()
        val line = query.trim()
        if (line.isEmpty()) return ResponseEntity.ok(emptyList())
        val wanted = limit.coerceIn(1, 100)
        val found = LinkedHashMap<ItemKey, LinkedItem>()
        // The address of a work's page first: what the user pastes from the site
        if (line.startsWith("http")) typeStore.sources.forEach { s ->
            val id = runCatching { s.source.itemIdOf(line) }.getOrNull() ?: return@forEach
            val head = s.items.find(id) ?: return@forEach
            found[ItemKey(s.id, id)] = LinkedItem(s.id, id, Corrected.title(head.title, s.corrections.fieldsOf(id)), head.url)
        }
        typeStore.sources.forEach { s ->
            val titled = s.corrections.allFields().filter { (_, f) -> f[Corrected.TITLE]?.lowercase()?.contains(line.lowercase()) == true }.keys
            (s.items.findByTitle(line, wanted) + s.items.heads(titled).values).forEach { head ->
                found.putIfAbsent(ItemKey(s.id, head.id), LinkedItem(s.id, head.id, Corrected.title(head.title, s.corrections.fieldsOf(head.id)), head.url))
            }
        }
        return ResponseEntity.ok(found.values.take(wanted))
    }

    override fun getItem(source: String, item: String): ResponseEntity<ItemDetails> =
        details.read(ItemKey(source, item))?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun getExplanation(source: String, item: String): ResponseEntity<List<ApiContributionGroup>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(recommendations.explanation(ItemKey(source, item)).map { it.toApi(withFeatures = false) })
    }

    override fun getHeldOut(source: String, item: String): ResponseEntity<ApiHeldOut> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        val h = recommendations.heldOut(ItemKey(source, item)) ?: return ResponseEntity.noContent().build()
        return ResponseEntity.ok(ApiHeldOut(h.score, h.explanation.map { it.toApi() }, h.gradePlace))
    }

    override fun refreshItem(source: String, item: String): ResponseEntity<ItemDetails> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        if (store.source.modes.none { it != SourceMode.BROWSER }) throw ResponseStatusException(HttpStatus.CONFLICT, "Этот источник не скачивает страницы сам: откройте работу в браузере")
        val refreshed = try {
            store.source.refresh(item, contexts.of(source))
        } catch (e: Exception) {
            log.warn("{}: {} was not loaded: {}", source, item, e.message)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Сайт не ответил: ${e.message}")
        }
        if (!refreshed) throw ResponseStatusException(HttpStatus.CONFLICT, "Источник не смог скачать страницу работы")
        textVectors.refresh(store, only = listOf(item))
        recommendations.scoreItem(ItemKey(source, item))
        return getItem(source, item)
    }

    override fun rateItem(source: String, item: String, ratingRequest: RatingRequest): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val head = store.items.find(item) ?: return ResponseEntity.notFound().build()
        if (ratingRequest.grade !in Grades.RANGE) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Оценка — от ${Grades.MIN} до ${Grades.MAX}")
        store.ratings.rate(item, head.version, ratingRequest.grade, Instant.now())
        events.publishEvent(GradesChanged(store.type, source, item))
        return card(ItemKey(source, item))
    }

    override fun unrateItem(source: String, item: String, version: String?): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val head = store.items.find(item) ?: return ResponseEntity.notFound().build()
        store.ratings.unrate(item, version ?: head.version)
        events.publishEvent(GradesChanged(store.type, source, item))
        return card(ItemKey(source, item))
    }

    private fun card(key: ItemKey): ResponseEntity<ItemSummary> =
        cards.cards(stores.typeOf(key.source), listOf(key)).firstOrNull()?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun getPicture(source: String, item: String, position: Int, full: Boolean): ResponseEntity<Resource> =
        when (val served = pictures.serve(ItemKey(source, item), position, full)) {
            null -> ResponseEntity.notFound().build()
            // A picture changes only together with its address, and then the file is replaced
            is Served.File -> ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(served.contentType))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(FileSystemResource(served.path))
            is Served.Redirect -> ResponseEntity.status(HttpStatus.FOUND).location(URI.create(served.url)).build()
        }

    override fun markPicture(source: String, item: String, position: Int, mark: Mark): ResponseEntity<Unit> {
        if (mark.mark != 1 && mark.mark != -1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Отметка — +1 или −1")
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val picture = store.pictures.find(item, position, null) ?: return ResponseEntity.notFound().build()
        store.marks.markPicture(item, position, picture.url, mark.mark, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun unmarkPicture(source: String, item: String, position: Int): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        store.marks.markPicture(item, position, "", null, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun markReview(source: String, item: String, reviewId: String, mark: Mark): ResponseEntity<Unit> {
        if (mark.mark != 1 && mark.mark != -1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Отметка — +1 или −1")
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (reviewId !in store.reviews.ids(item)) return ResponseEntity.notFound().build()
        store.marks.markReview(item, reviewId, mark.mark, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun unmarkReview(source: String, item: String, reviewId: String): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        store.marks.markReview(item, reviewId, null, Instant.now())
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }

    override fun getPart(source: String, item: String, partId: String): ResponseEntity<PartText> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val (part, content) = store.parts.text(item, partId) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(PartText(part.partId, part.position, content, part.title))
    }

    override fun giveMatchFeedback(matchFeedbackRequest: MatchFeedbackRequest): ResponseEntity<Unit> {
        val r = matchFeedbackRequest
        if (r.verdict !in -1..1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Ответ — +1, 0 или −1")
        val store = stores.source(r.markSource) ?: return ResponseEntity.notFound().build()
        store.marks.setFeedback(
            MarkKind.valueOf(r.kind.value),
            ThingRef(ItemKey(r.markSource, r.markItem), r.markRef),
            ThingRef(ItemKey(r.matchSource, r.matchItem), r.matchRef),
            r.verdict.takeIf { it != 0 }, Instant.now(),
        )
        events.publishEvent(MarksChanged(store.type))
        return ResponseEntity.noContent().build()
    }
}
