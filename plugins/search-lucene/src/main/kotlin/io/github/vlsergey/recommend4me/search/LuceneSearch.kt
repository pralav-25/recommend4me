package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tokenattributes.PositionIncrementAttribute
import org.apache.lucene.index.PostingsEnum
import org.apache.lucene.index.Term
import org.apache.lucene.util.BytesRefBuilder
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.BoostQuery
import org.apache.lucene.search.ConstantScoreQuery
import org.apache.lucene.search.DisjunctionMaxQuery
import org.apache.lucene.search.KnnFloatVectorQuery
import org.apache.lucene.search.PhraseQuery
import org.apache.lucene.search.PrefixQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.TermQuery
import org.apache.lucene.search.uhighlight.Passage
import org.apache.lucene.search.uhighlight.PassageFormatter
import org.apache.lucene.search.uhighlight.UnifiedHighlighter
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(LuceneSearch::class.java)

/**
 * Search by a line of text over everything known about an item, by meaning and by words at once:
 *
 * - BY MEANING: the line becomes a vector of the text encoder — in any language, a Russian line
 *   finds English overviews — and is compared with the vector of every text of every item; an item
 *   counts with its closest text. "corruption of a nun" finds a game whose overview never says
 *   "corruption".
 * - BY WORDS: the words of the line found — as words, in any of their forms, or as the beginnings
 *   of words — in the title, the facets and the texts, a field weighing what a match there says;
 *   with a bonus when a naming field (the title, the author) holds the whole line. A name the
 *   encoder never saw — a developer, a character — is found this way.
 *
 * Both are asked of one Lucene index per content type ([LuceneIndex]) in
 * `<data-dir>/types/<type>/search-index`.
 */
class LuceneSearch(private val dataDir: Path) : SearchProvider, AutoCloseable {

    override val id = "lucene"

    /** The open indexes — files, not data: a writer is opened once and held. */
    private val indexes = ConcurrentHashMap<String, LuceneIndex>()

    private fun index(contentType: String): LuceneIndex =
        indexes.computeIfAbsent(contentType) { LuceneIndex(dataDir.resolve("types").resolve(it).resolve("search-index")) }

    /**
     * Marks the words of a query in a text given to it, without the index — the texts are not stored
     * in it — the text read as its [language], as it was put in the index.
     */
    private fun highlighter(language: String) = UnifiedHighlighter.builderWithoutSearcher(Stemmers.analyzer(language))
        .withFormatter(SnippetFormatter())
        .withMaxNoHighlightPassages(0)
        // Not only the first 10 000 characters, the default: a changelog is longer
        .withMaxLength(MAX_TEXT)
        .build()

    override fun rebuild(contentType: String, documents: Sequence<SearchDocument>) {
        val started = System.currentTimeMillis()
        val count = index(contentType).rebuild(documents)
        log.info("search index of {}: {} items made in {} ms", contentType, count, System.currentTimeMillis() - started)
    }

    override fun update(contentType: String, documents: List<SearchDocument>, removed: Collection<ItemKey>) {
        index(contentType).update(documents, removed)
    }

    /** What [matches] needs of a search: the line's words, the language it is read as, its vector and the passage of every item nearest by meaning. */
    private class State(val terms: List<String>, val language: String, val vector: FloatArray?, val nearest: Map<ItemKey, SearchPassage>)

    private class Nearest(val cosine: Double, val passage: SearchPassage)

