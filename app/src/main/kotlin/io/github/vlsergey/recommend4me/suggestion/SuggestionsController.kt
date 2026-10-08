package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.api.SuggestionsApi
import io.github.vlsergey.recommend4me.api.model.Candidate
import io.github.vlsergey.recommend4me.api.model.MentionQuote
import io.github.vlsergey.recommend4me.api.model.TextRange
import io.github.vlsergey.recommend4me.api.model.FacetSuggestions as ApiFacetSuggestions
import io.github.vlsergey.recommend4me.api.model.SuggestedValue
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController

fun ItemFacetSuggestions.toApi() = ApiFacetSuggestions(
    facet = facet.key,
    label = facet.label,
    suggested = suggested.map { SuggestedValue(it.key, names[it.key] ?: it.key, it.chance) },
)

@RestController
class SuggestionsController(private val stores: Stores, private val suggestions: FacetSuggestions) : SuggestionsApi {

    override fun getSuggestions(source: String, item: String): ResponseEntity<List<ApiFacetSuggestions>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        return ResponseEntity.ok(suggestions.ofItem(store, item).map { it.toApi() })
    }

    override fun getCandidates(source: String, item: String, facet: String): ResponseEntity<List<Candidate>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        val found = suggestions.candidates(store, item, facet) ?: return ResponseEntity.notFound().build()
        // A universe and a character are entries of a catalogue: their pages there, as the dictionary keeps them
        val type = stores.typeOf(source)
        val pages = if (type.type.universes && facet in PAGED) UniverseFacet.pages(type.universes) else emptyMap()
        return ResponseEntity.ok(found.suggested.map { Candidate(it.key, found.names[it.key] ?: it.key, it.chance, it.mentions, pages[it.key]) })
    }

    override fun getMentions(source: String, item: String, facet: String, value: String): ResponseEntity<List<MentionQuote>> {
        val store = stores.source(source) ?: return ResponseEntity.notFound().build()
        if (store.items.find(item) == null) return ResponseEntity.notFound().build()
        val quotes = suggestions.quotes(store, item, facet, value) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(quotes.map { q -> MentionQuote(q.place, q.text, q.highlights.map { TextRange(it.first, it.last + 1) }) })
    }

    companion object {
        /** The facets whose values are entries of a catalogue of universes. */
        private val PAGED = setOf(UniverseFacets.UNIVERSE, UniverseFacets.CHARACTERS)
    }
}
