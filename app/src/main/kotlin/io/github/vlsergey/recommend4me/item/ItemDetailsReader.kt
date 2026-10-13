package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.api.model.FeatureContribution
import io.github.vlsergey.recommend4me.api.model.ItemDetails
import io.github.vlsergey.recommend4me.api.model.NumberValue
import io.github.vlsergey.recommend4me.api.model.PartInfo
import io.github.vlsergey.recommend4me.api.model.PictureInfo
import io.github.vlsergey.recommend4me.api.model.PictureMatch
import io.github.vlsergey.recommend4me.api.model.RatingRecord
import io.github.vlsergey.recommend4me.api.model.RelatedWorks
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.api.model.ReviewInfo
import io.github.vlsergey.recommend4me.api.model.ReviewMatch
import io.github.vlsergey.recommend4me.api.model.TextValue
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.model.ContributionGroup
import io.github.vlsergey.recommend4me.model.LabelledContribution
import io.github.vlsergey.recommend4me.api.model.ContributionGroup as ApiContributionGroup
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.downloadsPages
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component
import java.time.ZoneOffset

fun LabelledContribution.toApi() = FeatureContribution(
    feature = contribution.feature, labelKey = label.labelKey, value = label.value, valueKey = label.valueKey,
    contribution = contribution.contribution, present = contribution.present,
)

fun ContributionGroup.toApi(withFeatures: Boolean = true) =
    ApiContributionGroup(part = part, labelKey = labelKey, contribution = contribution, features = if (withFeatures) features.map { it.toApi() } else emptyList())

