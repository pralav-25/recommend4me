package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.LowerCaseFilter
import org.apache.lucene.analysis.TokenFilter
import org.apache.lucene.analysis.TokenStream
import org.apache.lucene.analysis.ar.ArabicAnalyzer
import org.apache.lucene.analysis.ar.ArabicNormalizationFilter
import org.apache.lucene.analysis.ar.ArabicStemFilter
import org.apache.lucene.analysis.bg.BulgarianAnalyzer
import org.apache.lucene.analysis.bg.BulgarianStemFilter
import org.apache.lucene.analysis.bn.BengaliAnalyzer
import org.apache.lucene.analysis.bn.BengaliNormalizationFilter
import org.apache.lucene.analysis.bn.BengaliStemFilter
import org.apache.lucene.analysis.ca.CatalanAnalyzer
import org.apache.lucene.analysis.cz.CzechAnalyzer
import org.apache.lucene.analysis.cz.CzechStemFilter
import org.apache.lucene.analysis.da.DanishAnalyzer
import org.apache.lucene.analysis.de.GermanAnalyzer
import org.apache.lucene.analysis.de.GermanLightStemFilter
import org.apache.lucene.analysis.de.GermanNormalizationFilter
import org.apache.lucene.analysis.el.GreekAnalyzer
import org.apache.lucene.analysis.el.GreekLowerCaseFilter
import org.apache.lucene.analysis.el.GreekStemFilter
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.en.EnglishPossessiveFilter
import org.apache.lucene.analysis.en.PorterStemFilter
import org.apache.lucene.analysis.es.SpanishAnalyzer
import org.apache.lucene.analysis.es.SpanishLightStemFilter
import org.apache.lucene.analysis.et.EstonianAnalyzer
import org.apache.lucene.analysis.eu.BasqueAnalyzer
import org.apache.lucene.analysis.fa.PersianAnalyzer
import org.apache.lucene.analysis.fa.PersianNormalizationFilter
import org.apache.lucene.analysis.fa.PersianStemFilter
import org.apache.lucene.analysis.fi.FinnishAnalyzer
import org.apache.lucene.analysis.fi.FinnishLightStemFilter
import org.apache.lucene.analysis.fr.FrenchAnalyzer
import org.apache.lucene.analysis.fr.FrenchLightStemFilter
import org.apache.lucene.analysis.ga.IrishAnalyzer
import org.apache.lucene.analysis.ga.IrishLowerCaseFilter
import org.apache.lucene.analysis.gl.GalicianAnalyzer
import org.apache.lucene.analysis.gl.GalicianStemFilter
import org.apache.lucene.analysis.hi.HindiAnalyzer
import org.apache.lucene.analysis.hi.HindiNormalizationFilter
import org.apache.lucene.analysis.hi.HindiStemFilter
import org.apache.lucene.analysis.hu.HungarianAnalyzer
import org.apache.lucene.analysis.hu.HungarianLightStemFilter
import org.apache.lucene.analysis.hy.ArmenianAnalyzer
import org.apache.lucene.analysis.id.IndonesianAnalyzer
import org.apache.lucene.analysis.id.IndonesianStemFilter
import org.apache.lucene.analysis.`in`.IndicNormalizationFilter
import org.apache.lucene.analysis.it.ItalianAnalyzer
import org.apache.lucene.analysis.it.ItalianLightStemFilter
import org.apache.lucene.analysis.lt.LithuanianAnalyzer
import org.apache.lucene.analysis.lv.LatvianAnalyzer
import org.apache.lucene.analysis.lv.LatvianStemFilter
import org.apache.lucene.analysis.miscellaneous.ASCIIFoldingFilter
import org.apache.lucene.analysis.ne.NepaliAnalyzer
import org.apache.lucene.analysis.nl.DutchAnalyzer
import org.apache.lucene.analysis.no.NorwegianAnalyzer
import org.apache.lucene.analysis.no.NorwegianLightStemFilter
import org.apache.lucene.analysis.pt.PortugueseAnalyzer
import org.apache.lucene.analysis.pt.PortugueseLightStemFilter
import org.apache.lucene.analysis.ro.RomanianAnalyzer
import org.apache.lucene.analysis.ru.RussianAnalyzer
import org.apache.lucene.analysis.ru.RussianLightStemFilter
import org.apache.lucene.analysis.snowball.SnowballFilter
import org.apache.lucene.analysis.sr.SerbianAnalyzer
import org.apache.lucene.analysis.sr.SerbianNormalizationFilter
import org.apache.lucene.analysis.standard.StandardTokenizer
import org.apache.lucene.analysis.sv.SwedishAnalyzer
import org.apache.lucene.analysis.sv.SwedishLightStemFilter
import org.apache.lucene.analysis.ta.TamilAnalyzer
import org.apache.lucene.analysis.te.TeluguAnalyzer
import org.apache.lucene.analysis.te.TeluguNormalizationFilter
import org.apache.lucene.analysis.te.TeluguStemFilter
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import org.apache.lucene.analysis.tr.ApostropheFilter
import org.apache.lucene.analysis.tr.TurkishAnalyzer
import org.apache.lucene.analysis.tr.TurkishLowerCaseFilter
import org.apache.lucene.util.BytesRef
import org.tartarus.snowball.ext.ArmenianStemmer
import org.tartarus.snowball.ext.BasqueStemmer
import org.tartarus.snowball.ext.CatalanStemmer
import org.tartarus.snowball.ext.DanishStemmer
import org.tartarus.snowball.ext.DutchStemmer
import org.tartarus.snowball.ext.EstonianStemmer
import org.tartarus.snowball.ext.IrishStemmer
import org.tartarus.snowball.ext.LithuanianStemmer
import org.tartarus.snowball.ext.NepaliStemmer
import org.tartarus.snowball.ext.RomanianStemmer
import org.tartarus.snowball.ext.SerbianStemmer
import org.tartarus.snowball.ext.TamilStemmer
import org.tartarus.snowball.ext.TurkishStemmer
import org.tartarus.snowball.ext.YiddishStemmer

