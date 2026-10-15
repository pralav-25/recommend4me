package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.api.SuggestionsApi
import io.github.vlsergey.recommend4me.api.model.Candidate
import io.github.vlsergey.recommend4me.api.model.MentionQuote
import io.github.vlsergey.recommend4me.api.model.TextRange
import io.github.vlsergey.recommend4me.api.model.FacetSuggestions as ApiFacetSuggestions
import io.github.vlsergey.recommend4me.api.model.SuggestedValue
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

fun ItemFacetSuggestions.toApi(store: SourceStore) = ApiFacetSuggestions(
    facet = facet.key,
    labelKey = I18nKeys.facetLabel(store.id, facet),
    suggested = store.descriptions(facet.key, suggested.map { it.key }).let { described ->
        suggested.map { SuggestedValue(it.key, names[it.key] ?: it.key, it.chance, store.nameKey(facet.key, it.key), described[it.key]) }
    },
)

@RestController
class SuggestionsController(private val stores: Stores, private val suggestions: FacetSuggestions) : SuggestionsApi {

    override fun getSuggestions(source: String, item: String): ResponseEntity<List<ApiFacetSuggestions>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(suggestions.ofItem(store, item).map { it.toApi(store) })
    }

    override fun settleSuggestions(source: String, item: String): ResponseEntity<Unit> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        suggestions.settled(store, item)
        return ResponseEntity.noContent().build()
    }

    override fun getCandidates(source: String, item: String, facet: String): ResponseEntity<List<Candidate>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val found = suggestions.candidates(store, item, facet) ?: return ResponseEntity.notFound().build()
        // A universe and a character are entries of a catalogue: their pages there, as the dictionary keeps them
        val type = stores.typeOf(source)
        val pages = if (type.type.universes && facet in PAGED) UniverseFacet.pages(type.universes) else emptyMap()
        // And what it says of them: told apart by it when the names are alike
        val described = store.descriptions(facet, found.candidates.map { it.key })
        return ResponseEntity.ok(
            found.candidates.map {
                Candidate(
                    key = it.key, name = found.names[it.key] ?: it.key, nameKey = store.nameKey(facet, it.key),
                    chance = it.chance, mentions = it.mentions, url = pages[it.key], description = described[it.key],
                )
            },
        )
    }

    override fun getMentions(source: String, item: String, facet: String, value: String): ResponseEntity<List<MentionQuote>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        val quotes = suggestions.quotes(store, item, facet, value) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            quotes.map { q ->
                MentionQuote(
                    text = q.text, highlights = q.highlights.map { TextRange(it.first, it.last + 1) },
                    labelKey = q.place.labelKey, title = q.place.title, part = q.place.part,
                )
            },
        )
    }

    companion object {
        /** The facets whose values are entries of a catalogue of universes. */
        private val PAGED = setOf(UniverseFacets.UNIVERSE, UniverseFacets.CHARACTERS, UniverseFacets.SECONDARY_CHARACTERS)
    }
}