    override fun search(contentType: String, line: String, language: String, context: SearchContext): SearchResult {
        val q = line.trim().lowercase()
        val read = Stemmers.of(language)
        if (q.isEmpty()) return SearchResult(q, emptyList(), State(emptyList(), read, null, emptyMap()))
        val started = System.currentTimeMillis()
        val index = index(contentType)
        val scores = HashMap<ItemKey, Double>()

        val vector = context.queryVector(q)
        // The function words of English and of the line's language
        val stops = CharArraySet(EnglishAnalyzer.ENGLISH_STOP_WORDS_SET, true).apply { addAll(Stemmers.stopWords(read)) }
        val terms = q.split(Regex("[^\\p{L}\\p{N}']+")).filter { it.length >= 2 && !stops.contains(it) }.distinct()
        val nearest = index.search { searcher ->
            val fields = index.fields(searcher).searched()
            val nearest = vector?.takeIf { fields.vectors }?.let { nearest(searcher, it) }.orEmpty()
            val semantic = closeness(nearest, index.items(searcher))
            semantic.forEach { (key, closeness) -> if (closeness >= SEMANTIC_FLOOR) scores[key] = SEMANTIC_WEIGHT * closeness }
            byWords(searcher, fields, q, terms, read).forEach { (key, score) ->
                // An item found by words still keeps its meaning score, so the two add up
                scores[key] = (scores[key] ?: (SEMANTIC_WEIGHT * (semantic[key] ?: 0.0))) + WORD_WEIGHT * score
            }
            nearest
        }
        val ranked = scores.entries.sortedByDescending { it.value }.take(MAX_RESULTS).map { it.key }
        log.info("'{}' in {}: {} items in {} ms", line, contentType, ranked.size, System.currentTimeMillis() - started)
        val passages = nearest.mapValues { it.value.passage }
        return SearchResult(q, ranked, State(terms, read, vector, passages), passages)
    }

    /**
     * What to show of every item of [keys]: the piece of its text where the words stand together,
     * or — found by meaning, its words apart or missing — the phrase of its text nearest by meaning
     * that is nearest to the line, or else the piece where a word is. The items' documents are read
     * in one go, the phrases' vectors in one batch.
     */
    override fun matches(contentType: String, result: SearchResult, keys: Collection<ItemKey>, context: SearchContext): Map<ItemKey, SearchMatch> {
        val state = result.state as? State ?: return emptyMap()
        val documents = context.documents(keys)
        val byWords = HashMap<ItemKey, SearchMatch>()
        val byMeaning = HashMap<ItemKey, Pair<SearchField, List<String>>>()
        keys.forEach { key ->
            val doc = documents[key] ?: return@forEach
            val words = snippet(state.terms, state.language, doc)
            if (words != null) byWords[key] = SearchMatch(words.first.key, false, words.second.text, words.second.highlights)
            // The words apart did not make the item a match, its meaning did: the phrase of the passage nearest by meaning is shown
            if (words?.third == true) return@forEach
            val passage = state.nearest[key] ?: return@forEach
            val field = doc.fields.firstOrNull { it.key == passage.field } ?: return@forEach
            val text = passage.text(doc)?.trim().orEmpty()
            // A passage of no phrase long enough — a list of tags, of titles — is shown as it is: a work
            // found is never shown without what it was found by
            val phrases = Phrases.split(text).filter { it.length >= MIN_PHRASE }.ifEmpty { listOfNotNull(text.takeIf { it.isNotEmpty() }) }
            if (phrases.isNotEmpty()) byMeaning[key] = field to phrases
        }
        val q = state.vector ?: return byWords
        val vectors = context.phraseVectors(byMeaning.values.flatMap { it.second }.toSet())
        val nearestPhrases = byMeaning.mapValues { (_, value) ->
            val (field, phrases) = value
            val best = phrases.maxBy { phrase -> vectors[phrase]?.let { v -> q.indices.sumOf { k -> q[k].toDouble() * v[k] } } ?: -1.0 }
            SearchMatch(field.key, true, best.take(MEANING_SNIPPET), emptyList())
        }
        return byWords + nearestPhrases
    }

