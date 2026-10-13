package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.ItemsApi
import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.i18n.failure
import io.github.vlsergey.recommend4me.api.model.FacetFilter
import io.github.vlsergey.recommend4me.api.model.ContributionGroup as ApiContributionGroup
import io.github.vlsergey.recommend4me.api.model.FacetValueUse
import io.github.vlsergey.recommend4me.api.model.HeldOut as ApiHeldOut
import io.github.vlsergey.recommend4me.api.model.LinkedItem
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetValues
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.ItemPage
import io.github.vlsergey.recommend4me.api.model.ItemQuery
import io.github.vlsergey.recommend4me.api.model.SearchJudgment
import io.github.vlsergey.recommend4me.api.model.SearchQuality
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.ItemSummary
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.MatchFeedbackRequest
import io.github.vlsergey.recommend4me.api.model.PartText
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.InterestRequest
import io.github.vlsergey.recommend4me.interest.InterestChanged
import io.github.vlsergey.recommend4me.interest.LinkedInterest
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.mark.MarksChanged
import io.github.vlsergey.recommend4me.mark.ThingRef
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.picture.PictureService
import io.github.vlsergey.recommend4me.picture.Served
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.rating.GradesChanged
import io.github.vlsergey.recommend4me.rating.LinkedGrades
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.downloadsPages
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
import java.net.URI
import java.time.Duration
import java.time.Instant
import io.github.vlsergey.recommend4me.api.model.ItemView as ApiItemView

private val log = LoggerFactory.getLogger(ItemsController::class.java)

private val BAD_RANGE = Label(
    "The range “%s” is not understood: “number=from..to” is wanted",
    "Диапазон «%s» не понят: нужно «число=от..до»",
    "Opseg „%s” nije razumljiv: potrebno je „broj=od..do”",
)
private val NO_DOWNLOADS = Label(
    "This source does not download pages by itself: open the work in the browser",
    "Этот источник не скачивает страницы сам: откройте работу в браузере",
    "Ovaj izvor sam ne preuzima stranice: otvorite delo u pregledaču",
)
private val BAD_MARK = Label("A mark is +1 or −1", "Отметка — +1 или −1", "Oznaka je +1 ili −1")

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
    private val removal: ItemRemoval,
    private val linkedGrades: LinkedGrades,
    private val linkedInterest: LinkedInterest,
    private val searchQuality: io.github.vlsergey.recommend4me.search.SearchQuality,
) : ItemsApi {

    override fun judgeSearch(type: String, searchJudgment: SearchJudgment): ResponseEntity<Unit> {
        val store = stores.type(type) ?: return ResponseEntity.notFound().build()
        val j = searchJudgment
        store.searchJudgments.judge(j.query, ItemKey(j.source, j.item), j.relevant, Instant.now())
        return ResponseEntity.noContent().build()
    }

    override fun getSearchQuality(type: String, top: Int): ResponseEntity<SearchQuality> =
        searchQuality.measure(type, top)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    override fun removeItem(source: String, item: String): ResponseEntity<Unit> =
        if (removal.remove(ItemKey(source, item))) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    override fun listItems(type: String, itemQuery: ItemQuery): ResponseEntity<ItemPage> {
        val q = itemQuery
        // A field sent as null is the field left out: the defaults of the API's own schema
        val d = ItemQuery()
        val ranges = q.ranges.orEmpty().map { NumberRange.parse(it) ?: throw failure(HttpStatus.BAD_REQUEST, BAD_RANGE, it) }
        val facets = q.facets.orEmpty().map { c -> FacetChoice(c.facet, c.hidden.orEmpty().toSet(), c.shown?.toSet(), c.withoutHidden == true) }
        return ResponseEntity.ok(
            list.page(
                type, ItemView.valueOf((q.view ?: d.view!!).name), ItemOrder.valueOf((q.sort ?: d.sort!!).name), q.sortNumber, q.search,
                facets, q.sources?.toSet()?.takeIf { it.isNotEmpty() }, ranges, q.offset ?: d.offset!!, q.limit ?: d.limit!!,
            ),
        )
    }

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
        if (!store.source.downloadsPages) throw failure(HttpStatus.CONFLICT, NO_DOWNLOADS)
        val refreshed = try {
            store.source.refresh(item, contexts.of(source))
        } catch (e: Exception) {
            log.warn("{}: {} was not loaded: {}", source, item, e.message)
            throw failure(HttpStatus.BAD_GATEWAY, Label("The site did not answer: %s", "Сайт не ответил: %s", "Sajt nije odgovorio: %s"), e.message)
        }
        if (!refreshed) {
            throw failure(
                HttpStatus.CONFLICT,
                Label("The source could not download the work's page", "Источник не смог скачать страницу работы", "Izvor nije mogao da preuzme stranicu dela"),
            )
        }
        textVectors.refresh(store, only = listOf(item))
        recommendations.scoreItem(ItemKey(source, item))
        return getItem(source, item)
    }

    override fun rateItem(source: String, item: String, ratingRequest: RatingRequest): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val head = store.items.find(item) ?: return ResponseEntity.notFound().build()
        if (ratingRequest.grade !in Grades.RANGE) throw failure(HttpStatus.BAD_REQUEST, Label("A grade is from %s to %s", "Оценка — от %s до %s", "Ocena je od %s do %s"), Grades.MIN, Grades.MAX)
        store.ratings.rate(item, head.version, ratingRequest.grade, Instant.now())
        events.publishEvent(GradesChanged(store.type, source, item))
        // The items linked to it are the same work: graded the same
        linkedGrades.share(ItemKey(source, item))
        return card(ItemKey(source, item))
    }

    override fun unrateItem(source: String, item: String, version: String?): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val head = store.items.find(item) ?: return ResponseEntity.notFound().build()
        store.ratings.unrate(item, version ?: head.version)
        events.publishEvent(GradesChanged(store.type, source, item))
        // The grade of the work taken back: of the items linked to it too; an old version's grade is the item's own
        if ((version ?: head.version) == head.version) linkedGrades.takeBack(ItemKey(source, item))
        return card(ItemKey(source, item))
    }

    override fun markInterest(source: String, item: String, interestRequest: InterestRequest): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (!store.items.exists(item)) return ResponseEntity.notFound().build()
        store.interest.mark(item, interestRequest.interesting, Instant.now())
        events.publishEvent(InterestChanged(store.type, source, item))
        // The items linked to it are the same work: marked the same
        linkedInterest.share(ItemKey(source, item))
        return card(ItemKey(source, item))
    }

    override fun unmarkInterest(source: String, item: String): ResponseEntity<ItemSummary> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (!store.items.exists(item)) return ResponseEntity.notFound().build()
        store.interest.forget(item)
        events.publishEvent(InterestChanged(store.type, source, item))
        linkedInterest.takeBack(ItemKey(source, item))
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
        if (mark.mark != 1 && mark.mark != -1) throw failure(HttpStatus.BAD_REQUEST, BAD_MARK)
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
        if (mark.mark != 1 && mark.mark != -1) throw failure(HttpStatus.BAD_REQUEST, BAD_MARK)
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
        if (r.verdict !in -1..1) throw failure(HttpStatus.BAD_REQUEST, Label("An answer is +1, 0 or −1", "Ответ — +1, 0 или −1", "Odgovor je +1, 0 ili −1"))
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
