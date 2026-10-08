package io.github.vlsergey.recommend4me.universe

/** A passage of a work's text that names a value: where in the work, the passage, and the names in it as ranges of it. */
class Quote(val place: String, val text: String, val highlights: List<IntRange>)

/**
 * HOW A WORK'S TEXTS NAME THE VALUES OF ONE FACET: [mentions] finds the names, [valuesOf] tells the
 * values a name found is of — a character of itself, of its universes for the facet "universe" —
 * and with [pairing] a value is two characters named in one paragraph. What is counted and what is
 * quoted are found by one search: the passages shown are those the counts were made of.
 */
class Naming(
    private val mentions: Mentions,
    private val valuesOf: (String) -> Collection<String> = { listOf(it) },
    private val pairing: ((String, String) -> String)? = null,
) {

    /** How many times the [texts] name each value: places, or paragraphs for a pairing; only the named ones. */
    fun count(texts: Sequence<String>): Map<String, Int> {
        if (pairing != null) return mentions.together(texts).mapKeys { (pair, _) -> pairing.invoke(pair.first, pair.second) }
        val out = HashMap<String, Int>()
        mentions.count(texts).forEach { (name, n) -> valuesOf(name).forEach { out.merge(it, n, Int::plus) } }
        return out
    }

    /**
     * The passages of the [texts] that name the [value], in their order, the names of it marked: a
     * paragraph, or the part of a long one around its names.
     */
    fun quotes(texts: Sequence<WorkText>, value: String): List<Quote> = texts.flatMap { text ->
        paragraphs(text.text).flatMap { range ->
            val paragraph = text.text.substring(range)
            val places = mentions.places(paragraph)
            val marked = if (pairing == null) places.filter { value in valuesOf(it.value) } else {
                val named = places.map { it.value }.toSortedSet().toList()
                val pairs = named.flatMapIndexed { k, a -> named.drop(k + 1).map { b -> a to b } }
                val of = pairs.filter { (a, b) -> pairing.invoke(a, b) == value }.flatMap { it.toList() }.toSet()
                if (of.isEmpty()) emptyList() else places.filter { it.value in of }
            }
            excerpts(paragraph, marked.map { it.start until it.end }).map { (cut, highlights) -> Quote(text.place, cut, highlights) }
        }
    }.toList()

    companion object {
        private val BREAK = Regex("\\n\\s*\\n|\\n")

        /** Around the names of a long paragraph, so much of it on each side. */
        private const val AROUND = 160

        /** The ranges of the paragraphs of a text, as [Mentions.together] splits it. */
        private fun paragraphs(text: String): List<IntRange> {
            val out = ArrayList<IntRange>()
            var from = 0
            BREAK.findAll(text).forEach { m ->
                if (m.range.first > from) out += from until m.range.first
                from = m.range.last + 1
            }
            if (from < text.length) out += from until text.length
            return out
        }

        /**
         * The [paragraph] as passages that hold the [names]: whole when short, else a passage around
         * each group of names near one another, cut at spaces; the names as ranges of its passage.
         */
        private fun excerpts(paragraph: String, names: List<IntRange>): List<Pair<String, List<IntRange>>> {
            if (names.isEmpty()) return emptyList()
            if (paragraph.length <= 3 * AROUND) return listOf(paragraph to names)
            val groups = ArrayList<MutableList<IntRange>>()
            names.sortedBy { it.first }.forEach { r ->
                val last = groups.lastOrNull()
                if (last != null && r.first - last.last().last <= 2 * AROUND) last += r else groups += mutableListOf(r)
            }
            return groups.map { group ->
                var start = (group.first().first - AROUND).coerceAtLeast(0)
                var end = (group.last().last + 1 + AROUND).coerceAtMost(paragraph.length)
                if (start > 0) paragraph.indexOf(' ', start).takeIf { it in start until group.first().first }?.let { start = it + 1 }
                if (end < paragraph.length) paragraph.lastIndexOf(' ', end).takeIf { it > group.last().last }?.let { end = it }
                val prefix = if (start > 0) "… " else ""
                val suffix = if (end < paragraph.length) " …" else ""
                val shift = prefix.length - start
                prefix + paragraph.substring(start, end) + suffix to group.map { (it.first + shift)..(it.last + shift) }
            }
        }
    }
}