    /**
     * The items nearest to the query's vector [q] by meaning: the [NEAREST] passages nearest — of
     * descriptions, of notes, of chapters — an item by its nearest passage.
     */
    private fun nearest(searcher: org.apache.lucene.search.IndexSearcher, q: FloatArray): Map<ItemKey, Nearest> {
        val out = HashMap<ItemKey, Nearest>()
        val stored = searcher.storedFields()
        val hits = try {
            searcher.search(KnnFloatVectorQuery(LuceneIndex.VECTOR, LuceneIndex.unit(q), NEAREST), NEAREST).scoreDocs
        } catch (e: IllegalArgumentException) {
            // Vectors of another dimension: an encoder changed and the index is not made again yet
            log.debug("passages are not searched: {}", e.message)
            return out
        }
        hits.forEach { hit ->
            // The score of a dot product is (1 + cos) / 2
            val cosine = 2.0 * hit.score - 1.0
            val doc = stored.document(hit.doc)
            val key = ItemKey.parse(doc.get(LuceneIndex.ID))
            if ((out[key]?.cosine ?: Double.NEGATIVE_INFINITY) < cosine) {
                val passage = SearchPassage(
                    doc.get(LuceneIndex.PASSAGE_FIELD),
                    doc.getField(LuceneIndex.PASSAGE_FROM).numericValue().toInt(),
                    doc.getField(LuceneIndex.PASSAGE_TO).numericValue().toInt(),
                )
                out[key] = Nearest(cosine, passage)
            }
        }
        return out
    }

    /**
     * How close the items nearest to the query by meaning are, ON THE QUERY'S OWN SCALE: 0 at the
     * [NEAREST]th nearest item — or at the median one of a smaller catalogue — 1 at the top percent
     * of the catalogue; the items farther than that are not matches by meaning. Raw cosines are no
     * scale at all — e5 puts nearly every pair of texts between 0.7 and 0.9 — and a fixed threshold
     * either lets everything through or nothing.
     */
    private fun closeness(found: Map<ItemKey, Nearest>, size: Int): Map<ItemKey, Double> {
        val nearest = found.mapValues { it.value.cosine }
        if (nearest.size < 2) return nearest
        val sorted = nearest.values.sortedDescending()
        val floor = sorted[minOf(NEAREST, size / 2, sorted.size).coerceAtLeast(1) - 1]
        val top = sorted[(size / 100).coerceIn(0, sorted.size - 1)]
        val span = (top - floor).coerceAtLeast(1e-6)
        return nearest.mapValues { (_, cos) -> ((cos - floor) / span).coerceIn(0.0, MAX_CLOSENESS) }
    }

    /**
     * Every item matching the [terms] by words, with its score. An item matches when it has all the
     * words, anywhere in it — of a long query all but a few ([required]): one word of two is a
     * coincidence ("Stolen Memories" for "stolen clothes"), and such an item is left to the search
     * by meaning. Its score: for every term found — in any of its forms, or a name typed in part —
     * the weight of the best field it is found in, averaged over the terms, and of several terms
     * only [APART] of that; plus [TOGETHER] times the weight of the best field where they all stand
     * together — within [NEAR] words of each other, in any order; plus [NAME_BONUS] when the whole
     * [line] stands as a phrase in a naming field — or, a single word typed in part, begins a word of it.
     */
    private fun byWords(searcher: org.apache.lucene.search.IndexSearcher, fields: LuceneIndex.Fields, line: String, terms: List<String>, language: String): Map<ItemKey, Double> {
        if (fields.text.isEmpty()) return emptyMap()
        val query = BooleanQuery.Builder()
        val termQueries = terms.mapNotNull { termQuery(fields, it, language) }
        if (termQueries.isNotEmpty()) {
            val byWords = BooleanQuery.Builder().setMinimumNumberShouldMatch(required(termQueries.size))
            termQueries.forEach { byWords.add(BoostQuery(it, 1f / termQueries.size), BooleanClause.Occur.SHOULD) }
            query.add(BoostQuery(byWords.build(), if (termQueries.size > 1) APART else 1f), BooleanClause.Occur.SHOULD)
        }
        val all = terms.flatMap { LuceneIndex.tokens(it, language) }.distinct()
        if (all.size > 1) {
            val together = fields.text.map { field ->
                val near = PhraseQuery.Builder().setSlop(NEAR).apply { all.forEach { add(Term(field.name, it)) } }.build()
                BoostQuery(ConstantScoreQuery(near), field.weight)
            }
            query.add(BoostQuery(DisjunctionMaxQuery(together, 0f), TOGETHER), BooleanClause.Occur.SHOULD)
        }
        val words = words(line, language)
        val naming = fields.text.filter { it.naming }
        if (words.isNotEmpty() && naming.isNotEmpty()) {
            val inName = naming.map { field ->
                val name = if (words.size == 1) wordQuery(field.name, words.single())
                else PhraseQuery(field.name, *words.map { it.token }.toTypedArray())
                ConstantScoreQuery(name)
            }
            query.add(BoostQuery(DisjunctionMaxQuery(inName, 0f), NAME_BONUS), BooleanClause.Occur.SHOULD)
        }
        val built = query.build()
        if (built.clauses().isEmpty()) return emptyMap()
        val hits = searcher.search(built, searcher.indexReader.maxDoc().coerceAtLeast(1))
        val stored = searcher.storedFields()
        return hits.scoreDocs.associate { hit -> ItemKey.parse(stored.document(hit.doc).get(LuceneIndex.ID)) to hit.score.toDouble() }
    }