/**
 * EVERY STEMMER LUCENE KNOWS, by the language's code (ISO 639-1): what a text of the language is
 * read by before it is put in the index — its words lower-cased as the language does it, normalized
 * as it writes them, their endings taken off ("драконы", "драконов", "дракон" one word) — and what
 * a line searched or a name looked for in a text of it is read by (user, 2026-10-16). A language of
 * none, English ([DEFAULT]).
 *
 * The chain of each is its own analyzer's of Lucene but the stop words: every word stays, at its
 * place — a name is found by its words in a row, and a function word may be a word of a name; the
 * function words are told apart where they matter ([stopWords]).
 */
object Stemmers {
    /** The stemmer of a text of a language none is known of. */
    const val DEFAULT = "en"

    /** A language's chain after the tokenizer, and its function words. */
    private class Language(val chain: (TokenStream) -> TokenStream, val stops: () -> CharArraySet)

    private fun snowball(lower: (TokenStream) -> TokenStream = ::LowerCaseFilter, stemmer: () -> org.tartarus.snowball.SnowballStemmer): (TokenStream) -> TokenStream =
        { SnowballFilter(lower(it), stemmer()) }

    private val LANGUAGES: Map<String, Language> = mapOf(
        "ar" to Language({ ArabicStemFilter(ArabicNormalizationFilter(LowerCaseFilter(it))) }, ArabicAnalyzer::getDefaultStopSet),
        "bg" to Language({ BulgarianStemFilter(LowerCaseFilter(it)) }, BulgarianAnalyzer::getDefaultStopSet),
        "bn" to Language({ BengaliStemFilter(BengaliNormalizationFilter(IndicNormalizationFilter(LowerCaseFilter(it)))) }, BengaliAnalyzer::getDefaultStopSet),
        "ca" to Language(snowball { CatalanStemmer() }, CatalanAnalyzer::getDefaultStopSet),
        "cs" to Language({ CzechStemFilter(LowerCaseFilter(it)) }, CzechAnalyzer::getDefaultStopSet),
        "da" to Language(snowball { DanishStemmer() }, DanishAnalyzer::getDefaultStopSet),
        "de" to Language({ GermanLightStemFilter(GermanNormalizationFilter(LowerCaseFilter(it))) }, GermanAnalyzer::getDefaultStopSet),
        "el" to Language({ GreekStemFilter(GreekLowerCaseFilter(it)) }, GreekAnalyzer::getDefaultStopSet),
        // Irregular verbs read as their base after the stemmer: "stolen" as "steal"; accents folded, as English writes borrowed words
        "en" to Language({ IrregularVerbs.filter(PorterStemFilter(EnglishPossessiveFilter(ASCIIFoldingFilter(LowerCaseFilter(it))))) }, { EnglishAnalyzer.ENGLISH_STOP_WORDS_SET }),
        "es" to Language({ SpanishLightStemFilter(LowerCaseFilter(it)) }, SpanishAnalyzer::getDefaultStopSet),
        "et" to Language(snowball { EstonianStemmer() }, EstonianAnalyzer::getDefaultStopSet),
        "eu" to Language(snowball { BasqueStemmer() }, BasqueAnalyzer::getDefaultStopSet),
        "fa" to Language({ PersianStemFilter(PersianNormalizationFilter(ArabicNormalizationFilter(LowerCaseFilter(it)))) }, PersianAnalyzer::getDefaultStopSet),
        "fi" to Language({ FinnishLightStemFilter(LowerCaseFilter(it)) }, FinnishAnalyzer::getDefaultStopSet),
        "fr" to Language({ FrenchLightStemFilter(LowerCaseFilter(it)) }, FrenchAnalyzer::getDefaultStopSet),
        "ga" to Language(snowball(::IrishLowerCaseFilter) { IrishStemmer() }, IrishAnalyzer::getDefaultStopSet),
        "gl" to Language({ GalicianStemFilter(LowerCaseFilter(it)) }, GalicianAnalyzer::getDefaultStopSet),
        "hi" to Language({ HindiStemFilter(HindiNormalizationFilter(IndicNormalizationFilter(LowerCaseFilter(it)))) }, HindiAnalyzer::getDefaultStopSet),
        "hu" to Language({ HungarianLightStemFilter(LowerCaseFilter(it)) }, HungarianAnalyzer::getDefaultStopSet),
        "hy" to Language(snowball { ArmenianStemmer() }, ArmenianAnalyzer::getDefaultStopSet),
        "id" to Language({ IndonesianStemFilter(LowerCaseFilter(it)) }, IndonesianAnalyzer::getDefaultStopSet),
        "it" to Language({ ItalianLightStemFilter(LowerCaseFilter(it)) }, ItalianAnalyzer::getDefaultStopSet),
        "lt" to Language(snowball { LithuanianStemmer() }, LithuanianAnalyzer::getDefaultStopSet),
        "lv" to Language({ LatvianStemFilter(LowerCaseFilter(it)) }, LatvianAnalyzer::getDefaultStopSet),
        "ne" to Language(snowball { NepaliStemmer() }, NepaliAnalyzer::getDefaultStopSet),
        "nl" to Language(snowball { DutchStemmer() }, DutchAnalyzer::getDefaultStopSet),
        "no" to Language({ NorwegianLightStemFilter(LowerCaseFilter(it)) }, NorwegianAnalyzer::getDefaultStopSet),
        "pt" to Language({ PortugueseLightStemFilter(LowerCaseFilter(it)) }, PortugueseAnalyzer::getDefaultStopSet),
        "ro" to Language(snowball { RomanianStemmer() }, RomanianAnalyzer::getDefaultStopSet),
        // 'ё' read as 'е', as Russian writes it most of the time
        "ru" to Language({ RussianLightStemFilter(Yo(LowerCaseFilter(it))) }, RussianAnalyzer::getDefaultStopSet),
        "sr" to Language(snowball({ SerbianNormalizationFilter(LowerCaseFilter(it)) }) { SerbianStemmer() }, SerbianAnalyzer::getDefaultStopSet),
        "sv" to Language({ SwedishLightStemFilter(LowerCaseFilter(it)) }, SwedishAnalyzer::getDefaultStopSet),
        "ta" to Language(snowball { TamilStemmer() }, TamilAnalyzer::getDefaultStopSet),
        "te" to Language({ TeluguStemFilter(TeluguNormalizationFilter(LowerCaseFilter(it))) }, TeluguAnalyzer::getDefaultStopSet),
        "tr" to Language(snowball({ TurkishLowerCaseFilter(ApostropheFilter(it)) }) { TurkishStemmer() }, TurkishAnalyzer::getDefaultStopSet),
        "yi" to Language(snowball { YiddishStemmer() }, { CharArraySet.EMPTY_SET }),
    )

