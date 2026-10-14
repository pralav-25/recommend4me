package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.capture.CapturedPageRepository
import io.github.vlsergey.recommend4me.contenttype.ContentType
import io.github.vlsergey.recommend4me.correction.CorrectionRepository
import io.github.vlsergey.recommend4me.correction.TypeLinkRepository
import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.draft.DraftRepository
import io.github.vlsergey.recommend4me.folder.DataFolder
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.interest.InterestRepository
import io.github.vlsergey.recommend4me.item.ItemRepository
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.likeness.MarkLikenessRepository
import io.github.vlsergey.recommend4me.likeness.WorkLikenessRepository
import io.github.vlsergey.recommend4me.mark.MarkRepository
import io.github.vlsergey.recommend4me.model.ModelRepository
import io.github.vlsergey.recommend4me.part.PartRepository
import io.github.vlsergey.recommend4me.picture.PictureFiles
import io.github.vlsergey.recommend4me.picture.PictureRepository
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.rating.RatingRepository
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.setvector.SetEmbeddingRepository
import io.github.vlsergey.recommend4me.setvector.SetVectorRepository
import io.github.vlsergey.recommend4me.signal.SignalRepository
import io.github.vlsergey.recommend4me.suggestion.SuggestionRepository
import io.github.vlsergey.recommend4me.suggestion.ValueNameRepository
import io.github.vlsergey.recommend4me.textvector.TextVectorRepository
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.universe.UniverseNames
import io.github.vlsergey.recommend4me.universe.UniverseRepository
import io.github.vlsergey.recommend4me.search.SearchJudgmentRepository
import io.github.vlsergey.recommend4me.search.SearchScoreRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

/** Everything of one source: its plugin, its folder and the repositories over its three database files. */
class SourceStore(
    val source: Source, contentType: ContentType, val folder: Path, databases: Databases, events: ApplicationEventPublisher,
    /** The user's languages, their first first. */
    languages: List<String>,
) {
    val id: String get() = source.id
    val type: String get() = source.contentType

    /**
     * What the application knows of the source's items: the source's own schema — the site's word,
     * never corrected — and the application's layer over it, which the user corrects: the universe
     * of fan fiction, the standard facets of the content type worked out for every work.
     */
    val schema: SourceSchema = run {
        val layer = (if (contentType.universes) UniverseFacet.defs(source.schema) else emptyList()) +
            contentType.layer.map { standard ->
                standard.asLayerFacet(examplesFrom = standard.examples?.let { e -> source.schema.facets.firstOrNull { it.standard === e }?.key })
            }
        if (layer.isEmpty()) source.schema else SourceSchema(
            facets = source.schema.facets + layer,
            numbers = source.schema.numbers,
            texts = source.schema.texts,
            reviews = source.schema.reviews,
            parts = source.schema.parts,
            versioned = source.schema.versioned,
            universeLine = source.schema.universeLine,
            kindFrom = source.schema.kindFrom,
            picturesTell = source.schema.picturesTell,
        )
    }

    private val dbs = databases.of(source.id)

    /**
     * One writer of what the site says at a time: a page the extension sends and a job's or a
     * refresh's download of the same work write the same rows, and a read-then-write of each — or
     * H2's MERGE of two at once — breaks a key. Taken by every write of the source's context, a few
     * milliseconds each; the parsing and the downloading stay outside it.
     */
    val writing = ReentrantLock()

    val items = ItemRepository(dbs.source.dsl) { ids -> events.publishEvent(ItemsChanged(source.id, ids.toList())) }
    val pictures = PictureRepository(dbs.source.dsl)
    val files = PictureFiles(folder)
    val reviews = ReviewRepository(dbs.source.dsl)
    val parts = PartRepository(dbs.source.dsl)
    val textVectors = TextVectorRepository(dbs.source.dsl)
    val setVectors = SetVectorRepository(dbs.source.dsl)
    val pages = CapturedPageRepository(dbs.source.dsl)
    val valueNames = ValueNameRepository(dbs.source.dsl)

    val corrections = CorrectionRepository(dbs.corrections.dsl)

    val ratings = RatingRepository(dbs.ratings.dsl)
    val interest = InterestRepository(dbs.ratings.dsl)
    val marks = MarkRepository(source.id, dbs.ratings.dsl)
    val signals = SignalRepository(dbs.ratings.dsl)

    /** What is worked out of the source's facets, in the model database of its content type. */
    /** The drafts of the layer of the source's works ([LayerDrafts][io.github.vlsergey.recommend4me.draft.LayerDrafts]), in the model database of its content type. */
    val drafts = DraftRepository(databases.model(source.contentType), source.id)

    val suggestions = SuggestionRepository(databases.model(source.contentType), source.id, drafts)

    /** The names of the layer of universes: the dictionary's of the content type, whatever the source; none for a type without universes. */
    private val dictionaryNames = if (contentType.universes) UniverseNames(UniverseRepository(databases.typeCorrections(contentType.id), languages)) else null

    /**
     * What the values [keys] of a facet are shown by: of the layer of universes, as the dictionary
     * names them ([UniverseNames]); of any other facet, as the source does.
     */
    fun names(facet: String, keys: Collection<String>): Map<String, String> {
        if (keys.isEmpty()) return emptyMap()
        if (facet in tagFacets) {
            // The tags as the user and every site name them ([TagRepository]); one the user made up, by the source
            val shown = tags.shown(keys)
            val rest = keys.filter { it !in shown }
            return (if (rest.isEmpty()) emptyMap() else items.facetNames(facet, rest)) + shown
        }
        return dictionaryNames?.of(facet, keys) { items.facetNames(facet, it!!) } ?: items.facetNames(facet, keys)
    }

    /** The name of every value of a facet the source or the dictionary names. */
    fun names(facet: String): Map<String, String> {
        // The normalized tags alone: the source's names of the facet are of the sites' keys it had before them
        if (facet in tagFacets) return tags.shown(null)
        return dictionaryNames?.of(facet, null) { if (it == null) items.facetNames(facet) else items.facetNames(facet, it) } ?: items.facetNames(facet)
    }

    /**
     * The key of the words the value [key] of a facet is SHOWN by, when it is a value of the
     * application's own — a standard value ("standard:facet.status.value.finished"), an original
     * character ("standard:character.oc-female"); null of any other, shown by its name. Its name
     * ([names]) keeps one language all the same: the model and the search read it.
     */
    fun nameKey(facet: String, key: String): String? =
        schema.facet(facet)?.let { I18nKeys.standardValue(it, key) } ?: UniverseFacet.originalCharacterKey(key)

    /** The user's word on the sites' free tags of the content type: merged, named, told apart — every source's alike. */
    val tags = TagRepository(databases.typeCorrections(contentType.id), databases.model(contentType.id))

    /** The facets of the layer learnt from the sites' tags by their names: the tags the user merges and names. */
    val tagFacets = schema.facets.filter { it.editable && it.examplesFrom != null }.map { it.key }.toSet()

    /** The tags under other tags of every facet of tags that has any, read now: facet to its hierarchy. */
    fun hierarchies(): Map<String, TagHierarchy> = tagFacets.associateWith { TagHierarchy(tags.parents()) }.filterValues { !it.empty }

    /** The tags the user ignores of every facet of tags that has any, read now: facet to their keys. */
    fun ignoredTags(): Map<String, Set<String>> = tags.ignored().takeIf { it.isNotEmpty() }?.let { ignored -> tagFacets.associateWith { ignored } }.orEmpty()

    /** The source database, for the source's own tables. */
    val sourceDsl = dbs.source.dsl
}

