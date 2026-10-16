package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.KnnFloatVectorField
import org.apache.lucene.document.StoredField
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.search.TermQuery
import org.apache.lucene.index.FieldInfos
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.Term
import org.apache.lucene.index.VectorSimilarityFunction
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.SearcherFactory
import org.apache.lucene.search.SearcherManager
import org.apache.lucene.store.FSDirectory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.exists

/**
 * The index of one content type on disk: of every item a document of its fields — a field per
 * [SearchField], searched by words — and a document of every passage of its texts ([SearchPassage]),
 * its vector searched by meaning; all of them under the item's [ID], replaced and removed together.
 *
 * THE WEIGHT OF A FIELD IS IN ITS NAME — `<marks>.<weight in hundredths>.<key>`, the marks `n` of a
 * field that names the item, `c` of the work's own words ([SearchField.counted]), `t` of neither —
 * so a search reads which fields there are and what each weighs from the index itself, whatever
 * source wrote them. Every passage's vector is in the one field [VECTOR].
 *
 * EVERY TEXT STEMMED BEFORE IT IS PUT IN, ONCE, as written in its language ([TextLanguages],
 * [Stemmers]) — one field of it, no copy by another language (user, 2026-10-16): every word as its
 * stem at its place, marked when written with a capital ([Stemmers.CAPITAL]).
 *
 * MADE ANEW BESIDE THE OLD ONE: a rebuild writes into a folder of its own while the searches use
 * the index as it was, then takes its place; what is updated meanwhile goes into both.
 */
class LuceneIndex(private val dir: Path) : AutoCloseable {

    private val lock = ReentrantLock()
    private var writer: IndexWriter = open(dir)
    private var searchers = SearcherManager(writer, SearcherFactory())

    /** The writer of a rebuild in progress: updates go into it too. */
    private var building: IndexWriter? = null

    /** Rebuilds the index from [documents]; searches use the old one until it is done. */
    fun rebuild(documents: Sequence<SearchDocument>): Int {
        val fresh = dir.resolveSibling(dir.fileName.toString() + ".new")
        fresh.toFile().deleteRecursively()
        val next = open(fresh)
        lock.withLock { building = next }
        var count = 0
        try {
            documents.forEach {
                next.updateDocuments(idTerm(it.key), documents(it))
                count++
            }
            next.commit()
        } catch (e: Exception) {
            lock.withLock { building = null }
            next.close()
            fresh.toFile().deleteRecursively()
            throw e
        }
        lock.withLock {
            building = null
            next.close()
            searchers.close()
            writer.close()
            dir.toFile().deleteRecursively()
            Files.move(fresh, dir)
            writer = open(dir)
            searchers = SearcherManager(writer, SearcherFactory())
        }
        return count
    }

    fun update(documents: List<SearchDocument>, removed: Collection<ItemKey>) = lock.withLock {
        listOfNotNull(writer, building).forEach { w ->
            documents.forEach { w.updateDocuments(idTerm(it.key), documents(it)) }
            removed.forEach { w.deleteDocuments(idTerm(it)) }
        }
        writer.commit()
        searchers.maybeRefresh()
    }

    /** Runs [action] with a searcher of the index as it is now. */
    fun <T> search(action: (IndexSearcher) -> T): T {
        val manager = lock.withLock { searchers }
        val searcher = manager.acquire()
        try {
            return action(searcher)
        } finally {
            manager.release(searcher)
        }
    }

    /** The text fields of the index with their weights, and whether it has passages' vectors. */
    fun fields(searcher: IndexSearcher): Fields {
        val names = FieldInfos.getMergedFieldInfos(searcher.indexReader).map { it.name }
        val text = names.mapNotNull(::textField).sortedByDescending { it.weight }
        return Fields(text, VECTOR in names)
    }

    class Fields(val text: List<IndexedField>, val vectors: Boolean) {
        /** Of the fields searched alone: not those of weight 0, the work's own words kept for the counting of names. */
        fun searched() = Fields(text.filter { it.weight > 0f }, vectors)
    }

    /** How many items the index has: their documents of fields, not their passages. */
    fun items(searcher: IndexSearcher): Int = searcher.count(TermQuery(Term(KIND, ITEM)))

    /** A text field of the index: its name there, its key in the documents, its weight, whether it names the item, whether it is the work's own words. */
    data class IndexedField(val name: String, val key: String, val weight: Float, val naming: Boolean, val counted: Boolean)

    override fun close() = lock.withLock {
        searchers.close()
        writer.close()
    }

