package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.work.TextPlace
import kotlin.test.Test
import kotlin.test.assertEquals

class QuotesTest {

    @Test
    fun `a quote is the paragraph of the names, a long one cut around them`() {
        val text = "Гарри посмотрел на Гермиону.\nРон ел."
        val short = Quotes.of(TextPlace(part = 1), text, listOf(19..26))
        assertEquals(listOf("Гарри посмотрел на Гермиону."), short.map { it.text })
        assertEquals(listOf("Гермиону"), short.single().highlights.map { short.single().text.substring(it) })
        assertEquals(1, short.single().place.part)

        val filler = "слово ".repeat(200)
        val long = filler + "Гермиона пришла. " + filler
        val quote = Quotes.of(TextPlace(part = 2), long, listOf(filler.length until filler.length + "Гермиона".length)).single()
        assert(quote.text.length < 500) { quote.text }
        assert(quote.text.startsWith("… ") && quote.text.endsWith(" …")) { quote.text }
        assertEquals(listOf("Гермиона"), quote.highlights.map { quote.text.substring(it) })
    }
}