/** Everything of one content type: its sources, the links across them, what is learnt. */
class TypeStore(val type: ContentType, val sources: List<SourceStore>, databases: Databases, languages: List<String>) {
    val id: String get() = type.id

    val links = TypeLinkRepository(databases.typeCorrections(type.id))
    val universes = UniverseRepository(databases.typeCorrections(type.id), languages)
    val searchJudgments = SearchJudgmentRepository(databases.typeCorrections(type.id))
    val tags = TagRepository(databases.typeCorrections(type.id), databases.model(type.id))
    val models = ModelRepository(databases.model(type.id))
    val embeddings = SetEmbeddingRepository(databases.model(type.id))
    val likeness = MarkLikenessRepository(databases.model(type.id))
    val workLikeness = WorkLikenessRepository(databases.model(type.id))
    val searchScores = SearchScoreRepository(databases.model(type.id))

    fun source(id: String): SourceStore? = sources.firstOrNull { it.id == id }
}

/** The stores of every source and content type. */
@Component
class Stores(
    plugins: Plugins, databases: Databases, folder: DataFolder, events: ApplicationEventPublisher,
    /** The user's languages, their first first: what the texts of the dictionaries are shown in. */
    @Value("\${recommend4me.languages:ru,en}") languages: List<String>,
) {

    val sources: List<SourceStore> = plugins.sources.map { SourceStore(it, plugins.type(it.contentType)!!, folder.source(it.id), databases, events, languages) }

    val types: List<TypeStore> = plugins.types.map { t -> TypeStore(t, sources.filter { it.type == t.id }, databases, languages) }

    fun source(id: String): SourceStore? = sources.firstOrNull { it.id == id }

    fun type(id: String): TypeStore? = types.firstOrNull { it.id == id }

    fun typeOf(sourceId: String): TypeStore = types.first { t -> t.sources.any { it.id == sourceId } }
}
