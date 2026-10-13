package io.github.vlsergey.recommend4me

import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.CardsRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
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
import io.github.vlsergey.recommend4me.api.model.LocalName
import io.github.vlsergey.recommend4me.api.model.FacetRole as ApiFacetRole
import io.github.vlsergey.recommend4me.api.model.UniverseRef
import io.github.vlsergey.recommend4me.api.model.TagBindingRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRequest
import io.github.vlsergey.recommend4me.api.model.TagNameRule
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.tag.TagsController
import io.github.vlsergey.recommend4me.universe.UniverseCatalogue
import io.github.vlsergey.recommend4me.universe.UniverseCharacter
import io.github.vlsergey.recommend4me.universe.UniverseClass
import io.github.vlsergey.recommend4me.universe.UniverseEntry
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniversesController
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpStatus
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
        private fun en(text: String) = mapOf("en" to text)
        private fun entries(languages: List<String>) = listOf(
            UniverseEntry("U1", en("Space opera"), en("Ships and pilots"), "https://fake.example/U1", languages),
            UniverseEntry("U2", en("Fleet"), en("More ships"), "https://fake.example/U2", languages),
        )

        override fun findUniverses(name: String, languages: List<String>) = if ("space" in name.lowercase()) entries(languages).take(1) else emptyList()
        override fun universe(id: String, languages: List<String>) = entries(languages).firstOrNull { it.id == id }
        override fun characters(id: String, languages: List<String>, properties: Set<String>) = listOf(
            UniverseCharacter(
                "C1", en("Pilot"), mapOf("en" to listOf("The Pilot")), emptyMap(), "https://fake.example/C1", languages, listOf("K1"),
                properties = if ("P1" in properties) mapOf("P1" to listOf("pilot (space)")) else emptyMap(),
            ),
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
        override val contentType = "books"
        override val homepage = "https://site.example"

        /** A character's tag on the site, as the catalogue states it. */
        override val catalogueProperties = mapOf("fake" to setOf("P1"))
        override val schema = SourceSchema(
            facets = listOf(
                // The site's tags: its word, the examples of the work's tags
                Books.TAG.asFacet(),
                FacetDef("pairing", infer = true, original = "pairings"),
                FacetDef("series", role = FacetRole.SERIES),
            ),
            texts = listOf(
                TextDef("annotation", block = "text:annotation"),
                TextDef("pairings", searchByMeaning = true),
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

        override fun windowSpans(text: String): List<IntRange> = if (text.isBlank()) emptyList() else listOf(text.indices)
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
    @Autowired lateinit var tagsApi: TagsController

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
            listOf("space", "ships").forEach { corrections.correctFacet("site", "s$k", FacetCorrection(facet = "tags", added = true, key = tag(it))) }
            listOf("dragons", "magic").forEach { corrections.correctFacet("site", "d$k", FacetCorrection(facet = "tags", added = true, key = tag(it))) }
        }
        val store = stores.source("site")!!
        textVectors.refresh(store)
        suggestions.refresh(store)
    }

    /** The key of the normalized tag the site's tag [site] is bound to. */
    private fun tag(site: String) = TagRepository.key(stores.type("books")!!.tags.bindings().getValue(SiteTagRef("site", site)))

    @Test
    fun `a work's page and a list show what the application knows, and the work's tags are corrected on them`() {
        catalogue()

        val page = pages.getPage("https://site.example/work/x1?from=list").body!!
        assertEquals("site", page.source)
        assertEquals("x1", page.itemId)
        assertEquals(".tags", page.decor.facets.single().after)
        val item = assertNotNull(page.item)
        // A facet the work has no values of comes too, by the key of its label: the page adds values
        // to it. The application's layer is labelled in the standard namespace, the site's own in its own
        assertEquals("standard:facet.universe", item.facets.single { it.facet == "universe" }.labelKey)
        assertEquals("site:facet.pairing", item.facets.single { it.facet == "pairing" }.labelKey)
        assertEquals("standard:facet.tags", item.facets.single { it.facet == "tags" }.labelKey)

        // The work's tags are worked out from the site's: the untagged work is given those of its kind
        val given = item.facets.single { it.facet == "tags" }.propertyValues
        assertTrue(given.any { it.key == tag("space") && it.inferred == true }, "given $given")
        assertTrue(given.none { it.key == tag("dragons") && it.offered != true }, "given $given")
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
        assertTrue(chanceOf(tag("space")) > 0.5 && chanceOf(tag("ships")) > 0.5, "the work's tags ${worked.propertyValues}")
        assertTrue(
            chanceOf(tag("magic")) < chanceOf(tag("space")) && chanceOf(tag("magic")) < chanceOf(tag("ships")),
            "magic on a space work: ${worked.propertyValues}",
        )

        // The site's word is not corrected
        val refused = assertThrows<Refused> { corrections.correctFacet("site", "x1", FacetCorrection(facet = "tag", added = true, key = "space")) }
        assertEquals(Refusal.SITE_FIELD, refused.refusal)
        // The field by the key of its label: the site's tags are a standard facet
        assertEquals(mapOf("field" to "standard:facet.tag"), refused.params)

        // The work's tags are: a value given and confirmed is the user's, one taken away is shown so
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, key = tag("space")))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = false, key = tag("ships")))
        val facets = pages.getPage("https://site.example/work/x1").body!!.item!!.facets.single { it.facet == "tags" }.propertyValues
        assertEquals(FacetValueInfo.Corrected.CONFIRMED, facets.single { it.key == tag("space") }.corrected)
        // A value taken away is still shown, crossed out, with the model's chance to keep its place by
        val ships = facets.single { it.key == tag("ships") }
        assertEquals(FacetValueInfo.Corrected.REMOVED, ships.corrected, "ships taken away: $facets")
        assertTrue(ships.chance != null, "ships keeps its chance: $ships")

        // A tag added by its name finds the tag of that name, any case; a new one is a new normalized tag of that name
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, name = "MAGIC"))
        corrections.correctFacet("site", "x1", FacetCorrection(facet = "tags", added = true, name = "Космос"))
        val values = items.getItem("site", "x1").body!!.allFacets.single { it.facet == "tags" }.propertyValues
        // Added — or confirmed, where the model gave it too
        val answeredYes = setOf(FacetValueInfo.Corrected.ADDED, FacetValueInfo.Corrected.CONFIRMED)
        val space = values.single { it.name == "Космос" }.key
        assertEquals(setOf(tag("space"), tag("magic"), space), values.filter { it.corrected in answeredYes }.map { it.key }.toSet())
        assertEquals(listOf(tag("magic")), items.listFacetValues("site", "tags", "mag", 10).body!!.map { it.key })

        // The prediction is told part by part, each part with its features; a page of the site gets the parts alone
        (3..8).forEach { k ->
            items.rateItem("site", "s$k", RatingRequest(5))
            items.rateItem("site", "d$k", RatingRequest(1))
        }
        recommendations.retrainAndScore("books")
        // What every part is worth: the ranking cross-validated without it, every part measured
        val worth = recommendations.worth("books")!!
        // The parts by the keys of their labels: the site's tags a standard facet, the title the standard text
        val tagLabel = "standard:facet.${Books.TAG.key}"
        assertTrue(worth.full != null && worth.parts.any { it.labelKey == tagLabel && it.metrics != null }, "parts ${worth.parts.map { it.labelKey }}")
        val explained = items.getItem("site", "x1").body!!.explanation
        assertTrue(explained.any { it.labelKey == tagLabel && it.features.isNotEmpty() }, "parts ${explained.map { it.labelKey }}")
        // A feature of a value: the facet's label and the value as the data names it
        val tagFeature = explained.single { it.labelKey == tagLabel }.features.first()
        assertEquals(tagLabel, tagFeature.labelKey)
        assertNotNull(tagFeature.value)
        assertTrue(explained.all { g -> ':' in g.labelKey && g.features.all { ':' in it.labelKey } }, "every label a key: $explained")
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
        assertTrue(graded.facets.single { it.facet == "tags" }.propertyValues.any { it.name == "Совсем новый тег" }, "s3 ${graded.facets}")

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

        // An error says what is wrong: by the key of its words, filled with what was asked
        val error = assertThrows<Refused> { corrections.correctFacet("site", "x1", FacetCorrection(facet = "nope", added = true, key = "x")) }
        assertEquals("standard:error.noField", error.refusal.key)
        assertEquals(mapOf("field" to "nope"), error.params)
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
        // The statements the site asked of the catalogue came with the characters, and are kept
        val pilot = stores.type("books")!!.universes.characters("fake", "U1").single { it.id == "C1" }
        assertEquals(mapOf("P1" to listOf("pilot (space)")), pilot.properties)
        assertEquals(setOf("P1"), stores.type("books")!!.universes.find("fake", "U1")!!.properties)

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
        // A universe the model gives a work — more likely than not — lets it have the universe's characters, confirmed or not
        fun outside() = items.getItem("site", "q9").body!!.allFacets.filter { it.facet == "characters" || it.facet == "pairings" }
        val givenUniverse = items.getItem("site", "q9").body!!.allFacets.single { it.facet == "universe" }.propertyValues
            .any { it.key == "fake:U2" && it.inferred == true }
        if (givenUniverse) {
            val chosen = outside().single { it.facet == "characters" }.propertyValues.map { it.key } +
                suggestionsApi.getCandidates("site", "q9", "characters").body!!.map { it.key }
            assertTrue(chosen.any { it.startsWith("fake:C") }, "characters of q9 $chosen")
        }
        // Said to have no universe, a work has no characters of one: only its site's line, and the original characters offered
        corrections.completeFacet("site", "q9", "universe")
        suggestionsApi.settleSuggestions("site", "q9")
        val none = outside()
        assertTrue(none.all { f -> f.propertyValues.all { it.offered == true } && f.original == "Knight/Princess" }, "outside $none")
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
        // Given or offered as the model's chance falls (above): when offered, no text of the work names it
        offered.find { it.key == "oc:male" }?.let { assertEquals(0, it.mentions) }
        assertEquals("Captain", offered.find { it.key == "fake:C2" }?.name ?: "Captain")
        // The passages naming a character, each by where it is: the annotation by the key of its label
        val quotes = suggestionsApi.getMentions("site", "n1", "characters", "fake:C2").body!!
        assertTrue(quotes.isNotEmpty() && quotes.all { it.labelKey == "site:text.annotation" && it.title == null && it.part == null }, "quotes $quotes")
        assertEquals(404, suggestionsApi.getCandidates("site", "n1", "nope").statusCode.value())

        // A character is a work's main one or its secondary one: never shown, given or offered in both
        fun keysOf(facet: String) = items.getItem("site", "n1").body!!.allFacets.find { it.facet == facet }?.propertyValues.orEmpty().map { it.key }
        fun candidatesOf(facet: String) = suggestionsApi.getCandidates("site", "n1", facet).body!!.map { it.key }
        // No work has a secondary character yet: nothing to learn from, every character of the universe offered all the same
        val untaught = suggestionsApi.getCandidates("site", "n1", "secondary-characters").body!!
        assertTrue(untaught.isNotEmpty() && untaught.all { it.chance == null }, "secondary $untaught")
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "characters", added = true, key = "fake:C1"))
        assertTrue("fake:C1" !in keysOf("secondary-characters") && "fake:C1" !in candidatesOf("secondary-characters"), "secondary ${keysOf("secondary-characters")}")
        assertTrue("fake:C2" in keysOf("secondary-characters") + candidatesOf("secondary-characters") || "fake:C2" in keysOf("characters"))
        // Said secondary, it leaves the main ones — the model's word yields to the user's
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "secondary-characters", added = true, key = "fake:C2"))
        assertTrue("fake:C2" !in keysOf("characters") && "fake:C2" !in candidatesOf("characters"), "main ${keysOf("characters")}")
        // Said main then, it is secondary no more: the earlier answer is taken back, not shown crossed out
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "characters", added = true, key = "fake:C2"))
        assertTrue("fake:C2" in keysOf("characters") && "fake:C2" !in keysOf("secondary-characters"), "main ${keysOf("characters")}, secondary ${keysOf("secondary-characters")}")
        // The original characters are many persons: in both
        corrections.correctFacet("site", "n1", FacetCorrection(facet = "characters", added = true, key = "oc:female"))
        assertTrue("oc:female" in keysOf("secondary-characters") + candidatesOf("secondary-characters"))
        // Shown by the key of its words; its name, which the model reads, stays one language
        val ofc = items.getItem("site", "n1").body!!.allFacets.single { it.facet == "characters" }.propertyValues.single { it.key == "oc:female" }
        assertEquals("standard:character.oc-female", ofc.nameKey)
        assertEquals(UniverseFacet.ORIGINAL_CHARACTERS.getValue("oc:female"), ofc.name)

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
        // An answer is kept at once, the chances it changes made apart: the page reads the work again once they are made
        fun settled(item: String) = suggestionsApi.settleSuggestions("site", item).let { items.getItem("site", item).body!! }
        corrections.correctFacet("site", "f1", FacetCorrection(facet = "universe", added = true, key = "fake:U2"))
        assertTrue(other(settled("f1")))
        corrections.completeFacet("site", "f1", "universe")
        val named = settled("f1").allFacets.single { it.facet == "universe" }
        assertEquals(true, named.complete)
        assertEquals(listOf("fake:U2"), named.propertyValues.filter { it.corrected != FacetValueInfo.Corrected.REMOVED }.map { it.key }, "values ${named.propertyValues}")
        assertEquals(emptyList(), suggestionsApi.getCandidates("site", "f1", "universe").body!!.map { it.key })
        // Taken back, the others are offered again
        corrections.uncompleteFacet("site", "f1", "universe")
        val reopened = settled("f1")
        assertNull(reopened.allFacets.single { it.facet == "universe" }.complete)
        assertTrue(other(reopened))
        this.universes.removeUniverse("books", "fake", "U1")

        // An original work, an alternative history and a realistic one have no universe of another's
        capture("o1|Our own world|space|Ships of the fleet|")
        corrections.correctFacet("site", "o1", FacetCorrection(facet = "kind", added = true, key = "alternative-history"))
        capture("o2|A school year|school|Lessons and friends|")
        corrections.correctFacet("site", "o2", FacetCorrection(facet = "kind", added = true, key = "realism"))
        // A standard value is shown by its key
        val kind = items.getItem("site", "o1").body!!.allFacets.single { it.facet == "kind" }.propertyValues.single { it.key == "alternative-history" }
        assertEquals("standard:facet.kind.value.alternative-history", kind.nameKey)
        assertTrue(items.listFacets("books").body!!.single { it.id == "kind" }.propertyValues.any { it.nameKey == "standard:facet.kind.value.realism" })
        suggestions.refresh(store)
        assertEquals(emptyList(), suggestionsApi.getCandidates("site", "o1", "universe").body!!.map { it.key })
        assertEquals(emptyList(), suggestionsApi.getCandidates("site", "o2", "universe").body!!.map { it.key })

        // The user's own name of a character and of a universe over the catalogue's: shown everywhere in its place
        fun characterName() = items.getItem("site", "p9").body!!.allFacets.single { it.facet == "characters" }.propertyValues.single { it.key == "fake:C1" }.name
        this.universes.nameUniverseEntry("books", "fake", "C1", LocalName("Лётчица"))
        assertEquals("Лётчица", characterName())
        assertEquals(listOf("Лётчица", "Pilot"), this.universes.listCharacters("books", "fake", "U2").body!!.single { it.character == "C1" }.names.take(2))
        this.universes.nameUniverseEntry("books", "fake", "U2", LocalName("Флот"))
        val fleet = this.universes.listUniverses("books").body!!.single { it.universe == "U2" }
        assertEquals("Флот" to "Fleet", fleet.name to fleet.catalogueName)
        // Taken back: the catalogue's names again
        this.universes.nameUniverseEntry("books", "fake", "C1", LocalName(null))
        this.universes.nameUniverseEntry("books", "fake", "U2", LocalName(""))
        assertEquals("Pilot", characterName())
        assertNull(this.universes.listUniverses("books").body!!.single { it.universe == "U2" }.localName)
    }

    @Test
    fun `every site's tag is bound to a normalized tag, the user merges them, names one, takes a site's tag out and links them`() {
        capture("t1|Hull one|hulls,cargo|Ships carry cargo between stations|")
        capture("t2|Hull two|starhulls|Freighters carry cargo between stations|")
        capture("t3|Hull three|hulls|A hull breach in deep space|")
        val type = stores.type("books")!!
        // Every site's tag bound as it is captured, each to a normalized tag of its own name
        fun tagOf(site: String) = TagRepository.key(type.tags.bindings().getValue(SiteTagRef("site", site)))
        val hulls = tagOf("hulls")
        val starhulls = tagOf("starhulls")
        val cargo = tagOf("cargo")
        assertEquals(3, setOf(hulls, starhulls, cargo).size)
        // The user's answer on a tag that will be merged into another
        corrections.correctFacet("site", "t3", FacetCorrection(facet = "tags", added = true, key = starhulls))
        items.rateItem("site", "t2", RatingRequest(5))
        textVectors.refresh(stores.source("site")!!)

        // Offered alike: the other tags of the sites, with how alike their names are; the site's own tag of it, merged
        val similar = tagsApi.listSimilarTags("books", hulls, null, 0, 50, null).body!!.tags
        assertTrue(similar.any { it.tag.key == starhulls && it.site == null }, "similar ${similar.map { it.tag.key }}")
        assertEquals(listOf("hulls"), similar.filter { it.site != null }.map { it.site!!.tag })
        // Nothing is merged by itself
        assertEquals(listOf("hulls"), tagsApi.getTag("books", hulls).body!!.variants.map { it.tag })

        // Merged: the site's tags of the other bound to this one, the other no more
        val merged = tagsApi.relateTags("books", TagRelationRequest(hulls, starhulls, TagRelation.MERGED)).body!!
        assertEquals(hulls, merged.key)
        assertEquals(setOf("hulls", "starhulls"), merged.variants.map { it.tag }.toSet())
        assertEquals(3, merged.works)
        assertEquals(hulls, tagOf("starhulls"))
        assertEquals(404, tagsApi.getTag("books", starhulls).statusCode.value())
        // Named by the name of the liked work: t2, graded 5, has "Starhulls"
        assertEquals("Starhulls", merged.name)
        assertEquals(TagNameRule.LIKED, merged.rule)
        // The answer on the tag merged is the tag's now
        val t3 = stores.source("site")!!.corrections.facetsOf("t3").filter { it.facet == "tags" }
        assertEquals(listOf(hulls to true), t3.map { it.key to it.added })
        // The work's tags show the tag by its name, whichever site's name it has
        val named = tagsApi.nameTag("books", TagNameRequest(hulls, "Корпуса")).body!!
        assertEquals("Корпуса", named.name)
        assertEquals(TagNameRule.EXPLICIT, named.rule)
        val shown = items.getItem("site", "t3").body!!.allFacets.single { it.facet == "tags" }.propertyValues.single { it.key == hulls }
        assertEquals("Корпуса", shown.name)
        // The name taken back: the sites' names decide again
        assertEquals(TagNameRule.LIKED, tagsApi.nameTag("books", TagNameRequest(hulls)).body!!.rule)

        // Both site's tags merged rows of it; one taken out, to a new tag of its own; the answer stays with the tag
        fun rows() = tagsApi.listSimilarTags("books", hulls, null, 0, 50, null).body!!.tags
        assertEquals(setOf("hulls", "starhulls"), rows().filter { it.site != null }.onEach { assertEquals(TagRelation.MERGED, it.relation) }.map { it.site!!.tag }.toSet())
        val out = tagsApi.bindSiteTag("books", TagBindingRequest("site", "starhulls")).body!!
        assertTrue(out.key != hulls && out.key != starhulls, "a new tag ${out.key}")
        assertEquals(1, out.works)
        assertEquals(listOf("hulls"), tagsApi.getTag("books", hulls).body!!.variants.map { it.tag })
        assertEquals(listOf(hulls to true), stores.source("site")!!.corrections.facetsOf("t3").filter { it.facet == "tags" }.map { it.key to it.added })
        val other = out.key
        // One row of it among the alike, not merged
        assertEquals(listOf(TagRelation.NONE), rows().filter { it.tag.key == other && it.site == null }.map { it.relation })

        // Told apart: stays among the alike, marked, whatever its likeness
        fun relationOf(key: String) = rows().singleOrNull { it.tag.key == key && it.site == null }?.relation
        tagsApi.relateTags("books", TagRelationRequest(hulls, other, TagRelation.DIFFERENT))
        assertEquals(TagRelation.DIFFERENT, relationOf(other))
        // The rows of a relation hidden on the server, the total without them
        val all = tagsApi.listSimilarTags("books", hulls, null, 0, 50, null).body!!
        val noDifferent = tagsApi.listSimilarTags("books", hulls, null, 0, 50, listOf(TagRelation.DIFFERENT)).body!!
        assertTrue(noDifferent.tags.none { it.relation == TagRelation.DIFFERENT }, "hidden ${noDifferent.tags.map { it.relation }}")
        assertEquals(all.total - 1, noDifferent.total)
        // A page at a time: the pages one after another are the whole table
        val paged = (0 until all.total).chunked(1).flatMap { tagsApi.listSimilarTags("books", hulls, null, it.first(), 1, null).body!!.tags }
        assertEquals(all.tags.map { it.tag.key to it.site?.tag }, paged.map { it.tag.key to it.site?.tag })
        // Searched for: alike the text, not the tag — all the rows, in another order
        val searched = tagsApi.listSimilarTags("books", hulls, "freight cargo", 0, 50, null).body!!
        assertEquals(all.total, searched.total)
        assertTrue(searched.tags.zipWithNext().all { (a, b) -> a.similarity >= b.similarity }, "the likest first")
        tagsApi.relateTags("books", TagRelationRequest(hulls, other, TagRelation.NONE))
        assertEquals(TagRelation.NONE, rows().single { it.tag.key == other && it.site == null }.relation)

        // Under a tag: a work with the child has the parent, shown as given by it
        val hull = tagsApi.relateTags("books", TagRelationRequest(hulls, other, TagRelation.CHILD)).body!!
        assertEquals(listOf(other), hull.children.map { it.key })
        assertEquals(TagRelation.CHILD, relationOf(other))
        fun tagsOf(id: String) = items.getItem("site", id).body!!.allFacets.single { it.facet == "tags" }.propertyValues
        // The child confirmed: the parent given by it — unless the model gives it the work by itself
        corrections.correctFacet("site", "t2", FacetCorrection(facet = "tags", added = true, key = other))
        val parent = tagsOf("t2").single { it.key == hulls }
        assertTrue(parent.implied == true || (parent.chance ?: 0.0) > 0.5, "hulls of t2 $parent")
        assertTrue(parent.offered != true, "hulls of t2 $parent")
        // A tag of several parents: under cargo too
        tagsApi.relateTags("books", TagRelationRequest(cargo, other, TagRelation.CHILD))
        assertEquals(setOf(hulls, cargo), tagsApi.getTag("books", other).body!!.parents.map { it.key }.toSet())
        tagsApi.relateTags("books", TagRelationRequest(cargo, other, TagRelation.NONE))
        // A tag never under its own descendant: other under hulls under cargo, and cargo not under other
        tagsApi.relateTags("books", TagRelationRequest(cargo, hulls, TagRelation.CHILD))
        assertEquals(Refusal.TAG_ABOVE, assertThrows<Refused> { tagsApi.relateTags("books", TagRelationRequest(other, cargo, TagRelation.CHILD)) }.refusal)
        tagsApi.relateTags("books", TagRelationRequest(cargo, hulls, TagRelation.NONE))
        // The parent taken away: the child goes with it, not shown at all
        corrections.correctFacet("site", "t2", FacetCorrection(facet = "tags", added = false, key = hulls))
        assertTrue(tagsOf("t2").none { it.key == other }, "tags ${tagsOf("t2")}")
        assertEquals(io.github.vlsergey.recommend4me.api.model.FacetValueInfo.Corrected.REMOVED, tagsOf("t2").single { it.key == hulls }.corrected)
        // The other way round: above it
        assertEquals(listOf(other), tagsApi.relateTags("books", TagRelationRequest(hulls, other, TagRelation.PARENT)).body!!.parents.map { it.key })
        tagsApi.relateTags("books", TagRelationRequest(hulls, other, TagRelation.NONE))
        assertEquals(TagRelation.NONE, relationOf(other) ?: TagRelation.NONE)

        // A tag the user makes up on a work by its name: a new normalized tag of that name; the same name again, the same tag
        val made = corrections.correctFacet("site", "t1", FacetCorrection(facet = "tags", added = true, name = "Пустота")).body!!
        val void = made.allFacets.single { it.facet == "tags" }.propertyValues.single { it.name == "Пустота" }.key
        assertEquals("Пустота", type.tags.explicitNames()[TagRepository.idOf(void)])
        corrections.correctFacet("site", "t3", FacetCorrection(facet = "tags", added = true, name = "пустота"))
        assertEquals(void, stores.source("site")!!.corrections.facetsOf("t3").single { it.facet == "tags" && it.added && it.key != hulls }.key)
    }
}