    /** How many of [terms] words of a query an item must have: all of one or two, of a longer query all but every third. */
    private fun required(terms: Int): Int = if (terms <= 2) terms else terms - terms / 3

    /** A word of the query as the index has it, and whether it may also find the words it begins. */
    private data class Word(val token: String, val prefix: Boolean)

    /**
     * The words of a term of the query. A word finds the words it begins — a name typed in part,
     * "herand" finds "herandu" — only when the analyzer left it as it is, from [MIN_PREFIX] letters
     * on: a word the stemmer changed is a word of the language, whose other forms the stemmer brings
     * to it already, and as a beginning it finds other words — "stolen", read as "steal", found
     * "stealth".
     */
    private fun words(term: String, language: String): List<Word> {
        val parts = LuceneIndex.tokens(term, language)
        val asTyped = parts.size == 1 && parts.single() == term.lowercase() && term.length >= MIN_PREFIX
        return parts.map { Word(it, asTyped) }
    }

    private fun wordQuery(field: String, word: Word): Query {
        val exact = TermQuery(Term(field, word.token))
        if (!word.prefix) return exact
        return BooleanQuery.Builder()
            .add(exact, BooleanClause.Occur.SHOULD)
            .add(PrefixQuery(Term(field, word.token)), BooleanClause.Occur.SHOULD)
            .build()
    }

    /** A term of the query, scored by the weight of the best field it is found in; a term the analyzer splits needs every part in that field. */
    private fun termQuery(fields: LuceneIndex.Fields, term: String, language: String): Query? {
        val parts = words(term, language)
        if (parts.isEmpty()) return null
        val perField = fields.text.map { field ->
            val each = parts.map { part -> wordQuery(field.name, part) }
            val inField = if (each.size == 1) each.single()
            else BooleanQuery.Builder().apply { each.forEach { add(it, BooleanClause.Occur.MUST) } }.build()
            BoostQuery(ConstantScoreQuery(inField), field.weight)
        }
        // The best field alone counts: a term in the title and in the changelog is still one term found
        return DisjunctionMaxQuery(perField, 0f)
    }

    /**
     * The piece of the item's text where the [terms] are found, with the words found marked: where
     * they all stand together, from the weightiest field that has them so — the place that made the
     * item a match — or else from the weightiest field that holds one; null when none is found.
     * The third value: whether the words all stand together there. The [terms] are of the [language].
     */
    private fun snippet(terms: List<String>, language: String, doc: SearchDocument): Triple<SearchField, Snippet, Boolean>? {
        val parts = terms.flatMap { words(it, language) }.distinct()
        if (parts.isEmpty()) return null
        val contents = doc.fields.sortedByDescending { it.weight }.mapNotNull { field ->
            field.values.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { field to it.joinToString(if (field.list) ", " else "\n\n") }
        }
        val all = terms.flatMap { LuceneIndex.tokens(it, language) }.distinct()
        // A text read as it was put in the index: as its own language, or the work's
        fun marker(content: String) = highlighter(TextLanguages.of(content, doc.language))
        if (all.size > 1) contents.forEach { (field, content) ->
            val name = LuceneIndex.fieldName(field)
            val near = PhraseQuery.Builder().setSlop(NEAR).apply { all.forEach { add(Term(name, it)) } }.build()
            val found = marker(content).highlightWithoutSearcher(name, near, content, 1) as Snippet?
            if (found != null) return Triple(field, found, true)
        }
        contents.forEach { (field, content) ->
            val name = LuceneIndex.fieldName(field)
            val query = BooleanQuery.Builder().apply { parts.forEach { part -> add(wordQuery(name, part), BooleanClause.Occur.SHOULD) } }.build()
            val found = marker(content).highlightWithoutSearcher(name, query, content, 1) as Snippet?
            if (found != null) return Triple(field, found, all.size <= 1)
        }
        return null
    }

