package io.github.vlsergey.recommend4me.correction

/**
 * The user answered on a value of a facet of a work — confirmed, took away, added, took back, said
 * no other is: what the work is searched by and what the model of the grades reads changed. Not
 * [ItemsChanged][io.github.vlsergey.recommend4me.item.ItemsChanged]: the site's data is as it was,
 * and the work's chances are made apart, at once ([FacetSuggestions.answered][io.github.vlsergey.recommend4me.suggestion.FacetSuggestions.answered]).
 */
data class FacetAnswered(val type: String, val source: String, val itemId: String, val facet: String)
