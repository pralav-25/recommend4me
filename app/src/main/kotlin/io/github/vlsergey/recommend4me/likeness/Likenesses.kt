package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.mark.MarkKind
import io.github.vlsergey.recommend4me.mark.MatchFeedback
import io.github.vlsergey.recommend4me.mark.ThingRef
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.review.ReviewRepository
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.vector.Vectors
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant

private val log = LoggerFactory.getLogger(Likenesses::class.java)

/** The likeness of every item of a content type to the marked pictures and to the marked reviews. */
class TypeLikeness(val pictures: MarkLikeness, val reviews: MarkLikeness) {
    val empty get() = pictures.marks.isEmpty() && reviews.marks.isEmpty()

    /** The axes of every kind of an item, its own marks and those of the works [excluded] not counted. */
    fun vectors(key: ItemKey, excluded: Set<ItemKey> = emptySet()): Map<String, FloatArray> = buildMap {
        pictures.vector(key, excluded)?.let { put(MarkLikeness.PICTURES, it) }
        reviews.vector(key, excluded)?.let { put(MarkLikeness.REVIEWS, it) }
    }
}

/**
 * Makes and keeps the likeness of the items of a content type to what the user marked: see
 * [MarkLikeness]. It is made again — a pass over every analysed picture and every review with a
 * vector — only when the marks or the user's words on their matches change, or the catalogue has
 * grown by [REMAKE_GROWTH]; a grade changes nothing in it.
 */
@Component
class Likenesses(private val plugins: Plugins, private val phrases: PhraseVectors) {

    /**
     * The likeness for a training, made again if it must be; [pictureCenter] the catalogue's mean picture.
     */
    fun forTraining(type: TypeStore, pictureCenter: FloatArray?, graded: Collection<ItemKey>): TypeLikeness {
        val imageEncoder = plugins.imageEncoder()
        val textEncoder = plugins.textEncoder()
        val pictures = if (imageEncoder == null || pictureCenter == null) MarkLikeness.none(0) else likenessOf(
            type, MarkKind.PICTURE, type.sources.sumOf { it.pictures.countAnalyzed(imageEncoder.id) }.toLong(),
            fingerprint(type, MarkKind.PICTURE, imageEncoder.id), graded,
        ) { makePictures(type, imageEncoder.id, pictureCenter) }
        val reviews = if (textEncoder == null) MarkLikeness.none(0) else likenessOf(
            type, MarkKind.REVIEW, type.sources.sumOf { it.reviews.countKeyed() }.toLong(),
            fingerprint(type, MarkKind.REVIEW, textEncoder.id), graded,
        ) { makeReviews(type, textEncoder.id) }
        return TypeLikeness(pictures, reviews)
    }

    /** The stored likeness without the items' rows — what one item is measured with. */
    fun stored(type: TypeStore): TypeLikeness = TypeLikeness(
        type.likeness.loadCalibration(MarkKind.PICTURE) ?: MarkLikeness.none(0),
        type.likeness.loadCalibration(MarkKind.REVIEW) ?: MarkLikeness.none(0),
    )

    /** One item's likeness to the marks, measured now from its own pictures and reviews with the [stored] calibration. */
    fun of(type: TypeStore, key: ItemKey, stored: TypeLikeness): Map<String, FloatArray> = buildMap {
        val store = type.source(key.source) ?: return@buildMap
        plugins.imageEncoder()?.let { encoder ->
            stored.pictures.rowOf(key, store.pictures.positioned(key.id, encoder.id).map { (p, v) -> p.toString() to v })
                ?.let { row -> put(MarkLikeness.PICTURES, stored.pictures.vector(row) { it.owner == key }) }
        }
        if (stored.reviews.marks.isNotEmpty()) {
            val texts = store.reviews.ofItem(key.id).map { it.reviewId to ReviewRepository.textOf(it.content) }.filter { it.second.isNotEmpty() }
            val vectors = phrases.cached(store, texts.map { it.second })
            stored.reviews.rowOf(key, texts.mapNotNull { (id, text) -> vectors[text]?.let { id to it } })
                ?.let { row -> put(MarkLikeness.REVIEWS, stored.reviews.vector(row) { it.owner == key }) }
        }
    }

    private fun likenessOf(type: TypeStore, kind: MarkKind, items: Long, fingerprint: Long, graded: Collection<ItemKey>, make: () -> MarkLikeness): MarkLikeness {
        val made = type.likeness.made(kind)
        if (made != null && made.fingerprint == fingerprint && items <= made.items * (1 + REMAKE_GROWTH)) {
            type.likeness.load(kind, graded)?.let { return it }
        }
        val started = System.currentTimeMillis()
        val likeness = make()
        type.likeness.save(kind, likeness, LikenessMade(fingerprint, items), Instant.now())
        log.info("{}: likeness to {} marked {} made over {} items in {} ms", type.id, likeness.marks.size, kind, items, System.currentTimeMillis() - started)
        return likeness
    }

    private fun feedback(type: TypeStore, kind: MarkKind): List<MatchFeedback> = type.sources.flatMap { it.marks.feedback(kind) }

