package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.vector.Vectors
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val log = LoggerFactory.getLogger(TextVectors::class.java)

/** The vectors of the texts of these items of a source were written or dropped. */
data class TextVectorsChanged(val source: String, val ids: List<String>)

/**
 * Vectors of the items' texts — those the model reads ([TextDef.block][io.github.vlsergey.recommend4me.source.TextDef.block]),
 * those searched by meaning, and the title ([TITLE]) — of the text as the user corrected it, kept with its hash and the
 * encoder that made them: a text is encoded again when it or the encoder changed.
 *
 * On start every source is gone through; afterwards the items whose texts were written are encoded
 * a few seconds after the last write — the queue of their ids is the only thing kept here.
 *
 * NO LOCK OVER AN ENCODING: re-encoding a catalogue after a change of encoder takes an hour; only
 * one WHOLE refresh of a source runs at a time, while refreshes of single items always run.
 */
@Service
class TextVectors(
    private val stores: Stores,
    private val plugins: Plugins,
    private val events: ApplicationEventPublisher,
) {
    private val whole = ConcurrentHashMap<String, AtomicBoolean>()
    private val queued = ConcurrentHashMap<String, MutableSet<String>>()
    private val worker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "text-vectors").apply { isDaemon = true } }
    private val scheduled = AtomicBoolean(false)

    @Volatile
    private var running = false

    fun running(): Boolean = running

    @EventListener
    fun itemsChanged(event: ItemsChanged) {
        queued.computeIfAbsent(event.source) { ConcurrentHashMap.newKeySet() } += event.ids
        if (scheduled.compareAndSet(false, true)) worker.schedule(::drain, DELAY_SECONDS, TimeUnit.SECONDS)
    }

    private fun drain() {
        scheduled.set(false)
        queued.keys.forEach { source ->
            val ids = queued[source] ?: return@forEach
            val batch = ids.toList()
            ids.removeAll(batch.toSet())
            val store = stores.source(source) ?: return@forEach
            try {
                refresh(store, only = batch)
            } catch (e: Exception) {
                log.warn("{}: texts of {} items were not encoded: {}", source, batch.size, e.message)
            }
        }
    }

    @Order(3)
    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        worker.execute {
            stores.sources.forEach { s ->
                try {
                    refresh(s)
                    fillWindows(s)
                } catch (e: Exception) {
                    log.error("{}: text vectors were not refreshed on start", s.id, e)
                }
            }
        }
    }

    /**
     * Encodes every text of the source whose text is new or changed, and drops the vectors of texts
     * that are gone; with [only], of those items. Returns how many texts were encoded.
     */
    fun refresh(
        store: SourceStore,
        only: Collection<String>? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): Int {
        val encoder = plugins.textEncoder() ?: return 0
        val flag = whole.computeIfAbsent(store.id) { AtomicBoolean(false) }
        if (only == null && !flag.compareAndSet(false, true)) return 0
        running = true
        try {
            val keys = store.schema.texts.filter { it.block != null || it.searchByMeaning }.map { it.key }
            val stored = if (only == null) store.textVectors.hashes(encoder.id) else only.flatMap { store.textVectors.hashes(encoder.id, it).entries }.associate { it.toPair() }
            val known = if (only == null) store.textVectors.keys() else only.flatMap { store.textVectors.keys(it) }.toSet()
            val fields = if (only == null) store.corrections.allFields() else store.corrections.fieldsOf(only)
            // The title of every item, as the user corrected it: a text the models read like any other
            val titles = if (only == null) store.items.titles() else store.items.heads(only).mapValues { it.value.title }

            class Todo(val itemId: String, val key: String, val text: String, val hash: String)
            val todo = ArrayList<Todo>()
            val present = HashSet<Pair<String, String>>()
            fun consider(itemId: String, site: Map<String, String>) {
                val own = fields[itemId].orEmpty()
                val texts = Corrected.texts(site, own) + listOfNotNull(titles[itemId]?.let { TITLE to Corrected.title(it, own) })
                (keys + TITLE).forEach { key ->
                    val text = texts[key]?.takeIf { it.isNotBlank() } ?: return@forEach
                    present += itemId to key
                    val hash = Vectors.hash(text)
                    if (stored[itemId to key] != hash) todo += Todo(itemId, key, text, hash)
                }
            }
            val considered = HashSet<String>()
            if (only == null) {
                if (keys.isNotEmpty()) store.items.forEachTexts(keys) { id, texts -> considered += id; consider(id, texts) }
                // The items with a title and no other text
                titles.keys.filter { it !in considered }.forEach { consider(it, emptyMap()) }
            } else store.items.textsOf(only, keys).let { all -> only.forEach { id -> consider(id, all[id].orEmpty()) } }
            val gone = known.filter { it !in present && (only == null || it.first in only) }
            gone.forEach { (id, key) -> store.textVectors.delete(id, key) }
            if (gone.isNotEmpty()) events.publishEvent(TextVectorsChanged(store.id, gone.map { it.first }.distinct()))
            if (todo.isEmpty()) return 0

            // The graded items first: the model learns from them, and an hour of re-encoding should
            // not leave it without texts until the end
            val graded = store.ratings.gradedItems()
            todo.sortByDescending { it.itemId in graded }
            if (todo.size > BATCH) log.info("{}: encoding {} texts by {}", store.id, todo.size, encoder.id)
            val started = System.currentTimeMillis()
            var done = 0
            for (batch in todo.chunked(BATCH)) {
                if (cancelled()) break
                val vectors = encoder.encode(batch.map { it.text })
                batch.forEachIndexed { i, t ->
                    vectors[i]?.let { store.textVectors.save(t.itemId, t.key, encoder.id, t.hash, it) }
                }
                keepWindows(store, batch.filterIndexed { i, _ -> vectors[i] != null }.map { Triple(it.itemId, it.key, it.text) })
                events.publishEvent(TextVectorsChanged(store.id, batch.map { it.itemId }.distinct()))
                done += batch.size
                progress(done, todo.size)
            }
            if (todo.size > BATCH) log.info("{}: {} texts encoded in {} ms", store.id, done, System.currentTimeMillis() - started)
            return done
        } finally {
            if (only == null) flag.set(false)
            running = false
        }
    }

    /**
     * The windows of texts just encoded: how many each has, and of a text of more than one the
     * vector of every window — what the search finds a piece of a long text by.
     */
    private fun keepWindows(store: SourceStore, texts: List<Triple<String, String, String>>) {
        val encoder = plugins.textEncoder() ?: return
        val counts = texts.map { encoder.windowSpans(it.third).size }
        val long = texts.indices.filter { counts[it] > 1 }
        val windows = if (long.isEmpty()) emptyList() else encoder.encodeWindows(long.map { texts[it].third })
        val of = long.withIndex().associate { (k, i) -> i to windows[k] }
        texts.forEachIndexed { i, (id, key, _) -> store.textVectors.saveWindows(id, key, counts[i], of[i].orEmpty()) }
    }

    /**
     * The windows of the texts encoded before their windows were kept, made now, a batch at a time:
     * of the texts as they are now — a text changed since is encoded again by [refresh], its windows
     * with it. Answers how many texts were gone through.
     */
    fun fillWindows(store: SourceStore, cancelled: () -> Boolean = { false }): Int {
        val encoder = plugins.textEncoder() ?: return 0
        val keys = store.schema.texts.filter { it.block != null || it.searchByMeaning }.map { it.key }
        var done = 0
        val started = System.currentTimeMillis()
        while (!cancelled()) {
            val todo = store.textVectors.withoutWindows(encoder.id, keys + TITLE, BATCH)
            if (todo.isEmpty()) break
            val ids = todo.map { it.first }.distinct()
            val fields = store.corrections.fieldsOf(ids)
            val site = store.items.textsOf(ids, keys)
            val titles = store.items.heads(ids).mapValues { it.value.title }
            val texts = todo.map { (id, key) ->
                val own = fields[id].orEmpty()
                val all = Corrected.texts(site[id].orEmpty(), own) + listOfNotNull(titles[id]?.let { TITLE to Corrected.title(it, own) })
                // A text gone since: no windows, and the next refresh drops its vector
                Triple(id, key, all[key].orEmpty())
            }
            keepWindows(store, texts)
            // The works searched by the meaning of the windows just kept: indexed again with them
            events.publishEvent(TextVectorsChanged(store.id, ids))
            done += texts.size
        }
        if (done > 0) log.info("{}: windows of {} texts counted in {} ms", store.id, done, System.currentTimeMillis() - started)
        return done
    }

    /** The vector of a search line as a query; null without a text encoder. */
    fun query(line: String): FloatArray? =
        plugins.textEncoder()?.encode(listOf(line), io.github.vlsergey.recommend4me.encoder.TextKind.QUERY)?.first()

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        /** The key the title of an item is kept under among its texts' vectors. */
        const val TITLE = "title"

        /**
         * Every text of the source's items the models read, by its key, with the block of the
         * models' input it is and its label: every text that has vectors — those of the source's
         * own blocks, the lines searched by meaning (the site's line of characters) — and the title.
         */
        fun blocks(schema: SourceSchema): Map<String, Pair<String, Label>> =
            schema.texts.filter { it.block != null || it.searchByMeaning }.associate { it.key to ((it.block ?: "text:${it.key}") to it.label) } +
                (TITLE to ("text:$TITLE" to TITLE_LABEL))

        val TITLE_LABEL = Label("Title", "Название", "Naslov")

        /** Texts per encoder call; their windows share batches of the graph. */
        private const val BATCH = 32
        private const val DELAY_SECONDS = 5L
    }
}
