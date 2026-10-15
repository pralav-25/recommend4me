package io.github.vlsergey.recommend4me.correction

/**
 * A source's data with the user's corrections on top: what the interface shows, the search finds
 * and the model learns from.
 */
object Corrected {

    const val TITLE = "title"
    fun textField(key: String) = "text:$key"
    fun numberField(key: String) = "number:$key"

    /**
     * The facets of an item ([site], facet to keys) with the values the user added and without those
     * taken away; of a facet of one value ([single]), a value the user added takes the place of
     * every other.
     */
    fun facets(site: Map<String, List<String>>, corrections: List<FacetCorrection>, single: Set<String> = emptySet()): Map<String, List<String>> {
        if (corrections.isEmpty()) return site
        val out = LinkedHashMap<String, MutableList<String>>()
        site.forEach { (facet, keys) -> out[facet] = keys.toMutableList() }
        corrections.filter { it.added && it.facet in single }.map { it.facet }.toSet().forEach { out.remove(it) }
        corrections.forEach { c ->
            val list = out.getOrPut(c.facet) { ArrayList() }
            if (c.added) { if (c.key !in list) list += c.key } else list.remove(c.key)
        }
        return out.filterValues { it.isNotEmpty() }
    }

    fun title(site: String, fields: Map<String, String>): String = fields[TITLE] ?: site

    fun texts(site: Map<String, String>, fields: Map<String, String>): Map<String, String> {
        val overridden = fields.filterKeys { it.startsWith("text:") }.mapKeys { it.key.removePrefix("text:") }
        return if (overridden.isEmpty()) site else (site + overridden).filterValues { it.isNotBlank() }
    }

    fun numbers(site: Map<String, Double>, fields: Map<String, String>): Map<String, Double> {
        val overridden = fields.filterKeys { it.startsWith("number:") }
            .mapNotNull { (k, v) -> v.trim().replace(',', '.').toDoubleOrNull()?.let { k.removePrefix("number:") to it } }.toMap()
        return if (overridden.isEmpty()) site else site + overridden
    }
}
