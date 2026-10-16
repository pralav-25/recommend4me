package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.work.TextPlace

/** A passage of a work's text that names a value: where in the work, the passage, and the names in it as ranges of it. */
class Quote(val place: TextPlace, val text: String, val highlights: List<IntRange>)

/**
 * THE PASSAGES A WORK NAMES A VALUE IN, as quotes: of a text and the places of the names in it —
 * found of the search index as the names are counted ([io.github.vlsergey.recommend4me.search.SearchProvider.places]) —
 * the paragraphs that hold them, cut around them when long.
 */
object Quotes {
    private val BREAK = Regex("\\n\\s*\\n|\\n")

    /** Around the names of a long paragraph, so much of it on each side. */
    private const val AROUND = 160

    /** The [text] of the [place] as quotes of the [names] in it, ranges of it: the paragraphs that hold them, cut around them when long. */
    fun of(place: TextPlace, text: String, names: List<IntRange>): List<Quote> = paragraphs(text).flatMap { range ->
        val inside = names.filter { it.first >= range.first && it.last <= range.last }.map { (it.first - range.first)..(it.last - range.first) }
        excerpts(text.substring(range), inside).map { (cut, highlights) -> Quote(place, cut, highlights) }
    }

    /** The ranges of the paragraphs of a text: a paragraph ends at a line break. */
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