    /** Norwegian of either written standard by the one stemmer. */
    private val ALIASES = mapOf("nb" to "no", "nn" to "no")

    /** Every language a stemmer is known of, by its code. */
    val languages: Set<String> get() = LANGUAGES.keys + ALIASES.keys

    /** The language a text of [language] is stemmed as: itself when a stemmer is known of it, else [DEFAULT]. */
    fun of(language: String?): String = language?.let { ALIASES[it] ?: it }?.takeIf { it in LANGUAGES } ?: DEFAULT

    /** The function words of the [language] ([of]), lower-cased. */
    fun stopWords(language: String): CharArraySet = LANGUAGES.getValue(of(language)).stops()

    /**
     * The analyzer of a text of the [language] ([of]): its words, each marked with the payload
     * [CAPITAL] when written with a capital ([Capitals]), stemmed by the language's chain; every
     * value of a field farther from the next than any name reaches ([LuceneIndex.VALUE_GAP]).
     */
    fun analyzer(language: String, reuse: Boolean = true): Analyzer {
        val chain = LANGUAGES.getValue(of(language)).chain
        return object : Analyzer(if (reuse) GLOBAL_REUSE_STRATEGY else NO_REUSE) {
            override fun createComponents(fieldName: String): TokenStreamComponents {
                val source = StandardTokenizer()
                return TokenStreamComponents(source, chain(Capitals(source)))
            }

            override fun getPositionIncrementGap(fieldName: String): Int = LuceneIndex.VALUE_GAP
        }
    }