    companion object {
        const val ID = "id"

        /** What a document is: an item's fields ([ITEM]) or a passage of it ([PASSAGE]). */
        private const val KIND = "kind"
        private const val ITEM = "item"
        private const val PASSAGE = "passage"

        /** The vector of a passage. */
        const val VECTOR = "v"

        /** The languages an item's texts were stemmed as, by a comma. */
        const val LANGUAGES = "languages"

        /** What an item's document is: the query of the documents of items alone, not of their passages. */
        val ITEMS = TermQuery(Term(KIND, ITEM))

        /** The languages the own words of [doc] are stemmed as ([TextLanguages]), in order. */
        fun languagesOf(doc: SearchDocument): List<String> =
            doc.fields.flatMap { f -> f.values.filter { it.isNotBlank() }.map { TextLanguages.of(it, doc.language) } }.distinct().sorted()

        /** Where a passage is: its field's key, its characters from and until in the field's text. */
        const val PASSAGE_FIELD = "p.field"
        const val PASSAGE_FROM = "p.from"
        const val PASSAGE_TO = "p.to"

        /**
         * The writer's: the texts come stemmed already, each as its language ([documents]); what it
         * tells is the gap between two values of one field — two tags, two attributes are not one
         * phrase — and it stems as English what is ever given it unstemmed.
         */
        val analyzer: Analyzer = Stemmers.analyzer(Stemmers.DEFAULT)

        /** The positions between two values of one field — two tags — farther than any phrase reaches. */
        const val VALUE_GAP = 100

        /** The words of a [text] of the [language] as the index has them: their stems. */
        fun tokens(text: String, language: String): List<String> = tokens(text, Stemmers.analyzer(language))

        /** The words of a [text] as the [analyzer] of its language reads them: of many texts, one analyzer for them all. */
        fun tokens(text: String, analyzer: Analyzer): List<String> {
            val out = ArrayList<String>()
            analyzer.tokenStream("", text).use { stream ->
                val term = stream.addAttribute(CharTermAttribute::class.java)
                stream.reset()
                while (stream.incrementToken()) out += term.toString()
                stream.end()
            }
            return out
        }

        fun fieldName(field: SearchField): String =
            ((if (field.naming) "n" else "") + (if (field.counted) "c" else "")).ifEmpty { "t" } + "." + Math.round(field.weight * 100) + "." + field.group

        private val MARKS = Regex("[nct]+")

        private fun textField(name: String): IndexedField? {
            val parts = name.split('.', limit = 3)
            if (parts.size < 3 || !MARKS.matches(parts[0])) return null
            val weight = parts[1].toIntOrNull() ?: return null
            return IndexedField(name, parts[2], weight / 100f, 'n' in parts[0], 'c' in parts[0])
        }

        private fun idTerm(key: ItemKey) = Term(ID, key.toString())

        private fun open(dir: Path): IndexWriter {
            if (!dir.exists()) Files.createDirectories(dir)
            return IndexWriter(FSDirectory.open(dir), IndexWriterConfig(analyzer).setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND))
        }

        /** The documents of an item: of its fields, and of every passage of it with a vector. */
        private fun documents(doc: SearchDocument): List<Document> {
            val id = doc.key.toString()
            // Every value stemmed as its language: a stream of its own each, of an analyzer that reuses none
            val stemmers = HashMap<String, Analyzer>()
            val used = sortedSetOf<String>()
            val fields = Document().apply {
                add(StringField(ID, id, Field.Store.YES))
                add(StringField(KIND, ITEM, Field.Store.NO))
                doc.fields.forEach { field ->
                    val name = fieldName(field)
                    field.values.filter { it.isNotBlank() }.forEach { value ->
                        val language = TextLanguages.of(value, doc.language).also { used += it }
                        val stream = stemmers.getOrPut(language) { Stemmers.analyzer(language, reuse = false) }.tokenStream(name, value)
                        // The work's own words with the marks their paragraphs begin with: two names in one paragraph are told by them
                        add(TextField(name, if (field.counted) ParagraphMarks(stream, value) else stream))
                    }
                }
                // The languages its texts were stemmed as: what a name looked for in them is read as
                add(StoredField(LANGUAGES, used.joinToString(",")))
            }
            val passages = doc.passages.mapNotNull { p ->
                val vector = p.vector ?: return@mapNotNull null
                // A piece of no phrase — "-", a changelog of dates — says nothing, and its vector lies
                // near every query: the same few items were found by meaning whatever was looked for
                val text = p.text(doc) ?: return@mapNotNull null
                if (Phrases.split(text).isEmpty()) return@mapNotNull null
                Document().apply {
                    add(StringField(ID, id, Field.Store.YES))
                    add(StringField(KIND, PASSAGE, Field.Store.NO))
                    add(StoredField(PASSAGE_FIELD, p.field))
                    add(StoredField(PASSAGE_FROM, p.from))
                    add(StoredField(PASSAGE_TO, p.to))
                    add(KnnFloatVectorField(VECTOR, unit(vector), VectorSimilarityFunction.DOT_PRODUCT))
                }
            }
            return listOf(fields) + passages
        }

        /** A dot product needs unit vectors; the stored ones are, up to the rounding of a float. */
        fun unit(v: FloatArray): FloatArray {
            var n = 0.0
            for (x in v) n += x.toDouble() * x
            n = kotlin.math.sqrt(n)
            return if (n > 1e-9) FloatArray(v.size) { (v[it] / n).toFloat() } else v
        }
    }
}
