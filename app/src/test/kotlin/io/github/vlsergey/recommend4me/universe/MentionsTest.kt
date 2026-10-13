package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.work.TextPlace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MentionsTest {

    private val mentions = Mentions(
        mapOf(
            "harry" to listOf("Гарри Поттер", "Harry Potter"),
            "hermione" to listOf("Гермиона Грейнджер", "Hermione Granger"),
            "james" to listOf("Джеймс Поттер"),
            "ron" to listOf("Рон Уизли"),
            "ginny" to listOf("Джинни Уизли", "Джиневра Уизли"),
        ),
    )

    @Test
    fun `a character is found however its name is declined, by a word of the name only it has`() {
        val counts = mentions.count(sequenceOf("Гарри посмотрел на Гермиону. Гермионы не было. Гарри Поттер ушёл к Джинни."))
        assertEquals(2, counts["harry"])
        assertEquals(2, counts["hermione"])
        assertEquals(1, counts["ginny"])
    }

    @Test
    fun `a family name tells nobody by itself`() {
        val counts = mentions.count(sequenceOf("Поттер и Уизли вошли. Джеймс Поттер остался."))
        assertNull(counts["harry"])
        assertNull(counts["ron"])
        assertEquals(1, counts["james"])
    }

    @Test
    fun `a word of a name alone is a name only with a capital and when it is no function word`() {
        val droids = Mentions(mapOf("c3po" to listOf("Си-Три-Пи-О"), "saw" to listOf("Со Геррера")))
        val counts = droids.count(sequenceOf("Пришёл со мной о чём-то. Со Геррера и Пи. пи равно трём."))
        assertEquals(1, counts["saw"])
        assertEquals(1, counts["c3po"])
        val nova = Mentions(mapOf("rick" to listOf("Рик Джонс", "Нова")))
        assertEquals(1, nova.count(sequenceOf("Однако новый мир — новые правила. Нова улетел."))["rick"])
    }

    @Test
    fun `characters named in one paragraph are together`() {
        val together = mentions.together(sequenceOf("Гарри поцеловал Гермиону.\n\nРон ел.\nГермиона и Рон спорили."))
        assertEquals(1, together["harry" to "hermione"])
        assertEquals(1, together["hermione" to "ron"])
        assertNull(together["harry" to "ron"])
    }

    @Test
    fun `a quote marks the names of the value where the text has them, a declined one and one with ё too`() {
        val text = WorkText(TextPlace(part = 1), "Гарри посмотрел на Гермиону.\nРон ел.\nЁлка, Гермиона и Гарри Поттер.")
        val quotes = Naming(mentions).quotes(sequenceOf(text), "hermione")
        assertEquals(listOf("Гарри посмотрел на Гермиону.", "Ёлка, Гермиона и Гарри Поттер."), quotes.map { it.text })
        assertEquals(listOf("Гермиону"), quotes[0].highlights.map { quotes[0].text.substring(it) })
        assertEquals(listOf("Гермиона"), quotes[1].highlights.map { quotes[1].text.substring(it) })
        assertEquals(1, quotes[0].place.part)
    }

    @Test
    fun `a pairing is quoted by the paragraphs naming its two, both marked`() {
        val pairing = { a: String, b: String -> listOf(a, b).sorted().joinToString("|") }
        val text = WorkText(TextPlace(labelKey = "test:text.description"), "Гарри поцеловал Гермиону.\n\nРон ел.\nГермиона и Рон спорили.")
        val quotes = Naming(mentions, pairing = pairing).quotes(sequenceOf(text), "hermione|ron")
        assertEquals(listOf("Гермиона и Рон спорили."), quotes.map { it.text })
        assertEquals(listOf("Гермиона", "Рон"), quotes[0].highlights.map { quotes[0].text.substring(it) })
    }

    @Test
    fun `a long paragraph is cut around the names`() {
        val filler = "слово ".repeat(200)
        val quotes = Naming(mentions).quotes(sequenceOf(WorkText(TextPlace(part = 2), filler + "Гермиона пришла. " + filler)), "hermione")
        val quote = quotes.single()
        assert(quote.text.length < 500) { quote.text }
        assert(quote.text.startsWith("… ") && quote.text.endsWith(" …")) { quote.text }
        assertEquals(listOf("Гермиона"), quote.highlights.map { quote.text.substring(it) })
    }
}