    /** A name found of the index: the value it is of, in which field of the work, in which paragraph of it. */
    private class FoundIn(val value: String, val field: String, val paragraph: Int)

    /**
     * The names counted of the index, not of the texts: of every name's stems the places in the
     * works' own fields ([LuceneIndex.IndexedField.counted]) with whether written with a capital, and
     * the marks the paragraphs begin with ([ParagraphMarks]); then the rules of [NameMatcher] over
     * them, a field at a time — no text read again, no chapter. Of the works [items], every work
     * indexed when null.
     */
    private fun named(contentType: String, items: Collection<ItemKey>?, names: Map<String, List<String>>): Map<ItemKey, List<FoundIn>> {
        if (names.isEmpty()) return emptyMap()
        val index = index(contentType)
        return index.search { searcher ->
            val stored = searcher.storedFields()
            // The documents of the items: their ids in the index, and the languages each was stemmed as
            val wanted = if (items == null) searcher.search(LuceneIndex.ITEMS, searcher.indexReader.maxDoc().coerceAtLeast(1)).scoreDocs.map { it.doc }
            else items.mapNotNull { key ->
                val one = BooleanQuery.Builder()
                    .add(TermQuery(Term(LuceneIndex.ID, key.toString())), BooleanClause.Occur.FILTER)
                    .add(LuceneIndex.ITEMS, BooleanClause.Occur.FILTER).build()
                searcher.search(one, 1).scoreDocs.firstOrNull()?.doc
            }
            val keys = HashMap<Int, ItemKey>()
            val languages = HashMap<Int, List<String>>()
            wanted.forEach { doc ->
                val d = stored.document(doc, setOf(LuceneIndex.ID, LuceneIndex.LANGUAGES))
                keys[doc] = ItemKey.parse(d.get(LuceneIndex.ID))
                languages[doc] = d.get(LuceneIndex.LANGUAGES)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
            }
            val counted = index.fields(searcher).text.filter { it.counted }.map { it.name }
            val out = HashMap<ItemKey, MutableList<FoundIn>>()
            // One matcher of every set of languages: of a catalogue of one language, one
            languages.entries.groupBy({ it.value }, { it.key }).forEach { (langs, docs) ->
                val matcher = NameMatcher(names, langs)
                // Every name's stem at its places, of every field of every document: the words the rules read;
                // and where its paragraphs begin
                val words = HashMap<Pair<Int, String>, MutableList<NamedWord>>()
                val marks = HashMap<Pair<Int, String>, IntArray>()
                val sorted = docs.sorted()
                searcher.indexReader.leaves().forEach { leaf ->
                    val inLeaf = sorted.filter { it >= leaf.docBase && it < leaf.docBase + leaf.reader().maxDoc() }.map { it - leaf.docBase }
                    if (inLeaf.isEmpty()) return@forEach
                    counted.forEach { field ->
                        val terms = leaf.reader().terms(field)?.iterator() ?: return@forEach
                        // One enumeration of the places, reused for every stem: thousands of stems of the names
                        var postings: PostingsEnum? = null
                        val term = BytesRefBuilder()
                        (matcher.stems + ParagraphMarks.MARK).forEach { stem ->
                            term.copyChars(stem)
                            if (!terms.seekExact(term.get())) return@forEach
                            val places = terms.postings(postings, PostingsEnum.PAYLOADS.toInt()).also { postings = it }
                            inLeaf.forEach { doc ->
                                // Gone to the document asked for, or past it: it may stand there already, gone to while looking for the one before
                                val standing = if (places.docID() < doc) places.advance(doc) else places.docID()
                                if (standing == doc) {
                                    val at = leaf.docBase + doc to field
                                    if (stem == ParagraphMarks.MARK) marks[at] = IntArray(places.freq()) { places.nextPosition() }
                                    else {
                                        val list = words.getOrPut(at) { ArrayList() }
                                        repeat(places.freq()) { list += NamedWord(stem, places.nextPosition(), places.payload == Stemmers.CAPITAL) }
                                    }
                                }
                            }
                        }
                    }
                }
                words.forEach { (at, list) ->
                    val begins = marks[at]?.also { it.sort() } ?: IntArray(0)
                    val found = out.getOrPut(keys.getValue(at.first)) { ArrayList() }
                    matcher.found(list).forEach { f ->
                        // Of the paragraph the name stands in: how many marks are before it
                        val paragraph = begins.binarySearch(f.first.position).let { if (it >= 0) it + 1 else -it - 1 }
                        found += FoundIn(f.value, at.second, paragraph)
                    }
                }
            }
            out.filterValues { it.isNotEmpty() }
        }
    }

