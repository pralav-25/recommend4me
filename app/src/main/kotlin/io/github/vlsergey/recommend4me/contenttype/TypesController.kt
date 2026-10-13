package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.api.TypesApi
import io.github.vlsergey.recommend4me.api.model.ContentTypeInfo
import io.github.vlsergey.recommend4me.api.model.FacetInfo
import io.github.vlsergey.recommend4me.api.model.NumberInfo
import io.github.vlsergey.recommend4me.api.model.SignalInfo
import io.github.vlsergey.recommend4me.api.model.SourceInfo
import io.github.vlsergey.recommend4me.api.model.TextInfo
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.Source
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
            universes = type.universes,
            sources = t.sources.map { store ->
                val s = store.source
                val schema = store.schema
                SourceInfo(
                    id = s.id,
                    homepage = s.homepage,
                    modes = s.modes.map { ApiSourceMode.valueOf(it.name) },
                    texts = schema.texts.map {
                        TextInfo(it.key, I18nKeys.textLabel(s.id, it.key), it.spoiler, it.role?.let { r -> ApiTextRole.valueOf(r.name) })
                    },
                    facets = schema.facets.map {
                        FacetInfo(
                            id = FeatureNames.facetId(s, it), key = it.key, labelKey = I18nKeys.facetLabel(s.id, it), filter = it.filter, onCard = it.onCard,
                            suggest = it.suggest, infer = it.infer, editable = it.editable, original = it.original, originalFacet = it.originalFacet,
                            role = it.role?.let { r -> ApiFacetRole.valueOf(r.name) },
                        )
                    },
                    numbers = schema.numbers.map {
                        NumberInfo(
                            FeatureNames.numberId(s, it), it.key, I18nKeys.numberLabel(s.id, it), NumberInfo.Scale.valueOf(it.scale.name),
                            it.role?.let { r -> ApiNumberRole.valueOf(r.name) },
                        )
                    },
                    versioned = schema.versioned,
                    picturesTell = schema.picturesTell,
                    reviewsLabelKey = if (schema.reviews) I18nKeys.qualified(s.id, I18nKeys.REVIEWS) else null,
                    partsLabelKey = if (schema.parts) I18nKeys.qualified(s.id, I18nKeys.PARTS) else null,
                    signals = s.signals.map { def ->
                        SignalInfo(def.key, I18nKeys.signalLabel(s.id, def), def.values.associateWith { I18nKeys.signalValueLabel(s.id, def, it) })
                    },
                )
            },
            // A standard number of several sources is one: one sorting, one filter ([ContentType.numbers])
            numbers = t.sources.flatMap { store -> store.schema.numbers.filter { it.sortable }.map { numberInfo(store.source, it) } }.distinctBy { it.id },
            filterNumbers = t.sources.flatMap { store -> store.schema.numbers.filter { it.filterable }.map { numberInfo(store.source, it) } }.distinctBy { it.id },
        )
    })

    private fun numberInfo(source: Source, def: NumberDef) = NumberInfo(FeatureNames.numberId(source, def), def.key, I18nKeys.numberLabel(source.id, def))
}