    /** What the likeness of a kind is made of: the marks, the words on their matches, the encoder, the way it is made. */
    private fun fingerprint(type: TypeStore, kind: MarkKind, encoder: String): Long {
        val marks = when (kind) {
            MarkKind.PICTURE -> type.sources.flatMap { s -> s.marks.pictureMarks().map { "${s.id}/${it.itemId}:${it.position}:${it.url}:${it.mark}" } }
            MarkKind.REVIEW -> type.sources.flatMap { s -> s.marks.reviewMarks().map { "${s.id}/${it.itemId}:${it.reviewId}:${it.mark}" } }
        }
        val words = feedback(type, kind).map { "${it.mark.item}:${it.mark.ref}:${it.match.item}:${it.match.ref}:${it.verdict}" }
        return Vectors.keyOf((listOf("v$VERSION", encoder) + marks.sorted() + words.sorted()).joinToString("|"))
    }

    private fun makePictures(type: TypeStore, encoder: String, center: FloatArray): MarkLikeness {
        val feedback = feedback(type, MarkKind.PICTURE)
        // The pictures the user's words on the matches name
        val named = HashMap<ThingRef, FloatArray>()
        feedback.map { it.match.item }.distinct().forEach { key ->
            type.source(key.source)?.pictures?.positioned(key.id, encoder)?.forEach { (p, v) -> named[ThingRef(key, p.toString())] = v }
        }
        val marks = type.sources.flatMap { s ->
            val marks = s.marks.pictureMarks()
            val vectors = s.pictures.vectorsOf(marks.map { it.itemId to it.position }, encoder)
            marks.mapNotNull { m ->
                val (url, v) = vectors[m.itemId to m.position] ?: return@mapNotNull null
                // A picture whose address changed is another one: the mark is not its
                if (url != m.url) return@mapNotNull null
                withFeedback(Marked(ItemKey(s.id, m.itemId), m.position.toString(), m.mark, v), feedback) { named[it] }
            }
        }
        return MarkLikeness.of(marks, center) { action ->
            type.sources.forEach { s ->
                s.pictures.forEachItem(encoder) { id, list -> action(ItemKey(s.id, id), list.map { (p, v) -> p.toString() to v }) }
            }
        }
    }

    /**
     * A review not encoded yet is left out until it is (the set vectors encode them in the
     * background): the likeness never waits for all of them. The catalogue's mean review is counted
     * in a first pass over the reviews' vectors.
     */
    private fun makeReviews(type: TypeStore, encoder: String): MarkLikeness {
        val feedback = feedback(type, MarkKind.REVIEW)
        // The reviews the user's words on the matches name, of whatever source of the type
        val named = HashMap<ThingRef, FloatArray>()
        feedback.map { it.match }.distinct().groupBy { it.item.source }.forEach { (sourceId, refs) ->
            val s = type.source(sourceId) ?: return@forEach
            val texts = s.reviews.contents(refs.map { it.item.id to it.ref }).mapValues { ReviewRepository.textOf(it.value) }
            val vectors = phrases.of(s, texts.values.filter { it.isNotEmpty() })
            texts.forEach { (ref, text) -> vectors[text]?.let { named[ThingRef(ItemKey(sourceId, ref.first), ref.second)] = it } }
        }
        val marks = type.sources.flatMap { s ->
            val marks = s.marks.reviewMarks()
            if (marks.isEmpty()) return@flatMap emptyList()
            val contents = s.reviews.contents(marks.map { it.itemId to it.reviewId }).mapValues { ReviewRepository.textOf(it.value) }
            val vectors = phrases.of(s, contents.values.filter { it.isNotEmpty() })
            marks.mapNotNull { m ->
                val v = contents[m.itemId to m.reviewId]?.let(vectors::get) ?: return@mapNotNull null
                withFeedback(Marked(ItemKey(s.id, m.itemId), m.reviewId, m.mark, v), feedback) { named[it] }
            }
        }
        if (marks.isEmpty()) return MarkLikeness.none(0)
        val dim = marks.first().vector.size
        val sum = DoubleArray(dim)
        var n = 0
        type.sources.forEach { s ->
            s.reviews.forEachItemVectors(encoder) { _, list ->
                list.forEach { (_, v) ->
                    for (k in v.indices) sum[k] += v[k].toDouble()
                    n++
                }
            }
        }
        val center = FloatArray(dim) { if (n > 0) (sum[it] / n).toFloat() else 0f }
        return MarkLikeness.of(marks, center) { action ->
            type.sources.forEach { s -> s.reviews.forEachItemVectors(encoder) { id, list -> action(ItemKey(s.id, id), list) } }
        }
    }

    /** The [mark] with what the user said of its matches; [vectorOf] finds a match's vector, null when it has none now. */
    private fun withFeedback(mark: Marked, feedback: List<MatchFeedback>, vectorOf: (ThingRef) -> FloatArray?): Marked {
        val own = feedback.filter { it.mark.item == mark.owner && it.mark.ref == mark.ref }
        if (own.isEmpty()) return mark
        return Marked(
            mark.owner, mark.ref, mark.mark, mark.vector,
            toward = own.filter { it.verdict > 0 }.mapNotNull { vectorOf(it.match) },
            awayFrom = own.filter { it.verdict < 0 }.mapNotNull { vectorOf(it.match) },
            rejected = own.filter { it.verdict < 0 }.map { it.match.item to it.match.ref }.toSet(),
        )
    }

    companion object {
        /** The catalogue's growth since the likeness was made from which it is made again. */
        private const val REMAKE_GROWTH = 0.02

        /** Raised when the way the likeness is made changes: the stored one is made again. */
        private const val VERSION = 1
    }
}
