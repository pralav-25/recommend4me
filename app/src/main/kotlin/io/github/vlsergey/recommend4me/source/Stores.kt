package io.github.vlsergey.recommend4me.source

import io.github.vlsergey.recommend4me.capture.CapturedPageRepository
import io.github.vlsergey.recommend4me.contenttype.ContentType
import io.github.vlsergey.recommend4me.correction.CorrectionRepository
import io.github.vlsergey.recommend4me.correction.TypeLinkRepository
import io.github.vlsergey.recommend4me.database.Databases
import io.github.vlsergey.recommend4me.folder.DataFolder
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
import io.github.vlsergey.recommend4me.universe.UniverseRepository
import io.github.vlsergey.recommend4me.search.SearchJudgmentRepository
import io.github.vlsergey.recommend4me.search.SearchScoreRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.nio.file.Path

/** Everything of one source: its plugin, its folder and the repositories over its three database files. */
class SourceStore(val source: Source, contentType: ContentType, val folder: Path, databases: Databases, events: ApplicationEventPublisher) {
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
            reviewsLabel = source.schema.reviewsLabel,
            partsLabel = source.schema.partsLabel,
            versioned = source.schema.versioned,
            universeLine = source.schema.universeLine,
            kindFrom = source.schema.kindFrom,
            picturesTell = source.schema.picturesTell,
        )
    }

    private val dbs = databases.of(source.id)

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
    val marks = MarkRepository(source.id, dbs.ratings.dsl)
    val signals = SignalRepository(dbs.ratings.dsl)

    /** What is worked out of the source's facets, in the model database of its content type. */
    val suggestions = SuggestionRepository(databases.model(source.contentType), source.id)

    /** The source database, for the source's own tables. */
    val sourceDsl = dbs.source.dsl
}

/** Everything of one content type: its sources, the links across them, what is learnt. */
class TypeStore(val type: ContentType, val sources: List<SourceStore>, databases: Databases, languages: List<String>) {
    val id: String get() = type.id

    val links = TypeLinkRepository(databases.typeCorrections(type.id))
    val universes = UniverseRepository(databases.typeCorrections(type.id), languages)
    val searchJudgments = SearchJudgmentRepository(databases.typeCorrections(type.id))
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

    val sources: List<SourceStore> = plugins.sources.map { SourceStore(it, plugins.type(it.contentType)!!, folder.source(it.id), databases, events) }

    val types: List<TypeStore> = plugins.types.map { t -> TypeStore(t, sources.filter { it.type == t.id }, databases, languages) }

    fun source(id: String): SourceStore? = sources.firstOrNull { it.id == id }

    fun type(id: String): TypeStore? = types.firstOrNull { it.id == id }

    fun typeOf(sourceId: String): TypeStore = types.first { t -> t.sources.any { it.id == sourceId } }
}