    override fun mentions(contentType: String, items: Collection<ItemKey>?, names: Map<String, List<String>>): Map<ItemKey, Map<String, Int>> =
        named(contentType, items, names).mapValues { (_, found) -> found.groupingBy { it.value }.eachCount() }

    override fun together(contentType: String, items: Collection<ItemKey>?, names: Map<String, List<String>>): Map<ItemKey, Map<Pair<String, String>, Int>> =
        named(contentType, items, names).mapValues { (_, found) ->
            val out = HashMap<Pair<String, String>, Int>()
            found.groupBy { it.field to it.paragraph }.values.forEach { paragraph ->
                val each = paragraph.map { it.value }.toSortedSet().toList()
                each.forEachIndexed { k, a -> each.drop(k + 1).forEach { b -> out.merge(a to b, 1, Int::plus) } }
            }
            out
        }.filterValues { it.isNotEmpty() }

    /** The names of a [text] of the document found again, read as it was put in the index: its words with their characters. */
    private fun foundIn(text: String, document: SearchDocument, matcher: NameMatcher): List<Found> {
        val words = ArrayList<NamedWord>()
        Stemmers.analyzer(TextLanguages.of(text, document.language)).tokenStream("", text).use { stream ->
            val term = stream.addAttribute(CharTermAttribute::class.java)
            val step = stream.addAttribute(PositionIncrementAttribute::class.java)
            val payload = stream.addAttribute(PayloadAttribute::class.java)
            val offset = stream.addAttribute(OffsetAttribute::class.java)
            stream.reset()
            var position = -1
            while (stream.incrementToken()) {
                position += step.positionIncrement
                words += NamedWord(term.toString(), position, payload.payload == Stemmers.CAPITAL, offset.startOffset(), offset.endOffset())
            }
            stream.end()
        }
        return matcher.found(words)
    }

    /** The places of the values [of] in the document's own words, found by the rules of [mentions] over its texts read again as they were put in the index. */
    override fun places(document: SearchDocument, names: Map<String, List<String>>, of: Collection<String>): List<NamePlace> {
        val wanted = of.toHashSet()
        val matcher = NameMatcher(names, LuceneIndex.languagesOf(document))
        return document.fields.filter { it.counted }.flatMap { field ->
            field.values.filter { it.isNotBlank() }.mapNotNull { text ->
                val ranges = foundIn(text, document, matcher).filter { it.value in wanted }.map { it.first.start until it.last.end }
                if (ranges.isEmpty()) null else NamePlace(field.key, text, ranges)
            }
        }
    }

