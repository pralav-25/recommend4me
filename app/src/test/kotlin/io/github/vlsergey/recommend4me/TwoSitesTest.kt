package io.github.vlsergey.recommend4me

import com.sun.net.httpserver.HttpServer
import io.github.vlsergey.recommend4me.api.model.CaptureRequest
import io.github.vlsergey.recommend4me.api.model.FacetCorrection
import io.github.vlsergey.recommend4me.api.model.ItemQuery
import io.github.vlsergey.recommend4me.api.model.ItemRef
import io.github.vlsergey.recommend4me.api.model.ItemSort
import io.github.vlsergey.recommend4me.api.model.JobRequest
import io.github.vlsergey.recommend4me.api.model.Mark
import io.github.vlsergey.recommend4me.api.model.RatingRequest
import io.github.vlsergey.recommend4me.api.model.ScorerChoice
import io.github.vlsergey.recommend4me.api.model.TagRelation
import io.github.vlsergey.recommend4me.api.model.TagRelationRequest
import io.github.vlsergey.recommend4me.capture.CaptureController
import io.github.vlsergey.recommend4me.capture.Captures
import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.correction.CorrectionsController
import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.item.ItemsController
import io.github.vlsergey.recommend4me.job.Jobs
import io.github.vlsergey.recommend4me.job.JobsController
import io.github.vlsergey.recommend4me.model.ModelController
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.refusal.Refused
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.settings.SettingsController
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.FacetValue
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.Job
import io.github.vlsergey.recommend4me.source.JobCancelled
import io.github.vlsergey.recommend4me.source.ReviewData
import io.github.vlsergey.recommend4me.source.SettingDef
import io.github.vlsergey.recommend4me.source.Source
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TextDef
import io.github.vlsergey.recommend4me.tag.SiteTagRef
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.tag.Tags
import io.github.vlsergey.recommend4me.tag.TagsController
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.core.io.FileSystemResource
import java.io.File
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two sites of one content type: a site read in the browser ("alpha") and one the application
 * scrapes itself ("beta"). What spans sources — a work on both sites, a tag of both — and what only
 * a scraping site has: jobs, settings, the page of a graded work loaded at once.
 */
@SpringBootTest(properties = ["recommend4me.data-dir=build/test-data/two-sites"])
class TwoSitesTest {

