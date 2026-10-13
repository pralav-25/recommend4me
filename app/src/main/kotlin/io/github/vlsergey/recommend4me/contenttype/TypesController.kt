package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.api.TypesApi
import io.github.vlsergey.recommend4me.api.model.ContentTypeInfo
import io.github.vlsergey.recommend4me.api.model.FacetInfo
import io.github.vlsergey.recommend4me.api.model.NumberInfo
import io.github.vlsergey.recommend4me.api.model.SignalInfo
import io.github.vlsergey.recommend4me.api.model.SourceInfo
import io.github.vlsergey.recommend4me.api.model.TextInfo
import io.github.vlsergey.recommend4me.i18n.text
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.api.model.NumberRole as ApiNumberRole
import io.github.vlsergey.recommend4me.api.model.SourceMode as ApiSourceMode
import io.github.vlsergey.recommend4me.api.model.TextRole as ApiTextRole

/** The content types and what their sources know of their items: the interface is built of it. */
@RestController
class TypesController(private val stores: Stores) : TypesApi {

    override fun listTypes(): ResponseEntity<List<ContentTypeInfo>> = ResponseEntity.ok(stores.types.filter { it.sources.isNotEmpty() }.map { t ->
        val type = t.type
        ContentTypeInfo(
            id = type.id,
            title = type.title.text(),
            grades = type.grades.map { it.text() },
            verb = type.verb.text(),
            universes = type.universes,
            sources = t.sources.map { store ->
                val s = store.source
                val schema = store.schema
                SourceInfo(
                    id = s.id,
                    title = s.title.text(),
                    homepage = s.homepage,
                    modes = s.modes.map { ApiSourceMode.valueOf(it.name) },
                    texts = schema.texts.map { TextInfo(it.key, it.label.text(), it.spoiler, it.role?.let { r -> ApiTextRole.valueOf(r.name) }) },
                    facets = schema.facets.map {
                        FacetInfo(
                            id = FeatureNames.facetId(s, it), key = it.key, label = it.label.text(), filter = it.filter, onCard = it.onCard,
                            suggest = it.suggest, infer = it.infer, editable = it.editable, original = it.original, originalFacet = it.originalFacet,
                            role = it.role?.let { r -> ApiFacetRole.valueOf(r.name) },
                        )
                    },
                    numbers = schema.numbers.map {
                        NumberInfo(
                            FeatureNames.numberId(s, it), it.key, it.label.text(), NumberInfo.Scale.valueOf(it.scale.name),
                            it.role?.let { r -> ApiNumberRole.valueOf(r.name) },
                        )
                    },
                    versioned = schema.versioned,
                    picturesTell = schema.picturesTell,
                    reviewsLabel = schema.reviewsLabel?.text(),
                    partsLabel = schema.partsLabel?.text(),
                    signals = s.signals.map { SignalInfo(it.key, it.label.text(), it.values.mapValues { (_, v) -> v.text() }) },
                )
            },
            // A standard number of several sources is one: one sorting, one filter ([ContentType.numbers])
            numbers = t.sources.flatMap { store -> store.schema.numbers.filter { it.sortable }.map { NumberInfo(FeatureNames.numberId(store.source, it), it.key, it.label.text()) } }
                .distinctBy { it.id },
            filterNumbers = t.sources.flatMap { store -> store.schema.numbers.filter { it.filterable }.map { NumberInfo(FeatureNames.numberId(store.source, it), it.key, it.label.text()) } }
                .distinctBy { it.id },
        )
    })
}
