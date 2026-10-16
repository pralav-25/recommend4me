package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer

/** A word of a text as the index has it: its stem, its place among the words, whether written with a capital; [start] and [end] its characters, when read from the text. */
internal class NamedWord(val stem: String, val position: Int, val capital: Boolean, val start: Int = -1, val end: Int = -1)

/** A name found: the value it is of, the place of its first word and of its last. */
internal class Found(val value: String, val first: NamedWord, val last: NamedWord)

/**
 * WHERE A WORK'S WORDS NAME EVERY VALUE of [names] (value to the names it goes by), the words read
 * as the [languages] its texts were stemmed as — the rules of the counting of names, the same
 * whether the words are read of the index or of a text:
 *
 * - a name of several words counts whole; one word of it counts by itself only when no other value
 *   has that word in a name — "Гарри" is Harry's alone, "Поттер" and "Уизли" are of a family and
 *   tell nobody — and it is no function word of the language nor of English ("Со" of "Со Геррера");
 * - a name of one word — a whole one or a word of a longer one — counts only where written with a
 *   capital: "Пи" of "Си-Три-Пи-О" names, "пи" of a formula not; "Nova" does, "новый" of the same
 *   stem not;
 * - at every place the longest name found counts, once.
 *
 * A name is read as every language of the [languages]: a work whose description is in Russian and
 * whose long chapter is in English is read as both, each word of it stemmed as one of them.
 */
internal class NameMatcher(names: Map<String, List<String>>, languages: Collection<String>) {

    /** A name as its stems, and the value it is of. */
    private data class Pattern(val stems: List<String>, val value: String)

    /** Every name by its first stem. */
    private val patterns: Map<String, List<Pattern>>

    /** Every stem a name has: the words looked up in the index. */
    val stems: Set<String>

    init {
        val all = languages.ifEmpty { listOf(Stemmers.DEFAULT) }.distinct().flatMap { language ->
            // One analyzer for every name: thousands of names of a dictionary
            val analyzer = Stemmers.analyzer(language)
            val whole = names.flatMap { (value, own) -> own.map { Pattern(LuceneIndex.tokens(it, analyzer), value) } }.filter { it.stems.isNotEmpty() }.distinct()
            val common = CharArraySet(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, true).apply { addAll(Stemmers.stopWords(language)) }
                .flatMap { LuceneIndex.tokens(String(it as CharArray), analyzer) }.toSet()
            val owners = HashMap<String, MutableSet<String>>()
            whole.forEach { p -> p.stems.forEach { owners.getOrPut(it) { HashSet() } += p.value } }
            val single = owners.filter { (stem, of) -> of.size == 1 && stem !in common }.map { (stem, of) -> Pattern(listOf(stem), of.first()) }
            whole + single
        }.distinct()
        patterns = all.groupBy { it.stems.first() }
        stems = all.flatMap { it.stems }.toSet()
    }

    /** The names found among the [words] of one text, in the order of their places: the longest name at a place, places not overlapping. */
    fun found(words: List<NamedWord>): List<Found> {
        val at = HashMap<Int, NamedWord>(words.size * 2)
        words.forEach { at[it.position] = it }
        val out = ArrayList<Found>()
        var next = Int.MIN_VALUE
        words.sortedBy { it.position }.forEach { word ->
            if (word.position < next) return@forEach
            val match = patterns[word.stem]
                ?.filter { p -> p.stems.indices.all { at[word.position + it]?.stem == p.stems[it] } }
                ?.filter { p -> p.stems.size > 1 || word.capital }
                ?.maxByOrNull { it.stems.size }
                ?: return@forEach
            out += Found(match.value, word, at.getValue(word.position + match.stems.size - 1))
            next = word.position + match.stems.size
        }
        return out
    }
}
