package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.CapturedPage
import io.github.vlsergey.recommend4me.source.ItemStore
import io.github.vlsergey.recommend4me.source.PartData
import io.github.vlsergey.recommend4me.source.SourceContext
import io.github.vlsergey.recommend4me.source.SourceContexts
import io.github.vlsergey.recommend4me.source.SourceMode
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.time.Instant

private val log = LoggerFactory.getLogger(Captures::class.java)

/** A part whose text a captured page gave: the item, the part's title, how long its text is. */
class CapturedPart(val item: ItemKey, val title: String?, val length: Int)

/** What a captured page gave: the source that took it, the items it touched, the parts whose text it gave. */
class Captured(val source: String?, val items: List<ItemKey>, val parts: List<CapturedPart> = emptyList())

/**
 * THE BROWSER TRACKING MODE: the pages the user opens, sent by the extension. Every page a source
 * wants is kept (gzipped, in the source database) and handed to the source; a kept page is handed
 * to it again when the source's parser grows ([Source.parserVersion][io.github.vlsergey.recommend4me.source.Source.parserVersion]).
 */
@Service
class Captures(private val stores: Stores, private val contexts: SourceContexts) {

    private fun tracking(): List<SourceStore> = stores.sources.filter { SourceMode.BROWSER in it.source.modes }

    /** The addresses every source wants, by source. */
    fun patterns(): Map<String, List<String>> = tracking().associate { s -> s.id to s.source.capturePatterns.map { it.pattern } }

    fun capture(url: String, html: String): Captured {
        val store = tracking().firstOrNull { s -> s.source.capturePatterns.any { it.matches(url) } } ?: return Captured(null, emptyList())
        val now = Instant.now()
        val started = System.currentTimeMillis()
        // The parts whose text the page gave, as the source saves them: the user sees a chapter's text went
        val texts = ArrayList<CapturedPart>()
        val own = contexts.of(store.id)
        val context = object : SourceContext by own {
            override val items: ItemStore = object : ItemStore by own.items {
                override fun saveParts(itemId: String, parts: List<PartData>) {
                    own.items.saveParts(itemId, parts)
                    parts.forEach { p -> p.content?.let { texts += CapturedPart(ItemKey(store.id, itemId), p.title, it.length) } }
                }
            }
        }
        val items = try {
            store.source.capture(CapturedPage(url, html, now), context)
        } catch (e: Exception) {
            log.warn("{}: the page {} was not read: {}", store.id, url, e.message)
            emptyList()
        }
        store.pages.keep(url, html, items.firstOrNull(), now, store.source.parserVersion)
        log.info("{}: {} gave {} items, the text of {} parts, in {} ms", store.id, url, items.size, texts.size, System.currentTimeMillis() - started)
        return Captured(store.id, items.map { ItemKey(store.id, it) }, texts)
    }

    /** The pages kept last of every source, newest first. */
    fun recent(limit: Int): List<Pair<String, KeptPage>> =
        tracking().flatMap { s -> s.pages.recent(limit).map { s.id to it } }.sortedByDescending { it.second.capturedAt }.take(limit)

    /** The kept pages read by an older parser, read again. */
    @Order(1)
    @EventListener(ApplicationReadyEvent::class)
    fun reparse() {
        stores.sources.forEach { s ->
            val version = s.source.parserVersion
            val older = s.pages.older(version)
            if (older.isEmpty()) return@forEach
            log.info("{}: reading {} kept pages again", s.id, older.size)
            val context = contexts.of(s.id)
            older.forEach { url ->
                val (page, html) = s.pages.read(url) ?: return@forEach
                try {
                    s.source.capture(CapturedPage(page.url, html, page.capturedAt), context)
                } catch (e: Exception) {
                    log.warn("{}: the kept page {} cannot be read: {}", s.id, url, e.message)
                }
                s.pages.markRead(url, version)
            }
        }
    }
}