    companion object {
        private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        /** How many times the picture host was asked for a picture. */
        private val pictureHits = Collections.synchronizedList(ArrayList<String>())

        /** A picture of a few bytes: the application keeps what the host gives, it does not decode a preview. */
        private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

        val pictureBase: String get() = "http://127.0.0.1:${server.address.port}"

        @JvmStatic
        @BeforeAll
        fun start() {
            File("build/test-data/two-sites").deleteRecursively()
            server.createContext("/") { exchange ->
                pictureHits += exchange.requestURI.path
                if (exchange.requestURI.path == "/cover.png") {
                    exchange.responseHeaders.add("Content-Type", "image/png")
                    exchange.sendResponseHeaders(200, PNG.size.toLong())
                    exchange.responseBody.use { it.write(PNG) }
                } else {
                    exchange.sendResponseHeaders(404, -1)
                    exchange.close()
                }
            }
            server.start()
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.stop(0)
    }

    @TestConfiguration
    class Plugins {
        @Bean
        fun alpha(): Source = Alpha()

        @Bean
        fun beta(): Source = Beta()

        @Bean
        fun wordsEncoder(): TextEncoder = SiteTest.WordsEncoder()
    }

    /**
     * A site read in the browser: pages "<id>|<title>|<tags>|<annotation>|<picture>|<review>". Its
     * parser's version can be raised: from version 2 it reads the title in capitals.
     */
    class Alpha : Source {
        override val id = "alpha"
        override val contentType = "books"
        override val homepage = "https://alpha.example"
        override val schema = SourceSchema(
            facets = listOf(Books.TAG.asFacet()),
            texts = listOf(TextDef("annotation", block = "text:annotation")),
            reviews = true,
        )
        override val modes = setOf(SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://alpha\\.example/work/.*"))
        override fun itemUrl(itemId: String) = "https://alpha.example/work/$itemId"
        override val parserVersion: Int get() = version

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val parts = page.html.split('|')
            val (id, title, tags, annotation, picture) = parts
            val review = parts[5]
            context.items.upsert(ItemHead(id, itemUrl(id), if (version >= 2) title.uppercase() else title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")), page.capturedAt)
            context.items.setFacet(id, Books.TAG.key, tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it) })
            context.items.setTexts(id, mapOf("annotation" to annotation))
            if (picture.isNotBlank()) context.items.setPictures(id, listOf("$pictureBase/$picture"))
            if (review.isNotBlank()) context.items.saveReviews(id, listOf(ReviewData("r1", "reader", null, review, null)))
            return listOf(id)
        }

        companion object {
            @Volatile
            var version = 1
        }
    }

    /**
     * A site the application scrapes: a job of the whole catalogue writes three works and waits till
     * it is stopped; a job of the updates writes one and ends. The page of a work it is asked to
     * refresh is "loaded" and remembered.
     */
    class Beta : Source {
        override val id = "beta"
        override val contentType = "books"
        override val homepage = "https://beta.example"
        override val schema = SourceSchema(facets = listOf(Books.TAG.asFacet()), texts = listOf(TextDef("annotation", block = "text:annotation")))
        override val modes = setOf(SourceMode.SCRAPE_ALL, SourceMode.UPDATES, SourceMode.BROWSER)
        override val capturePatterns = listOf(Regex("https://beta\\.example/work/.*"))
        override fun itemUrl(itemId: String) = "https://beta.example/work/$itemId"
        override val settings = listOf(SettingDef("cookie", secret = true), SettingDef("delay", default = "5"))

        override fun capture(page: CapturedPage, context: SourceContext): List<String> {
            val (id, title, tags, annotation) = page.html.split('|')
            write(context, id, title, tags, annotation, page.capturedAt)
            return listOf(id)
        }

        private fun write(context: SourceContext, id: String, title: String, tags: String, annotation: String, now: Instant) {
            context.items.upsert(ItemHead(id, itemUrl(id), title, updatedAt = Instant.parse("2026-01-01T00:00:00Z")), now)
            context.items.setFacet(id, Books.TAG.key, tags.split(',').filter { it.isNotBlank() }.map { FacetValue(it, it) })
            context.items.setTexts(id, mapOf("annotation" to annotation))
        }

        override fun scrape(mode: SourceMode, context: SourceContext, job: Job) {
            if (mode == SourceMode.UPDATES) {
                write(context, "u1", "An update", "ocean", "News from the sea", Instant.now())
                job.found(1)
                return
            }
            job.phase("catalogue", total = 3)
            (1..3).forEach { k ->
                write(context, "s$k", "Scraped $k", "ocean", "A scraped tale of the sea $k", Instant.now())
                job.progress(k, 3)
            }
            job.found(3)
            // The rest of a long catalogue: till the user stops it
            while (!job.cancelled) Thread.sleep(10)
            throw JobCancelled()
        }

        override fun needsRefresh(itemId: String, context: SourceContext) = itemId.startsWith("b")

        override fun refresh(itemId: String, context: SourceContext): Boolean {
            refreshed += itemId
            context.items.setTexts(itemId, mapOf("annotation" to "The whole page of $itemId, loaded once graded"))
            return true
        }

        companion object {
            val refreshed: MutableList<String> = Collections.synchronizedList(ArrayList())
        }
    }

    @Autowired lateinit var captures: CaptureController
    @Autowired lateinit var captured: Captures
    @Autowired lateinit var items: ItemsController
    @Autowired lateinit var corrections: CorrectionsController
    @Autowired lateinit var tags: TagsController
    @Autowired lateinit var stores: Stores
    @Autowired lateinit var jobs: Jobs
    @Autowired lateinit var jobsApi: JobsController
    @Autowired lateinit var settingsApi: SettingsController
    @Autowired lateinit var settings: Settings
    @Autowired lateinit var model: ModelController
    @Autowired lateinit var tagService: Tags
    @Autowired lateinit var contexts: SourceContexts
    @Autowired lateinit var searchService: io.github.vlsergey.recommend4me.search.Search

    private fun alpha(body: String) = captures.capturePage(CaptureRequest("https://alpha.example/work/${body.substringBefore('|')}", body))
    private fun beta(body: String) = captures.capturePage(CaptureRequest("https://beta.example/work/${body.substringBefore('|')}", body))

    /** The key of the normalized tag the site's tag [tag] of [source] is bound to. */
    private fun tagOf(source: String, tag: String) = TagRepository.key(stores.type("books")!!.tags.bindings().getValue(SiteTagRef(source, tag)))

    /** Waits up to [seconds] for [condition]; true when it came. */
    private fun waitFor(seconds: Int = 30, condition: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    @Test
    fun `one work on two sites is one work — a grade or a mark of either is of both`() {
        alpha("a1|The Lighthouse|sea|A keeper of a lighthouse waits for the storm||")
        beta("b9|The Lighthouse|ocean|A keeper of a lighthouse waits for the storm")
        corrections.linkItem("alpha", "a1", ItemRef("beta", "b9"))
        items.rateItem("alpha", "a1", RatingRequest(4))
        assertEquals(4, items.getItem("beta", "b9").body!!.summary.grade)
        // The link taken back: each keeps its grade, and is its own after
        corrections.unlinkItem("alpha", "a1", "beta", "b9")
        items.rateItem("beta", "b9", RatingRequest(2))
        assertEquals(4, items.getItem("alpha", "a1").body!!.summary.grade)
        items.unrateItem("alpha", "a1", null)
        items.unrateItem("beta", "b9", null)
    }

    @Test
    fun `tags of two sites merged — the user's answers on both move to the one kept, in each site's database`() {
        alpha("a2|Waves|waves|Waves on the shore||")
        alpha("a3|Calm|calm|A calm sea||")
        beta("b2|Breakers|breakers|Breakers on the rocks")
        beta("b3|Still water|calm|Still water")
        val waves = tagOf("alpha", "waves")
        val breakers = tagOf("beta", "breakers")
        // The user's word on works of both sites: the one tag on one, the other on the other
        corrections.correctFacet("alpha", "a3", FacetCorrection(facet = "tags", added = true, key = breakers))
        corrections.correctFacet("beta", "b3", FacetCorrection(facet = "tags", added = false, key = waves))

        val merged = tags.relateTags("books", TagRelationRequest(waves, breakers, TagRelation.MERGED)).body!!
        assertEquals(setOf(SiteTagRef("alpha", "waves"), SiteTagRef("beta", "breakers")), merged.variants.map { SiteTagRef(it.source, it.tag) }.toSet())
        assertEquals(404, tags.getTag("books", breakers).statusCode.value())
        fun answers(source: String, item: String) = stores.source(source)!!.corrections.facetsOf(item).filter { it.facet == "tags" }.map { it.key to it.added }
        assertEquals(listOf(waves to true), answers("alpha", "a3"))
        assertEquals(listOf(waves to false), answers("beta", "b3"))
    }

    @Test
    fun `one work written at once by several writers — a page sent and a page downloaded — is written whole by each, with no key broken`() {
        val context = contexts.of("alpha")
        val threads = 8
        repeat(30) { round ->
            val id = "race$round"
            val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
            val start = java.util.concurrent.CountDownLatch(1)
            val done = (1..threads).map { k ->
                pool.submit<Unit> {
                    start.await()
                    context.items.upsert(ItemHead(id, "https://alpha.example/work/$id", "Race $k", version = "v$k"), Instant.now())
                    context.items.setFacet(id, Books.TAG.key, listOf(FacetValue("tag$k", "Tag $k")))
                    context.items.setTexts(id, mapOf("annotation" to "Written by $k"))
                    context.items.setNumbers(id, mapOf("likes" to k.toDouble()))
                }
            }
            start.countDown()
            done.forEach { it.get() }
            pool.shutdown()
            val head = assertNotNull(context.items.find(id))
            assertTrue(head.title.startsWith("Race "))
            assertEquals(1, context.items.facets(id)[Books.TAG.key]?.size, "one writer's tags whole")
        }
    }

    @Test
    fun `a universe or a pairing named by a name typed that the dictionary has not is refused, never made a value`() {
        alpha("n1|Wizards|magic|A school of magic||")
        listOf("universe", "pairings").forEach { facet ->
            val refused = assertThrows<Refused> { corrections.correctFacet("alpha", "n1", FacetCorrection(facet = facet, added = true, name = "Harry Potter")) }
            assertEquals(Refusal.NOT_IN_DICTIONARY, refused.refusal)
            assertEquals(mapOf("value" to "Harry Potter"), refused.params)
        }
        assertTrue(stores.source("alpha")!!.corrections.facetsOf("n1").none { it.facet == "universe" || it.facet == "pairings" })
        // A character may be named as the work names it
        corrections.correctFacet("alpha", "n1", FacetCorrection(facet = "characters", added = true, name = "Neville"))
        assertEquals(listOf("neville"), stores.source("alpha")!!.corrections.facetsOf("n1").filter { it.facet == "characters" }.map { it.key })
    }

    @Test
    fun `a word on two tags refused leaves what was said of them as it was`() {
        alpha("t1|Fleet|fleet|A fleet sails||")
        alpha("t2|Frigates|frigates|Frigates of the fleet||")
        alpha("t3|Gunboats|gunboats|Gunboats of the frigates||")
        val fleet = tagOf("alpha", "fleet")
        val frigates = tagOf("alpha", "frigates")
        val gunboats = tagOf("alpha", "gunboats")
        tags.relateTags("books", TagRelationRequest(fleet, frigates, TagRelation.CHILD))
        tags.relateTags("books", TagRelationRequest(frigates, gunboats, TagRelation.CHILD))
        // And right under the fleet as well
        tags.relateTags("books", TagRelationRequest(fleet, gunboats, TagRelation.CHILD))
        // The fleet put under the gunboats: it is above them through the frigates — refused, and the
        // link said of the two before is not taken away by the refused word
        assertEquals(Refusal.TAG_ABOVE, assertThrows<Refused> { tags.relateTags("books", TagRelationRequest(gunboats, fleet, TagRelation.CHILD)) }.refusal)
        assertTrue(fleet in tags.getTag("books", gunboats).body!!.parents.map { it.key })
        tags.relateTags("books", TagRelationRequest(fleet, gunboats, TagRelation.NONE))
        tags.relateTags("books", TagRelationRequest(frigates, gunboats, TagRelation.NONE))
        tags.relateTags("books", TagRelationRequest(fleet, frigates, TagRelation.NONE))
    }

    @Test
    fun `the answers of a merge a stop left unmoved are moved on the start`() {
        alpha("t4|Anchors|anchors|Anchors aweigh||")
        beta("t5|Moorings|moorings|At the moorings")
        val anchors = tagOf("alpha", "anchors")
        val moorings = tagOf("beta", "moorings")
        corrections.correctFacet("beta", "t5", FacetCorrection(facet = "tags", added = true, key = moorings))
        // Merged, and stopped before the answers were moved
        val type = stores.type("books")!!
        type.tags.merge(TagRepository.idOf(moorings)!!, TagRepository.idOf(anchors)!!, Instant.now())
        assertEquals(listOf(moorings to true), stores.source("beta")!!.corrections.facetsOf("t5").filter { it.facet == "tags" }.map { it.key to it.added })

        tagService.start()
        assertEquals(listOf(anchors to true), stores.source("beta")!!.corrections.facetsOf("t5").filter { it.facet == "tags" }.map { it.key to it.added })
        assertEquals(emptyList(), type.tags.pendingMerges())
    }

    @Test
    fun `a tag under another through a third is marked so among the alike, and is said of the tags between`() {
        alpha("a4|Sailing|sailing|Sails and winds||")
        alpha("a5|Yachts|yachts|A yacht race||")
        alpha("a6|Regatta|regatta|The regatta of yachts||")
        val sailing = tagOf("alpha", "sailing")
        val yachts = tagOf("alpha", "yachts")
        val regatta = tagOf("alpha", "regatta")
        tags.relateTags("books", TagRelationRequest(sailing, yachts, TagRelation.CHILD))
        tags.relateTags("books", TagRelationRequest(yachts, regatta, TagRelation.CHILD))

        fun relationOf(tag: String, other: String) = tags.listSimilarTags("books", tag, null, 0, 500, null, null).body!!.tags.single { it.tag.key == other && it.site == null }.relation
        assertEquals(TagRelation.CHILD, relationOf(sailing, yachts))
        assertEquals(TagRelation.DESCENDANT, relationOf(sailing, regatta))
        assertEquals(TagRelation.ANCESTOR, relationOf(regatta, sailing))
        assertEquals(TagRelation.PARENT, relationOf(regatta, yachts))
        // Hidden by their filter, the others not
        val shown = tags.listSimilarTags("books", sailing, null, 0, 500, null, listOf(TagRelation.DESCENDANT, TagRelation.ANCESTOR)).body!!.tags
        assertTrue(shown.none { it.tag.key == regatta && it.site == null } && shown.any { it.tag.key == yachts })
        // Never said of the two themselves
        val through = assertThrows<Refused> { tags.relateTags("books", TagRelationRequest(sailing, regatta, TagRelation.DESCENDANT)) }
        assertEquals(Refusal.TAG_THROUGH, through.refusal)
        tags.relateTags("books", TagRelationRequest(yachts, regatta, TagRelation.NONE))
        tags.relateTags("books", TagRelationRequest(sailing, yachts, TagRelation.NONE))
    }

    @Test
    fun `a scraping job writes the catalogue, is remembered as running till it ends, is stopped, and goes on after a restart`() {
        assertTrue(jobs.start("beta", SourceMode.SCRAPE_ALL))
        assertTrue(waitFor { jobs.status("beta").newItems == 3 })
        assertEquals("RUNNING", jobsApi.listJobs().body!!.single { it.source == "beta" }.state.name)
        // One job of a source at a time; no job of a source that has no such mode
        assertTrue(!jobs.start("beta", SourceMode.UPDATES))
        assertTrue(!jobs.start("alpha", SourceMode.UPDATES))
        // Remembered as running: a restart goes on with it
        assertEquals(SourceMode.SCRAPE_ALL, jobs.resumable("beta"))
        assertNotNull(items.getItem("beta", "s3").body)

        jobs.cancel("beta")
        assertTrue(waitFor { jobs.status("beta").state.name == "CANCELLED" })
        assertNull(jobs.resumable("beta"), "stopped by the user, it waits for the user")

        // Left running by the last stop of the application: started again on the start
        settings.set("job.beta.running", SourceMode.SCRAPE_ALL.name)
        jobs.resume()
        assertTrue(waitFor { jobs.status("beta").state.name == "RUNNING" })
        jobs.cancel("beta")
        assertTrue(waitFor { jobs.status("beta").state.name == "CANCELLED" })

        // A job of the updates runs to its end and is not remembered
        assertEquals("RUNNING", jobsApi.startJob("beta", JobRequest(io.github.vlsergey.recommend4me.api.model.SourceMode.UPDATES)).body!!.state.name)
        assertTrue(waitFor(120) { jobs.status("beta").state.name == "DONE" }, "state ${jobs.status("beta")}")
        assertEquals(1, jobs.status("beta").newItems)
        assertNull(jobs.resumable("beta"))
        assertNotNull(items.getItem("beta", "u1").body)
    }

    @Test
    fun `the page of a work graded is loaded at once, when the site has more of it`() {
        beta("b5|Deep|depth|Short")
        items.rateItem("beta", "b5", RatingRequest(5))
        assertTrue(waitFor { "b5" in Beta.refreshed })
        assertTrue(waitFor { items.getItem("beta", "b5").body!!.texts.any { "loaded once graded" in it.content } })
        // A work the site has no more of is not asked for
        beta("x5|Shallow|depth|Short")
        items.rateItem("beta", "x5", RatingRequest(3))
        Thread.sleep(500)
        assertTrue("x5" !in Beta.refreshed)
        items.unrateItem("beta", "b5", null)
        items.unrateItem("beta", "x5", null)
    }

    @Test
    fun `kept pages are read again when the parser changes`() {
        alpha("a7|Quiet harbour|harbour|Boats at rest||")
        assertEquals("Quiet harbour", items.getItem("alpha", "a7").body!!.summary.title)
        Alpha.version = 2
        try {
            captured.reparse()
            assertEquals("QUIET HARBOUR", items.getItem("alpha", "a7").body!!.summary.title)
        } finally {
            Alpha.version = 1
        }
    }

    @Test
    fun `a review and a picture are marked, taken back, and only with a mark of +1 or -1`() {
        alpha("a8|Storm|storm|A storm at sea|cover.png|The storm scene is wonderful")
        assertEquals(204, items.markReview("alpha", "a8", "r1", Mark(1)).statusCode.value())
        assertEquals(1, items.getItem("alpha", "a8").body!!.reviews.single().mark)
        assertEquals(204, items.unmarkReview("alpha", "a8", "r1").statusCode.value())
        assertNull(items.getItem("alpha", "a8").body!!.reviews.single().mark)
        assertEquals(404, items.markReview("alpha", "a8", "no-such", Mark(1)).statusCode.value())
        assertThrows<Refused> { items.markReview("alpha", "a8", "r1", Mark(0)) }

        assertEquals(204, items.markPicture("alpha", "a8", 0, Mark(-1)).statusCode.value())
        assertEquals(-1, items.getItem("alpha", "a8").body!!.pictures.single().mark)
        items.unmarkPicture("alpha", "a8", 0)
        assertNull(items.getItem("alpha", "a8").body!!.pictures.single().mark)
        assertEquals(404, items.markPicture("alpha", "a8", 7, Mark(1)).statusCode.value())
    }

    @Test
    fun `a picture is downloaded once and served from the file after, one the host has not is sent to the host`() {
        alpha("a9|Cover|cover|A work with a cover|cover.png|")
        pictureHits.clear()
        val first = items.getPicture("alpha", "a9", 0, false)
        assertEquals(200, first.statusCode.value())
        assertEquals("image/png", first.headers.contentType.toString())
        assertTrue((first.body as FileSystemResource).file.readBytes().contentEquals(PNG))
        assertEquals(200, items.getPicture("alpha", "a9", 0, false).statusCode.value())
        assertEquals(listOf("/cover.png"), pictureHits.toList())
        // The original not kept: the browser is sent to the site for it
        assertEquals(302, items.getPicture("alpha", "a9", 0, true).statusCode.value())

        alpha("a10|Lost cover|cover|A cover the host lost|lost.png|")
        val lost = items.getPicture("alpha", "a10", 0, false)
        assertEquals(302, lost.statusCode.value())
        assertEquals("$pictureBase/lost.png", lost.headers.location.toString())
        assertEquals(404, items.getPicture("alpha", "a10", 3, false).statusCode.value())
    }

    @Test
    fun `a source's settings — a secret never sent back, a default shown, a blank value taken away, an unknown key refused`() {
        fun beta() = settingsApi.getSettings().body!!.single { it.source == "beta" }.propertyValues.associateBy { it.key }
        assertTrue(settingsApi.getSettings().body!!.none { it.source == "alpha" }, "a source without settings is not listed")
        assertEquals(false, beta().getValue("cookie").set)
        assertEquals("5", beta().getValue("delay").value)
        assertEquals("beta:setting.cookie", beta().getValue("cookie").labelKey)

        settingsApi.updateSourceSettings("beta", mapOf("cookie" to " session=1 ", "delay" to "7"))
        assertEquals(true, beta().getValue("cookie").set)
        assertNull(beta().getValue("cookie").value)
        assertEquals("session=1", settings.of("beta").get("cookie"))
        assertEquals("7", beta().getValue("delay").value)

        settingsApi.updateSourceSettings("beta", mapOf("delay" to "  "))
        assertEquals("5", beta().getValue("delay").value)
        val unknown = assertThrows<Refused> { settingsApi.updateSourceSettings("beta", mapOf("nope" to "1")) }
        assertEquals(Refusal.NO_SETTING, unknown.refusal)
        assertEquals(mapOf("setting" to "nope"), unknown.params)
        assertEquals(404, settingsApi.updateSourceSettings("nobody", emptyMap()).statusCode.value())
    }

    @Test
    fun `the model of a type — trained on demand, its scorer chosen, an unknown scorer refused`() {
        (1..8).forEach { k ->
            alpha("m$k|Work $k|${if (k % 2 == 0) "sea" else "land"}|${if (k % 2 == 0) "Ships and storms at sea" else "Fields and farms on land"} $k||")
            items.rateItem("alpha", "m$k", RatingRequest(if (k % 2 == 0) 5 else 1))
        }
        val trained = model.trainModel("books").body!!
        assertTrue(trained.trained)
        assertTrue(trained.scorers.any { it.id == "pairwise-logistic" })
        assertEquals("pairwise-logistic", model.chooseScorer("books", ScorerChoice("pairwise-logistic")).body!!.scorer)
        assertEquals(Refusal.NO_SCORER, assertThrows<Refused> { model.chooseScorer("books", ScorerChoice("nope")) }.refusal)
        assertTrue(model.getModel("books").body!!.trained)
        assertEquals(404, model.getModel("nothing").statusCode.value())
        (1..8).forEach { items.unrateItem("alpha", "m$it", null) }
    }

    @Test
    fun `a tag the user gave a work finds it at once`() {
        alpha("q3|Deep water|water|Waves over the reef||")
        // Indexed as the site wrote it first
        assertTrue(waitFor(60) { items.listItems("books", ItemQuery(search = "water")).body!!.items.any { it.item == "q3" } })
        corrections.correctFacet("alpha", "q3", FacetCorrection(facet = "tags", added = true, name = "Mermaids"))
        assertTrue(
            waitFor(60) { items.listItems("books", ItemQuery(search = "mermaids", sort = ItemSort.SCORE)).body!!.items.firstOrNull()?.item == "q3" },
            "document ${searchService.documents(stores.source("alpha")!!, listOf("q3"), passages = false).values.single().fields.map { it.key to it.values }}",
        )
    }

    @Test
    fun `a search finds the works by their words, the likest first`() {
        alpha("q1|Night watch|watch|A lighthouse keeper watches the night sea||")
        alpha("q2|Gardens|garden|Roses and tulips in a spring garden||")
        val found = waitFor(60) {
            items.listItems("books", ItemQuery(search = "lighthouse keeper", sort = ItemSort.SCORE)).body!!.items.firstOrNull()?.item == "q1"
        }
        assertTrue(found, "found ${items.listItems("books", ItemQuery(search = "lighthouse keeper")).body!!.items.map { it.item }}")
    }
}
