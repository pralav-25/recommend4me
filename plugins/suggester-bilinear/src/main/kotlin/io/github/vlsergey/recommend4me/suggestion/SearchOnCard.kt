package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats

/** The card has no room left for a search's vectors: the searches go on in the main memory. */
internal class NoRoomOnCard : RuntimeException("no room on the card for the searches")

/**
 * A SEARCH'S VECTORS ON THE CARD ([Search]): the point, the gradient, the direction, the remembered
 * steps and changes of every model, worked by cuBLAS — tens of dot products and sums of vectors of
 * a quarter of a million numbers a step of every model, the card's work, not the processor's.
 * Every vector made is freed by [close].
 */
internal class SearchOnCard(private val cublas: Cublas) : Space<Cublas.DeviceFloats>, AutoCloseable {
    private val made = ArrayList<Cublas.DeviceFloats>()

    override fun make(size: Int): Cublas.DeviceFloats = (cublas.allocate(size.toLong()) ?: throw NoRoomOnCard()).also { made += it }

    override fun dot(a: Cublas.DeviceFloats, b: Cublas.DeviceFloats, n: Int) = cublas.dot(a, 0, b, 0, n)

    override fun axpy(c: Float, x: Cublas.DeviceFloats, y: Cublas.DeviceFloats, n: Int) = cublas.axpy(c, x, 0, y, 0, n)

    override fun scale(c: Float, x: Cublas.DeviceFloats, n: Int) = cublas.scale(c, x, 0, n)

    override fun copy(from: Cublas.DeviceFloats, into: Cublas.DeviceFloats, n: Int) = into.copyFrom(from, 0, 0, n)

    override fun put(from: FloatArray, into: Cublas.DeviceFloats) = into.upload(Floats.of(from), 0, 0, from.size)

    override fun take(from: Cublas.DeviceFloats, n: Int): FloatArray = Floats(n).also { from.download(it, 0, 0, n) }.toArray()

    override fun close() = made.forEach { it.close() }
}

/**
 * WHERE A MODEL'S POINT — OR ITS GRADIENT — LIES WHEN ITS SEARCH IS ON THE CARD: its weights A,
 * nine tenths of its numbers, on the card, [cardCount] of them at [cardAt] of [card] — the products
 * of the items' features read and write them there, and they never leave it; the rest — B, E, the
 * biases, the pair's own weights — in the main memory ([host]), where the pairs of the items and
 * their values are worked out, carried over through [scratch] on the card.
 */
internal class CardPieces(
    private val cublas: Cublas,
    private val card: Cublas.DeviceFloats,
    private val cardAt: Long,
    private val cardCount: Int,
    private val host: Pieces,
    private val scratch: Cublas.DeviceFloats,
) : Place<Cublas.DeviceFloats> {

    override fun write(x: Cublas.DeviceFloats) {
        card.copyFrom(x, 0, cardAt, cardCount)
        scratch.copyFrom(x, cardCount.toLong(), 0, host.size)
        toHost()
    }

    override fun writeStep(x: Cublas.DeviceFloats, t: Float, d: Cublas.DeviceFloats) {
        card.copyFrom(x, 0, cardAt, cardCount)
        cublas.axpy(t, d, 0, card, cardAt, cardCount)
        scratch.copyFrom(x, cardCount.toLong(), 0, host.size)
        cublas.axpy(t, d, cardCount.toLong(), scratch, 0, host.size)
        toHost()
    }

    override fun read(into: Cublas.DeviceFloats) {
        into.copyFrom(card, cardAt, 0, cardCount)
        var k = cardCount.toLong()
        for (p in host.floats.indices) {
            into.upload(host.floats[p], host.at[p], k, host.count[p])
            k += host.count[p]
        }
    }

    /** The rest of the point, made in [scratch], into the main memory where the pairs read it. */
    private fun toHost() {
        var k = 0L
        for (p in host.floats.indices) {
            scratch.download(host.floats[p], host.at[p], k, host.count[p])
            k += host.count[p]
        }
    }
}
