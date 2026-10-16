package io.github.vlsergey.recommend4me.search

import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.PayloadAttribute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StemmersTest {

    /** The words of [text] as the [language]'s analyzer gives them, each with whether it is marked written with a capital. */
    private fun words(language: String, text: String): List<Pair<String, Boolean>> = Stemmers.analyzer(language).tokenStream("t", text).use { stream ->
        val term = stream.addAttribute(CharTermAttribute::class.java)
        val payload = stream.addAttribute(PayloadAttribute::class.java)
        val out = ArrayList<Pair<String, Boolean>>()
        stream.reset()
        while (stream.incrementToken()) out += term.toString() to (payload.payload == Stemmers.CAPITAL)
        stream.end()
        out
    }

    @Test
    fun `every language's stemmer reads a text, and a language of none is read as English`() {
        Stemmers.languages.forEach { language -> assertTrue(words(language, "Words of a text, read").isNotEmpty(), language) }
        assertEquals("en", Stemmers.of("zh"))
        assertEquals("en", Stemmers.of(null))
        assertEquals("no", Stemmers.of("nb"))
    }

    @Test
    fun `a word is stemmed by its language, its capital kept beside it`() {
        assertEquals(listOf("дракон" to true, "дракон" to false, "елк" to false), words("ru", "Драконы драконов ёлки"))
        assertEquals(listOf("steal" to true, "steal" to false, "dragon" to false), words("en", "Stolen steals dragons"))
        assertEquals(words("de", "Häuser").single().first, words("de", "Haus").single().first)
    }

    @Test
    fun `a long text tells its own language, a short one takes the work's`() {
        val russian = "Дракон прилетел в деревню на рассвете, и жители проснулись от его рёва. Старый кузнец вышел первым, " +
            "держа в руках молот, а за ним потянулись остальные. Никто не знал, чего хочет зверь, но все понимали, что " +
            "сегодня всё изменится навсегда, и прежняя жизнь уже не вернётся, как бы они ни старались её удержать."
        assertEquals("ru", TextLanguages.of(russian, "en"))
        assertEquals("en", TextLanguages.of("Дракон и кузнец", "en"))
        assertEquals("ru", TextLanguages.of("The dragon", "ru"))
    }
}
