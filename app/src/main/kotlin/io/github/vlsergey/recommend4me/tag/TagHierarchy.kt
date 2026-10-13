package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection

/**
 * THE TAGS UNDER OTHER TAGS, the user's word ([TagRepository.parents]): a work with a tag has every
 * tag above it — shown on its card, read by the model; a work the user said has not a tag has not
 * any tag under it either — not shown, the model told so. The user's "has not" of a tag above wins
 * over a "has" of a tag under it.
 */
class TagHierarchy(private val parents: Map<String, Set<String>>) {
    private val children: Map<String, Set<String>> =
        parents.flatMap { (c, ps) -> ps.map { it to c } }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    val empty: Boolean get() = parents.isEmpty()

    fun parentsOf(tag: String): Set<String> = parents[tag].orEmpty()

    fun childrenOf(tag: String): Set<String> = children[tag].orEmpty()

    /** Every tag above [tag], however far. */
    fun ancestors(tag: String): Set<String> = walk(tag, parents)

    /** Every tag under [tag], however far. */
    fun descendants(tag: String): Set<String> = walk(tag, children)

    private fun walk(from: String, next: Map<String, Set<String>>): Set<String> {
        val out = LinkedHashSet<String>()
        val queue = ArrayDeque(next[from].orEmpty())
        while (queue.isNotEmpty()) {
            val t = queue.removeFirst()
            if (t != from && out.add(t)) queue += next[t].orEmpty()
        }
        return out
    }

    /**
     * The user's answers on the tags of a [facet] with what they say of the others: a "has" of a tag,
     * a "has" of every tag above it; a "has not", a "has not" of every tag under it — over any "has"
     * of one. The answers of the other facets as they are.
     */
    fun expand(facet: String, corrections: List<FacetCorrection>): List<FacetCorrection> {
        val own = corrections.filter { it.facet == facet }
        if (empty || own.isEmpty()) return corrections
        val rejected = own.filter { !it.added }.flatMap { listOf(it.key) + descendants(it.key) }.toSet()
        val added = own.filter { it.added && it.key !in rejected }
        val said = own.map { it.key }.toSet()
        val impliedAdded = added.flatMap { ancestors(it.key) }.toSet() - rejected - said
        val impliedRejected = rejected - own.filter { !it.added }.map { it.key }.toSet()
        val itemId = own.first().itemId
        return corrections.filter { it.facet != facet } + added +
            impliedAdded.map { FacetCorrection(itemId, facet, it, true, null) } +
            own.filter { !it.added } + impliedRejected.map { FacetCorrection(itemId, facet, it, false, null) }
    }

    /** A work's tags with every tag above them, but those the user said it has not — [rejected], with every tag under them. */
    fun close(keys: List<String>, rejected: Set<String>): List<String> {
        if (empty) return keys
        val kept = keys.filter { it !in rejected }
        return (kept + kept.flatMap { ancestors(it) }.filter { it !in rejected }).distinct()
    }

    /** What the hierarchy is, for a fingerprint of what is learnt from it. */
    fun signature(): String = parents.entries.sortedBy { it.key }.joinToString(";") { (c, ps) -> c + ">" + ps.sorted().joinToString(",") }

    companion object {
        val NONE = TagHierarchy(emptyMap())

        /**
         * An item's values of every facet, the user's answers applied ([Corrected.facets]) — of the
         * facets of tags, with what the tags above and under say ([hierarchies]: facet to its tags').
         */
        fun corrected(
            values: Map<String, List<String>>, corrections: List<FacetCorrection>, hierarchies: Map<String, TagHierarchy>,
        ): Map<String, List<String>> {
            if (hierarchies.isEmpty()) return Corrected.facets(values, corrections)
            val expanded = hierarchies.entries.fold(corrections) { cs, (facet, h) -> h.expand(facet, cs) }
            val out = LinkedHashMap(Corrected.facets(values, expanded))
            hierarchies.forEach { (facet, h) ->
                val keys = out[facet] ?: return@forEach
                out[facet] = h.close(keys, expanded.filter { it.facet == facet && !it.added }.map { it.key }.toSet())
            }
            return out
        }
    }
}