    /**
     * A stream of its own of every call: the values of one field of a document are given the writer
     * as streams made before it reads any — streams reused would read the last value only.
     */
    private val NO_REUSE = object : Analyzer.ReuseStrategy() {
        override fun getReusableComponents(analyzer: Analyzer, fieldName: String): Analyzer.TokenStreamComponents? = null

        override fun setReusableComponents(analyzer: Analyzer, fieldName: String, components: Analyzer.TokenStreamComponents) {}
    }

    /** The payload of a word written with a capital: one byte, 1. */
    val CAPITAL = BytesRef(byteArrayOf(1))

    /** Marks every word written with a capital by the payload [CAPITAL]: a name of one word counts only so ("Captain" names, "captain" not). */
    private class Capitals(input: TokenStream) : TokenFilter(input) {
        private val term = addAttribute(CharTermAttribute::class.java)
        private val payload = addAttribute(PayloadAttribute::class.java)

        override fun incrementToken(): Boolean {
            if (!input.incrementToken()) return false
            payload.payload = if (term.isNotEmpty() && Character.isUpperCase(Character.codePointAt(term, 0))) CAPITAL else null
            return true
        }
    }

    /** 'ё' as 'е', in place. */
    private class Yo(input: TokenStream) : TokenFilter(input) {
        private val term = addAttribute(CharTermAttribute::class.java)

        override fun incrementToken(): Boolean {
            if (!input.incrementToken()) return false
            val chars = term.buffer()
            for (i in 0 until term.length) if (chars[i] == 'ё') chars[i] = 'е'
            return true
        }
    }
}
