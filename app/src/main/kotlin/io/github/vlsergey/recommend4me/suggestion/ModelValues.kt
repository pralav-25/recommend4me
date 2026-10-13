package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.source.SourceSchema

/**
 * The values of an item's facets as the application reads them before the user's corrections: the
 * site's — never any taken away — and, of a facet the application works out
 * ([FacetDef.infer][io.github.vlsergey.recommend4me.source.FacetDef.infer]), every value the model
 * says the item more likely has than not.
 */
object ModelValues {

    /** More likely than not: the one boundary a chance is read by. */
    const val LIKELY = 0.5

    /**
     * How many values of a facet of the layer a work is offered at least that the user has not
     * answered on and the site does not give: besides every value more likely than not, the
     * likeliest others up to this many, likely or not — the user's choice.
     */
    const val OFFERED = 3

    /**
     * The values to offer beside those a work is shown with: of [chances] (value to chance), the
     * likeliest the work is not shown with, the user has not answered on and the site does not
     * give ([fromSite]) — as many as lack to make [OFFERED] with the [shown] values of that kind.
     */
    fun offered(chances: Map<String, Double>, shown: Set<String>, answered: Set<String>, fromSite: Set<String>): List<String> {
        val open = shown.count { it !in answered && it !in fromSite }
        return chances.entries.asSequence()
            .filter { (key, _) -> key !in shown && key !in answered && key !in fromSite }
            .sortedByDescending { it.value }.take((OFFERED - open).coerceAtLeast(0)).map { it.key }.toList()
    }

    fun of(schema: SourceSchema, site: Map<String, List<String>>, chances: Map<String, Map<String, Chance>>?): Map<String, List<String>> {
        if (chances.isNullOrEmpty()) return site
        val worked = schema.facets.filter { it.infer && it.key in chances }
        if (worked.isEmpty()) return site
        val out = LinkedHashMap(site)
        worked.forEach { def ->
            val own = site[def.key].orEmpty()
            val given = given(chances[def.key].orEmpty()).filter { it !in own }
            if (given.isNotEmpty()) out[def.key] = own + given
        }
        return out
    }

    /** The values the model gives an item of a facet it works out — and every draft of the layer ([Chance.drafted]) — the likeliest first. */
    fun given(chances: Map<String, Chance>): List<String> =
        chances.values.filter { !it.had && (it.drafted || it.chance > LIKELY) }.sortedByDescending { it.chance }.map { it.key }
}
