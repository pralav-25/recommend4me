package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.CardsRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.correction.CorrectionsController
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.encoder.TextKind
import io.github.vlsergey.recommend4me.item.ItemsController
import io.github.vlsergey.recommend4me.model.MeasuringState
import io.github.vlsergey.recommend4me.model.Recommendations
import io.github.vlsergey.recommend4me.model.ScorerComparisons
import io.github.vlsergey.recommend4me.page.PagesController
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.CardDecor
import io.github.vlsergey.recommend4me.source.FacetDecor
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.PageDecor
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.suggestion.FacetSuggestions
import io.github.vlsergey.recommend4me.suggestion.SuggestionsController
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.api.model.Inclusion
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.universe.UniverseCatalogue
import io.github.vlsergey.recommend4me.universe.UniverseCharacter
import io.github.vlsergey.recommend4me.universe.UniverseClass
import io.github.vlsergey.recommend4me.universe.UniverseEntry
import io.github.vlsergey.recommend4me.universe.UniversesController
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The site as the application's interface: the extension's view of a work's page and of a list,
 * the suggested tags confirmed and rejected, a value added by its name, the works found for a link.
 */
@SpringBootTest(properties = ["recommend4me.data-dir=build/test-data/site"])
class SiteTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun clean() {
            File("build/test-data/site").deleteRecursively()
        }
    }

    @TestConfiguration
    class Plugins {
        @Bean
        fun siteSource(): Source = SiteSource()

        @Bean
        fun wordsEncoder(): TextEncoder = WordsEncoder()

        @Bean
        fun fakeCatalogue(): UniverseCatalogue = FakeCatalogue()
    }

    /** A catalogue of two universes of space opera, each with the same two characters and a station, a place; its texts in English. */
    class FakeCatalogue : UniverseCatalogue {
        override val id = "fake"
        override val title = "Fake"
        private fun en(text: String) = mapOf("en" to text)
        private fun entries(languages: List<String>) = listOf(
            UniverseEntry("U1", en("Space opera"), en("Ships and pilots"), "https://fake.example/U1", languages),
            UniverseEntry("U2", en("Fleet"), en("More ships"), "https://fake.example/U2", languages),
        )

        override fun findUniverses(name: String, languages: List<String>) = if ("space" in name.lowercase()) entries(languages).take(1) else emptyList()
        override fun universe(id: String, languages: List<String>) = entries(languages).firstOrNull { it.id == id }
        override fun characters(id: String, languages: List<String>) = listOf(
            UniverseCharacter("C1", en("Pilot"), mapOf("en" to listOf("The Pilot")), emptyMap(), "https://fake.example/C1", languages, listOf("K1")),
            UniverseCharacter("C2", en("Captain"), emptyMap(), en("Of the fleet"), "https://fake.example/C2", languages, listOf("K1")),
            UniverseCharacter("S1", en("Station"), emptyMap(), en("Where ships dock"), "https://fake.example/S1", languages, listOf("K2")),
        )

        override fun classes(ids: Collection<String>, languages: List<String>) =
            listOf(UniverseClass("K1", en("person"), true, languages), UniverseClass("K2", en("place"), false, languages)).filter { it.id in ids }
    }

    /**
     * A site whose pages are "<id>|<title>|<tags>|<annotation>|<pairings>": tags to suggest, and
     * pairings the application works out from the line the site writes them in.
     */
    class SiteSource : Source {
        override val id = "site"
        override val title = "Site"
        override val contentType = "books"
        override val homepage = "https://site.example"
        override val schema = SourceSchema(
            facets = listOf(
                // The site's tags: its word, the examples of the work's tags
                Books.TAG.asFacet(),
                FacetDef("pairing", "Пэйринг", infer = true, original = "pairings"),
                FacetDef("series", "Серия", role = FacetRole.SERIES),
            ),
            texts = listOf(
                TextDef("annotation", "Аннотация", block = "text:annotation"),
                TextDef("pairings", "Пэйринги на сайте", searchByMeaning = true),
            ),
            universeLine = "pairings",
        )
        override val modes = setOf(SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://site\\.example/work/.*"))
        override fun itemUrl(itemId: String) = "https://site.example/work/$itemId"
        override fun itemIdOf(url: String) = Regex("^https://site\\.example/work/([^/?#]+)").find(url)?.groupValues?.get(1)
        override val pageDecor = PageDecor(
            panelAfter = "h1",
            facets = listOf(FacetDecor(Books.TAGS.key, after = ".tags")),
            cards = CardDecor(".card", "a.title"),
        )

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val parts = page.html.split('|')
            val (id, title, tags, annotation, pairings) = parts
            context.items.upsert(ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")), page.capturedAt)
            context.items.setFacet(id, "tag", tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it.replaceFirstChar(Char::uppercase)) })
            parts.getOrNull(5)?.let { context.items.setFacet(id, "series", listOf(FacetValue(it.lowercase(), it))) }
            context.items.setFacet(id, "pairing", pairings.split(", ").filter { it.isNotBlank() }.map { FacetValue(it.lowercase(), it) })
            context.items.setTexts(id, mapOf("annotation" to annotation, "pairings" to pairings))
            return listOf(id)
        }
    }

    /** Texts as bags of their words, hashed into a few dimensions: alike words, alike vectors. */
    class WordsEncoder : TextEncoder {
        override val id = "words"
        override val dim = 512
        override fun ready() = true

        override fun encode(texts: List<String>, kind: TextKind): List<FloatArray?> = texts.map { text ->
            val v = FloatArray(dim)
            Regex("\\p{L}+").findAll(text.lowercase()).forEach { w -> v[Math.floorMod(w.value.take(5).hashCode(), dim)] += 1f }
            val n = sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            if (n == 0f) null else FloatArray(dim) { v[it] / n }
        }

        override fun encodeWindows(texts: List<String>): List<List<FloatArray>> = encode(texts).map { listOfNotNull(it) }
    }

    @Autowired lateinit var captures: CaptureController
    @Autowired lateinit var items: ItemsController
    @Autowired lateinit var corrections: CorrectionsController
    @Autowired lateinit var pages: PagesController
    @Autowired lateinit var suggestionsApi: SuggestionsController
    @Autowired lateinit var stores: Stores
    @Autowired lateinit var textVectors: TextVectors
    @Autowired lateinit var suggestions: FacetSuggestions
    @Autowired lateinit var universes: UniversesController
    @Autowired lateinit var recommendations: Recommendations
    @Autowired lateinit var comparisons: ScorerComparisons

    private fun capture(body: String) = captures.capturePage(CaptureRequest("https://site.example/work/${body.substringBefore('|')}", body))

    /**
     * Forty works of two kinds, one of the first kind the site gave a dragons' tag by mistake, and
     * one of the first kind it gave nothing.
     */
    private fun catalogue() {
        repeat(20) { k ->
            // As on a site: some works with one tag of their two, some untagged, some without pairings
            fun tags(one: String, two: String) = when {
                k % 5 == 0 -> ""
                k % 3 == 0 -> one
                else -> "$one,$two"
            }
            // And some crossovers: the other kind's pairing beside the own one
            fun pairing(own: String, other: String) = when {
                k % 4 == 0 -> ""
                k % 7 == 3 -> "$own, $other"
                else -> own
            }
            // Titles of all sorts, that tell nothing of the kind: the description does
            val names = listOf("Anna", "Boris", "Clara", "Denis", "Elena", "Fedor", "Galina", "Igor", "Kira", "Leonid", "Maria", "Nikita", "Olga", "Pavel")
            fun title(i: Int) = "${names[i % names.size]} and ${names[(i * 5 + 3) % names.size]} ${listOf("Story", "Summer", "Letters", "Road", "Winter")[i % 5]}"
            capture("s$k|${title(2 * k)}|${tags("space", "ships")}|Starships cross the galaxy, pilots fight in orbit near distant planets $k|${pairing("Pilot/Captain", "Knight/Princess")}")
            capture("d$k|${title(2 * k + 1)}|${tags("dragons", "magic")}|A dragon guards the castle, wizards cast spells and magic $k|${pairing("Knight/Princess", "Pilot/Captain")}")
        }
        capture("w1|Wrong tag|space,ships,magic|Starships cross the galaxy, pilots fight in orbit near distant planets|Pilot/Captain")
        capture("x1|Lost fleet||Pilots of starships lost beyond the galaxy fight near planets|")
        // The user's tags of some works the site left untagged: where the work's tags part from the
        // site's, the model learns what the texts say of them
        listOf(5, 10, 15).forEach { k ->
            listOf("space", "ships").forEach { corrections.correctFacet("site", "s$k", FacetCorrection(facet = "tags", added = true, key = it)) }
            listOf("dragons", "magic").forEach { corrections.correctFacet("site", "d$k", FacetCorrection(facet = "tags", added = true, key = it)) }
        }
        val store = stores.source("site")!!
        textVectors.refresh(store)
        suggestions.refresh(store)
    }

    @Test
    fun `a work's page and a list show what the application knows, and the work's tags are corrected on them`() {
        catalogue()

        val page = pages.getPage("https://site.example/work/x1?from=list").body!!
        assertEquals("site", page.source)
        assertEquals("x1", page.itemId)
        assertEquals(".tags", page.decor.facets.single().after)
        val item = assertNotNull(page.item)
        assertEquals(5, item.grades.size)
        // A facet the work has no values of comes too, named: the page adds values to it
        assertEquals("Вселенная", item.facets.single { it.facet == "universe" }.label)

        // The work's tags are worked out from the site's: the untagged work is given those of its kind
        val given = item.facets.single { it.facet == "tags" }.propertyValues
        assertTrue(given.any { it.key == "space" && it.inferred == true }, "given $given")
        assertTrue(given.none { it.key == "dragons" && it.offered != true }, "given $given")
        // Beside what it is given, the likeliest others are offered: three the site does not give, at least
        assertEquals(3, given.count { it.corrected == null }, "given $given")
        // Asked for more: every other value of the facet, the likeliest first — the offered ones
        // among them, beyond them those whose chances are not kept
        val more = suggestionsApi.getCandidates("site", "x1", "tags").body!!
        val offeredKeys = given.filter { it.offered == true }.map { it.key }.toSet()
        assertTrue(more.map { it.key }.containsAll(offeredKeys) && more.size > offeredKeys.size, "more $more, offered $offeredKeys")
        assertTrue(more.none { c -> given.any { it.key == c.key && it.offered != true } }, "none the work is given: $more")
        assertTrue(more.zipWithNext().all { (a, b) -> a.chance!! >= b.chance!! }, "the likeliest first: $more")

        // The pairing of its kind is given to it at once, marked as the model's
        val pairing = item.facets.single { it.facet == "pairing" }.propertyValues.single()
        assertEquals("pilot/captain", pairing.key)
        assertEquals(true, pairing.inferred)
        assertTrue(pairing.chance!! > 0.5, "the chance ${pairing.chance}")

        // The site's tags are its word, shown as they are; of the work's, the site's mistaken one is the least likely
        val w1 = items.getItem("site", "w1").body!!.allFacets
        assertEquals(setOf("space", "ships", "magic"), w1.single { it.facet == "tag" }.propertyValues.map { it.key }.toSet())
        assertEquals(false, w1.single { it.facet == "tag" }.editable)
        val worked = w1.single { it.facet == "tags" }
        assertEquals(true, worked.editable)
        // A value not given at all is less likely than not
        fun chanceOf(key: String) = worked.propertyValues.find { it.key == key }?.chance ?: 0.0
        assertTrue(chanceOf("space") > 0.5 && chanceOf("ships") > 0.5, "the work's tags ${worked.propertyValues}")
        assertTrue(chanceOf("magic") < chanceOf("space") && chanceOf("magic") < chanceOf("ships"), "magic on a space work: ${worked.propertyValues}")

        // The site's word is not corrected
        val refused = assertThrows<ResponseStatusException> { corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, key = "space")) }
        assertTrue(refused.reason!!.contains("слово сайта"), refused.reason)

        // The work's tags are: a value given and confirmed is the user's, one taken away is shown so
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, key = "space"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = false, key = "ships"))
        val facets = pages.getPage("https://site.example/work/x1").body!!.item!!.facets.single { it.facet == "tags" }.propertyValues
        assertEquals(FacetValueInfo.Corrected.CONFIRMED, facets.single { it.key == "space" }.corrected)
        // A value taken away is still shown, crossed out, with the model's chance to keep its place by
        val ships = facets.single { it.key == "ships" }
        assertEquals(FacetValueInfo.Corrected.REMOVED, ships.corrected, "ships taken away: $facets")
        assertTrue(ships.chance != null, "ships keeps its chance: $ships")

        // A value added by its name finds the key of the sites' value, any case; a new one gets its name in lower case
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, name = "MAGIC"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, name = "Космос"))
        val values = items.getItem("site", "x1").body!!.allFacets.single { it.facet == "tags" }.propertyValues
        // Added — or confirmed, where the model gave it too
        val answeredYes = setOf(FacetValueInfo.Corrected.ADDED, FacetValueInfo.Corrected.CONFIRMED)
        assertEquals(setOf("space", "magic", "космос"), values.filter { it.corrected in answeredYes }.map { it.key }.toSet())
        assertEquals("Космос", values.single { it.key == "космос" }.name)
        assertEquals(listOf("magic"), items.listFacetValues("site", "tags", "mag", 10).body!!.map { it.key })

        // The prediction is told part by part, each part with its features; a page of the site gets the parts alone
        (3..8).forEach { k ->
            items.rateItem("site", "s$k", RatingRequest(5))
            items.rateItem("site", "d$k", RatingRequest(1))
        }
        recommendations.retrainAndScore("books")
        // What every part is worth: the ranking cross-validated without it, every part measured
        val worth = recommendations.worth("books")!!
        assertTrue(worth.full != null && worth.parts.any { it.label == Books.TAG.label && it.metrics != null }, "parts ${worth.parts.map { it.label }}")
        val explained = items.getItem("site", "x1").body!!.explanation
        assertTrue(explained.any { it.label == Books.TAG.label && it.features.isNotEmpty() }, "parts ${explained.map { it.label }}")
        assertTrue(explained.zipWithNext().all { (a, b) -> abs(a.contribution) >= abs(b.contribution) }, "strongest first")
        val onPage = items.getExplanation("site", "x1").body!!
        assertTrue(onPage.isNotEmpty() && onPage.all { it.features.isEmpty() }, "on a page $onPage")
        // What a model that had not seen a graded work says is read apart; an ungraded work has none
        val heldOut = items.getHeldOut("site", "s3")
        assertEquals(HttpStatus.OK, heldOut.statusCode)
        assertTrue(heldOut.body!!.explanation.isNotEmpty())
        assertEquals(HttpStatus.NO_CONTENT, items.getHeldOut("site", "x1").statusCode)

        // The scorers are compared in the background, each one's result there once it is measured;
        // a comparison under way is answered as it stands, not started again
        val comparison = comparisons.start("books")
        val again = comparisons.start("books")
        if (again !== comparison) assertNotNull(comparison.finishedAt, "a second comparison started while the first was under way")
        val until = System.currentTimeMillis() + 60_000
        while (comparison.finishedAt == null && System.currentTimeMillis() < until) Thread.sleep(50)
        assertTrue(comparison.grades > 0, "measured on ${comparison.grades} grades")
        val measured = comparison.scorers.single()
        assertEquals(MeasuringState.DONE, measured.state, measured.error)
        assertTrue(measured.chosen != null && measured.millis != null, "the line measured: ${measured.chosen}")

        // Graded works answered on before the model is trained again: a tag two graded works now
        // have is a feature the kept model does not know, and a work is still told of as it is now
        corrections.correctFacet("site", "s4", FacetCorrection(facet = "tags", added = true, name = "Совсем новый тег"))
        corrections.correctFacet("site", "s3", FacetCorrection(facet = "tags", added = true, name = "Совсем новый тег"))
        val graded = pages.getPage("https://site.example/work/s3").body!!.item!!
        assertTrue(graded.facets.single { it.facet == "tags" }.propertyValues.any { it.key == "совсем новый тег" }, "s3 ${graded.facets}")

        // The other works of the series, with the user's grades
        capture("s1|Space 1|space|Starships cross the galaxy 1|Pilot/Captain|Галактика")
        capture("s2|Space 2|space|Starships cross the galaxy 2|Pilot/Captain|Галактика")
        items.rateItem("site", "s2", RatingRequest(5))
        val series = items.getItem("site", "s1").body!!.related.single()
        assertEquals(Triple("Галактика", ApiFacetRole.SERIES, listOf("s2")), Triple(series.name, series.role, series.items.map { it.item }))
        assertEquals(5, series.items.single().grade)

        // The cards of a list get the works' grades
        items.rateItem("site", "s1", RatingRequest(4))
        val cards = pages.getCards(CardsRequest("https://site.example/list", listOf("/work/s1", "/work/unknown", "https://elsewhere.example/work/s2"))).body!!
        assertEquals(listOf("/work/s1"), cards.map { it.link })
        assertEquals(4, cards.single().grade)

        // A work is found by a piece of its title and by the address of its page
        assertEquals(listOf("x1"), items.lookupItems("books", "lost fl", 10).body!!.map { it.item })
        assertEquals("d3", items.lookupItems("books", "https://site.example/work/d3", 10).body!!.first().item)

        // A page of no work, and a page of no source
        assertNull(pages.getPage("https://site.example/about").body!!.item)
        assertEquals(404, pages.getPage("https://nowhere.example/").statusCode.value())

        // An error says what is wrong
        val error = assertThrows<ResponseStatusException> { corrections.correctFacet("site", "x1", FacetCorrection(facet = "nope", added = true, key = "x")) }
        assertTrue(error.reason!!.contains("nope"))
    }

    @Test
    fun `a universe found by the button joins the dictionary with its characters, and works are linked to it`() {
        capture("u1|Lone pilot|space|A pilot alone in orbit|")
        assertEquals(listOf("U1"), universes.searchUniverses("books", "Space").body!!.map { it.universe })
        val added = universes.addUniverse("books", UniverseRef("fake", "U1")).body!!
        assertEquals("fake:U1", added.value)
        assertEquals(2, added.characters)
        assertEquals(true, universes.searchUniverses("books", "space").body!!.single().added)
        assertEquals(listOf("Pilot", "The Pilot"), universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "C1" }.names)

        // The station is said to be in the universe, but a place: shown, not kept as a character
        assertEquals(3, added.total)
        val station = universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "S1" }
        assertEquals(false, station.included)
        assertEquals(listOf("K2"), station.classes)
        val place = universes.listUniverseClasses("books", "fake", "U1").body!!.single { it.propertyClass == "K2" }
        assertEquals(Triple("place", false, 1), Triple(place.name, place.included, place.count))
        // The user keeps the places of the universe, then leaves this one out by itself, then takes both words back
        universes.chooseUniverseClass("books", "fake", "U1", "K2", Inclusion(true))
        assertEquals(3, universes.listUniverses("books").body!!.single { it.universe == "U1" }.characters)
        universes.chooseUniverseEntry("books", "fake", "U1", "S1", Inclusion(false))
        assertEquals(false, universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "S1" }.choice)
        assertEquals(2, universes.listUniverses("books").body!!.single { it.universe == "U1" }.characters)
        universes.resetUniverseEntry("books", "fake", "U1", "S1")
        universes.resetUniverseClass("books", "fake", "U1", "K2")
        // The words stay over a refresh of the universe
        universes.chooseUniverseEntry("books", "fake", "U1", "S1", Inclusion(true))
        universes.addUniverse("books", UniverseRef("fake", "U1"))
        assertEquals(true, universes.listCharacters("books", "fake", "U1").body!!.single { it.character == "S1" }.included)
        universes.resetUniverseEntry("books", "fake", "U1", "S1")

        // The universe is a value to pick for any work, linked to none yet; linked, it names itself
        assertTrue(items.listFacetValues("site", "universe", "space", 10).body!!.any { it.key == "fake:U1" })
        corrections.correctFacet("site", "u1", FacetCorrection(facet = "universe", added = true, key = "fake:U1"))
        assertEquals("Space opera", items.getItem("site", "u1").body!!.allFacets.single { it.facet == "universe" }.propertyValues.single { it.key == "fake:U1" }.name)
        assertEquals(1, universes.listUniverses("books").body!!.single { it.universe == "U1" }.works)

        // Removed, it takes its links with it
        universes.removeUniverse("books", "fake", "U1")
        assertTrue(universes.listUniverses("books").body!!.none { it.universe == "U1" })
        val left = items.getItem("site", "u1").body!!.allFacets.filter { it.facet == "universe" }
        assertTrue(left.none { f -> f.propertyValues.any { it.key == "fake:U1" } }, "left $left")
    }

    @Test
    fun `the characters and the pairings of a work are worked out within its universes`() {
        universes.addUniverse("books", UniverseRef("fake", "U2"))
        // Works of the universe, the site's line naming its pairing, and works of no universe
        repeat(12) { k ->
            capture("p$k|Pilots $k|space|Pilots fly starships in orbit near planets $k|Pilot/Captain")
            capture("q$k|Knights $k|dragons|A knight guards the princess in the castle $k|Knight/Princess")
        }
        repeat(12) { k -> corrections.correctFacet("site", "p$k", FacetCorrection(facet = "universe", added = true, key = "fake:U2")) }
        // The user's word on half of them
        repeat(6) { k ->
            listOf("fake:C1", "fake:C2").forEach { corrections.correctFacet("site", "p$k", FacetCorrection(facet = "characters", added = true, key = it)) }
            corrections.correctFacet("site", "p$k", FacetCorrection(facet = "pairings", added = true, key = "pair:fake:C1|fake:C2"))
        }
        val store = stores.source("site")!!
        textVectors.refresh(store)
        suggestions.refresh(store)
        // The pairings are made of the characters the model gave: once more, over what it gave
        suggestions.refresh(store)

        val facets = items.getItem("site", "p9").body!!.allFacets
        val characters = facets.single { it.facet == "characters" }.propertyValues
        assertEquals(setOf("fake:C1", "fake:C2"), characters.filter { it.inferred == true }.map { it.key }.toSet(), "characters $characters")
        assertEquals("Pilot", characters.single { it.key == "fake:C1" }.name)
        val pairing = facets.single { it.facet == "pairings" }.propertyValues.single { it.offered != true }
        assertEquals("pair:fake:C1|fake:C2", pairing.key)
        assertEquals("Pilot / Captain", pairing.name)
        // Beside the two it gives, the likeliest other character is offered: three to answer on at least
        val unanswered = characters.filter { it.corrected == null }
        assertEquals(1, unanswered.count { it.offered == true }, "characters $characters")
        assertTrue(unanswered.size >= 3, "characters $characters")
        // A work of no universe has no characters of it: only its site's line, and the original characters offered
        val outside = items.getItem("site", "q9").body!!.allFacets.filter { it.facet == "characters" || it.facet == "pairings" }
        assertTrue(outside.all { f -> f.propertyValues.all { it.offered == true } && f.original == "Knight/Princess" }, "outside $outside")
        // Its site's line is shown above
        assertEquals("Pilot/Captain", facets.single { it.facet == "characters" }.original)

        // A new work of the universe: every character it may have is offered, with how often its texts name them
        // A name of one word is a name written with a capital: the lower-case "captain" names nobody
        capture("n1|The captain|space|The Captain commands. The Captain waits. The Captain sleeps, the captain of the guard does not|")
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "universe", added = true, key = "fake:U2"))
        textVectors.refresh(store)
        // The candidates first: asked for, the work's chances are made current, and the card read after them shows the same
        val offered = suggestionsApi.getCandidates("site", "n1", "characters").body!!
        val given = items.getItem("site", "n1").body!!.allFacets.find { it.facet == "characters" }?.propertyValues.orEmpty().filter { it.offered != true }.map { it.key }
        assertEquals(setOf("fake:C1", "fake:C2", "oc:female", "oc:male"), (offered.map { it.key } + given).toSet(), "offered $offered, given $given")
        assertTrue(offered.none { it.key in given }, "offered $offered, given $given")
        assertTrue(offered.zipWithNext().all { (a, b) -> a.chance!! >= b.chance!! }, "the likeliest first: $offered")
        offered.find { it.key == "fake:C2" }?.let { assertEquals(3, it.mentions) }
        assertEquals(0, offered.single { it.key == "oc:male" }.mentions)
        assertEquals("Captain", offered.find { it.key == "fake:C2" }?.name ?: "Captain")
        assertEquals(404, suggestionsApi.getCandidates("site", "n1", "nope").statusCode.value())

        // The universe of a fan fiction is worked out as its characters are: given when likely, offered first when not
        capture("f1|Fleet tales|space|Ships of the fleet|")
        corrections.correctFacet("site", "f1", FacetCorrection(facet = "kind", added = true, key = "fanfiction"))
        textVectors.refresh(store)
        suggestions.refresh(store)
        val universes = items.getItem("site", "f1").body!!.allFacets.single { it.facet == "universe" }.propertyValues.map { it.key }
        val offeredUniverses = suggestionsApi.getCandidates("site", "f1", "universe").body!!.map { it.key }
        assertEquals("fake:U2", (universes + offeredUniverses).first(), "given $universes, offered $offeredUniverses")

        // Its universes all named: no other is offered, the work has its own alone
        this.universes.addUniverse("books", UniverseRef("fake", "U1"))
        // The other universe: given by the model or offered among the candidates
        fun other(details: io.github.vlsergey.recommend4me.api.model.ItemDetails) =
            details.allFacets.single { it.facet == "universe" }.propertyValues.any { it.key == "fake:U1" } ||
                suggestionsApi.getCandidates("site", "f1", "universe").body!!.any { it.key == "fake:U1" }
        assertTrue(other(corrections.correctFacet("site", "f1", FacetCorrection(facet = "universe", added = true, key = "fake:U2")).body!!))
        val named = corrections.completeFacet("site", "f1", "universe").body!!.allFacets.single { it.facet == "universe" }
        assertEquals(true, named.complete)
        assertEquals(listOf("fake:U2"), named.propertyValues.filter { it.corrected != FacetValueInfo.Corrected.REMOVED }.map { it.key }, "values ${named.propertyValues}")
        assertEquals(emptyList(), suggestionsApi.getCandidates("site", "f1", "universe").body!!.map { it.key })
        // Taken back, the others are offered again
        val reopened = corrections.uncompleteFacet("site", "f1", "universe").body!!
        assertNull(reopened.allFacets.single { it.facet == "universe" }.complete)
        assertTrue(other(reopened))
        this.universes.removeUniverse("books", "fake", "U1")

        // An original work, an alternative history and a realistic one have no universe of another's
        capture("o1|Our own world|space|Ships of the fleet|")
        corrections.correctFacet("site", "o1", FacetCorrection(facet = "kind", added = true, key = "alternative-history"))
        capture("o2|A school year|school|Lessons and friends|")
        corrections.correctFacet("site", "o2", FacetCorrection(facet = "kind", added = true, key = "realism"))
        suggestions.refresh(store)
        assertEquals(emptyList(), suggestionsApi.getCandidates("site", "o1", "universe").body!!.map { it.key })
        assertEquals(emptyList(), suggestionsApi.getCandidates("site", "o2", "universe").body!!.map { it.key })
    }
}