    /**
     * The places of the two values [a] and [b] named in one paragraph of the document's own words, as
     * [together] counts them: of every value of a field, the names of both in the paragraphs naming both.
     */
    override fun placesTogether(document: SearchDocument, names: Map<String, List<String>>, a: String, b: String): List<NamePlace> {
        val matcher = NameMatcher(names, LuceneIndex.languagesOf(document))
        return document.fields.filter { it.counted }.flatMap { field ->
            field.values.filter { it.isNotBlank() }.mapNotNull { text ->
                // A paragraph ends at a line break, as the marks of the index have it
                val found = foundIn(text, document, matcher).filter { it.value == a || it.value == b }
                val breaks = text.indices.filter { text[it] == '\n' }
                fun paragraphOf(at: Int) = breaks.binarySearch(at).let { if (it >= 0) it else -it - 1 }
                val ranges = found.groupBy { paragraphOf(it.first.start) }.values
                    .filter { inOne -> inOne.any { it.value == a } && inOne.any { it.value == b } }
                    .flatten().map { it.first.start until it.last.end }
                if (ranges.isEmpty()) null else NamePlace(field.key, text, ranges.sortedBy { it.first })
            }
        }
    }

    override fun close() {
        indexes.values.forEach { it.close() }
    }

    companion object {
        private const val MAX_RESULTS = 500
        private const val MAX_TEXT = 1_000_000

        /** The items nearest by meaning that are asked for; the farther ones are no matches by meaning. */
        private const val NEAREST = 2000

        /** How much of a text nearest by meaning is shown. */
        private const val MEANING_SNIPPET = 200

        /** Shorter phrases — "Download", a date — say nothing of what an item is about. */
        private const val MIN_PHRASE = 20

        /** Closer than this to the top percent than to the zero of the scale, or it is not a match by meaning. */
        private const val SEMANTIC_FLOOR = 0.5

        /** The few items above the top percent are not told apart further than this. */
        private const val MAX_CLOSENESS = 1.5
        private const val SEMANTIC_WEIGHT = 1.0
        private const val WORD_WEIGHT = 0.6

        /**
         * The whole line in a naming field: what is looked for, ahead of any likeness by meaning —
         * WORD_WEIGHT · (1 + NAME_BONUS) = 1.8 beats MAX_CLOSENESS = 1.5. At 1.0 a developer's name, a
         * word the encoder never saw, lost to the items its vector happened to lie near.
         */
        private const val NAME_BONUS = 2.0f

        /** A word of the query left as typed finds the words it begins from this length on. */
        private const val MIN_PREFIX = 3

        /** How far apart, in words, the words of a query still stand together. */
        private const val NEAR = 6

        /** What the words standing together weigh against the words found apart. */
        private const val TOGETHER = 2f

        /**
         * What the words of a query found apart weigh: WORD_WEIGHT · APART = 0.15 is below the least
         * likeness by meaning that counts, so such items come after those alike by meaning.
         */
        private const val APART = 0.25f
    }
}

/** A piece of a found text, and where in it the words of the query stand (start, end). */
class Snippet(val text: String, val highlights: List<Pair<Int, Int>>)

/**
 * The best passage of a text as a [Snippet]: the passage — a sentence — cut to its part around the
 * first word found when it is long, the words found as ranges in it; null without a word found.
 */
private class SnippetFormatter : PassageFormatter() {
    override fun format(passages: Array<Passage>, content: String): Any? {
        val passage = passages.firstOrNull { it.numMatches > 0 } ?: return null
        var from = passage.startOffset
        var to = passage.endOffset
        val first = passage.matchStarts[0]
        if (to - from > MAX) {
            from = maxOf(from, first - MAX / 3)
            to = minOf(to, from + MAX)
        }
        // A word boundary, not the middle of a word
        while (from > passage.startOffset && !content[from - 1].isWhitespace()) from--
        val raw = content.substring(from, to)
        val base = from + (raw.length - raw.trimStart().length)
        val text = raw.trim()
        val prefix = if (from > passage.startOffset) "…" else ""
        val suffix = if (to < passage.endOffset) "…" else ""
        val highlights = (0 until passage.numMatches)
            .map { passage.matchStarts[it] - base to passage.matchEnds[it] - base }
            .filter { (start, end) -> start >= 0 && end <= text.length }
            // A word found both as a word and as the beginning of one is one word
            .distinct()
            .map { (start, end) -> start + prefix.length to end + prefix.length }
        return Snippet(prefix + text + suffix, highlights)
    }

    companion object {
        /** How long a piece of text is shown at most, around the first word found. */
        private const val MAX = 220
    }
}
