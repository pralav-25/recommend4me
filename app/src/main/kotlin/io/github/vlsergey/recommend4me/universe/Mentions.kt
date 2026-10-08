package io.github.vlsergey.recommend4me.universe

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.ru.RussianAnalyzer
import org.apache.lucene.analysis.ru.RussianLightStemFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute

/** A word of a text: its stem, the characters it stands at, from [start] to before [end], and whether it is written with a capital. */
class Token(val stem: String, val start: Int, val end: Int, val capital: Boolean)

/** A place a text names a character at: the characters from [start] to before [end], and the character's value. */
class Place(val start: Int, val end: Int, val value: String)

/**
 * HOW OFTEN A WORK'S TEXTS NAME EACH CHARACTER: its description, its notes, the chapters read, the
 * site's line of its characters — by every name the character goes by, however it is declined
 * ("Гермиона", "Гермионы", "Гермионе" are one stem).
 *
 * A name of several words counts whole; one word of it counts by itself only when no other
 * character of the [characters] has that word in a name — "Гарри" is Harry's alone, "Поттер" and
 * "Уизли" are of a family and tell nobody — when it is no function word ("Со" of "Со Геррера").
 * A name of one word counts only where the text writes it with a capital. At every place the
 * longest name found counts, once.
 */
class Mentions(characters: Map<String, List<String>>) {

    /** A name as its stems, and the character it is of. */
    private data class Pattern(val stems: List<String>, val value: String)

    /** Every name by its first stem. */
    private val patterns: Map<String, List<Pattern>>

    init {
        val whole = characters.flatMap { (value, names) -> names.map { Pattern(stems(it), value) } }.filter { it.stems.isNotEmpty() }.distinct()
        // A word of a name is a name by itself when it is of one character only — and is no word of
        // the language's own: "Со" of "Со Геррера", "О" of "Си-Три-Пи-О" name nobody
        val owners = HashMap<String, MutableSet<String>>()
        whole.forEach { p -> p.stems.forEach { owners.getOrPut(it) { HashSet() } += p.value } }
        val single = owners.filter { (stem, of) -> of.size == 1 && stem !in COMMON }
            .map { (stem, of) -> Pattern(listOf(stem), of.first()) }
        patterns = (whole + single).distinct().groupBy { it.stems.first() }
    }

    /** How many times the [texts] name each character; only the named ones. */
    fun count(texts: Sequence<String>): Map<String, Int> {
        val out = HashMap<String, Int>()
        texts.forEach { text -> found(tokens(text)).forEach { out.merge(it.value, 1, Int::plus) } }
        return out
    }

    /** In how many paragraphs of the [texts] each two characters are named together, the pair in the order of their values. */
    fun together(texts: Sequence<String>): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        texts.flatMap { it.split(PARAGRAPH) }.forEach { paragraph ->
            val named = found(tokens(paragraph)).map { it.value }.toSortedSet().toList()
            named.forEachIndexed { k, a -> named.drop(k + 1).forEach { b -> out.merge(a to b, 1, Int::plus) } }
        }
        return out
    }

    /** Every place of the [text] a character is named at, as [count] finds them: where in the text, and whose name. */
    fun places(text: String): List<Place> {
        val words = tokens(text)
        return found(words).map { Place(words[it.from].start, words[it.from + it.size - 1].end, it.value) }
    }

    /** A name found among words: the character, the word it starts at and how many words it takes. */
    private class Found(val value: String, val from: Int, val size: Int)

    /**
     * The characters named at every place of the [words], the longest name at a place, places not
     * overlapping. A name of one word — a whole one or a word of a longer one — is a name only
     * written as one, with a capital: "Пи" of "Си-Три-Пи-О" is a name, "пи" of a formula is not;
     * "Нова" is, "новый" of the same stem is not.
     */
    private fun found(words: List<Token>): List<Found> {
        val out = ArrayList<Found>()
        var at = 0
        while (at < words.size) {
            val match = patterns[words[at].stem]
                ?.filter { p -> at + p.stems.size <= words.size && p.stems.indices.all { words[at + it].stem == p.stems[it] } }
                ?.filter { p -> p.stems.size > 1 || words[at].capital }
                ?.maxByOrNull { it.stems.size }
            if (match == null) at++ else {
                out += Found(match.value, at, match.stems.size)
                at += match.stems.size
            }
        }
        return out
    }

    companion object {
        private val PARAGRAPH = Regex("\\n\\s*\\n|\\n")

        /** The words of a text, lower-cased, stemmed as Russian: the chain the search reads texts by. */
        private val analyzer = object : Analyzer() {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val tokenizer = StandardTokenizer()
                return TokenStreamComponents(tokenizer, RussianLightStemFilter(LowerCaseFilter(tokenizer)))
            }
        }

        /** The stems of the words of a text, in order. */
        fun stems(text: String): List<String> = tokens(text).map { it.stem }

        /** The function words of Russian and English — prepositions, conjunctions, pronouns — as stems: no word of a name stands for it alone. */
        private val COMMON: Set<String> = (RussianAnalyzer.getDefaultStopSet() + EnglishAnalyzer.ENGLISH_STOP_WORDS_SET)
            .flatMap { stems(String(it as CharArray)) }.toSet()

        /** The words of a text, in order: each word's stem and where it stands in the text. */
        fun tokens(text: String): List<Token> {
            val out = ArrayList<Token>()
            // 'ё' to 'е' keeps every character in its place: the places found are the text's own
            analyzer.tokenStream("", text.replace('ё', 'е').replace('Ё', 'Е')).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                val offset = stream.addAttribute(OffsetAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) {
                    out += Token(term.toString(), offset.startOffset(), offset.endOffset(), text[offset.startOffset()].isUpperCase())
                }
                stream.end()
            }
            return out
        }
    }
}