/** Everything the dialog of a work shows. */
@Component
class ItemDetailsReader(
    private val stores: Stores,
    private val plugins: Plugins,
    private val cards: ItemCards,
    private val recommendations: Recommendations,
    private val works: Works,
    private val suggestions: FacetSuggestions,
) {
    /**
     * The work as the dialog shows it. [withChances]: its chances made current first — waited for
     * while an answer's are being made; without, the chances as they are now: the answer is read back
     * at once, and the page asks for the chances it changes once they are made (settleSuggestions).
     */
    fun read(key: ItemKey, withChances: Boolean = true): ItemDetails? {
        val store = stores.source(key.source) ?: return null
        val type = stores.typeOf(key.source)
        val summary = cards.cards(type, listOf(key)).firstOrNull() ?: return null
        val schema = store.schema
        val site = store.items.facets(key.id)
        val corrections = store.corrections.facetsOf(key.id)
        val fields = store.corrections.fieldsOf(key.id)
        // Made now for a work that never had them; the answer itself made those it changed current
        if (withChances) suggestions.ensureFor(store, key.id)
        val chances = store.suggestions.ofItems(listOf(key.id))[key.id]
        val names = schema.facets.associate { def ->
            def.key to store.names(
                def.key,
                site[def.key].orEmpty() + corrections.filter { it.facet == def.key }.map { it.key } + ModelValues.named(chances?.get(def.key)),
            )
        }
        // A facet's own line as the site writes it is shown with the facet, not among the texts
        val originals = schema.facets.mapNotNull { it.original }.toSet()
        val siteTexts = store.items.texts(key.id)
        val texts = Corrected.texts(siteTexts, fields)
        val siteNumbers = store.items.numbers(key.id)
        val numbers = Corrected.numbers(siteNumbers, fields)
        val model = recommendations.details(key)
        val encoder = plugins.imageEncoder()?.id
        val pictureMarks = store.marks.pictureMarksOf(key.id)
        val pictures = store.pictures.ofItem(key.id, encoder)
        val reviewMarks = store.marks.reviewMarksOf(key.id)
        val feedback = type.sources.flatMap { it.marks.feedback(MarkKind.PICTURE) + it.marks.feedback(MarkKind.REVIEW) }
            .filter { it.match.item == key }
        fun verdict(kind: MarkKind, mark: io.github.vlsergey.recommend4me.likeness.Marked, matchRef: String?) =
            feedback.firstOrNull { it.kind == kind && it.mark.item == mark.owner && it.mark.ref == mark.ref && it.match.ref == matchRef }?.verdict
        fun titleOf(k: ItemKey): String = stores.source(k.source)?.let { s ->
            s.items.find(k.id)?.let { Corrected.title(it.title, s.corrections.fieldsOf(k.id)) }
        } ?: k.toString()
        return ItemDetails(
            summary = summary,
            titleCorrected = Corrected.TITLE in fields,
            allFacets = cards.facets(store, site, chances, corrections, names, texts, complete = store.corrections.completeOf(key.id)) { true },
            texts = schema.texts.filter { it.key !in originals }.mapNotNull { def ->
                texts[def.key]?.let { TextValue(def.key, I18nKeys.textLabel(store.id, def.key), it, def.spoiler, Corrected.textField(def.key) in fields) }
            },
            allNumbers = schema.numbers.mapNotNull { def ->
                numbers[def.key]?.let { NumberValue(def.key, I18nKeys.numberLabel(store.id, def), it, Corrected.numberField(def.key) in fields) }
            },
            // Of a graded work, what a model that had not seen it says is read apart (heldOut): it is
            // fitted for the asking, and every correction would wait a second or more for it
            explanation = model.explanation.map { it.toApi() },
            ratings = ratings(type, key),
            reviews = model.reviews.map { (r, influence) ->
                ReviewInfo(
                    reviewId = r.reviewId, content = r.content, author = r.author, stars = r.stars,
                    postedAt = r.postedAt?.atOffset(ZoneOffset.UTC), influence = influence, mark = reviewMarks[r.reviewId],
                )
            },
            reviewCount = store.reviews.countOf(key.id),
            pictures = pictures.map { p ->
                PictureInfo(
                    position = p.position, hasFull = p.fullFile != null, analyzed = p.analyzed,
                    mark = pictureMarks.firstOrNull { it.position == p.position && it.url == p.url }?.mark,
                    influence = model.pictures[p.position], error = p.error,
                )
            },
            parts = store.parts.ofItem(key.id).map {
                PartInfo(it.partId, it.position, it.hasText, it.title, it.publishedAt?.atOffset(ZoneOffset.UTC), model.parts[it.partId])
            },
            pictureMatches = model.pictureMatches.map {
                PictureMatch(
                    position = it.ref?.toIntOrNull() ?: 0, markSource = it.mark.owner.source, markItem = it.mark.owner.id,
                    markTitle = titleOf(it.mark.owner), markPosition = it.mark.ref.toIntOrNull() ?: 0, mark = it.mark.mark,
                    strength = it.strength.toDouble(), verdict = verdict(MarkKind.PICTURE, it.mark, it.ref),
                )
            },
            reviewMatches = model.reviewMatches.let { matches ->
                val contents = matches.flatMap { m -> listOfNotNull(m.ref?.let { key to it }, m.mark.owner to m.mark.ref) }
                    .groupBy { it.first.source }
                    .flatMap { (source, refs) -> stores.source(source)?.reviews?.contents(refs.map { it.first.id to it.second })?.map { (k, v) -> (ItemKey(source, k.first) to k.second) to v }.orEmpty() }
                    .toMap()
                matches.map {
                    ReviewMatch(
                        reviewId = it.ref.orEmpty(), content = contents[key to it.ref.orEmpty()].orEmpty(),
                        markSource = it.mark.owner.source, markItem = it.mark.owner.id, markReviewId = it.mark.ref,
                        markTitle = titleOf(it.mark.owner), markContent = contents[it.mark.owner to it.mark.ref].orEmpty(),
                        mark = it.mark.mark, strength = it.strength.toDouble(), verdict = verdict(MarkKind.REVIEW, it.mark, it.ref),
                    )
                }
            },
            canRefresh = store.source.downloadsPages,
            related = related(type, store, key, Corrected.facets(site, corrections), names),
        )
    }

    /**
     * The other works of the series the work is a part of and of the people who made it — the
     * facets of the roles SERIES and AUTHOR — in the same source, with the user's grades: how the
     * user liked the volumes before tells most of a volume after. The series first; a work shown
     * there is not shown again under its author.
     */
    private fun related(
        type: io.github.vlsergey.recommend4me.source.TypeStore,
        store: io.github.vlsergey.recommend4me.source.SourceStore,
        key: ItemKey,
        values: Map<String, List<String>>,
        names: Map<String, Map<String, String>>,
    ): List<RelatedWorks> {
        // The series first: an author's works already shown as volumes are not shown again under the author
        val roles = mapOf(FacetRole.SERIES to ApiFacetRole.SERIES, FacetRole.AUTHOR to ApiFacetRole.AUTHOR)
        val defs = store.schema.facets.filter { it.role in roles }.sortedBy { if (it.role == FacetRole.SERIES) 0 else 1 }
        if (defs.isEmpty()) return emptyList()
        val corrections = store.corrections.allFacets()
        val shown = HashSet<String>()
        return defs.flatMap { def ->
            val keys = values[def.key].orEmpty()
            val bySite = store.items.itemsWith(def.key, keys)
            val own = corrections.filter { it.facet == def.key && it.key in keys }
            keys.mapNotNull { value ->
                val removed = own.filter { it.key == value && !it.added }.map { it.itemId }.toSet()
                val ids = (bySite[value].orEmpty() + own.filter { it.key == value && it.added }.map { it.itemId }).distinct()
                    .filter { it != key.id && it !in removed && it !in shown }
                if (ids.isEmpty()) return@mapNotNull null
                shown += ids
                val items = cards.cards(type, ids.map { ItemKey(store.id, it) }).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                // A value the user added is named as they wrote it
                val name = names[def.key]?.get(value) ?: own.firstNotNullOfOrNull { c -> c.name.takeIf { c.key == value && c.added } } ?: value
                RelatedWorks(facet = def.key, labelKey = I18nKeys.facetLabel(store.id, def), role = roles.getValue(def.role!!), value = value, name = name, items = items)
            }
        }
    }

    /** The grades of every item of the work [key] is of, newest first. */
    fun ratings(type: io.github.vlsergey.recommend4me.source.TypeStore, key: ItemKey): List<RatingRecord> =
        works.of(type).members(key).flatMap { m ->
            val s = stores.source(m.source) ?: return@flatMap emptyList()
            val version = s.items.find(m.id)?.version
            s.ratings.ofItem(m.id).map {
                RatingRecord(source = m.source, item = m.id, version = it.version, grade = it.grade, ratedAt = it.ratedAt.atOffset(ZoneOffset.UTC), current = it.version == version)
            }
        }.sortedByDescending { it.ratedAt }
}
