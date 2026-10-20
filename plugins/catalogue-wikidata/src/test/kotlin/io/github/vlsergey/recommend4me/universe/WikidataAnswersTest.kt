package io.github.vlsergey.recommend4me.universe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import tools.jackson.databind.json.JsonMapper
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the catalogue makes of Wikidata's answers, without Wikidata: a local server answers as its
 * API does, and remembers what it was asked.
 */
class WikidataAnswersTest {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val asked = Collections.synchronizedList(ArrayList<Map<String, String>>())

    /** The answer to the next request, before the usual one: a status and its headers. */
    private val failures = Collections.synchronizedList(ArrayList<Pair<Int, Map<String, String>>>())

    private lateinit var catalogue: WikidataCatalogue

    @BeforeTest
    fun start() {
        server.createContext("/w/api.php") { exchange -> answer(exchange) }
        server.start()
        catalogue = WikidataCatalogue(JsonMapper.builder().build(), api = "http://127.0.0.1:${server.address.port}/w/api.php")
    }

    @AfterTest
    fun stop() = server.stop(0)

    private fun answer(exchange: HttpExchange) {
        val query = exchange.requestURI.rawQuery.split('&').associate { p ->
            p.substringBefore('=') to URLDecoder.decode(p.substringAfter('=', ""), Charsets.UTF_8)
        }
        asked += query
        failures.removeFirstOrNull()?.let { (status, headers) ->
            headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
            return
        }
        val body = when (query["action"]) {
            "wbgetentities" -> entities(query.getValue("ids").split('|'), claims = query["props"] == "claims")
            "wbsearchentities" -> """{"search":[{"id":"Q2"},{"id":"Q3"}]}"""
            else -> "{}"
        }
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    /**
     * The entities asked: Q404 is missing; every other has an English and a Russian label, an alias,
     * a description, and its classes — Q10 is a subclass of Q11 and, deprecated, of Q12, a fictional
     * analog of Q13, and of itself.
     */
    private fun entities(ids: List<String>, claims: Boolean): String = ids.joinToString(",", """{"entities":{""", "}}") { id ->
        if (id == "Q404") return@joinToString """"$id":{"id":"$id","missing":""}"""
        if (claims) {
            fun claim(property: String, value: String, rank: String = "normal") =
                """{"rank":"$rank","mainsnak":{"property":"$property","datavalue":{"value":{"id":"$value"}}}}"""
            val own = if (id == "Q10") {
                """"P279":[${claim("P279", "Q11")},${claim("P279", "Q12", "deprecated")},${claim("P279", "Q10")}],"P1074":[${claim("P1074", "Q13")}],"P31":[${claim("P31", "Q99")}]"""
            } else ""
            """"$id":{"id":"$id","claims":{$own}}"""
        } else {
            """"$id":{"id":"$id","labels":{"en":{"value":"Thing $id"},"ru":{"value":"Вещь $id"}},"aliases":{"en":[{"value":"Alias $id"}]},"descriptions":{"en":{"value":"About $id"}}}"""
        }
    }

    private val languages = listOf("ru", "en")

    @Test
    fun `an entry is read with its names in every language asked, a missing one left out, in the order asked`() {
        val read = catalogue.entries(listOf("Q2", "Q404", "Q1", "not an id"), languages)
        assertEquals(listOf("Q2", "Q1"), read.map { it.id })
        val q2 = read.first()
        assertEquals(mapOf("ru" to "Вещь Q2", "en" to "Thing Q2"), q2.labels)
        assertEquals(mapOf("en" to listOf("Alias Q2")), q2.aliases)
        assertEquals(mapOf("en" to "About Q2"), q2.descriptions)
        assertEquals("https://www.wikidata.org/wiki/Q2", q2.url)
    }

    @Test
    fun `entries are asked fifty at a time, the API's limit`() {
        val ids = (1..120).map { "Q$it" }
        assertEquals(ids, catalogue.entries(ids, languages).map { it.id })
        assertEquals(listOf(50, 50, 20), asked.map { it.getValue("ids").split('|').size })
    }

    @Test
    fun `a Q-id or an entry's address is the entry itself, any other text is searched for`() {
        assertEquals(listOf("Q42"), catalogue.find("Q42", languages).map { it.id })
        assertEquals(listOf("Q42"), catalogue.find("https://www.wikidata.org/wiki/Q42", languages).map { it.id })
        assertTrue(asked.none { it["action"] == "wbsearchentities" })
        assertEquals(listOf("Q2", "Q3"), catalogue.find("thing", languages).map { it.id })
        // Searched in every language, the entries found read once
        assertEquals(languages, asked.filter { it["action"] == "wbsearchentities" }.map { it["language"] })
    }

    @Test
    fun `what an entry is a kind of — its classes and what it is a fictional analog of, never what it is an instance of, nor a deprecated class, nor itself`() {
        assertEquals(mapOf("Q10" to listOf("Q11", "Q13")), catalogue.above(listOf("Q10", "Q20")))
    }

    @Test
    fun `a version of a character based on it, of its sex and going by its name, is not an entry of its own`() {
        fun character(id: String, label: String, sex: CharacterSex?, aliases: List<String> = emptyList()) =
            UniverseCharacter(id, mapOf("en" to label), mapOf("en" to aliases), emptyMap(), "", languages, sex = sex)
        val comics = character("Q945817", "Gwen Stacy", CharacterSex.FEMALE)
        val films = character("Q98454800", "Gwen Stacy", CharacterSex.FEMALE)
        val spiderWoman = character("Q20979395", "Spider-Woman", CharacterSex.FEMALE, listOf("Gwen Stacy"))
        val verse = character("Q119806001", "Gwen Stacy", CharacterSex.FEMALE)
        val renamed = character("Q1", "Ghost-Spider", CharacterSex.FEMALE)
        val otherSex = character("Q2", "Gwen Stacy", CharacterSex.MALE)
        val elsewhere = character("Q3", "Gwen Stacy", CharacterSex.FEMALE)
        val kept = WikidataCatalogue.withoutVersions(
            listOf(comics, films, spiderWoman, verse, renamed, otherSex, elsewhere),
            mapOf(
                "Q98454800" to setOf("Q945817"), "Q119806001" to setOf("Q20979395"),
                "Q1" to setOf("Q945817"), "Q2" to setOf("Q945817"), "Q3" to setOf("Q404"),
            ),
        )
        // The films' and the Spider-Verse's Gwen go by the names of the ones they are based on; a version
        // of another name, another sex, or based on an entry outside the universe stays
        assertEquals(listOf("Q945817", "Q20979395", "Q1", "Q2", "Q3"), kept.map { it.id })
    }

    @Test
    fun `asked too often, the request is sent again after the wait the API asks for, any other failure fails`() {
        failures += 429 to mapOf("Retry-After" to "0")
        assertEquals(listOf("Q2"), catalogue.entries(listOf("Q2"), languages).map { it.id })
        assertEquals(2, asked.size)

        failures += 500 to emptyMap()
        assertFailsWith<IllegalStateException> { catalogue.entries(listOf("Q2"), languages) }
        // A 429 without a wait to keep fails too
        failures += 429 to emptyMap()
        assertFailsWith<IllegalStateException> { catalogue.entries(listOf("Q2"), languages) }
    }
}
